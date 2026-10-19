package fr.minenorth.portes.client;

import com.mojang.blaze3d.platform.InputConstants;
import fr.minenorth.portes.MineNorthPortes;
import fr.minenorth.portes.gang.GangGui;
import fr.minenorth.portes.gang.GangView;
import fr.minenorth.portes.network.GangActionPacket;
import fr.minenorth.portes.network.ModNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Côté client des gangs : touche G (« Mon gang ») et réception des mises à jour envoyées par le serveur. */
public final class GangClient {
    private GangClient() {}

    public static final KeyMapping OPEN_KEY = new KeyMapping("key.minenorthportes.gang", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.minenorthportes");

    /** Appelé par le paquet serveur -> client. */
    public static void onView(GangView view) {
        Minecraft mc = Minecraft.getInstance();
        Screen cur = mc.screen;
        if (cur instanceof GangScreen gs) {
            gs.update(view);
        } else if (view.open && cur == null && mc.player != null) {
            mc.setScreen(new GangScreen(view));
        } else if (view.open && !view.invites.isEmpty() && mc.player != null) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§e⚑ Invitation de gang reçue - fermez cet écran puis appuyez sur G."), true);
        }
    }

    public static void send(int action, String a, String b, long pos) { ModNetwork.CHANNEL.sendToServer(new GangActionPacket(action, a == null ? "" : a, b == null ? "" : b, pos)); }

    public static void requestOpen() { send(GangGui.REFRESH, "", "", 0); }

    @Mod.EventBusSubscriber(modid = MineNorthPortes.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModEvents {
        private ModEvents() {}

        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent e) { e.register(OPEN_KEY); }
    }

    @Mod.EventBusSubscriber(modid = MineNorthPortes.MOD_ID, value = Dist.CLIENT)
    public static final class ForgeEvents {
        private ForgeEvents() {}

        @SubscribeEvent
        public static void tick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            while (OPEN_KEY.consumeClick()) {
                if (mc.screen == null && mc.player != null && mc.level != null) requestOpen();
            }
        }
    }
}
