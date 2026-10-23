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

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    /** Dernière pose de fabrique refusée par NOTRE règle (pour distinguer un refus venu d'un autre mod, ex. WorldGuard). */
    private static BlockEvent.EntityPlaceEvent ownCancel;

    /** Refus visible : message dans le chat (il reste affiché) + barre d'action + trace dans le journal du serveur. */
    static void refuse(ServerPlayer p, String why, String what) {
        p.sendSystemMessage(Component.literal("§c✖ " + what + " : " + why));
        p.displayClientMessage(Component.literal("§c" + why), true);
        LOG.info("[Portes/gangs] {} : refus ({}) pour {} ({}) - {}", what, why, p.getGameProfile().getName(), p.getUUID(), p.blockPosition());
    }

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
            ownCancel = e;
            refuse(p, err, factory ? "Pose de la Fabrique d'armes" : chest ? "Pose du coffre de gang" : "Pose de la pancarte");
        }
    }

    /**
     * Si la pose d'une fabrique est annulée par UN AUTRE mod (protection WorldGuard, zone protégée…), on le dit au joueur au lieu de laisser
     * le bloc disparaître sans explication.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onPlaceCanceledByOther(BlockEvent.EntityPlaceEvent e) {
        if (!e.isCanceled() || e == ownCancel || !(e.getPlacedBlock().getBlock() instanceof FactoryBlock)) return;
        if (e.getEntity() instanceof ServerPlayer p) {
            refuse(p, "un autre système (protection de la zone, WorldGuard…) a refusé la pose ici. Demandez au staff de vous donner le droit de construire à cet endroit.", "Pose de la Fabrique d'armes");
        }
    }

    /** Pose (objet en main) et démontage (accroupi, main vide) de la fabrique, pris en premier : aucun autre mod ne voit le clic. */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onUse(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock e) {
        if (e.getLevel().isClientSide() || e.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        if (e.getItemStack().getItem() instanceof FactoryItem) {
            e.setCanceled(true);
            e.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
            if (GangService.allow(p.getUUID(), "fabrique_pose", 300)) FactoryManager.placeFromUse(p, e.getPos(), e.getFace());
        } else if (p.isShiftKeyDown() && e.getItemStack().isEmpty() && p.level().getBlockState(e.getPos()).getBlock() instanceof FactoryBlock) {
            e.setCanceled(true);
            e.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
            if (GangService.allow(p.getUUID(), "fabrique_demonte", 300)) FactoryManager.dismantleFromUse(p, e.getPos());
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
