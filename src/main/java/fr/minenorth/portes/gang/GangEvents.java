package fr.minenorth.portes.gang;

import fr.minenorth.api.PlayerWipeEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Garde-fous de pose / destruction des blocs de gang, restitution de l'ancien coffre, et nettoyage quand un joueur est supprimé. */
@Mod.EventBusSubscriber
public final class GangEvents {
    private GangEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        Block b = e.getPlacedBlock().getBlock();
        boolean chest = b instanceof GangChestBlock, factory = b instanceof FactoryBlock, sign = b instanceof TerritorySignBlock;
        if (!chest && !factory && !sign) return;
        if (!(e.getEntity() instanceof ServerPlayer p)) { e.setCanceled(true); return; }
        String err = null;
        if (chest) err = "Le coffre de gang n'existe plus.";
        else if (factory) err = e.getLevel() instanceof ServerLevel l ? FactoryManager.placementError(p, l.dimension().location().toString(), e.getPos()) : "Impossible ici.";
        else if (!p.hasPermissions(2)) err = "Seul un administrateur pose une pancarte de territoire.";
        if (err != null) {
            e.setCanceled(true);
            p.displayClientMessage(Component.literal("§c" + err), true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(BlockEvent.BreakEvent e) {
        Block b = e.getState().getBlock();
        ServerPlayer p = e.getPlayer() instanceof ServerPlayer sp ? sp : null;
        if (p == null) return;
        if (b instanceof TerritorySignBlock && !p.hasPermissions(2)) {
            e.setCanceled(true);
            p.displayClientMessage(Component.literal("§cCette pancarte est protégée."), true);
            return;
        }
        if (!(b instanceof FactoryBlock) || !(e.getLevel() instanceof ServerLevel level)) return;
        GangData data = GangData.get(p.server);
        Factory f = data.factory(level.dimension().location().toString(), e.getPos().asLong());
        if (f == null) return; // fabrique orpheline : n'importe qui peut la retirer
        // Le gang propriétaire (chef/lieutenants) peut la démonter ; le gang qui TIENT la zone peut la détruire ; personne d'autre (les OP suivent la même règle).
        Gang mine = data.gangOf(p.getUUID());
        GangRank rank = mine == null ? null : mine.rankOf(p.getUUID());
        boolean owner = mine != null && mine.id.equals(f.gang()) && rank.atLeast(GangRank.LIEUTENANT);
        Territory t = FactoryManager.zoneAt(data, f.dim, e.getPos());
        boolean holder = mine != null && t != null && mine.id.equals(t.holder());
        if (!owner && !holder) {
            e.setCanceled(true);
            p.displayClientMessage(Component.literal("§cCette fabrique est protégée : seul le gang qui contrôle la zone peut la détruire."), true);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) GangService.deliverLegacy(p);
    }

    @SubscribeEvent
    public static void onWipe(PlayerWipeEvent e) {
        if (GangService.wipe(e.server(), e.player())) e.cleaned("Portes (gangs)");
    }
}
