package fr.minenorth.portes.client;

import fr.minenorth.portes.gang.FactoryRecipe;
import fr.minenorth.portes.gang.GangGui;
import fr.minenorth.portes.gang.GangView;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Écran de la Fabrique d'armes (clic droit sur le bloc) : page « Fabrication » (catalogue, file, stock prêt, saisie) et page « Achats »
 * (matières premières payées avec le compte du gang). Le client ne fait que demander ; le serveur vérifie le grade, la zone, la distance,
 * le stock, les matières et le solde, puis renvoie l'état à jour.
 */
public class FactoryScreen extends Screen {
    private static final int PANEL_W = 520;

    private GangView v;
    private int page;
    private int pageIndex;    // 0 = fabrication, 1 = achats
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
    private int panelH() { return Math.min(380, height - 12); }
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

    private String needsText(GangView.FactoryInfo fi, String needs) {
        Map<String, Integer> n = FactoryRecipe.parseNeeds(needs);
        if (n == null || n.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var e : n.entrySet()) {
            String label = e.getKey();
            for (GangView.MatInfo m : fi.materials) if (m.id().equals(e.getKey())) label = m.label();
            if (sb.length() > 0) sb.append(", ");
            sb.append(label).append(" x").append(e.getValue());
        }
        return sb.toString();
    }

    private boolean hasMaterials(GangView.FactoryInfo fi, String needs) {
        Map<String, Integer> n = FactoryRecipe.parseNeeds(needs);
        if (n == null) return false;
        for (var e : n.entrySet()) {
            int have = 0;
            for (GangView.MatInfo m : fi.materials) if (m.id().equals(e.getKey())) have = m.stock();
            if (have < e.getValue()) return false;
        }
        return true;
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        texts.clear(); cards.clear();
        GangView.FactoryInfo fi = f();
        if (fi == null) return;
        int x = left() + 16, w = PANEL_W - 32;
        btn(left() + PANEL_W - 100, top() + 10, 86, 16, "Fermer", MineNorthButton.GHOST, this::onClose);
        // onglets (sous le titre) : Fabrication / Achats ; sur la page Achats, un bouton Retour ramène à la fabrication
        int ty = top() + 46;
        btn(x, ty, 110, 18, "Fabrication", pageIndex == 0 ? MineNorthStyle.PINK : MineNorthStyle.DARK, () -> { pageIndex = 0; page = 0; rebuildWidgets(); });
        btn(x + 114, ty, 110, 18, "Achats", pageIndex == 1 ? MineNorthStyle.PINK : MineNorthStyle.DARK, () -> { pageIndex = 1; page = 0; rebuildWidgets(); });
        if (pageIndex == 1) btn(x + w - 90, ty, 90, 18, "◀ Retour", MineNorthStyle.DARK, () -> { pageIndex = 0; page = 0; rebuildWidgets(); });
        else text(x + 232, ty + 5, fit("Compte du gang : " + MineNorthStyle.euros(fi.balance), w - 232), MineNorthStyle.WARN);
        int y = ty + 26;
        text(x, y, fit(fi.status, w), fi.running ? MineNorthStyle.OK : MineNorthStyle.WARN);
        if (pageIndex == 1) buildShop(fi, x, y + 16, w); else buildProduction(fi, x, y + 16, w);
        if (fi.canSeize) {
            btn(x, top() + panelH() - 52, 250, 22, "SAISIR CETTE FABRIQUE (zone à vous)", 0xFFC03060, () -> act(GangGui.FACTORY_SEIZE, "", ""));
        } else if (!fi.mine) {
            text(x, top() + panelH() - 48, fit("Fabrique de " + fi.gangName + " : le gang qui tient la zone peut la saisir ou la détruire.", w), MineNorthStyle.MUTED);
        }
    }

    // ---- page Fabrication
    private void buildProduction(GangView.FactoryInfo fi, int x, int y, int w) {
        int cw = 250;
        text(x, y, "CATALOGUE", MineNorthStyle.BLUE);
        int rowH = 42;
        int avail = top() + panelH() - 64 - (y + 14);
        int per = Math.max(2, avail / rowH);
        int pages = Math.max(1, (fi.recipes.size() + per - 1) / per);
        if (page >= pages) page = pages - 1;
        boolean queueFull = fi.queue.size() >= fi.queueMax || fi.queue.size() + fi.stock.size() >= fi.stockMax;
        for (int i = page * per, row = 0; i < fi.recipes.size() && row < per; i++, row++) {
            GangView.Recipe r = fi.recipes.get(i);
            int ry = y + 14 + row * rowH;
            boolean mats = hasMaterials(fi, r.needs());
            boolean money = fi.balance >= r.cents();
            cards.add(new int[]{x, ry, cw, rowH - 4, mats && money ? MineNorthStyle.CYAN : 0xFF8A3040});
            text(x + 8, ry + 4, fit(r.label() + "  x" + r.qty(), 160), MineNorthStyle.WHITE);
            text(x + 8, ry + 15, fit(time(r.seconds()) + (r.cents() > 0 ? "  -  " + MineNorthStyle.euros(r.cents()) : ""), 160), MineNorthStyle.MUTED);
            String nt = needsText(fi, r.needs());
            text(x + 8, ry + 26, fit(nt.isEmpty() ? "Aucune matière" : nt, 160), mats ? MineNorthStyle.TEXT : MineNorthStyle.ALERT);
            final int idx = i;
            btn(x + cw - 78, ry + 8, 74, 20, "Fabriquer", MineNorthStyle.GREEN, () -> act(GangGui.FACTORY_QUEUE, Integer.toString(idx), r.label()))
                    .enabled(fi.canQueue && !queueFull && mats && money);
        }
        int py = y + 14 + per * rowH;
        if (pages > 1) {
            btn(x, py, 40, 14, "◀", MineNorthStyle.DARK, () -> { page = Math.max(0, page - 1); rebuildWidgets(); }).enabled(page > 0);
            btn(x + 44, py, 40, 14, "▶", MineNorthStyle.DARK, () -> { page = Math.min(pages - 1, page + 1); rebuildWidgets(); }).enabled(page < pages - 1);
        }
        if (fi.mine && !fi.canSpend) text(x, py + 18, fit("Dépenses : " + fi.spendRank + " seulement.", cw), MineNorthStyle.MUTED);
        else if (fi.canQueue) text(x, py + 18, fit("Matières : onglet Achats. Prix : compte du gang.", cw), MineNorthStyle.MUTED);

        int rx = x + 262, rw = w - 262;
        text(rx, y, "FILE DE FABRICATION (" + fi.queue.size() + "/" + fi.queueMax + ")", MineNorthStyle.BLUE);
        int qy = y + 14;
        if (fi.queue.isEmpty()) { text(rx, qy + 2, "Rien en cours.", MineNorthStyle.MUTED); qy += 14; }
        int maxJobs = Math.max(1, Math.min(4, (panelH() - 250) / 25));
        int maxLots = Math.max(1, Math.min(3, (panelH() - 300) / 21));
        for (int i = 0; i < Math.min(maxJobs, fi.queue.size()); i++) {
            GangView.JobInfo j = fi.queue.get(i);
            cards.add(new int[]{rx, qy, rw, 22, MineNorthStyle.PINK});
            text(rx + 8, qy + 3, fit(j.label() + " x" + j.qty(), rw - 76), MineNorthStyle.TEXT);
            int done = j.total() <= 0 ? 0 : (j.total() - j.remaining()) * (rw - 76) / j.total();
            cards.add(new int[]{rx + 8, qy + 15, rw - 76, 3, 0xFF2E2480});
            cards.add(new int[]{rx + 8, qy + 15, Math.max(0, Math.min(rw - 76, done)), 3, fi.running && i == 0 ? MineNorthStyle.OK : MineNorthStyle.MUTED});
            text(rx + rw - 64, qy + 7, time(j.remaining()), i == 0 && fi.running ? MineNorthStyle.OK : MineNorthStyle.MUTED);
            final int idx = i;
            btn(rx + rw - 22, qy + 2, 18, 18, "X", 0xFFC03060, () -> act(GangGui.FACTORY_CANCEL, Integer.toString(idx), "")).enabled(fi.canSpend);
            qy += 25;
        }
        if (fi.queue.size() > maxJobs) { text(rx, qy, "… et " + (fi.queue.size() - maxJobs) + " autre(s)", MineNorthStyle.MUTED); qy += 12; }
        qy += 6;
        text(rx, qy, "STOCK PRÊT (" + fi.stock.size() + "/" + fi.stockMax + ")", MineNorthStyle.BLUE);
        qy += 14;
        if (fi.stock.isEmpty()) { text(rx, qy + 2, "Rien de prêt.", MineNorthStyle.MUTED); qy += 14; }
        for (int i = 0; i < Math.min(maxLots, fi.stock.size()); i++) {
            GangView.LotInfo l = fi.stock.get(i);
            cards.add(new int[]{rx, qy, rw, 18, MineNorthStyle.OK});
            text(rx + 8, qy + 5, fit(l.qty() + " x " + l.label(), rw - 12), MineNorthStyle.WHITE);
            qy += 21;
        }
        if (fi.stock.size() > maxLots) { text(rx, qy, "… et " + (fi.stock.size() - maxLots) + " autre(s) lot(s)", MineNorthStyle.MUTED); qy += 12; }
        btn(rx, qy + 4, rw, 22, "RÉCUPÉRER TOUT", MineNorthStyle.GREEN, () -> act(GangGui.FACTORY_COLLECT, "", "")).enabled(fi.canCollect && !fi.stock.isEmpty());
        if (fi.mine && !fi.canCollect) text(rx, qy + 30, fit("Récupération : " + fi.collectRank + " au moins.", rw), MineNorthStyle.MUTED);
    }

    // ---- page Achats
    private void buildShop(GangView.FactoryInfo fi, int x, int y, int w) {
        text(x, y, "MATIÈRES PREMIÈRES (payées avec le compte du gang : " + MineNorthStyle.euros(fi.balance) + ")", MineNorthStyle.BLUE);
        text(x, y + 12, fit(fi.canSpend ? "Chaque recette consomme ces matières au lancement." : "Seuls " + fi.spendRank + " peuvent acheter (réglé par le chef).", w), fi.canSpend ? MineNorthStyle.MUTED : MineNorthStyle.WARN);
        int rowH = 30;
        int avail = top() + panelH() - 64 - (y + 30);
        int per = Math.max(2, avail / rowH);
        int pages = Math.max(1, (fi.materials.size() + per - 1) / per);
        if (page >= pages) page = pages - 1;
        if (fi.materials.isEmpty()) text(x, y + 36, "Aucune matière n'est vendue pour le moment.", MineNorthStyle.MUTED);
        for (int i = page * per, row = 0; i < fi.materials.size() && row < per; i++, row++) {
            GangView.MatInfo m = fi.materials.get(i);
            int ry = y + 30 + row * rowH;
            cards.add(new int[]{x, ry, w, rowH - 4, MineNorthStyle.CYAN});
            text(x + 8, ry + 4, fit(m.label(), 150), MineNorthStyle.WHITE);
            text(x + 8, ry + 14, fit("Réserve " + m.stock() + "/" + m.cap(), 150), MineNorthStyle.MUTED);
            text(x + 170, ry + 9, fit("+" + m.pack() + " pour " + MineNorthStyle.euros(m.cents()), 150), MineNorthStyle.TEXT);
            boolean can1 = fi.canSpend && fi.balance >= m.cents() && m.stock() + m.pack() <= m.cap();
            boolean can5 = fi.canSpend && fi.balance >= m.cents() * 5 && m.stock() + (long) m.pack() * 5 <= m.cap();
            btn(x + w - 150, ry + 3, 70, 20, "Acheter x1", MineNorthStyle.GREEN, () -> act(GangGui.FACTORY_BUY, m.id(), "1")).enabled(can1);
            btn(x + w - 76, ry + 3, 72, 20, "Acheter x5", MineNorthStyle.GREEN, () -> act(GangGui.FACTORY_BUY, m.id(), "5")).enabled(can5);
        }
        if (pages > 1) {
            int py = y + 30 + per * rowH;
            btn(x, py, 40, 14, "◀", MineNorthStyle.DARK, () -> { page = Math.max(0, page - 1); rebuildWidgets(); }).enabled(page > 0);
            btn(x + 44, py, 40, 14, "▶", MineNorthStyle.DARK, () -> { page = Math.min(pages - 1, page + 1); rebuildWidgets(); }).enabled(page < pages - 1);
            text(x + 92, py + 3, "Page " + (page + 1) + "/" + pages, MineNorthStyle.MUTED);
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
