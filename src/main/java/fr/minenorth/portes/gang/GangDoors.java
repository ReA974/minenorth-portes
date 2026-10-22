package fr.minenorth.portes.gang;

import fr.minenorth.portes.config.SystemeConfigFiles;
import fr.minenorth.portes.door.OwnerDoorData;
import fr.minenorth.portes.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lien entre les gangs et les portes. Une porte de gang est une porte normale du mod (OwnerDoorData) de type GANG, sans propriétaire,
 * dont le champ « permission » vaut {@code gang:<uuid du gang>:<grade minimum>} (0 chef, 1 lieutenant, 2 membre).
 * Le droit d'ouvrir est recalculé à chaque fois à partir du gang : un membre exclu perd l'accès immédiatement.
 */
public final class GangDoors {
    private GangDoors() {}

    public static final String PREFIX = "gang:";
    private static final double MAX_DIST_SQR = 64.0; // 8 blocs

    public static String permission(UUID gang, GangRank minRank) { return PREFIX + gang + ":" + minRank.level(); }

    public static UUID gangId(String permission) {
        if (permission == null || !permission.startsWith(PREFIX)) return null;
        String[] parts = permission.split(":");
        if (parts.length < 2) return null;
        try { return UUID.fromString(parts[1]); } catch (IllegalArgumentException e) { return null; }
    }

    public static GangRank minRank(String permission) {
        try { return GangRank.ofLevel(Integer.parseInt(permission.split(":")[2].trim())); } catch (RuntimeException e) { return GangRank.MEMBRE; }
    }

    /** Vrai si ce joueur ouvre une porte dont la permission est celle d'un gang (les OP ouvrent toujours, comme pour les entreprises). */
    public static boolean canOpen(ServerPlayer p, String permission) {
        if (p.hasPermissions(2)) return true;
        UUID id = gangId(permission);
        if (id == null) return false;
        Gang g = GangData.get(p.server).gang(id);
        GangRank mine = g == null ? null : g.rankOf(p.getUUID());
        return mine != null && mine.atLeast(minRank(permission));
    }

    /** Porte visée par le joueur (6 blocs), en position de la moitié basse ; null si ce n'est pas une porte. */
    public static BlockPos aimedDoor(ServerPlayer p) {
        HitResult hr = p.pick(6.0, 1.0F, false);
        if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = bhr.getBlockPos();
            if (p.serverLevel().getBlockState(pos).getBlock() instanceof DoorBlock) return pos;
        }
        return null;
    }

    private static boolean near(ServerPlayer p, BlockPos pos) {
        return p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= MAX_DIST_SQR;
    }

    // ------------------------------------------------------------------ attribution

    /** Attribue la porte au gang du joueur. Renvoie le message d'erreur, ou null si c'est fait. */
    public static String claim(ServerPlayer p, BlockPos rawPos, GangRank minRank) {
        MinecraftServer s = p.server;
        ServerLevel l = p.serverLevel();
        GangData gd = GangData.get(s);
        Gang g = gd.gangOf(p.getUUID());
        if (g == null) return "Vous n'êtes dans aucun gang.";
        if (!g.rankOf(p.getUUID()).atLeast(GangRank.LIEUTENANT)) return "Seuls le chef et les lieutenants gèrent les portes du gang.";
        if (!(l.getBlockState(rawPos).getBlock() instanceof DoorBlock)) return "Ce n'est pas une porte.";
        if (!near(p, rawPos)) return "Approchez-vous de la porte.";
        if (!GangService.allow(p.getUUID(), "porte", 600)) return "Doucement.";
        OwnerDoorData od = OwnerDoorData.get(l);
        BlockPos pos = OwnerDoorData.normalize(l, rawPos);
        OwnerDoorData.DoorEntry e = od.get(l, pos);
        if (e != null && gangId(e.permission()) != null && e.type() == OwnerDoorData.DoorType.GANG) {
            if (g.id.equals(gangId(e.permission()))) return "Cette porte est déjà au gang (changez son grade dans le menu de la porte).";
            return "Cette porte appartient à un autre gang.";
        }
        if (e != null) {
            // Seule une porte de maison qu'on possède soi-même (sans annonce) peut passer au gang.
            boolean own = e.type() == OwnerDoorData.DoorType.PERSONAL && p.getUUID().equals(e.owner()) && !e.temporary();
            if (!own || od.listing(l, pos) != null) return "Cette porte est déjà protégée : elle ne peut pas être prise par un gang.";
        } else {
            if (od.listing(l, pos) != null) return "Cette porte fait partie d'un bien à vendre ou à louer.";
            if (!canClaimHere(g, l, pos)) {
                return "Une porte libre doit être dans une zone que votre gang contrôle, ou à moins de " + GangConfig.baseRadius() + " blocs de la base du gang (le chef la définit dans l'onglet Bases).";
            }
        }
        if (countDoors(s, g) >= GangConfig.doorsMax()) return "Votre gang a déjà " + GangConfig.doorsMax() + " portes.";
        od.assign(l, pos, null, g.name, false, OwnerDoorData.DoorType.GANG, permission(g.id, minRank));
        od.setHouseName(l, pos, "");
        SystemeConfigFiles.writeDoors(s, od);
        ModNetwork.syncDoorLocks(s);
        g.addLog(p.getUUID(), "attribue une porte au gang (" + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ")", GangConfig.logMax());
        GangData.get(s).setDirty();
        return null;
    }

    /** Une porte libre peut rejoindre le gang si elle est dans une zone que le gang contrôle, ou près de sa base. Les OP suivent la même règle. */
    static boolean canClaimHere(Gang g, ServerLevel l, BlockPos pos) {
        String dim = l.dimension().location().toString();
        for (Territory t : GangData.get(l.getServer()).territories()) {
            if (g.id.equals(t.holder()) && t.contains(dim, pos.getX(), pos.getY(), pos.getZ())) return true;
        }
        if (g.hasBase() && g.baseDim().equals(dim)) {
            double r = GangConfig.baseRadius();
            return BlockPos.of(g.basePos()).distSqr(pos) <= r * r;
        }
        return false;
    }

    /** Retire la porte du gang (le joueur doit être près de la porte) : elle redevient une porte libre. */
    public static String unclaim(ServerPlayer p, BlockPos rawPos) {
        if (!near(p, rawPos)) return "Approchez-vous de la porte.";
        return unclaimAt(p, p.level().dimension().location().toString(), rawPos.asLong());
    }

    /** Retire une porte du gang depuis la liste de l'écran (sans être devant) : seul le chef ou un lieutenant du gang concerné, ou un OP. */
    public static String unclaimAt(ServerPlayer p, String dim, long packed) {
        ServerLevel l = null;
        for (ServerLevel cand : p.server.getAllLevels()) if (cand.dimension().location().toString().equals(dim)) l = cand;
        if (l == null) return "Dimension inconnue.";
        BlockPos rawPos = BlockPos.of(packed);
        Gang g = GangData.get(p.server).gangOf(p.getUUID());
        OwnerDoorData od = OwnerDoorData.get(l);
        OwnerDoorData.DoorEntry e = od.get(l, rawPos);
        if (e == null || e.type() != OwnerDoorData.DoorType.GANG) return "Ce n'est pas une porte de gang.";
        UUID gid = gangId(e.permission());
        boolean allowed = p.hasPermissions(2) || (g != null && g.id.equals(gid) && g.rankOf(p.getUUID()).atLeast(GangRank.LIEUTENANT));
        if (!allowed) return "Seuls le chef et les lieutenants du gang peuvent retirer cette porte.";
        od.unassign(l, rawPos);
        SystemeConfigFiles.writeDoors(p.server, od);
        ModNetwork.syncDoorLocks(p.server);
        return null;
    }

    /** Depuis le panneau d'une porte de maison : ouvre la fenêtre « donner cette porte au gang » (chef/lieutenant, porte à soi, à portée). */
    public static String offerOwnDoor(ServerPlayer p, BlockPos rawPos) {
        ServerLevel l = p.serverLevel();
        Gang g = GangData.get(p.server).gangOf(p.getUUID());
        if (g == null) return "Vous n'êtes dans aucun gang.";
        if (!g.rankOf(p.getUUID()).atLeast(GangRank.LIEUTENANT)) return "Seuls le chef et les lieutenants gèrent les portes du gang.";
        if (!near(p, rawPos) || !(l.getBlockState(rawPos).getBlock() instanceof DoorBlock)) return "Approchez-vous de la porte.";
        OwnerDoorData.DoorEntry e = OwnerDoorData.get(l).get(l, rawPos);
        if (e == null || e.type() != OwnerDoorData.DoorType.PERSONAL || !p.getUUID().equals(e.owner())) return "Ce n'est pas votre porte.";
        GangGui.sendDoor(p, 3, OwnerDoorData.normalize(l, rawPos), GangRank.MEMBRE.level());
        return null;
    }

    /** Le gang a changé de nom : les portes affichent le nouveau. */
    public static void refreshNames(MinecraftServer s, Gang g) {
        OwnerDoorData od = OwnerDoorData.get(s.overworld());
        boolean any = false;
        for (OwnerDoorData.DoorKey k : doorsOf(s, g)) {
            ServerLevel l = s.getLevel(k.dimension());
            OwnerDoorData.DoorEntry e = l == null ? null : od.entry(k);
            if (e == null) continue;
            od.assign(l, k.pos(), null, g.name, false, OwnerDoorData.DoorType.GANG, e.permission());
            any = true;
        }
        if (any) SystemeConfigFiles.writeDoors(s, od);
    }

    public static String setMinRank(ServerPlayer p, BlockPos rawPos, GangRank rank) {
        ServerLevel l = p.serverLevel();
        Gang g = GangData.get(p.server).gangOf(p.getUUID());
        OwnerDoorData od = OwnerDoorData.get(l);
        OwnerDoorData.DoorEntry e = od.get(l, rawPos);
        if (e == null || e.type() != OwnerDoorData.DoorType.GANG) return "Ce n'est pas une porte de gang.";
        if (!near(p, rawPos)) return "Approchez-vous de la porte.";
        UUID gid = gangId(e.permission());
        if (g == null || !g.id.equals(gid) || !g.rankOf(p.getUUID()).atLeast(GangRank.LIEUTENANT)) return "Cette porte n'est pas gérée par vous.";
        od.assign(l, rawPos, null, g.name, false, OwnerDoorData.DoorType.GANG, permission(g.id, rank));
        SystemeConfigFiles.writeDoors(p.server, od);
        ModNetwork.syncDoorLocks(p.server);
        return null;
    }

    // ------------------------------------------------------------------ comptage et libération

    public static List<OwnerDoorData.DoorKey> doorsOf(MinecraftServer s, Gang g) {
        List<OwnerDoorData.DoorKey> out = new ArrayList<>();
        OwnerDoorData od = OwnerDoorData.get(s.overworld());
        for (Map.Entry<OwnerDoorData.DoorKey, OwnerDoorData.DoorEntry> en : od.allDoors()) {
            OwnerDoorData.DoorEntry e = en.getValue();
            if (e.type() == OwnerDoorData.DoorType.GANG && g.id.equals(gangId(e.permission()))) out.add(en.getKey());
        }
        return out;
    }

    public static int countDoors(MinecraftServer s, Gang g) { return doorsOf(s, g).size(); }

    /** Le gang disparaît : ses portes redeviennent libres. */
    public static void releaseAll(MinecraftServer s, Gang g) {
        OwnerDoorData od = OwnerDoorData.get(s.overworld());
        List<OwnerDoorData.DoorKey> keys = doorsOf(s, g);
        if (keys.isEmpty()) return;
        for (OwnerDoorData.DoorKey k : keys) {
            ServerLevel l = s.getLevel(k.dimension());
            if (l != null) od.unassign(l, k.pos());
        }
        SystemeConfigFiles.writeDoors(s, od);
        ModNetwork.syncDoorLocks(s);
    }

    // ------------------------------------------------------------------ clic accroupi sur une porte

    /**
     * Clic accroupi du joueur sur une porte. Renvoie vrai si on a répondu (le clic ne doit alors rien faire d'autre).
     * Les OP gardent leur panneau d'administration habituel.
     */
    public static boolean onSneakClick(ServerPlayer p, ServerLevel l, BlockPos rawPos, OwnerDoorData.DoorEntry entry) {
        if (p.hasPermissions(2)) return false;
        Gang mine = GangData.get(p.server).gangOf(p.getUUID());
        if (mine == null) return false;
        boolean lead = mine.rankOf(p.getUUID()).atLeast(GangRank.LIEUTENANT);
        BlockPos pos = OwnerDoorData.normalize(l, rawPos);
        if (entry == null) {
            if (!lead || !canClaimHere(mine, l, pos) || OwnerDoorData.get(l).listing(l, pos) != null) return false;
            if (!GangService.allow(p.getUUID(), "portemsg", 700)) return true;
            GangGui.sendDoor(p, 1, pos, GangRank.MEMBRE.level());
            return true;
        }
        if (entry.type() != OwnerDoorData.DoorType.GANG || !mine.id.equals(gangId(entry.permission()))) return false;
        if (!GangService.allow(p.getUUID(), "portemsg", 700)) return true;
        GangRank min = minRank(entry.permission());
        if (!lead) {
            p.displayClientMessage(Component.literal("§7Porte de " + mine.name() + " - ouverte aux grades : " + min.label().toLowerCase(java.util.Locale.ROOT) + " et plus."), true);
            return true;
        }
        GangGui.sendDoor(p, 2, pos, min.level());
        return true;
    }

    // ------------------------------------------------------------------ crochetage

    private static final Map<UUID, Long> ALERTED = new java.util.HashMap<>();

    /** Le gang est prévenu (au plus toutes les 20 s) quand quelqu'un crochète l'une de ses portes. */
    public static void alertLockpick(ServerPlayer thief, BlockPos pos, OwnerDoorData.DoorEntry entry) {
        UUID gid = gangId(entry.permission());
        Gang g = gid == null ? null : GangData.get(thief.server).gang(gid);
        if (g == null) return;
        long now = System.currentTimeMillis();
        Long last = ALERTED.get(gid);
        if (last != null && now - last < 20_000L) return;
        ALERTED.put(gid, now);
        GangService.notifyGang(thief.server, g, "§c§lAlerte ! §cQuelqu'un crochète une porte de la base (" + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ").");
    }
}
