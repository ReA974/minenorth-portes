package fr.minenorth.portes.client;

import fr.minenorth.portes.gang.GangGui;
import fr.minenorth.portes.gang.GangView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Écran « Mon gang » (application « Mon gang » du téléphone, ou Carnet de gang). Tout se fait ici : membres, bases, territoires, paramètres, invitations,
 * et la petite fenêtre « porte » (clic droit accroupi sur une porte). Le client ne décide de rien : chaque bouton envoie
 * une intention au serveur, qui revérifie les droits puis renvoie l'état à jour.
 */
public class GangScreen extends Screen {
    private static final int PANEL_W = 520;
    private static final int[] SWATCHES = {0xE53935, 0xFB8C00, 0xFDD835, 0x43A047, 0x00ACC1, 0x1E88E5, 0x8E24AA, 0xEC407A, 0xF5F5F5, 0x757575, 0x6D4C41, 0x212121};
    private static final String[] RANKS = {"Chef", "Lieutenant", "Membre"};

    private GangView v;
    private int tab = 0;
    private int page = 0;
    private boolean showLog;
    private boolean doorOnly, doorClosed;
    private int ticks;

    // saisies conservées entre deux reconstructions
    private String fName = "", fTag = "", fRadius = "40", fConfirm = "", fTerrName = "";
    private int fColor = SWATCHES[0];
    private String pendingChef = "", pendingKick = "", pendingAdmin = "";
    private boolean leaveStage, dissolveStage;
    private EditBox eName, eTag, eConfirm, eTerr, eRadius, eIncZone, eIncCap, eIncInt;
    private int adminPage;
    private String fIncZone, fIncCap, fIncInt;
    private Boolean fOnline;
    private final java.util.Map<String, String> zoneVals = new java.util.HashMap<>();
    private final List<Object[]> zoneBoxes = new ArrayList<>();
    private Boolean fFacOn;
    private String fFacMax, fFacStock, fFacQueue, fFacRank;
    private EditBox eFacMax, eFacStock, eFacQueue;
    private final java.util.Map<Integer, String[]> recipeVals = new java.util.HashMap<>();
    private final List<Object[]> recipeBoxes = new ArrayList<>();

    private final List<Object[]> texts = new ArrayList<>();
    private final List<int[]> cards = new ArrayList<>();
    private final List<int[]> swatchRects = new ArrayList<>();
    private final List<Object[]> mapRects = new ArrayList<>();

    public GangScreen(GangView view) {
        super(Component.literal("Mon gang"));
        this.v = view;
        this.fColor = view.color;
        this.fName = view.name;
        this.fTag = view.tag;
        this.doorOnly = view.doorMode != 0;
    }

    /** Nouvel état envoyé par le serveur (après une action, ou rafraîchissement automatique). */
    public void update(GangView nv) {
        boolean wasDoor = v.doorMode != 0;
        boolean action = !nv.message.isBlank() || nv.doorMode != 0 || wasDoor;
        boolean same = !action && sameContent(v, nv);
        keep();
        this.v = nv;
        if (nv.inGang && fName.isBlank()) { fName = nv.name; fTag = nv.tag; fColor = nv.color; }
        if (doorOnly && nv.doorMode == 0 && wasDoor) {
            // La fenêtre « porte » est terminée : on referme et on dit simplement ce qui s'est passé.
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && !nv.message.isBlank()) mc.player.displayClientMessage(Component.literal((nv.messageError ? "§c" : "§a") + nv.message), true);
            onClose();
            return;
        }
        if (same) return; // rafraîchissement automatique sans changement : on ne touche pas aux champs en cours de saisie
        if (action && !nv.messageError) { fIncZone = null; fIncCap = null; fIncInt = null; fOnline = null; zoneVals.clear();
            fFacOn = null; fFacMax = null; fFacStock = null; fFacQueue = null; fFacRank = null; recipeVals.clear(); }
        if (action) { leaveStage = false; if (nv.messageError) { dissolveStage = false; pendingChef = ""; pendingKick = ""; pendingAdmin = ""; } }
        rebuildWidgets();
    }

    private static boolean sameContent(GangView a, GangView b) {
        net.minecraft.network.FriendlyByteBuf x = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        net.minecraft.network.FriendlyByteBuf y = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        a.encode(x); b.encode(y);
        return x.equals(y);
    }

    private void act(int action, String a, String b, long pos) { keep(); GangClient.send(action, a, b, pos); }

    private int left() { return (width - PANEL_W) / 2; }
    private int panelH() { return Math.min(372, height - 12); }
    private int top() { return Math.max(6, (height - panelH()) / 2); }
    private int cx() { return left() + 16; }
    private int cy() { return top() + 72; }
    private int cw() { return PANEL_W - 32; }
    private int ch() { return panelH() - 72 - 34; }
    private int rows() { return Math.max(3, (ch() - 24) / 22); }

    private MineNorthButton btn(int x, int y, int w, int h, String label, int color, Runnable r) {
        return addRenderableWidget(new MineNorthButton(x, y, w, h, Component.literal(label), color, r));
    }

    private EditBox box(int x, int y, int w, String hint, String value, int max) {
        EditBox b = new EditBox(font, x, y, w, 18, Component.literal(hint));
        b.setHint(Component.literal(hint));
        b.setMaxLength(max);
        b.setValue(value == null ? "" : value);
        addRenderableWidget(b);
        return b;
    }

    private void text(int x, int y, String s, int color) { texts.add(new Object[]{x, y, s, color}); }

    private String fit(String s, int w) { return font.plainSubstrByWidth(s, w); }

    private void keep() {
        if (eName != null) fName = eName.getValue();
        if (eTag != null) fTag = eTag.getValue();
        if (eConfirm != null) fConfirm = eConfirm.getValue();
        if (eTerr != null) fTerrName = eTerr.getValue();
        if (eRadius != null) fRadius = eRadius.getValue();
        if (eIncZone != null) fIncZone = eIncZone.getValue();
        if (eIncCap != null) fIncCap = eIncCap.getValue();
        if (eIncInt != null) fIncInt = eIncInt.getValue();
        if (eFacMax != null) fFacMax = eFacMax.getValue();
        if (eFacStock != null) fFacStock = eFacStock.getValue();
        if (eFacQueue != null) fFacQueue = eFacQueue.getValue();
        for (Object[] rb : recipeBoxes) {
            EditBox[] bx = (EditBox[]) rb[1];
            String[] vals = new String[bx.length];
            for (int k = 0; k < bx.length; k++) vals[k] = bx[k].getValue();
            recipeVals.put((Integer) rb[0], vals);
        }
        for (Object[] zb : zoneBoxes) zoneVals.put((String) zb[0], ((EditBox) zb[1]).getValue());
    }

    private void setTab(int t) {
        keep();
        tab = t; page = 0; leaveStage = false; dissolveStage = false; pendingChef = ""; pendingKick = ""; pendingAdmin = ""; fConfirm = "";
        rebuildWidgets();
    }

    // ------------------------------------------------------------------ construction

    private List<String> tabNames() {
        List<String> t = new ArrayList<>();
        if (v.inGang) { t.add("Membres"); t.add("Bases"); t.add("Territoires"); t.add("Paramètres"); }
        else { t.add("Fonder un gang"); t.add("Invitations" + (v.invites.isEmpty() ? "" : " (" + v.invites.size() + ")")); t.add("Territoires"); }
        if (v.op) t.add("Admin");
        return t;
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        texts.clear(); cards.clear(); swatchRects.clear(); mapRects.clear();
        eName = eTag = eConfirm = eTerr = eRadius = eIncZone = eIncCap = eIncInt = null;
        zoneBoxes.clear();
        recipeBoxes.clear();
        eFacMax = eFacStock = eFacQueue = null;
        if (v.doorMode != 0 && !doorClosed) { buildDoor(); return; }
        int x = left(), y = top(), w = PANEL_W;
        btn(x + w - 100, y + 10, 86, 16, "Fermer", MineNorthButton.GHOST, this::onClose);
        List<String> names = tabNames();
        if (tab >= names.size()) tab = 0;
        int tw = Math.min(110, (w - 32) / names.size() - 4);
        for (int i = 0; i < names.size(); i++) {
            final int idx = i;
            btn(x + 16 + i * (tw + 4), y + 46, tw, 18, names.get(i), i == tab ? MineNorthStyle.PINK : MineNorthStyle.DARK, () -> setTab(idx));
        }
        String name = names.get(tab);
        if (name.startsWith("Membres")) buildMembers();
        else if (name.startsWith("Bases")) buildBases();
        else if (name.startsWith("Territoires")) buildTerritories();
        else if (name.startsWith("Paramètres")) buildSettings();
        else if (name.startsWith("Fonder")) buildCreate();
        else if (name.startsWith("Invitations")) buildInvites();
        else if (name.startsWith("Admin")) buildAdmin();
    }

    private void pager(int total) {
        int per = rows();
        int pages = Math.max(1, (total + per - 1) / per);
        if (page >= pages) page = pages - 1;
        if (pages <= 1) return;
        int y = top() + panelH() - 56;
        btn(cx(), y, 60, 16, "◀", MineNorthStyle.DARK, () -> { page = Math.max(0, page - 1); rebuildWidgets(); }).enabled(page > 0);
        btn(cx() + 66, y, 60, 16, "▶", MineNorthStyle.DARK, () -> { page = Math.min(pages - 1, page + 1); rebuildWidgets(); }).enabled(page < pages - 1);
        text(cx() + 134, y + 4, "Page " + (page + 1) + "/" + pages, MineNorthStyle.MUTED);
    }

    // ---- Membres
    private void buildMembers() {
        int x = cx(), y = cy();
        boolean lead = v.myRank >= 0 && v.myRank <= 1, chef = v.myRank == 0;
        int listW = lead ? 318 : cw();
        text(x, y, "MEMBRES (" + v.members.size() + "/" + v.membersMax + ")", MineNorthStyle.BLUE);
        int per = rows();
        pager(v.members.size());
        for (int i = page * per, row = 0; i < v.members.size() && row < per; i++, row++) {
            GangView.Member m = v.members.get(i);
            int ry = y + 14 + row * 22;
            cards.add(new int[]{x, ry, listW, 20, 0xFF000000 | v.color});
            text(x + 8, ry + 6, fit(m.name(), 110), m.online() ? MineNorthStyle.OK : MineNorthStyle.MUTED);
            text(x + 120, ry + 6, RANKS[Math.max(0, Math.min(2, m.rank()))], m.rank() == 0 ? MineNorthStyle.WARN : MineNorthStyle.TEXT);
            if (!lead || m.rank() <= v.myRank) continue;
            int bx = x + 190;
            if (chef) {
                if (m.rank() == 2) btn(bx, ry + 1, 62, 18, "Lieut.", MineNorthStyle.CYAN, () -> act(GangGui.SET_RANK, m.uuid(), "lieutenant", 0));
                else btn(bx, ry + 1, 62, 18, "Membre", MineNorthStyle.DARK, () -> act(GangGui.SET_RANK, m.uuid(), "membre", 0));
                boolean armed = pendingChef.equals(m.uuid());
                btn(bx + 64, ry + 1, 28, 18, armed ? "OK ?" : "Chef", armed ? 0xFFB8860B : MineNorthStyle.PINK, () -> {
                    if (armed) act(GangGui.SET_RANK, m.uuid(), "chef", 0); else { pendingChef = m.uuid(); rebuildWidgets(); }
                });
            }
            boolean kArmed = pendingKick.equals(m.uuid());
            btn(bx + 96, ry + 1, 30, 18, kArmed ? "OK ?" : "Excl.", 0xFFC03060, () -> {
                if (kArmed) act(GangGui.KICK, m.uuid(), "", 0); else { pendingKick = m.uuid(); rebuildWidgets(); }
            });
        }
        if (lead) {
            int rx = x + 330;
            text(rx, y, "INVITER UN JOUEUR PROCHE", MineNorthStyle.BLUE);
            if (v.nearby.isEmpty()) {
                text(rx, y + 16, "Personne autour de vous", MineNorthStyle.MUTED);
                text(rx, y + 28, "(48 blocs, sans gang)", MineNorthStyle.MUTED);
            }
            for (int i = 0; i < Math.min(8, v.nearby.size()); i++) {
                GangView.Nearby n = v.nearby.get(i);
                int ry = y + 14 + i * 22;
                cards.add(new int[]{rx, ry, 172, 20, MineNorthStyle.CYAN});
                text(rx + 8, ry + 6, fit(n.name(), 90), MineNorthStyle.TEXT);
                btn(rx + 100, ry + 1, 70, 18, "Inviter", MineNorthStyle.GREEN, () -> act(GangGui.INVITE, n.uuid(), "", 0));
            }
        }
    }

    // ---- Bases
    private void buildBases() {
        int x = cx(), y = cy();
        boolean lead = v.myRank >= 0 && v.myRank <= 1;
        text(x, y, "BASE DU GANG", MineNorthStyle.BLUE);
        String baseTxt = v.hasBase ? "Base : " + net.minecraft.core.BlockPos.of(v.basePos).toShortString().replace(",", " ") + " (rayon " + v.baseRadius + ")" : "Pas de base définie";
        text(x + 110, y, baseTxt + "   Portes " + v.doors.size() + "/" + v.doorsMax + "   Fabriques " + v.factoriesCount + "/" + v.factoriesMax, MineNorthStyle.TEXT);
        text(x, y + 12, "Porte libre : clic droit accroupi, dans une zone que vous contrôlez ou près de la base. Fabrique : voir l'onglet Territoires.", MineNorthStyle.MUTED);
        if (v.myRank == 0) btn(x, y + 24, 170, 16, "Définir la base ici", MineNorthStyle.GREEN, () -> act(GangGui.SET_BASE, "", "", 0));
        if (v.myRank == 0 || v.op) {
            btn(x + cw() - 150, y + 24, 150, 16, showLog ? "Voir les portes" : "Journal du gang", MineNorthStyle.DARK, () -> { showLog = !showLog; page = 0; rebuildWidgets(); });
        }
        int per = Math.max(3, rows() - 1);
        int top = y + 44;
        if (showLog && (v.myRank == 0 || v.op)) {
            text(x + 180, y + 28, "JOURNAL DU GANG (du plus récent au plus ancien)", MineNorthStyle.BLUE);
            if (v.log.isEmpty()) text(x, top + 4, "Rien pour le moment.", MineNorthStyle.MUTED);
            int pages = Math.max(1, (v.log.size() + per - 1) / per);
            if (page >= pages) page = pages - 1;
            for (int i = page * per, row = 0; i < v.log.size() && row < per; i++, row++) {
                text(x + 4, top + row * 14, fit(v.log.get(i), cw() - 8), MineNorthStyle.TEXT);
            }
            logPager(pages);
            return;
        }
        text(x + 180, y + 28, "PORTES DU GANG", MineNorthStyle.BLUE);
        if (v.doors.isEmpty()) text(x, top + 4, "Aucune porte. Accroupissez-vous devant une porte libre près de votre Coffre de gang.", MineNorthStyle.MUTED);
        int pages = Math.max(1, (v.doors.size() + per - 1) / per);
        if (page >= pages) page = pages - 1;
        for (int i = page * per, row = 0; i < v.doors.size() && row < per; i++, row++) {
            GangView.DoorInfo d = v.doors.get(i);
            int ry = top + row * 22;
            net.minecraft.core.BlockPos bp = net.minecraft.core.BlockPos.of(d.pos());
            cards.add(new int[]{x, ry, cw(), 20, 0xFF000000 | v.color});
            text(x + 8, ry + 6, bp.getX() + "  " + bp.getY() + "  " + bp.getZ() + "   (" + d.dim().replace("minecraft:", "") + ")", MineNorthStyle.TEXT);
            text(x + 250, ry + 6, "ouverte aux : " + RANKS[Math.max(0, Math.min(2, d.minRank()))].toLowerCase(Locale.ROOT) + "s et au-dessus", MineNorthStyle.MUTED);
            if (lead) btn(x + cw() - 70, ry + 1, 66, 18, "Retirer", MineNorthStyle.DARK, () -> act(GangGui.DOOR_REMOVE_REMOTE, d.dim(), "", d.pos()));
        }
        logPager(pages);
    }

    private void logPager(int pages) {
        if (pages <= 1) return;
        int y = top() + panelH() - 56;
        btn(cx(), y, 60, 16, "◀", MineNorthStyle.DARK, () -> { page = Math.max(0, page - 1); rebuildWidgets(); }).enabled(page > 0);
        btn(cx() + 66, y, 60, 16, "▶", MineNorthStyle.DARK, () -> { page = Math.min(pages - 1, page + 1); rebuildWidgets(); }).enabled(page < pages - 1);
        text(cx() + 134, y + 4, "Page " + (page + 1) + "/" + pages, MineNorthStyle.MUTED);
    }

    // ---- Territoires
    private void buildTerritories() {
        int x = cx(), y = cy();
        text(x, y, "TERRITOIRES", MineNorthStyle.BLUE);
        String dimNow = Minecraft.getInstance().level == null ? "" : Minecraft.getInstance().level.dimension().location().toString();
        List<GangView.Terr> here = new ArrayList<>();
        for (GangView.Terr t : v.territories) if (t.dim().equals(dimNow)) here.add(t);
        int mapX = x, mapY = y + 14, mapW = 300, mapH = ch() - 52;
        cards.add(new int[]{mapX, mapY, mapW, mapH, 0xFF2E2480});
        if (here.isEmpty()) {
            text(mapX + 10, mapY + 10, v.territories.isEmpty() ? "Aucun territoire défini pour le moment." : "Aucun territoire dans cette dimension.", MineNorthStyle.MUTED);
        } else {
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (GangView.Terr t : here) { minX = Math.min(minX, t.x1()); minZ = Math.min(minZ, t.z1()); maxX = Math.max(maxX, t.x2()); maxZ = Math.max(maxZ, t.z2()); }
            double spanX = Math.max(1, maxX - minX), spanZ = Math.max(1, maxZ - minZ);
            double sc = Math.min((mapW - 20) / spanX, (mapH - 20) / spanZ);
            for (GangView.Terr t : here) {
                int rx = mapX + 10 + (int) ((t.x1() - minX) * sc), rz = mapY + 10 + (int) ((t.z1() - minZ) * sc);
                int rw = Math.max(6, (int) ((t.x2() - t.x1()) * sc)), rh = Math.max(6, (int) ((t.z2() - t.z1()) * sc));
                mapRects.add(new Object[]{rx, rz, rw, rh, t});
            }
        }
        int lx = x + 312;
        int per = Math.max(3, (ch() - 40) / 34);
        int pages = Math.max(1, (v.territories.size() + per - 1) / per);
        if (page >= pages) page = pages - 1;
        for (int i = page * per, row = 0; i < v.territories.size() && row < per; i++, row++) {
            GangView.Terr t = v.territories.get(i);
            int ry = y + 14 + row * 34;
            cards.add(new int[]{lx, ry, 192, 30, 0xFF000000 | t.holderColor()});
            text(lx + 8, ry + 4, fit(t.name(), 176), MineNorthStyle.WHITE);
            String holder = t.holderTag().isEmpty() ? "libre" : "[" + t.holderTag() + "] " + t.holderName();
            text(lx + 8, ry + 16, fit(holder + (t.status().isEmpty() ? "" : "  " + t.status()), 176), t.mine() ? MineNorthStyle.OK : MineNorthStyle.MUTED);
        }
        if (pages > 1) {
            int py = y + 14 + per * 34;
            btn(lx, py, 40, 14, "◀", MineNorthStyle.DARK, () -> { page = Math.max(0, page - 1); rebuildWidgets(); }).enabled(page > 0);
            btn(lx + 44, py, 40, 14, "▶", MineNorthStyle.DARK, () -> { page = Math.min(pages - 1, page + 1); rebuildWidgets(); }).enabled(page < pages - 1);
        }
        String income = "Revenu par défaut : " + MineNorthStyle.euros(v.incomePerZone) + " par zone toutes les " + v.incomeMinutes + " min (survolez une zone pour son revenu)"
                + (v.incomeCap > 0 ? " (plafond " + MineNorthStyle.euros(v.incomeCap) + "/h)" : "") + ", sur le compte du chef.";
        text(x, y + ch() - 30, income, MineNorthStyle.TEXT);
        text(x, y + ch() - 18, v.inGang ? "Votre gang tient " + v.zonesHeld + " zone(s). Une zone contrôlée permet de poser une Fabrique d'armes (clic droit dessus pour l'utiliser)." : "Rejoignez un gang pour prendre des zones.", MineNorthStyle.MUTED);
    }

    // ---- Paramètres
    private void buildSettings() {
        int x = cx(), y = cy();
        boolean chef = v.myRank == 0;
        text(x, y, "GANG", MineNorthStyle.BLUE);
        if (chef) {
            text(x, y + 14, "Nom", MineNorthStyle.MUTED);
            eName = box(x, y + 24, 220, "Nom du gang", fName, 20);
            text(x + 232, y + 14, "Tag (2-4)", MineNorthStyle.MUTED);
            eTag = box(x + 232, y + 24, 70, "TAG", fTag, 4);
            text(x, y + 50, "Couleur", MineNorthStyle.MUTED);
            swatches(x, y + 62);
            btn(x, y + 90, 200, 20, "ENREGISTRER", MineNorthStyle.GREEN, () -> act(GangGui.SETTINGS, eName.getValue(), eTag.getValue().toUpperCase(Locale.ROOT) + "|" + String.format("#%06X", fColor), 0));
        } else {
            cards.add(new int[]{x, y + 14, 300, 20, 0xFF000000 | v.color});
            text(x + 8, y + 20, v.name + "  [" + v.tag + "]", MineNorthStyle.WHITE);
            text(x, y + 40, "Seul le chef modifie le nom, le tag et la couleur.", MineNorthStyle.MUTED);
        }
        int by = y + 124;
        text(x, by - 12, "DANGER", MineNorthStyle.ALERT);
        // Quitter (double confirmation)
        if (!leaveStage) btn(x, by, 200, 20, "Quitter le gang", MineNorthStyle.DARK, () -> { leaveStage = true; rebuildWidgets(); });
        else {
            text(x, by + 2, "Quitter vraiment ?", MineNorthStyle.WARN);
            btn(x + 120, by, 120, 20, "OUI, QUITTER", MineNorthStyle.PINK, () -> act(GangGui.LEAVE, "", "", 0));
            btn(x + 246, by, 70, 20, "Annuler", MineNorthStyle.DARK, () -> { leaveStage = false; rebuildWidgets(); });
        }
        if (chef) {
            int dy = by + 30;
            if (!dissolveStage) btn(x, dy, 200, 20, "Dissoudre le gang", MineNorthStyle.PINK, () -> { dissolveStage = true; fConfirm = ""; rebuildWidgets(); });
            else {
                text(x, dy + 2, "Définitif (coffre vide, portes libérées). Tapez le tag « " + v.tag + " » :", MineNorthStyle.WARN);
                eConfirm = box(x, dy + 14, 80, v.tag, fConfirm, 4);
                btn(x + 90, dy + 13, 150, 20, "DISSOUDRE POUR DE BON", MineNorthStyle.PINK, () -> {
                    if (eConfirm != null && eConfirm.getValue().equalsIgnoreCase(v.tag)) act(GangGui.DISSOLVE, "", "1", 0);
                });
                btn(x + 246, dy + 13, 70, 20, "Annuler", MineNorthStyle.DARK, () -> { dissolveStage = false; rebuildWidgets(); });
            }
        }
    }

    private void swatches(int x, int y) {
        for (int i = 0; i < SWATCHES.length; i++) {
            final int c = SWATCHES[i];
            swatchRects.add(new int[]{x + i * 22, y, 18, 18, c});
            btn(x + i * 22, y, 18, 18, "", MineNorthButton.GHOST, () -> { keep(); fColor = c; rebuildWidgets(); });
        }
    }

    // ---- Fonder un gang
    private void buildCreate() {
        int x = cx(), y = cy();
        text(x, y, "FONDER UN GANG", MineNorthStyle.BLUE);
        text(x, y + 14, "Nom", MineNorthStyle.MUTED);
        eName = box(x, y + 24, 220, "Nom du gang (3-20 caractères)", fName, 20);
        text(x + 232, y + 14, "Tag (2-4)", MineNorthStyle.MUTED);
        eTag = box(x + 232, y + 24, 70, "TAG", fTag, 4);
        text(x, y + 50, "Couleur", MineNorthStyle.MUTED);
        swatches(x, y + 62);
        text(x, y + 92, "Coût : " + (v.createCost > 0 ? MineNorthStyle.euros(v.createCost) + " (carte bancaire sur vous)" : "gratuit"), MineNorthStyle.TEXT);
        btn(x, y + 108, 220, 22, "FONDER LE GANG", MineNorthStyle.GREEN, () -> act(GangGui.CREATE, eName.getValue(), eTag.getValue().toUpperCase(Locale.ROOT) + "|" + String.format("#%06X", fColor), 0));
        text(x, y + 140, "Un gang : un chef, des lieutenants, des membres. Une base avec ses portes et un coffre commun,", MineNorthStyle.MUTED);
        text(x, y + 152, "et des territoires à tenir pour toucher un petit revenu.", MineNorthStyle.MUTED);
    }

    // ---- Invitations
    private void buildInvites() {
        int x = cx(), y = cy();
        text(x, y, "INVITATIONS REÇUES", MineNorthStyle.BLUE);
        if (v.invites.isEmpty()) text(x, y + 16, "Aucune invitation. Un chef ou un lieutenant peut vous inviter s'il est près de vous.", MineNorthStyle.MUTED);
        int per = rows();
        pager(v.invites.size());
        for (int i = page * per, row = 0; i < v.invites.size() && row < per; i++, row++) {
            GangView.Invite inv = v.invites.get(i);
            int ry = y + 14 + row * 24;
            cards.add(new int[]{x, ry, cw(), 22, 0xFF000000 | inv.color()});
            text(x + 8, ry + 7, fit("[" + inv.tag() + "] " + inv.name() + "  - invité par " + inv.inviter(), 330), MineNorthStyle.WHITE);
            btn(x + cw() - 150, ry + 2, 72, 18, "Accepter", MineNorthStyle.GREEN, () -> act(GangGui.ACCEPT, inv.tag(), "", 0));
            btn(x + cw() - 74, ry + 2, 70, 18, "Refuser", MineNorthStyle.DARK, () -> act(GangGui.DECLINE, inv.tag(), "", 0));
        }
    }

    // ---- Admin
    private void buildAdmin() {
        int x = cx(), y = cy();
        text(x, y, "ADMINISTRATION (OP)", MineNorthStyle.PINK);
        btn(x + 150, y - 4, 110, 16, "Zones et gangs", adminPage == 0 ? MineNorthStyle.PINK : MineNorthStyle.DARK, () -> { keep(); adminPage = 0; page = 0; rebuildWidgets(); });
        btn(x + 264, y - 4, 80, 16, "Revenus", adminPage == 1 ? MineNorthStyle.PINK : MineNorthStyle.DARK, () -> { keep(); adminPage = 1; page = 0; rebuildWidgets(); });
        btn(x + 348, y - 4, 80, 16, "Fabrique", adminPage == 2 ? MineNorthStyle.PINK : MineNorthStyle.DARK, () -> { keep(); adminPage = 2; page = 0; rebuildWidgets(); });
        if (adminPage == 1) { buildAdminIncome(x, y); return; }
        if (adminPage == 2) { buildAdminFactory(x, y); return; }
        text(x, y + 14, "Créer un territoire autour de vous (toute la hauteur)", MineNorthStyle.MUTED);
        eTerr = box(x, y + 26, 200, "Nom du territoire", fTerrName, 32);
        eRadius = box(x + 208, y + 26, 50, "Rayon", fRadius, 3);
        btn(x + 266, y + 25, 180, 20, "CRÉER ICI", MineNorthStyle.GREEN, () -> act(GangGui.ADMIN_TERR_CREATE, eTerr.getValue(), eRadius.getValue(), 0));
        int per = Math.max(2, (ch() - 70) / 22);
        int total = v.territories.size() + v.allGangs.size();
        int ry = y + 56;
        int shown = 0;
        text(x, ry - 4, "TERRITOIRES", MineNorthStyle.BLUE);
        for (GangView.Terr t : v.territories) {
            if (shown++ >= per / 2 + 1) break;
            ry += 20;
            cards.add(new int[]{x, ry - 2, cw(), 18, 0xFF000000 | t.holderColor()});
            text(x + 8, ry + 3, fit(t.name() + "  " + (t.holderTag().isEmpty() ? "libre" : "[" + t.holderTag() + "]"), 250), MineNorthStyle.TEXT);
            btn(x + cw() - 160, ry - 1, 76, 16, "Libérer", MineNorthStyle.DARK, () -> act(GangGui.ADMIN_TERR_FREE, t.name(), "", 0));
            btn(x + cw() - 80, ry - 1, 76, 16, "Supprimer", MineNorthStyle.PINK, () -> act(GangGui.ADMIN_TERR_DELETE, t.name(), "", 0));
        }
        ry += 24;
        text(x, ry - 4, "GANGS", MineNorthStyle.BLUE);
        int g = 0;
        for (GangView.GangInfo gi : v.allGangs) {
            if (g++ >= per / 2) break;
            ry += 20;
            cards.add(new int[]{x, ry - 2, cw(), 18, 0xFF000000 | gi.color()});
            text(x + 8, ry + 3, fit("[" + gi.tag() + "] " + gi.name() + "  - " + gi.members() + " membre(s)", 280), MineNorthStyle.TEXT);
            boolean armed = pendingAdmin.equals(gi.tag());
            btn(x + cw() - 170, ry - 1, 166, 16, armed ? "CONFIRMER (jette le coffre)" : "Dissoudre", MineNorthStyle.PINK, () -> {
                if (armed) act(GangGui.ADMIN_DISSOLVE, gi.tag(), "1", 0); else { pendingAdmin = gi.tag(); rebuildWidgets(); }
            });
        }
    }

    /** Admin > Revenus : valeurs par défaut, puis le revenu de chaque zone. Tout est revérifié par le serveur. */
    private void buildAdminIncome(int x, int y) {
        if (fIncZone == null) fIncZone = Long.toString(v.incomePerZone / 100L);
        if (fIncCap == null) fIncCap = Long.toString(v.incomeCap / 100L);
        if (fIncInt == null) fIncInt = Integer.toString(v.incomeMinutes);
        if (fOnline == null) fOnline = v.incomeNeedsOnline;
        text(x, y + 14, "PAR DÉFAUT (toutes les zones sans réglage propre)", MineNorthStyle.BLUE);
        text(x, y + 28, "Revenu / zone (€)", MineNorthStyle.MUTED);
        eIncZone = box(x, y + 38, 90, "20", fIncZone, 6);
        text(x + 100, y + 28, "Plafond par heure (€, 0 = aucun)", MineNorthStyle.MUTED);
        eIncCap = box(x + 100, y + 38, 90, "120", fIncCap, 7);
        text(x + 200, y + 28, "Toutes les (min)", MineNorthStyle.MUTED);
        eIncInt = box(x + 200, y + 38, 70, "10", fIncInt, 4);
        final boolean online = fOnline;
        btn(x + 280, y + 37, 226, 20, "Versé seulement si un membre est en ligne : " + (online ? "OUI" : "NON"), MineNorthStyle.DARK, () -> { keep(); fOnline = !online; rebuildWidgets(); });
        btn(x, y + 62, 190, 20, "ENREGISTRER LES DÉFAUTS", MineNorthStyle.GREEN, () ->
                act(GangGui.ADMIN_INCOME_GLOBAL, eIncZone.getValue() + ";" + eIncCap.getValue() + ";" + eIncInt.getValue(), online ? "1" : "0", 0));
        text(x + 200, y + 68, "Le plafond s'applique au total d'un gang. Versé sur le compte du chef.", MineNorthStyle.MUTED);

        text(x, y + 92, "REVENU DE CHAQUE ZONE (€ par versement, vide ou « défaut » = valeur par défaut)", MineNorthStyle.BLUE);
        int per = Math.max(2, (ch() - 128) / 24);
        int pages = Math.max(1, (v.territories.size() + per - 1) / per);
        if (page >= pages) page = pages - 1;
        if (v.territories.isEmpty()) text(x, y + 108, "Aucun territoire. Créez-en un dans « Zones et gangs ».", MineNorthStyle.MUTED);
        for (int i = page * per, row = 0; i < v.territories.size() && row < per; i++, row++) {
            GangView.Terr t = v.territories.get(i);
            int ry = y + 106 + row * 24;
            cards.add(new int[]{x, ry, cw(), 22, 0xFF000000 | t.holderColor()});
            text(x + 8, ry + 7, fit(t.name(), 140), MineNorthStyle.WHITE);
            text(x + 156, ry + 7, "actuel : " + MineNorthStyle.euros(t.incomeCents()) + (t.customIncome() ? "" : " (défaut)"), t.customIncome() ? MineNorthStyle.WARN : MineNorthStyle.MUTED);
            String shown = zoneVals.getOrDefault(t.name(), t.customIncome() ? plainEuros(t.incomeCents()) : "");
            EditBox eb = box(x + 310, ry + 2, 62, "défaut", shown, 10);
            zoneBoxes.add(new Object[]{t.name(), eb});
            btn(x + 378, ry + 2, 60, 18, "OK", MineNorthStyle.GREEN, () -> act(GangGui.ADMIN_INCOME_ZONE, t.name(), eb.getValue(), 0));
            btn(x + 442, ry + 2, 64, 18, "Défaut", MineNorthStyle.DARK, () -> act(GangGui.ADMIN_INCOME_ZONE, t.name(), "defaut", 0));
        }
        if (pages > 1) {
            int py = y + 106 + per * 24;
            btn(x, py, 40, 14, "◀", MineNorthStyle.DARK, () -> { keep(); page = Math.max(0, page - 1); rebuildWidgets(); }).enabled(page > 0);
            btn(x + 44, py, 40, 14, "▶", MineNorthStyle.DARK, () -> { keep(); page = Math.min(pages - 1, page + 1); rebuildWidgets(); }).enabled(page < pages - 1);
            text(x + 92, py + 3, "Page " + (page + 1) + "/" + pages, MineNorthStyle.MUTED);
        }
    }

    /** Admin > Fabrique : réglages généraux puis recettes (libellé, objet, quantité, secondes, euros). Le serveur revérifie tout. */
    private void buildAdminFactory(int x, int y) {
        if (fFacOn == null) fFacOn = v.factoryEnabled;
        if (fFacMax == null) fFacMax = Integer.toString(v.factoryMaxPerGang);
        if (fFacStock == null) fFacStock = Integer.toString(v.factoryStockMax);
        if (fFacQueue == null) fFacQueue = Integer.toString(v.factoryQueueMax);
        if (fFacRank == null) fFacRank = v.factoryCollectRank;
        text(x, y + 14, "FABRIQUE D'ARMES (avantage des zones contrôlées)", MineNorthStyle.BLUE);
        text(x + 128, y + 28, "Par gang", MineNorthStyle.MUTED);
        text(x + 184, y + 28, "Stock max", MineNorthStyle.MUTED);
        text(x + 240, y + 28, "File max", MineNorthStyle.MUTED);
        final boolean on = fFacOn;
        btn(x, y + 38, 120, 18, "Activée : " + (on ? "OUI" : "NON"), on ? MineNorthStyle.GREEN : MineNorthStyle.DARK, () -> { keep(); fFacOn = !on; rebuildWidgets(); });
        eFacMax = box(x + 128, y + 38, 50, "1", fFacMax, 2);
        eFacStock = box(x + 184, y + 38, 50, "10", fFacStock, 3);
        eFacQueue = box(x + 240, y + 38, 50, "5", fFacQueue, 2);
        final String rank = fFacRank;
        btn(x + 298, y + 38, 130, 18, "Récupérer dès : " + rank, MineNorthStyle.DARK, () -> {
            keep();
            fFacRank = rank.equals("membre") ? "lieutenant" : rank.equals("lieutenant") ? "chef" : "membre";
            rebuildWidgets();
        });
        btn(x + 434, y + 38, 72, 18, "ENREGISTRER", MineNorthStyle.GREEN, () ->
                act(GangGui.ADMIN_FACTORY_GLOBAL, (on ? "1" : "0") + ";" + eFacMax.getValue() + ";" + eFacStock.getValue() + ";" + eFacQueue.getValue() + ";" + rank, "", 0));
        text(x, y + 62, "RECETTES : libellé | objet (modid:objet{données}) | quantité par lot | secondes | euros", MineNorthStyle.BLUE);
        int per = Math.max(2, (ch() - 108) / 24);
        int total = v.adminRecipes.size() + 1; // la dernière ligne sert à en ajouter une
        int pages = Math.max(1, (total + per - 1) / per);
        if (page >= pages) page = pages - 1;
        for (int i = page * per, row = 0; i < total && row < per; i++, row++) {
            final int idx = i;
            boolean isNew = i >= v.adminRecipes.size();
            GangView.Recipe r = isNew ? null : v.adminRecipes.get(i);
            String[] def = isNew ? new String[]{"", "", "1", "60", "0"}
                    : new String[]{r.label(), r.spec(), Integer.toString(r.qty()), Integer.toString(r.seconds()), plainEuros(r.cents())};
            String[] vals = recipeVals.getOrDefault(i, def);
            int ry = y + 76 + row * 24;
            cards.add(new int[]{x, ry - 2, cw(), 22, isNew ? MineNorthStyle.GREEN : MineNorthStyle.CYAN});
            EditBox[] bx = new EditBox[5];
            bx[0] = box(x + 4, ry, 80, "Libellé", vals[0], 32);
            bx[1] = box(x + 88, ry, 188, "tacz:ammo{AmmoId:'tacz:9mm'}", vals[1], 220);
            bx[2] = box(x + 280, ry, 36, "Qté", vals[2], 3);
            bx[3] = box(x + 320, ry, 40, "Sec", vals[3], 5);
            bx[4] = box(x + 364, ry, 44, "€", vals[4], 9);
            recipeBoxes.add(new Object[]{i, bx});
            btn(x + 412, ry, 34, 18, isNew ? "Ajout" : "OK", MineNorthStyle.GREEN, () ->
                    act(GangGui.ADMIN_FACTORY_RECIPE, Integer.toString(isNew ? -1 : idx), bx[0].getValue() + "|" + bx[1].getValue() + "|" + bx[2].getValue() + "|" + bx[3].getValue() + "|" + bx[4].getValue(), 0));
            if (!isNew) btn(x + 450, ry, 52, 18, "Suppr.", MineNorthStyle.PINK, () -> act(GangGui.ADMIN_FACTORY_DELETE, Integer.toString(idx), "", 0));
        }
        if (pages > 1) {
            int py = y + 76 + per * 24;
            btn(x, py, 40, 14, "◀", MineNorthStyle.DARK, () -> { keep(); page = Math.max(0, page - 1); rebuildWidgets(); }).enabled(page > 0);
            btn(x + 44, py, 40, 14, "▶", MineNorthStyle.DARK, () -> { keep(); page = Math.min(pages - 1, page + 1); rebuildWidgets(); }).enabled(page < pages - 1);
            text(x + 92, py + 3, "Page " + (page + 1) + "/" + pages, MineNorthStyle.MUTED);
        }
    }

    private static String plainEuros(long cents) {
        return cents % 100 == 0 ? Long.toString(cents / 100) : String.format(Locale.ROOT, "%d.%02d", cents / 100, cents % 100);
    }

    // ---- Fenêtre « porte »
    private void buildDoor() {
        int w = 300, h = 150, x = (width - w) / 2, y = (height - h) / 2;
        String title = v.doorMode == 2 ? "Porte du gang" : v.doorMode == 3 ? "Donner votre porte au gang" : "Porte de gang ?";
        texts.add(new Object[]{x + 12, y + 10, title, MineNorthStyle.WHITE});
        cards.add(new int[]{x - 3, y - 3, w + 6, h + 6, 0xFF0E0E10});
        cards.add(new int[]{x, y, w, h, 0xFF161048});
        String sub = v.doorMode == 2 ? "Qui peut l'ouvrir (grade minimum) :" : "Elle s'ouvrira pour (grade minimum) :";
        texts.add(new Object[]{x + 12, y + 28, sub, MineNorthStyle.MUTED});
        String[] labels = {"Membres", "Lieutenants", "Chef seul"};
        int[] lv = {2, 1, 0};
        for (int i = 0; i < 3; i++) {
            final int level = lv[i];
            boolean current = v.doorMode == 2 && v.doorMin == level;
            btn(x + 12 + i * 94, y + 44, 90, 22, labels[i], current ? MineNorthStyle.PINK : MineNorthStyle.CYAN, () -> {
                String rank = level == 0 ? "chef" : level == 1 ? "lieutenant" : "membre";
                if (v.doorMode == 2) GangClient.send(GangGui.DOOR_RANK, "", rank, v.doorPos);
                else GangClient.send(GangGui.DOOR_CLAIM, "", rank, v.doorPos);
            });
        }
        if (v.doorMode == 2) btn(x + 12, y + 76, 276, 22, "Retirer cette porte du gang", MineNorthStyle.DARK, () -> GangClient.send(GangGui.DOOR_REMOVE, "", "", v.doorPos));
        else texts.add(new Object[]{x + 12, y + 80, "Tous les membres du grade choisi (et au-dessus) l'ouvrent.", MineNorthStyle.MUTED});
        if (!v.message.isBlank()) texts.add(new Object[]{x + 12, y + 108, fit(v.message, w - 24), v.messageError ? MineNorthStyle.ALERT : MineNorthStyle.OK});
        btn(x + w - 98, y + h - 26, 86, 16, "Fermer", MineNorthButton.GHOST, this::onClose);
    }

    // ------------------------------------------------------------------ rendu

    @Override
    public void tick() {
        super.tick();
        if (++ticks % 100 == 0 && !(v.doorMode != 0 && !doorClosed)) GangClient.send(GangGui.REFRESH_QUIET, "", "", 0);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        boolean door = v.doorMode != 0 && !doorClosed;
        if (!door) {
            MineNorthStyle.panel(g, left(), top(), PANEL_W, panelH(), "MON GANG", v.inGang ? v.name + "  [" + v.tag + "]" : "Pas encore de gang");
            if (v.inGang) g.fill(left() + PANEL_W - 130, top() + 30, left() + PANEL_W - 14, top() + 36, 0xFF000000 | v.color);
        }
        for (int[] c : cards) {
            if (door || c.length < 5) { g.fill(c[0], c[1], c[0] + c[2], c[1] + c[3], c[4]); continue; }
            MineNorthStyle.card(g, c[0], c[1], c[2], c[3], false, c[4]);
        }
        for (int[] s : swatchRects) {
            g.fill(s[0] - 1, s[1] - 1, s[0] + s[2] + 1, s[1] + s[3] + 1, s[4] == fColor ? MineNorthStyle.WHITE : MineNorthStyle.FRAME);
            g.fill(s[0], s[1], s[0] + s[2], s[1] + s[3], 0xFF000000 | s[4]);
        }
        GangView.Terr hover = null;
        for (Object[] m : mapRects) {
            GangView.Terr t = (GangView.Terr) m[4];
            int rx = (Integer) m[0], rz = (Integer) m[1], rw = (Integer) m[2], rh = (Integer) m[3];
            g.fill(rx - 1, rz - 1, rx + rw + 1, rz + rh + 1, t.mine() ? MineNorthStyle.WHITE : MineNorthStyle.FRAME);
            g.fill(rx, rz, rx + rw, rz + rh, 0xFF000000 | t.holderColor());
            if (mx >= rx && mx < rx + rw && my >= rz && my < rz + rh) hover = t;
        }
        for (Object[] t : texts) g.drawString(font, (String) t[2], (Integer) t[0], (Integer) t[1], (Integer) t[3], false);
        super.render(g, mx, my, pt);
        if (hover != null) {
            g.renderTooltip(font, Component.literal(hover.name() + " - " + (hover.holderTag().isEmpty() ? "libre" : hover.holderName() + (hover.status().isEmpty() ? "" : " (" + hover.status() + ")")) + " - revenu " + MineNorthStyle.euros(hover.incomeCents()) + "/versement"), mx, my);
        }
        if (!door && !v.message.isBlank()) {
            g.drawString(font, fit(v.message, PANEL_W - 32), left() + 16, top() + panelH() - 22, v.messageError ? MineNorthStyle.ALERT : MineNorthStyle.OK, false);
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (delta != 0) { page = Math.max(0, page - (int) Math.signum(delta)); rebuildWidgets(); return true; }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
