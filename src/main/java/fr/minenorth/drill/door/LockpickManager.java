package fr.minenorth.portes.door;

import fr.minenorth.portes.item.ModItems;
import fr.minenorth.portes.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DoorBlock;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Crochetage des portes de maison (PERSONAL uniquement).
 * Le client joue le mini-jeu et renvoie « gagné / perdu » ; le serveur contrôle la session, la distance,
 * l'éligibilité et tire la chance de réussite selon le niveau de serrure.
 */
public final class LockpickManager {
    private LockpickManager() {}

    private static final long SESSION_MS = 60_000L;
    private static final long ACCESS_MS = 30_000L;
    private static final double MAX_DIST_SQR = 36.0;
    private static final Random RANDOM = new Random();

    private record Session(OwnerDoorData.DoorKey key, long expiryMs) {}

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, Map<OwnerDoorData.DoorKey, Long>> ACCESS = new HashMap<>();

    public static boolean holdsLockpick(ServerPlayer p) {
        return p.getMainHandItem().is(ModItems.LOCKPICK.get());
    }

    /** Porte de maison, que le joueur ne peut pas déjà ouvrir. */
    public static boolean canPick(ServerPlayer p, ServerLevel l, BlockPos pos, OwnerDoorData.DoorEntry entry) {
        return entry != null && entry.type() == OwnerDoorData.DoorType.PERSONAL && !OwnerDoorManager.canOpen(p, l, pos, entry);
    }

    /**
     * Clic droit avec un crochet sur une porte enregistrée. Renvoie vrai si le clic a été traité (crochet en main) :
     * mini-jeu lancé, ou refus affiché. Renvoie faux si le joueur n'a pas de crochet ou peut simplement ouvrir la porte.
     */
    public static boolean tryStart(ServerPlayer p, ServerLevel l, BlockPos pos, OwnerDoorData.DoorEntry entry) {
        if (entry == null || !holdsLockpick(p)) return false;
        if (OwnerDoorManager.canOpen(p, l, pos, entry)) return false;
        if (entry.type() != OwnerDoorData.DoorType.PERSONAL) {
            p.displayClientMessage(Component.literal("§cCette porte ne peut pas être crochetée."), true);
            return true;
        }
        if (p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > MAX_DIST_SQR) return true;
        OwnerDoorData.DoorKey key = new OwnerDoorData.DoorKey(l.dimension(), OwnerDoorData.normalize(l, pos));
        SESSIONS.put(p.getUUID(), new Session(key, System.currentTimeMillis() + SESSION_MS));
        ModNetwork.sendLockpickStart(p, key.pos(), OwnerDoorData.securityOf(entry));
        return true;
    }

    /** Résultat du mini-jeu envoyé par le client. */
    public static void finish(ServerPlayer p, boolean won) {
        Session s = SESSIONS.remove(p.getUUID());
        if (s == null || s.expiryMs() < System.currentTimeMillis()) return;
        ServerLevel l = p.server.getLevel(s.key().dimension());
        if (l == null || p.level().dimension() != s.key().dimension()) return;
        BlockPos pos = s.key().pos();
        if (p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > MAX_DIST_SQR) return;
        OwnerDoorData.DoorEntry entry = OwnerDoorData.get(l).get(l, pos);
        if (!canPick(p, l, pos, entry)) return;
        ItemStack held = p.getMainHandItem();
        if (!held.is(ModItems.LOCKPICK.get())) {
            p.displayClientMessage(Component.literal("§cVous n'avez plus votre crochet en main."), true);
            return;
        }
        held.hurtAndBreak(1, p, pl -> pl.broadcastBreakEvent(InteractionHand.MAIN_HAND));

        int chance = OwnerDoorData.PICK_CHANCE[OwnerDoorData.securityOf(entry)];
        if (!won || RANDOM.nextInt(100) >= chance) {
            p.displayClientMessage(Component.literal("§cLa serrure résiste."), true);
            return;
        }
        ACCESS.computeIfAbsent(p.getUUID(), k -> new HashMap<>()).put(s.key(), System.currentTimeMillis() + ACCESS_MS);
        var state = l.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock && !state.getValue(DoorBlock.OPEN)) OwnerDoorManager.toggleDoor(l, pos);
        ModNetwork.syncDoorLocks(p.server);
        p.displayClientMessage(Component.literal("§aSerrure crochetée !"), true);
    }

    /** Autorisation temporaire (30 s) obtenue par crochetage ; ne donne aucun droit durable. */
    public static boolean hasTempAccess(UUID player, OwnerDoorData.DoorKey key) {
        Map<OwnerDoorData.DoorKey, Long> m = ACCESS.get(player);
        if (m == null) return false;
        long now = System.currentTimeMillis();
        m.values().removeIf(t -> t < now);
        if (m.isEmpty()) { ACCESS.remove(player); return false; }
        return m.containsKey(key);
    }

    public static void forget(UUID player) {
        SESSIONS.remove(player);
        ACCESS.remove(player);
    }
}
