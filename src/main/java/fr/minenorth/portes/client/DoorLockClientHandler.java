package fr.minenorth.portes.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.DoorBlock;
import fr.minenorth.portes.network.ModNetwork;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet;
import java.util.Set;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public final class DoorLockClientHandler {
    private static final Set<String> LOCKED = new HashSet<>();
    private DoorLockClientHandler() {}

    public static void setLocked(Iterable<String> values) {
        LOCKED.clear();
        for (String value : values) LOCKED.add(value);
    }

    public static void clear() { LOCKED.clear(); }

    @SubscribeEvent
    public static void rightClick(PlayerInteractEvent.RightClickBlock event) {
        if (Minecraft.getInstance().level == null || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        if (!(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof DoorBlock)) return;
        BlockPos pos = event.getPos();
        String key = Minecraft.getInstance().level.dimension().location() + "|" + pos.asLong();
        String lower = Minecraft.getInstance().level.dimension().location() + "|" + pos.below().asLong();
        if (LOCKED.contains(key) || LOCKED.contains(lower)) {
            // The client must never let Minecraft play the vanilla door animation
            // for a locked door. We still forward the click to the server so that
            // denial messages and the sneak-click management/request UI work.
            ModNetwork.CHANNEL.sendToServer(new ModNetwork.DoorInteractPacket(pos.asLong(), event.getEntity().isShiftKeyDown()));
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }
}
