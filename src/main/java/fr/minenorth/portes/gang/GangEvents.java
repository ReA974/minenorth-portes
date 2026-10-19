package fr.minenorth.portes.gang;

import fr.minenorth.api.PlayerWipeEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Garde-fous de pose / destruction des blocs de gang, et nettoyage quand un joueur est supprimé. */
@Mod.EventBusSubscriber
public final class GangEvents {
    private GangEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        Block b = e.getPlacedBlock().getBlock();
        boolean chest = b instanceof GangChestBlock, sign = b instanceof TerritorySignBlock;
        if (!chest && !sign) return;
        if (!(e.getEntity() instanceof ServerPlayer p)) { e.setCanceled(true); return; }
        String err = null;
        if (chest) err = GangService.chestPlacementError(p);
        else if (!p.hasPermissions(2)) err = "Seul un administrateur pose une pancarte de territoire.";
        if (err != null) {
            e.setCanceled(true);
            p.displayClientMessage(Component.literal("§c" + err), true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(BlockEvent.BreakEvent e) {
        Block b = e.getState().getBlock();
        if (!(b instanceof GangChestBlock) && !(b instanceof TerritorySignBlock)) return;
        ServerPlayer p = e.getPlayer() instanceof ServerPlayer sp ? sp : null;
        if (p == null || p.hasPermissions(2)) return;
        if (b instanceof TerritorySignBlock) {
            e.setCanceled(true);
            p.displayClientMessage(Component.literal("§cCette pancarte est protégée."), true);
            return;
        }
        if (!(e.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return;
        Gang g = GangData.get(p.server).gangByChest(level.dimension().location().toString(), e.getPos().asLong());
        if (g == null) return; // coffre orphelin : n'importe qui peut le retirer
        GangRank r = g.rankOf(p.getUUID());
        if (r == null || !r.atLeast(GangRank.LIEUTENANT)) {
            e.setCanceled(true);
            p.displayClientMessage(Component.literal("§cCe coffre appartient au gang " + g.name() + "."), true);
        }
    }

    @SubscribeEvent
    public static void onWipe(PlayerWipeEvent e) {
        if (GangService.wipe(e.server(), e.player())) e.cleaned("Portes (gangs)");
    }
}
