package fr.minenorth.portes.client;

import fr.minenorth.portes.gang.GangGui;
import fr.minenorth.portes.gang.GangView;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Écran de la Fabrique d'armes (clic droit sur le bloc) : catalogue, file de fabrication, stock prêt, saisie. Le client ne fait que demander ;
 * le serveur vérifie le grade, la zone, la distance, le stock et le compte du chef, puis renvoie l'état à jour.
 */
public class FactoryScreen extends Screen {
    private static final int PANEL_W = 520;

    private GangView v;
    private int page;
    private int ticks;
    private final List<Object[]> texts = new ArrayList<>();
    private final List<int[]> cards = new ArrayList<>();

    public FactoryScreen(GangView view) {
        super(Component.literal("Fabrique d'armes"));
        this.v = view;
    }

    public void update(GangView nv) {
        if (nv.factory == null) return; // réponse d'un autre écran : on garde celui-ci
        this.v = nv;
        rebuildWidgets();
    }

    private GangView.FactoryInfo f() { return v.factory; }

    private int left() { return (width - PANEL_W) / 2; }
    private int panelH() { return Math.min(360, height - 12); }
    private int top() { return Math.max(6, (height - panelH()) / 2); }

    private MineNorthButton btn(int x, int y, int w, int h, String label, int color, Runnable r) {
        return addRenderableWidget(new MineNorthButton(x, y, w, h, Component.literal(label), color, r));
    }

    private void text(int x, int y, String s, int color) { texts.add(new Object[]{x, y, s, color}); }

    private String fit(String s, int w) { return font.plainSubstrByWidth(s, w); }

    private void act(int action, String a, String b) { GangClient.send(action, a, b, f().pos); }

    static String time(int seconds) {
        int s = Math.max(0, seconds);
        return s >= 3600 ? String.format("%dh%02d", s / 3600, (s % 3600) / 60) : String.format("%d:%02d", s / 60, s % 60);
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        texts.clear(); cards.clear();
        GangView.FactoryInfo fi = f();
        if (fi == null) return;
        int x = left() + 16, y = top() + 50, w = PANEL_W - 32;
        btn(left() + PANEL_W - 100, top() + 10, 86, 16, "Fermer", MineNorthButton.GHOST, this::onClose);
        text(x, y, fit(fi.status, w), fi.running ? MineNorthStyle.OK : MineNorthStyle.WARN);

        // ---- catalogue
        int cx = x, cy = y + 16, cw = 250;
        text(cx, cy, "CATALOGUE", MineNorthStyle.BLUE);
        int per = Math.max(2, (panelH() - 140) / 34);
        int pages = Math.max(1, (fi.recipes.size() + per - 1) / per);
        if (page >= pages) page = pages - 1;
        boolean queueFull = fi.queue.size() >= fi.queueMax || fi.queue.size() + fi.stock.size() >= fi.stockMax;
        for (int i = page * per, row = 0; i < fi.recipes.size() && row < per; i++, row++) {
            GangView.Recipe r = fi.recipes.get(i);
            int ry = cy + 14 + row * 34;
            cards.add(new int[]{cx, ry, cw, 30, MineNorthStyle.CYAN});
            text(cx + 8, ry + 4, fit(r.label() + "  x" + r.qty(), 160), MineNorthStyle.WHITE);
            text(cx + 8, ry + 16, time(r.seconds()) + "  -  " + MineNorthStyle.euros(r.cents()), MineNorthStyle.MUTED);
            final int idx = i;
            btn(cx + cw - 78, ry + 5, 74, 20, "Fabriquer", MineNorthStyle.GREEN, () -> act(GangGui.FACTORY_QUEUE, Integer.toString(idx), r.label()))
                    .enabled(fi.canQueue && !queueFull);
        }
        if (pages > 1) {
            int py = cy + 14 + per * 34;
            btn(cx, py, 40, 14, "◀", MineNorthStyle.DARK, () -> { page = Math.max(0, page - 1); rebuildWidgets(); }).enabled(page > 0);
            btn(cx + 44, py, 40, 14, "▶", MineNorthStyle.DARK, () -> { page = Math.min(pages - 1, page + 1); rebuildWidgets(); }).enabled(page < pages - 1);
        }
        if (!fi.canQueue && fi.mine) text(cx, cy + 14 + per * 34 + 18, "Seuls le chef et les lieutenants lancent une fabrication.", MineNorthStyle.MUTED);
        else if (fi.canQueue) text(cx, cy + 14 + per * 34 + 18, "Le prix est prélevé sur le compte du chef.", MineNorthStyle.MUTED);

        // ---- file + stock
        int rx = x + 262, rw = w - 262;
        text(rx, cy, "FILE DE FABRICATION (" + fi.queue.size() + "/" + fi.queueMax + ")", MineNorthStyle.BLUE);
        int qy = cy + 14;
        if (fi.queue.isEmpty()) { text(rx, qy + 2, "Rien en cours.", MineNorthStyle.MUTED); qy += 14; }
        for (int i = 0; i < Math.min(5, fi.queue.size()); i++) {
            GangView.JobInfo j = fi.queue.get(i);
            cards.add(new int[]{rx, qy, rw, 22, MineNorthStyle.PINK});
            text(rx + 8, qy + 3, fit(j.label() + " x" + j.qty(), rw - 70), MineNorthStyle.TEXT);
            int done = j.total() <= 0 ? 0 : (j.total() - j.remaining()) * (rw - 70) / j.total();
            cards.add(new int[]{rx + 8, qy + 15, rw - 70, 3, 0xFF2E2480});
            cards.add(new int[]{rx + 8, qy + 15, Math.max(0, Math.min(rw - 70, done)), 3, fi.running && i == 0 ? MineNorthStyle.OK : MineNorthStyle.MUTED});
            text(rx + rw - 58, qy + 7, time(j.remaining()), i == 0 && fi.running ? MineNorthStyle.OK : MineNorthStyle.MUTED);
            final int idx = i;
            btn(rx + rw - 22, qy + 2, 18, 18, "X", 0xFFC03060, () -> act(GangGui.FACTORY_CANCEL, Integer.toString(idx), "")).enabled(fi.canQueue || (fi.mine && v.myRank >= 0 && v.myRank <= 1));
            qy += 25;
        }
        qy += 6;
        text(rx, qy, "STOCK PRÊT (" + fi.stock.size() + "/" + fi.stockMax + ")", MineNorthStyle.BLUE);
        qy += 14;
        if (fi.stock.isEmpty()) { text(rx, qy + 2, "Rien de prêt.", MineNorthStyle.MUTED); qy += 14; }
        for (int i = 0; i < Math.min(5, fi.stock.size()); i++) {
            GangView.LotInfo l = fi.stock.get(i);
            cards.add(new int[]{rx, qy, rw, 18, MineNorthStyle.OK});
            text(rx + 8, qy + 5, fit(l.qty() + " x " + l.label(), rw - 12), MineNorthStyle.WHITE);
            qy += 21;
        }
        if (fi.stock.size() > 5) { text(rx, qy, "… et " + (fi.stock.size() - 5) + " autre(s) lot(s)", MineNorthStyle.MUTED); qy += 12; }
        btn(rx, qy + 4, rw, 22, "RÉCUPÉRER TOUT", MineNorthStyle.GREEN, () -> act(GangGui.FACTORY_COLLECT, "", "")).enabled(fi.canCollect && !fi.stock.isEmpty());
        if (fi.mine && !fi.canCollect) text(rx, qy + 30, "Récupération : " + fi.collectRank + " au moins.", MineNorthStyle.MUTED);

        if (fi.canSeize) {
            btn(x, top() + panelH() - 52, 250, 22, "SAISIR CETTE FABRIQUE (zone à vous)", 0xFFC03060, () -> act(GangGui.FACTORY_SEIZE, "", ""));
        } else if (!fi.mine) {
            text(x, top() + panelH() - 48, "Fabrique de " + fi.gangName + " : le gang qui tient la zone peut la saisir ou la détruire.", MineNorthStyle.MUTED);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (++ticks % 20 == 0 && f() != null) GangClient.send(GangGui.FACTORY_VIEW, "", "", f().pos);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        GangView.FactoryInfo fi = f();
        String sub = fi == null ? "" : "[" + fi.gangTag + "] " + fi.gangName + "  -  zone : " + fi.zone;
        MineNorthStyle.panel(g, left(), top(), PANEL_W, panelH(), "FABRIQUE D'ARMES", sub);
        if (fi != null) g.fill(left() + PANEL_W - 130, top() + 30, left() + PANEL_W - 14, top() + 36, 0xFF000000 | fi.gangColor);
        for (int[] c : cards) {
            if (c.length >= 5 && c[3] <= 4) g.fill(c[0], c[1], c[0] + c[2], c[1] + c[3], c[4] | 0xFF000000);
            else MineNorthStyle.card(g, c[0], c[1], c[2], c[3], false, c[4]);
        }
        for (Object[] t : texts) g.drawString(font, (String) t[2], (Integer) t[0], (Integer) t[1], (Integer) t[3], false);
        super.render(g, mx, my, pt);
        if (!v.message.isBlank()) g.drawString(font, fit(v.message, PANEL_W - 32), left() + 16, top() + panelH() - 22, v.messageError ? MineNorthStyle.ALERT : MineNorthStyle.OK, false);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
