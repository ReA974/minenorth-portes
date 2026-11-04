package fr.minenorth.portes.deco;

import com.mojang.logging.LogUtils;
import fr.minenorth.api.MineNorth;
import fr.minenorth.portes.agency.AgencyService;
import fr.minenorth.portes.agency.PropertyAdmin;
import fr.minenorth.portes.agency.PropertyCategory;
import fr.minenorth.portes.agency.PropertyInfoData;
import fr.minenorth.portes.agency.PropertyLog;
import fr.minenorth.portes.door.OwnerDoorData;
import fr.minenorth.portes.door.WorldGuardBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Terrains nus à bâtir.
 * <ul>
 *   <li><b>Création</b> (staff, panneau MineNorth Admin) : zone de X1/Z1 à X2/Z2, de « profondeur » blocs sous le sol à « hauteur » blocs au-dessus ;
 *   photo de la zone d'origine (blocs + données des blocs) dans world/data/minenorth_terrains/&lt;région&gt;.nbt ; région WorldGuard du même nom
 *   (créée par l'API WorldGuard, sinon commandes données au staff) ; repère du bien au centre ; fiche de vente ou de location au prix
 *   surface x prix au m².</li>
 *   <li><b>Construction</b> : l'acheteur (ou le locataire) et ses colocataires sont membres de la région : ils construisent librement, mais pas au-delà
 *   de la hauteur et de la profondeur (bornes de la région).</li>
 *   <li><b>Revente / fin de bail / expulsion / libération</b> : d'abord tout ce qui a été posé par les occupants part au garde-meuble de celui qui l'a
 *   posé (registre des blocs posés, avec le contenu des coffres) ; puis le terrain est remis exactement comme sur la photo, par lots de
 *   {@code terrainBlocsParTick} blocs par tick. Les objets qui tombent pendant la remise à nu (contenu d'un coffre non enregistré…) vont au
 *   garde-meuble de l'ancien titulaire ; les blocs non enregistrés (terre creusée, blocs naturels) ne sont pas rendus : aucune duplication.
 *   Pendant la remise à nu le terrain ne peut pas être acquis ; un arrêt du serveur la reprend au démarrage.</li>
 * </ul>
 */
@Mod.EventBusSubscriber
public final class TerrainService {
    private TerrainService() {}

    private static final Logger LOG = LogUtils.getLogger();

    private static final class Job {
        final TerrainData.Land land;
        final BlockState[] palette;
        final int[] blocks;
        final ListTag blockEntities;
        int index;
        int changed;
        final String reason;

        Job(TerrainData.Land land, BlockState[] palette, int[] blocks, ListTag bes, String reason) {
            this.land = land; this.palette = palette; this.blocks = blocks; this.blockEntities = bes; this.reason = reason;
        }
    }

    private static final Map<String, Job> JOBS = new LinkedHashMap<>();
    private static boolean resumeAsked;

    private static String ok(String m) { return "OK\t" + m; }
    private static String ko(String m) { return "KO\t" + m; }

    private static Path dir(MinecraftServer s) { return s.getWorldPath(LevelResource.ROOT).resolve("data").resolve("minenorth_terrains"); }

    public static boolean busy(MinecraftServer s, String dimension, String houseName) {
        TerrainData.Land l = TerrainData.get(s).land(PropertyInfoData.key(dimension, houseName));
        return l != null && l.resetting;
    }

    // ------------------------------------------------------------------ création

    /**
     * Crée un terrain. mode : SALE ou RENTAL (days = durée de location). pricePerM2 en centimes. Renvoie « OK » ou « KO » + TAB + message.
     * Aucun contrôle de droit : le panneau admin vérifie le staff.
     */
    public static String create(MinecraftServer s, String dimension, String rawName, int x1, int z1, int x2, int z2, int height, int depth,
                                long pricePerM2, String mode, int days, String actor) {
        DecoConfig cfg = DecoConfig.get();
        String name = OwnerDoorData.cleanHouseName(rawName);
        if (name.isEmpty()) return ko("Donne un nom au terrain.");
        String region = WorldGuardBridge.regionName(name);
        if (region.isEmpty()) return ko("Nom invalide : il faut au moins une lettre ou un chiffre.");
        if (AgencyService.referenceDoorKey(s, dimension, name) != null) return ko("Un bien porte déjà ce nom.");
        ServerLevel level = Mover.level(s, dimension);
        if (level == null) return ko("Dimension introuvable : " + dimension + ".");
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2), minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        int sx = maxX - minX + 1, sz = maxZ - minZ + 1;
        if (sx > cfg.terrainCoteMax || sz > cfg.terrainCoteMax) return ko("Côté trop grand : " + cfg.terrainCoteMax + " blocs au plus.");
        if (sx * sz > cfg.terrainSurfaceMax) return ko("Surface trop grande : " + cfg.terrainSurfaceMax + " m² au plus.");
        if (height < 1 || height > cfg.terrainHauteurMax) return ko("Hauteur : 1 à " + cfg.terrainHauteurMax + " blocs.");
        if (depth < 0 || depth > cfg.terrainProfondeurMax) return ko("Profondeur : 0 à " + cfg.terrainProfondeurMax + " blocs.");
        if (pricePerM2 < 1) return ko("Prix au m² invalide.");
        boolean rental = "RENTAL".equals(mode);
        if (rental && (days < 1 || days > PropertyAdmin.MAX_DAYS)) return ko("Durée de location : 1 à " + PropertyAdmin.MAX_DAYS + " jours.");
        long price = pricePerM2 * sx * sz;
        if (price > PropertyAdmin.MAX_PRICE_CENTS) return ko("Prix total trop élevé (100 000 000 € au plus).");
        int cx = (minX + maxX) >> 1, cz = (minZ + maxZ) >> 1;
        level.getChunk(cx >> 4, cz >> 4);
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz) - 1;
        int minY = Math.max(level.getMinBuildHeight(), ground - depth), maxY = Math.min(level.getMaxBuildHeight() - 1, ground + height);
        long volume = (long) sx * sz * (maxY - minY + 1);
        if (volume > cfg.terrainVolumeMax) return ko("Volume trop grand (" + volume + " blocs, " + cfg.terrainVolumeMax + " au plus) : réduis la surface ou la hauteur.");
        OwnerDoorData d = OwnerDoorData.get(s.overworld());
        OwnerDoorData.DoorKey marker = new OwnerDoorData.DoorKey(level.dimension(), new BlockPos(cx, ground + 1, cz));
        if (d.entry(marker) != null) return ko("Une porte est déjà enregistrée au centre du terrain.");

        String file = region + ".nbt";
        try {
            snapshot(level, minX, minY, minZ, maxX, maxY, maxZ, dir(s).resolve(file));
        } catch (Exception e) {
            LOG.error("[Portes/terrains] Photo du terrain impossible", e);
            return ko("Photo du terrain impossible : " + e.getMessage());
        }
        String wg = WgRegions.createCuboid(s, dimension, region, minX, minY, minZ, maxX, maxY, maxZ);

        d.createLandMarker(marker, name);
        PropertyInfoData.get(s.overworld()).set(dimension, name, PropertyCategory.LAND, false,
                "Terrain à bâtir de " + sx * sz + " m² (" + sx + " x " + sz + "). Construction jusqu'à " + height + " blocs au-dessus du sol et "
                        + depth + " en dessous. Remis à nu à la revente.", "", 0);
        OwnerDoorData.DoorKey key = AgencyService.referenceDoorKey(s, dimension, name);
        if (key == null) key = marker;
        if (rental) d.relistHouseForRental(key, price, days); else d.relistHouseForSale(key, price);
        TerrainData.get(s).put(new TerrainData.Land(PropertyInfoData.key(dimension, name), dimension, name, minX, minY, minZ, maxX, maxY, maxZ, ground,
                height, depth, pricePerM2, file, actor == null ? "" : actor, System.currentTimeMillis()));
        Zones.invalidate();
        AgencyService.persist(s, d);
        String by = actor == null || actor.isBlank() ? "Staff" : actor;
        PropertyLog.add(s, dimension, name, by + " (staff) crée le terrain : " + sx + " x " + sz + " (" + sx * sz + " m²), y " + minY + " à " + maxY + ", "
                + euro(pricePerM2) + " le m² -> " + (rental ? "location " + euro(price) + " / " + days + " j" : "vente " + euro(price)));
        DecoJournal.log("Terrain « " + name + " » créé par " + by + " : " + minX + "," + minY + "," + minZ + " -> " + maxX + "," + maxY + "," + maxZ
                + " (" + volume + " blocs photographiés)" + (wg == null ? "" : " ; région WorldGuard NON créée : " + wg));
        String msg = "Terrain « " + name + " » créé : " + sx * sz + " m², " + (rental ? "en location " : "en vente ") + euro(price) + ".";
        if (wg != null) msg += " Région WorldGuard à créer à la main (" + wg + ") : " + WgRegions.manual(region, minX, minY, minZ, maxX, maxY, maxZ) + ".";
        return ok(msg);
    }

    static String euro(long cents) { return String.format(Locale.FRANCE, "%.2f €", cents / 100.0); }

    /** Change le prix au m² : la fiche de vente ou de location prend le nouveau prix (surface x prix au m²). */
    public static String setPricePerM2(MinecraftServer s, String dimension, String name, long cents, String actor) {
        TerrainData data = TerrainData.get(s);
        TerrainData.Land l = data.land(PropertyInfoData.key(dimension, name));
        if (l == null) return ko("Ce bien n'est pas un terrain créé par le panneau.");
        if (cents < 1) return ko("Prix au m² invalide.");
        long price = cents * l.area();
        if (price > PropertyAdmin.MAX_PRICE_CENTS) return ko("Prix total trop élevé.");
        OwnerDoorData d = OwnerDoorData.get(s.overworld());
        OwnerDoorData.DoorKey key = AgencyService.referenceDoorKey(s, dimension, name);
        long old = l.pricePerM2;
        l.pricePerM2 = cents;
        data.setDirty();
        if (key != null && d.setListingPrice(key, price)) AgencyService.persist(s, d);
        PropertyLog.add(s, dimension, name, (actor == null || actor.isBlank() ? "Staff" : actor) + " (staff) change le prix au m² : " + euro(old) + " -> " + euro(cents));
        return ok("Prix au m² de " + name + " : " + euro(cents) + " (fiche : " + euro(price) + ").");
    }

    // ------------------------------------------------------------------ photo

    private static void snapshot(ServerLevel level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Path file) throws Exception {
        int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
        Map<BlockState, Integer> pal = new HashMap<>();
        ListTag palette = new ListTag();
        int[] blocks = new int[sx * sy * sz];
        ListTag bes = new ListTag();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int i = 0;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) for (int y = minY; y <= maxY; y++) {
            p.set(x, y, z);
            BlockState st = level.getBlockState(p);
            Integer idx = pal.get(st);
            if (idx == null) {
                idx = palette.size();
                pal.put(st, idx);
                palette.add(NbtUtils.writeBlockState(st));
            }
            blocks[i++] = idx;
            BlockEntity be = level.getBlockEntity(p);
            if (be != null) bes.add(be.saveWithFullMetadata());
        }
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("Bounds", new int[]{minX, minY, minZ, maxX, maxY, maxZ});
        tag.put("Palette", palette);
        tag.putIntArray("Blocks", blocks);
        tag.put("BlockEntities", bes);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) { NbtIo.writeCompressed(tag, out); }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    // ------------------------------------------------------------------ remise à nu

    /** Lance la remise à nu (après le déménagement des blocs enregistrés). oldHolder : « p:uuid » de l'ancien titulaire, ou vide. */
    public static void reset(MinecraftServer s, String key, String reason, String oldHolder) {
        TerrainData data = TerrainData.get(s);
        TerrainData.Land l = data.land(key);
        if (l == null || JOBS.containsKey(key)) return;
        l.resetting = true;
        l.resetFor = oldHolder != null && oldHolder.startsWith("p:") ? oldHolder.substring(2) : "";
        data.setDirty();
        start(s, l, reason);
    }

    private static void start(MinecraftServer s, TerrainData.Land l, String reason) {
        ServerLevel level = Mover.level(s, l.dimension);
        try {
            if (level == null) throw new IllegalStateException("dimension introuvable");
            CompoundTag tag;
            try (InputStream in = Files.newInputStream(dir(s).resolve(l.snapshot))) { tag = NbtIo.readCompressed(in); }
            ListTag pal = tag.getList("Palette", Tag.TAG_COMPOUND);
            BlockState[] palette = new BlockState[pal.size()];
            var blocks = level.holderLookup(Registries.BLOCK);
            for (int i = 0; i < pal.size(); i++) palette[i] = NbtUtils.readBlockState(blocks, pal.getCompound(i));
            int[] b = tag.getIntArray("Blocks");
            if (b.length != (long) (l.maxX - l.minX + 1) * (l.maxY - l.minY + 1) * (l.maxZ - l.minZ + 1)) throw new IllegalStateException("photo de taille différente");
            JOBS.put(l.key, new Job(l, palette, b, tag.getList("BlockEntities", Tag.TAG_COMPOUND), reason));
            DecoJournal.log("Remise à nu du terrain « " + l.name + " » (" + reason + ") : début.");
        } catch (Exception e) {
            LOG.error("[Portes/terrains] Remise à nu de « {} » impossible", l.name, e);
            DecoJournal.log("Remise à nu du terrain « " + l.name + " » IMPOSSIBLE : " + e + " (le terrain reste bloqué : /deco terrain-debloquer " + l.name + ").");
            PropertyLog.add(s, l.dimension, l.name, "Remise à nu impossible (" + e.getMessage() + ") : terrain bloqué, voir le staff");
        }
    }

    /** Reprend au démarrage les remises à nu interrompues (au premier tick : les mondes sont prêts). */
    public static void resumePending(MinecraftServer s) { resumeAsked = true; JOBS.clear(); }

    /** Débloque un terrain dont la remise à nu a échoué (photo perdue) : il redevient disponible tel quel. */
    public static boolean unlock(MinecraftServer s, String name) {
        for (TerrainData.Land l : TerrainData.get(s).all()) {
            if (!l.name.equalsIgnoreCase(name.trim())) continue;
            JOBS.remove(l.key);
            l.resetting = false;
            TerrainData.get(s).setDirty();
            return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer s = e.getServer();
        if (resumeAsked) {
            resumeAsked = false;
            for (TerrainData.Land l : TerrainData.get(s).all()) if (l.resetting && !JOBS.containsKey(l.key)) start(s, l, "reprise après redémarrage");
        }
        if (JOBS.isEmpty()) return;
        Job job = JOBS.values().iterator().next();
        try {
            if (step(s, job)) JOBS.remove(job.land.key);
        } catch (RuntimeException ex) {
            LOG.error("[Portes/terrains] Remise à nu de « {} » interrompue", job.land.name, ex);
            JOBS.remove(job.land.key);
        }
    }

    /** Un lot de blocs ; renvoie vrai quand le terrain est entièrement remis à nu. */
    private static boolean step(MinecraftServer s, Job job) {
        TerrainData.Land l = job.land;
        ServerLevel level = Mover.level(s, l.dimension);
        if (level == null) return true;
        int sy = l.maxY - l.minY + 1, sz = l.maxZ - l.minZ + 1;
        int budget = DecoConfig.get().terrainBlocsParTick;
        UUID to = l.resetFor.isEmpty() ? null : UUID.fromString(l.resetFor);
        GardeMeuble store = GardeMeuble.get(s);
        Mover.beginCapture(level, new AABB(l.minX - 2, l.minY - 2, l.minZ - 2, l.maxX + 3, l.maxY + 3, l.maxZ + 3));
        List<ItemStack> found = new ArrayList<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        try {
            while (budget-- > 0 && job.index < job.blocks.length) {
                int i = job.index++;
                int x = l.minX + i / (sy * sz), z = l.minZ + (i / sy) % sz, y = l.minY + i % sy;
                p.set(x, y, z);
                BlockState want = job.palette[job.blocks[i]];
                if (level.getBlockState(p) == want) continue;
                Mover.drain(level, p, found);
                level.setBlock(p, want, Mover.FLAGS);
                job.changed++;
            }
        } finally {
            found.addAll(Mover.endCapture());
        }
        int given = 0;
        for (ItemStack st : found) {
            if (to == null || st.isEmpty()) continue;
            store.add(to, st);
            given += st.getCount();
        }
        if (given > 0) DecoJournal.log("Remise à nu « " + l.name + " » : " + given + " objet(s) trouvés -> garde-meuble de " + MineNorth.pseudo(s, to));
        if (job.index < job.blocks.length) return false;
        for (Tag t : job.blockEntities) {
            CompoundTag c = (CompoundTag) t;
            BlockEntity be = level.getBlockEntity(new BlockPos(c.getInt("x"), c.getInt("y"), c.getInt("z")));
            if (be != null) { be.load(c); be.setChanged(); }
        }
        l.resetting = false;
        l.resetFor = "";
        TerrainData.get(s).setDirty();
        PropertyLog.add(s, l.dimension, l.name, "Terrain remis à nu (" + job.reason + ") : " + job.changed + " bloc(s) remis comme à l'origine");
        DecoJournal.log("Remise à nu du terrain « " + l.name + " » terminée : " + job.changed + " bloc(s).");
        Zones.invalidate();
        return true;
    }

    // ------------------------------------------------------------------ pour la carte

    /** Terrains disponibles (fiche active, personne dessus, pas en remise à nu) : « dimension|x|y|z|nom|mode|prix|surface ». */
    public static List<String> available(MinecraftServer s) {
        List<String> out = new ArrayList<>();
        TerrainData data = TerrainData.get(s);
        for (AgencyService.PropertyView v : AgencyService.catalogAll(s)) {
            if (v.category() != PropertyCategory.LAND || v.mode() == OwnerDoorData.ListingMode.NONE) continue;
            TerrainData.Land l = data.land(PropertyInfoData.key(v.dimension(), v.houseName()));
            if (l == null || l.resetting) continue;
            AgencyService.Occupancy occ = null;
            for (AgencyService.Occupancy o : AgencyService.occupancies(s)) if (o.key().equals(l.key)) occ = o;
            if (occ != null && occ.holder() != null) continue;
            out.add(v.dimension() + "|" + v.x() + "|" + v.y() + "|" + v.z() + "|" + v.houseName() + "|" + v.mode().name() + "|" + v.priceCents() + "|" + l.area());
        }
        return out;
    }
}
