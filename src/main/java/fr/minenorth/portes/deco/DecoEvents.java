package fr.minenorth.portes.deco;

import fr.minenorth.portes.deco.PlacedLedger.Part;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.PistonEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Tenue du registre des blocs posés ({@link PlacedLedger}) : un bloc posé par un occupant dans son bien y entre (avec l'objet exact utilisé) ;
 * cassé, soufflé par une explosion ou déplacé par un piston, il en sort. Les événements annulés par un autre mod (WorldGuard, protections)
 * ne sont pas pris en compte (priorité la plus basse).
 */
@Mod.EventBusSubscriber
public final class DecoEvents {
    private DecoEvents() {}

    @SubscribeEvent
    public static void onStarting(ServerStartingEvent e) {
        DecoConfig.load();
        DecoCatalog.load();
        TerrainService.resumePending(e.getServer());
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) { DecoCommands.register(e.getDispatcher()); }

    private static String dim(ServerLevel l) { return l.dimension().location().toString(); }

    /** Objet tenu qui a servi à poser ce bloc (Forge remet la pile d'origine en main pendant l'événement). */
    private static ItemStack held(ServerPlayer p, BlockEvent.EntityPlaceEvent e) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack st = p.getItemInHand(hand);
            if (st.getItem() instanceof BlockItem bi && bi.getBlock() == e.getPlacedBlock().getBlock()) return st.copyWithCount(1);
        }
        ItemStack main = p.getMainHandItem();
        if (!main.isEmpty()) return main.copyWithCount(1);
        ItemStack off = p.getOffhandItem();
        return off.isEmpty() ? ItemStack.EMPTY : off.copyWithCount(1);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (e.isCanceled() || !DecoConfig.get().actif) return;
        if (!(e.getEntity() instanceof ServerPlayer p) || !(e.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = e.getPos();
        Zones.Home h = Zones.at(p.server, level, pos);
        if (h == null || !h.livesHere(p.getUUID())) return;
        ItemStack item = held(p, e);
        if (item.isEmpty()) return;
        List<Part> parts = new ArrayList<>();
        if (e instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            for (BlockSnapshot snap : multi.getReplacedBlockSnapshots()) {
                BlockPos sp = snap.getPos();
                if (sp.equals(pos)) continue;
                parts.add(new Part(sp.asLong(), Mover.id(level.getBlockState(sp))));
            }
        }
        PlacedLedger.get(p.server).record(h.occ().key(), pos.asLong(),
                new PlacedLedger.Placed(Mover.id(e.getPlacedBlock()), item, p.getUUID(), System.currentTimeMillis(), List.copyOf(parts)));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (e.isCanceled() || !(e.getLevel() instanceof ServerLevel level)) return;
        PlacedLedger.get(level.getServer()).forget(dim(level), e.getPos().asLong());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onExplode(ExplosionEvent.Detonate e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        PlacedLedger l = PlacedLedger.get(level.getServer());
        for (BlockPos p : e.getAffectedBlocks()) l.forget(dim(level), p.asLong());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPiston(PistonEvent.Pre e) {
        if (e.isCanceled() || !(e.getLevel() instanceof ServerLevel level)) return;
        PistonStructureResolver r = e.getStructureHelper();
        if (r == null || !r.resolve()) return;
        PlacedLedger l = PlacedLedger.get(level.getServer());
        for (BlockPos p : r.getToPush()) l.forget(dim(level), p.asLong());
        for (BlockPos p : r.getToDestroy()) l.forget(dim(level), p.asLong());
    }
}
