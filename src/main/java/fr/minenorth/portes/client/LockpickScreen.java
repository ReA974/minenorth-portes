package fr.minenorth.portes.client;

import fr.minenorth.portes.network.ModNetwork;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

/**
 * Mini-jeu de crochetage : 3 goupilles. Un curseur oscille sur une barre, il faut l'arrêter dans la zone verte
 * (clic ou Espace). Une goupille ratée = échec. Le résultat est envoyé au serveur, qui tire ensuite la chance
 * de réussite selon le niveau de la serrure.
 */
public class LockpickScreen extends Screen {
    private static final int PINS = 3;
    private static final int BAR_W = 240;
    private static final int BAR_H = 18;
    /** Largeur de la zone verte et vitesse du curseur, par niveau de serrure (Normal, Avancé, Expert). */
    private static final int[] ZONE_W = {28, 20, 12};
    private static final double[] SPEED = {1.0, 1.4, 1.9};

    private final int security;
    private final Random random = new Random();
    private final long t0 = Util.getMillis();
    private int pin;
    private int zoneStart;
    private boolean failed;
    private boolean done;

    public LockpickScreen(long pos, int security) {
        super(Component.literal("Crochetage"));
        this.security = Math.max(0, Math.min(ZONE_W.length - 1, security - 1));
        newZone();
    }

    private void newZone() {
        zoneStart = random.nextInt(BAR_W - ZONE_W[security]);
    }

    private double cursor() {
        double t = (Util.getMillis() - t0) / 1000.0;
        return (Math.sin(t * SPEED[security] * 3.0) + 1.0) / 2.0 * BAR_W;
    }

    private void stopCursor() {
        if (done) return;
        double c = cursor();
        if (c < zoneStart || c > zoneStart + ZONE_W[security]) {
            failed = true;
            finish(false);
        } else if (++pin >= PINS) {
            finish(true);
        } else {
            newZone();
        }
    }

    private void finish(boolean won) {
        done = true;
        ModNetwork.CHANNEL.sendToServer(new ModNetwork.LockpickResultPacket(won));
        onClose();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) { stopCursor(); return true; }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_ENTER) { stopCursor(); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        int w = BAR_W + 60, h = 130;
        int left = (width - w) / 2, top = (height - h) / 2;
        MineNorthStyle.panel(g, left, top, w, h, "CROCHETAGE", "Serrure " + new String[]{"Normale", "Avancée", "Experte"}[security]);

        int bx = left + 30, by = top + 62;
        g.fill(bx - 1, by - 1, bx + BAR_W + 1, by + BAR_H + 1, MineNorthStyle.FRAME);
        g.fill(bx, by, bx + BAR_W, by + BAR_H, MineNorthStyle.LIST);
        g.fill(bx + zoneStart, by, bx + zoneStart + ZONE_W[security], by + BAR_H, MineNorthStyle.GREEN);
        int cx = bx + (int) cursor();
        g.fill(cx - 1, by - 4, cx + 2, by + BAR_H + 4, failed ? MineNorthStyle.ALERT : MineNorthStyle.WHITE);

        for (int i = 0; i < PINS; i++) {
            g.fill(bx + i * 20, by + BAR_H + 12, bx + i * 20 + 14, by + BAR_H + 20, i < pin ? MineNorthStyle.OK : MineNorthStyle.DISABLED);
        }
        String hint = "Clic ou Espace pour bloquer la goupille dans la zone verte";
        g.drawString(font, hint, left + (w - font.width(hint)) / 2, top + 46, MineNorthStyle.MUTED, false);
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
