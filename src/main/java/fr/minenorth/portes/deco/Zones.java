package fr.minenorth.portes.deco;

import fr.minenorth.portes.agency.AgencyService;
import fr.minenorth.portes.agency.AgencyService.Occupancy;
import fr.minenorth.portes.agency.PropertyCategory;
import fr.minenorth.portes.door.WorldGuardBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Où sont les biens dans le monde : chaque bien occupe sa région WorldGuard (lue dans regions.yml, même nom que la maison) ; un terrain occupe
 * la zone enregistrée à sa création. Sans région lisible : à moins de {@code rayonSansRegion} blocs d'une de ses portes.
 * « Chez soi » = dans un bien dont on est titulaire (propriétaire, locataire, patron de l'entreprise) ou colocataire. Thread serveur uniquement.
 */
public final class Zones {
    private Zones() {}

    public record Home(Occupancy occ, RegionShape shape, boolean fromRegion) {
        public boolean contains(Level level, BlockPos p) {
            return level.dimension().location().toString().equals(occ.dimension()) && shape.contains(p.getX(), p.getY(), p.getZ());
        }

        /**
         * Le joueur est-il chez lui ici ? Dans la région (ou la zone du terrain), ou bien à moins de {@code rayonSansRegion} blocs d'une porte du
         * bien : une région WorldGuard tracée trop juste (ou absente du fichier lu) ne doit pas empêcher l'occupant d'être reconnu chez lui.
         */
        public boolean around(Level level, BlockPos p) {
            if (contains(level, p)) return true;
            if (terrain() || !level.dimension().location().toString().equals(occ.dimension())) return false;
            int r = DecoConfig.get().rayonSansRegion;
            for (BlockPos d : occ.doors())
                if (Math.abs(d.getX() - p.getX()) <= r && Math.abs(d.getZ() - p.getZ()) <= r && p.getY() >= d.getY() - 6 && p.getY() <= d.getY() + r) return true;
            return false;
        }

        public boolean livesHere(UUID player) { return occ.occupants().contains(player); }

        public boolean terrain() { return occ.category() == PropertyCategory.LAND; }
    }

    private record RegionFile(long modified, Map<String, RegionShape> regions) {}

    private static final Map<Path, RegionFile> FILES = new HashMap<>();
    private static List<Home> cache = List.of();
    private static int cacheTick;
    /** Liste à recalculer. Un drapeau et non un tick « très ancien » : now - Integer.MIN_VALUE déborde, la liste restait vide pour toujours. */
    private static boolean stale = true;

    /** À appeler quand l'occupation change (achat, vente…) : la liste est recalculée au prochain appel. */
    public static void invalidate() { stale = true; }

    public static List<Home> homes(MinecraftServer s) {
        int now = s.getTickCount();
        if (stale || now - cacheTick >= 20 || now < cacheTick) {
            cache = build(s);
            cacheTick = now;
            stale = false;
        }
        return cache;
    }

    private static Map<String, RegionShape> regions(MinecraftServer s, String dim) {
        Path f = WorldGuardBridge.regionsFile(s, dim);
        try {
            if (!Files.isRegularFile(f)) return Map.of();
            long m = Files.getLastModifiedTime(f).toMillis();
            RegionFile rf = FILES.get(f);
            if (rf == null || rf.modified() != m) {
                rf = new RegionFile(m, RegionShape.parse(Files.readString(f, StandardCharsets.UTF_8)));
                FILES.put(f, rf);
            }
            return rf.regions();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static List<Home> build(MinecraftServer s) {
        List<Home> out = new ArrayList<>();
        TerrainData lands = TerrainData.get(s);
        int r = DecoConfig.get().rayonSansRegion;
        for (Occupancy o : AgencyService.occupancies(s)) {
            TerrainData.Land land = o.category() == PropertyCategory.LAND ? lands.land(o.key()) : null;
            if (land != null) { out.add(new Home(o, land.shape(), true)); continue; }
            RegionShape shape = regions(s, o.dimension()).get(WorldGuardBridge.regionName(o.houseName()));
            if (shape != null) { out.add(new Home(o, shape, true)); continue; }
            if (o.doors().isEmpty()) continue;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos p : o.doors()) {
                minX = Math.min(minX, p.getX() - r); maxX = Math.max(maxX, p.getX() + r);
                minY = Math.min(minY, p.getY() - 4); maxY = Math.max(maxY, p.getY() + r);
                minZ = Math.min(minZ, p.getZ() - r); maxZ = Math.max(maxZ, p.getZ() + r);
            }
            out.add(new Home(o, RegionShape.box(minX, minY, minZ, maxX, maxY, maxZ), false));
        }
        return out;
    }

    /** Bien qui contient cette position (une vraie région d'abord, la zone approximative autour des portes ensuite), ou null. */
    public static Home at(MinecraftServer s, Level level, BlockPos pos) {
        Home approx = null;
        for (Home h : homes(s)) {
            if (!h.contains(level, pos)) continue;
            if (h.fromRegion()) return h;
            if (approx == null) approx = h;
        }
        return approx;
    }

    /** Bien de cet occupant qui contient cette position (sa région d'abord, puis les abords de ses portes), ou null. */
    public static Home atFor(MinecraftServer s, Level level, BlockPos pos, UUID player) {
        Home found = null;
        for (Home h : homes(s)) {
            if (!h.livesHere(player)) continue;
            if (h.fromRegion() && h.contains(level, pos)) return h;
            if (found == null && h.around(level, pos)) found = h;
        }
        return found;
    }

    /** Bien où se trouve le joueur et dont il est occupant, ou null : il n'est pas chez lui. */
    public static Home homeOf(ServerPlayer p) {
        return atFor(p.server, p.level(), p.blockPosition(), p.getUUID());
    }

    /** Bien par sa clé (dimension|nom), ou null. */
    public static Home byKey(MinecraftServer s, String key) {
        for (Home h : homes(s)) if (h.occ().key().equals(key)) return h;
        return null;
    }
}
