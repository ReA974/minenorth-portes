package fr.minenorth.portes.client;

import fr.minenorth.portes.gang.GangGui;
import fr.minenorth.portes.gang.GangView;
import fr.minenorth.portes.network.GangActionPacket;
import fr.minenorth.portes.network.ModNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Côté client des gangs : ouverture depuis le téléphone et réception des mises à jour envoyées par le serveur. */
public final class GangClient {
    private GangClient() {}

    /** Appelé par le paquet serveur -> client. */
    public static void onView(GangView view) {
        Minecraft mc = Minecraft.getInstance();
        Screen cur = mc.screen;
        if (cur instanceof FactoryScreen fs) {
            fs.update(view);
        } else if (view.factory != null && view.open && cur == null && mc.player != null) {
            mc.setScreen(new FactoryScreen(view));
        } else if (cur instanceof GangScreen gs) {
            gs.update(view);
        } else if (view.open && cur == null && mc.player != null) {
            mc.setScreen(new GangScreen(view));
        } else if (view.open && !view.invites.isEmpty() && mc.player != null) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§e⚑ Invitation de gang reçue - ouvrez l'application Mon gang de votre téléphone."), true);
        }
    }

    public static void send(int action, String a, String b, long pos) { ModNetwork.CHANNEL.sendToServer(new GangActionPacket(action, a == null ? "" : a, b == null ? "" : b, pos)); }

    public static void requestOpen() { send(GangGui.REFRESH, "", "", 0); }

    /** Appelé par l'application « Mon gang » du téléphone MRPhone (jar patché, par réflexion) : ferme le téléphone puis ouvre l'écran. */
    public static void openFromPhone() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        mc.setScreen(null);
        requestOpen();
    }
}
