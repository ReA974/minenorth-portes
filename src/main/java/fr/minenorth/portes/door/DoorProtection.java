package fr.minenorth.portes.door;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.TargetBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Protection des portes enregistrées, en plus du clic droit (géré par OwnerDoorManager) :
 *  - impossible de casser une porte protégée (ni le bloc qui la porte) sauf pour un OP ;
 *  - les explosions l'épargnent ;
 *  - impossible de poser un objet de redstone ou un piston à moins de 2 blocs d'une porte qu'on ne peut pas ouvrir
 *    (bouton, levier, plaque, torche, poudre, répéteur, observateur, piston…), sinon on pourrait l'ouvrir ou la détruire à distance.
 * Les redstones déjà posées avant l'enregistrement de la porte ne sont pas retirées : à vérifier par le staff.
 */
@Mod.EventBusSubscriber
public final class DoorProtection {
    private DoorProtection() {}

    private static final int REDSTONE_RANGE = 2;

    /** Entrée de la porte à cette position (moitié haute ou basse), ou null si elle n'est pas protégée. */
    private static OwnerDoorData.DoorEntry protectedDoor(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof DoorBlock)) return null;
        return OwnerDoorData.get(level).get(level, pos);
    }

    /** Porte protégée à cette position, ou juste au-dessus (le bloc qui la porte). */
    private static BlockPos guardedDoor(ServerLevel level, BlockPos pos) {
        if (protectedDoor(level, pos) != null) return pos;
        if (protectedDoor(level, pos.above()) != null) return pos.above();
        return null;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !(e.getPlayer() instanceof ServerPlayer p)) return;
        if (p.hasPermissions(2)) return;
        if (guardedDoor(level, e.getPos()) == null) return;
        e.setCanceled(true);
        p.displayClientMessage(Component.literal("§cCette porte est protégée."), true);
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        e.getAffectedBlocks().removeIf(pos -> guardedDoor(level, pos) != null);
    }

    private static boolean isRedstoneLike(BlockState s) {
        Block b = s.getBlock();
        return s.isSignalSource() || b instanceof RedStoneWireBlock || b instanceof DiodeBlock || b instanceof ObserverBlock
                || b instanceof PistonBaseBlock || b instanceof TargetBlock;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !isRedstoneLike(e.getPlacedBlock())) return;
        ServerPlayer p = e.getEntity() instanceof ServerPlayer sp ? sp : null;
        if (p != null && p.hasPermissions(2)) return;
        BlockPos origin = e.getPos();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-REDSTONE_RANGE, -REDSTONE_RANGE, -REDSTONE_RANGE),
                origin.offset(REDSTONE_RANGE, REDSTONE_RANGE, REDSTONE_RANGE))) {
            if (pos.distManhattan(origin) > REDSTONE_RANGE + 1) continue;
            OwnerDoorData.DoorEntry entry = protectedDoor(level, pos);
            if (entry == null) continue;
            // Le propriétaire (ou toute personne autorisée) peut équiper sa propre porte.
            if (p != null && OwnerDoorManager.canOpen(p, level, OwnerDoorData.normalize(level, pos.immutable()), entry)) continue;
            e.setCanceled(true);
            if (p != null) p.displayClientMessage(Component.literal("§cImpossible de poser ça près d'une porte protégée."), true);
            return;
        }
    }

    /** Pour les autres mods : vrai si cette position est une porte protégée. */
    public static boolean isProtected(Level level, BlockPos pos) {
        return level instanceof ServerLevel l && protectedDoor(l, pos) != null;
    }
}
