package fr.minenorth.portes.client.phone;

import fr.minenorth.portes.client.GangClient;
import fr.minenorth.portes.client.MineNorthStyle;
import fr.minenorth.portes.gang.GangGui;
import fr.minenorth.portes.gang.GangView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Application « Mon gang » dessinée dans l'écran du téléphone. Mêmes fonctions que {@link fr.minenorth.portes.client.GangScreen}
 * (membres, bases et portes, journal, territoires, compte, paramètres, fonder, invitations), mise en page portrait.
 * Le client ne décide de rien : chaque bouton envoie une intention au serveur, qui revérifie les droits puis renvoie l'état.
 * La fenêtre « porte » (clic droit accroupi) et la fabrique gardent leurs écrans habituels.
 */
public class PhoneGangScreen extends PhoneAppBase {
    private static final int[] SWATCHES = {0xE53935, 0xFB8C00, 0xFDD835, 0x43A047, 0x00ACC1, 0x1E88E5, 0x8E24AA, 0xEC407A, 0xF5F5F5, 0x757575, 0x6D4C41, 0x212121};
    private static final String[] RANKS = {"Chef", "Lieutenant", "Membre"};

    private GangView v;
    private boolean inGang;
    private final Field fName = new Field("Nom du gang (3-20 caractères)", "", 20), fTag = new Field("TAG", "", 4),
            fDeposit = new Field("Montant en €", "", 7), fConfirm = new Field("Tag", "", 4);
    private int fColor;
    private String pendingChef = "", pendingKick = "", selTerr = "";
    private boolean leaveStage, dissolveStage, showLog;
    private int ticks;

    public PhoneGangScreen(GangView view) {
        super("Mon gang");
        this.v = view;
        this.inGang = view.inGang;
        fName.value = view.name;
        fTag.value = view.tag;
        fColor = view.color;
    }

    /** Nouvel état envoyé par le serveur (après une action, ou rafraîchissement automatique). */
    public void update(GangView nv) {
        if (nv.inGang != inGang) { tab = 0; resetScroll(); inGang = nv.inGang; }
        if (nv.inGang && fName.value.isBlank()) { fName.value = nv.name; fTag.value = nv.tag; fColor = nv.color; }
        if (!nv.message.isBlank()) {
            leaveStage = false;
            if (nv.messageError) { dissolveStage = false; pendingChef = ""; pendingKick = ""; }
            else fDeposit.value = "";
        }
        this.v = nv;
    }

    private void act(int action, String a, String b, long pos) { GangClient.send(action, a, b, pos); }

    @Override
    public void tick() {
        if (++ticks % 100 == 0) act(GangGui.REFRESH_QUIET, "", "", 0);
    }

    @Override protected String title() { return "Mon gang"; }

    @Override
    protected String[] tabs() {
        if (v.inGang) return new String[]{"Membres", "Bases", "Territoires", "Compte", "Paramètres"};
        return new String[]{"Fonder", v.invites.isEmpty() ? "Invitations" : "Invitations (" + v.invites.size() + ")", "Territoires"};
    }

    @Override protected String badge() { return v.inGang ? "[" + v.tag + "] " + RANKS[Math.max(0, Math.min(2, v.myRank))] : ""; }

    @Override protected int badgeColor() { return DARK; }

    @Override protected String message() { return v.message == null ? "" : v.message; }

    @Override protected boolean messageError() { return v.messageError; }

    @Override
    protected void tabChanged() {
        leaveStage = false; dissolveStage = false; pendingChef = ""; pendingKick = ""; fConfirm.value = "";
    }

    @Override
    protected float content(GuiGraphics g, int mx, int my, float y) {
        String name = tabs()[tab];
        if (name.startsWith("Membres")) return members(g, mx, my, y);
        if (name.startsWith("Bases")) return bases(g, mx, my, y);
        if (name.startsWith("Territoires")) return territories(g, mx, my, y);
        if (name.startsWith("Compte")) return account(g, mx, my, y);
        if (name.startsWith("Paramètres")) return settings(g, mx, my, y);
        if (name.startsWith("Fonder")) return create(g, mx, my, y);
        return invites(g, mx, my, y);
    }

    private float section(GuiGraphics g, String title, float y) {
        text(g, title, L, y, 9, R - L, CYAN);
        return y + 16;
    }

    private void stripe(GuiGraphics g, float t, float b, int color) { box(g, L, t, L + 6, b, 3, 0xFF000000 | color); }

    // ------------------------------------------------------------------ membres

    private float members(GuiGraphics g, int mx, int my, float y) {
        boolean lead = v.myRank >= 0 && v.myRank <= 1, chef = v.myRank == 0;
        y = section(g, "MEMBRES (" + v.members.size() + "/" + v.membersMax + ")", y);
        for (GangView.Member m : v.members) {
            boolean manage = lead && m.rank() > v.myRank;
            float h = manage ? 54 : 32;
            box(g, L, y, R, y + h, 12, CARD);
            stripe(g, y + 6, y + 26, v.color);
            text(g, m.name(), L + 14, y + 10, 11, 150, m.online() ? OK : MUTED);
            right(g, RANKS[Math.max(0, Math.min(2, m.rank()))], R - 12, y + 11, 9, m.rank() == 0 ? WARN : TEXT);
            if (manage) {
                float by = y + 28, bx = L + 14;
                if (chef) {
                    if (m.rank() == 2) button(g, mx, my, bx, by, bx + 78, by + 20, "Lieutenant", 8.5f, CYAN, true, () -> act(GangGui.SET_RANK, m.uuid(), "lieutenant", 0));
                    else button(g, mx, my, bx, by, bx + 78, by + 20, "Membre", 8.5f, DARK, true, () -> act(GangGui.SET_RANK, m.uuid(), "membre", 0));
                    boolean armed = pendingChef.equals(m.uuid());
                    button(g, mx, my, bx + 84, by, bx + 162, by + 20, armed ? "Confirmer ?" : "Chef", 8.5f, armed ? 0xFFB8860B : PINK, true, () -> {
                        if (armed) act(GangGui.SET_RANK, m.uuid(), "chef", 0); else { pendingChef = m.uuid(); pendingKick = ""; }
                    });
                }
                boolean kArmed = pendingKick.equals(m.uuid());
                button(g, mx, my, R - 12 - 84, by, R - 12, by + 20, kArmed ? "Confirmer ?" : "Exclure", 8.5f, RED, true, () -> {
                    if (kArmed) act(GangGui.KICK, m.uuid(), "", 0); else { pendingKick = m.uuid(); pendingChef = ""; }
                });
            }
            y += h + 6;
        }
        if (lead) {
            y = section(g, "INVITER UN JOUEUR PROCHE", y + 6);
            if (v.nearby.isEmpty()) y = wrap(g, "Personne autour de vous (48 blocs, joueurs sans gang).", L, y, 9, R - L, MUTED);
            for (GangView.Nearby n : v.nearby) {
                box(g, L, y, R, y + 30, 12, CARD);
                text(g, n.name(), L + 12, y + 9, 10.5f, R - L - 110, TEXT);
                button(g, mx, my, R - 12 - 80, y + 5, R - 12, y + 25, "Inviter", 9, GREEN, true, () -> act(GangGui.INVITE, n.uuid(), "", 0));
                y += 36;
            }
        }
        return y;
    }

    // ------------------------------------------------------------------ bases, portes et journal

    private float bases(GuiGraphics g, int mx, int my, float y) {
        boolean lead = v.myRank >= 0 && v.myRank <= 1;
        y = section(g, "BASE DU GANG", y);
        box(g, L, y, R, y + 46, 12, CARD);
        stripe(g, y + 8, y + 38, v.color);
        text(g, v.hasBase ? "Base : " + BlockPos.of(v.basePos).toShortString().replace(",", "") + " (rayon " + v.baseRadius + ")" : "Pas de base définie",
                L + 14, y + 9, 10, R - L - 26, v.hasBase ? WHITE : MUTED);
        text(g, "Portes " + v.doors.size() + "/" + v.doorsMax + " · Fabriques " + v.factoriesCount + "/" + v.factoriesMax, L + 14, y + 27, 9, R - L - 26, TEXT);
        y += 52;
        y = wrap(g, "Porte libre : clic droit accroupi, dans une zone que vous contrôlez ou près de la base. Fabrique : voir l'onglet Territoires.",
                L, y, 8.5f, R - L, MUTED) + 4;
        if (v.myRank == 0) {
            button(g, mx, my, L, y, R, y + 24, "Définir la base ici", 10, GREEN, true, () -> act(GangGui.SET_BASE, "", "", 0));
            y += 30;
        }
        boolean canLog = v.myRank == 0 || v.op;
        if (canLog) {
            button(g, mx, my, L, y, R, y + 22, showLog ? "Voir les portes" : "Journal du gang", 9.5f, DARK, true, () -> { showLog = !showLog; resetScroll(); });
            y += 30;
        }
        if (showLog && canLog) {
            y = section(g, "JOURNAL (du plus récent au plus ancien)", y);
            if (v.log.isEmpty()) return wrap(g, "Rien pour le moment.", L, y, 9, R - L, MUTED);
            for (String line : v.log) y = wrap(g, line, L, y, 8.5f, R - L, TEXT) + 3;
            return y;
        }
        y = section(g, "PORTES DU GANG", y);
        if (v.doors.isEmpty()) return wrap(g, "Aucune porte. Accroupissez-vous devant une porte libre près de votre Coffre de gang.", L, y, 9, R - L, MUTED);
        for (GangView.DoorInfo d : v.doors) {
            BlockPos bp = BlockPos.of(d.pos());
            box(g, L, y, R, y + 42, 12, CARD);
            stripe(g, y + 6, y + 36, v.color);
            text(g, bp.getX() + "  " + bp.getY() + "  " + bp.getZ() + "  (" + d.dim().replace("minecraft:", "") + ")", L + 14, y + 8, 10, R - L - (lead ? 110 : 26), WHITE);
            text(g, "Ouverte aux " + RANKS[Math.max(0, Math.min(2, d.minRank()))].toLowerCase(Locale.ROOT) + "s et au-dessus", L + 14, y + 25, 8.5f,
                    R - L - (lead ? 110 : 26), MUTED);
            if (lead) button(g, mx, my, R - 12 - 80, y + 11, R - 12, y + 31, "Retirer", 9, RED, true, () -> act(GangGui.DOOR_REMOVE_REMOTE, d.dim(), "", d.pos()));
            y += 48;
        }
        return y;
    }

    // ------------------------------------------------------------------ territoires

    private float territories(GuiGraphics g, int mx, int my, float y) {
        y = section(g, "TERRITOIRES", y);
        Minecraft mc = Minecraft.getInstance();
        String dimNow = mc.level == null ? "" : mc.level.dimension().location().toString();
        List<GangView.Terr> here = new ArrayList<>();
        for (GangView.Terr t : v.territories) if (t.dim().equals(dimNow)) here.add(t);
        float mapH = 170;
        box(g, L, y, R, y + mapH, 14, HOVER);
        if (here.isEmpty()) {
            center(g, v.territories.isEmpty() ? "Aucun territoire défini pour le moment." : "Aucun territoire dans cette dimension.", 150, y + mapH / 2 - 5, 9, R - L - 20, MUTED);
        } else {
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (GangView.Terr t : here) { minX = Math.min(minX, t.x1()); minZ = Math.min(minZ, t.z1()); maxX = Math.max(maxX, t.x2()); maxZ = Math.max(maxZ, t.z2()); }
            float sc = (float) Math.min((R - L - 24) / (double) Math.max(1, maxX - minX), (mapH - 24) / (double) Math.max(1, maxZ - minZ));
            float ox = L + 12 + ((R - L - 24) - (maxX - minX) * sc) / 2, oz = y + 12 + ((mapH - 24) - (maxZ - minZ) * sc) / 2;
            for (GangView.Terr t : here) {
                float l = ox + (t.x1() - minX) * sc, tp = oz + (t.z1() - minZ) * sc;
                float r = l + Math.max(6, (t.x2() - t.x1()) * sc), b = tp + Math.max(6, (t.z2() - t.z1()) * sc);
                boolean sel = t.name().equals(selTerr);
                if (sel || t.mine()) box(g, l - 1.5f, tp - 1.5f, r + 1.5f, b + 1.5f, 3, sel ? WHITE : OK);
                box(g, l, tp, r, b, 2, 0xFF000000 | t.holderColor());
                hit(l, tp, r, b, () -> selTerr = t.name());
            }
        }
        y += mapH + 8;
        for (GangView.Terr t : v.territories) {
            boolean sel = t.name().equals(selTerr);
            float h = sel ? 64 : 42;
            box(g, L, y, R, y + h, 12, sel ? HOVER : CARD);
            stripe(g, y + 6, y + 36, t.holderColor());
            text(g, t.name(), L + 14, y + 7, 10.5f, R - L - 26, WHITE);
            String holder = t.holderTag().isEmpty() ? "libre" : "[" + t.holderTag() + "] " + t.holderName();
            text(g, holder + (t.status().isEmpty() ? "" : " · " + t.status()), L + 14, y + 25, 8.5f, R - L - 26, t.mine() ? OK : MUTED);
            if (sel) {
                text(g, "Revenu " + MineNorthStyle.euros(t.incomeCents()) + " par versement · " + (t.dim().replace("minecraft:", "")) + " " + t.x1() + "/" + t.z1()
                        + " → " + t.x2() + "/" + t.z2(), L + 14, y + 45, 8, R - L - 26, TEXT);
            }
            hit(L, y, R, y + h, () -> selTerr = sel ? "" : t.name());
            y += h + 6;
        }
        y = wrap(g, "Revenu par défaut : " + MineNorthStyle.euros(v.incomePerZone) + " par zone toutes les " + v.incomeMinutes + " min"
                + (v.incomeCap > 0 ? " (plafond " + MineNorthStyle.euros(v.incomeCap) + "/h)" : "") + (v.incomeToGang ? ", sur le compte du gang." : ", sur le compte du chef."),
                L, y + 4, 8.5f, R - L, TEXT);
        return wrap(g, v.inGang ? "Votre gang tient " + v.zonesHeld + " zone(s). Une zone contrôlée permet de poser une Fabrique d'armes (clic droit dessus pour l'utiliser)."
                : "Rejoignez un gang pour prendre des zones.", L, y + 4, 8.5f, R - L, MUTED);
    }

    // ------------------------------------------------------------------ compte du gang

    private float account(GuiGraphics g, int mx, int my, float y) {
        boolean chef = v.myRank == 0;
        y = section(g, "COMPTE DU GANG", y);
        box(g, L, y, R, y + 56, 14, CARD);
        stripe(g, y + 8, y + 48, v.color);
        text(g, "Solde", L + 14, y + 8, 9, 100, MUTED);
        text(g, MineNorthStyle.euros(v.balance), L + 14, y + 23, 18, R - L - 26, WHITE);
        y += 62;
        y = wrap(g, "Revenus : " + (v.incomeToGang ? "versés sur ce compte" : "versés au compte du chef"), L, y, 9, R - L, TEXT);
        y = wrap(g, "Dépenses : " + (v.spendRank == 0 ? "le chef seul" : "le chef et les lieutenants") + (v.canSpend ? " (vous pouvez)" : " (pas vous)"), L, y + 2, 9,
                R - L, v.canSpend ? OK : MUTED);
        y = wrap(g, "Sert à acheter les matières et lancer la fabrique d'armes.", L, y + 2, 8.5f, R - L, MUTED) + 6;
        y = section(g, "DÉPOSER (carte bancaire)", y);
        int[] quick = {50, 100, 500, 1000};
        float qw = (R - L - 3 * 5) / 4;
        for (int i = 0; i < quick.length; i++) {
            final int q = quick[i];
            float l = L + i * (qw + 5);
            button(g, mx, my, l, y, l + qw, y + 22, "+" + q + " €", 9, CYAN, true, () -> act(GangGui.GANG_DEPOSIT, Integer.toString(q), "", 0));
        }
        y += 28;
        field(g, mx, my, fDeposit, L, y, R - 70);
        button(g, mx, my, R - 64, y, R, y + 22, "Déposer", 9, GREEN, !fDeposit.value.isBlank(), () -> act(GangGui.GANG_DEPOSIT, fDeposit.value.trim(), "", 0));
        y += 30;
        if (chef) {
            final int cur = v.spendRank;
            button(g, mx, my, L, y, R, y + 22, "Qui dépense : " + (cur == 0 ? "le chef seul" : "chef et lieutenants") + " (changer)", 8.5f, DARK, true,
                    () -> act(GangGui.GANG_SPEND_RANK, cur == 0 ? "lieutenant" : "chef", "", 0));
            y += 30;
        }
        y = section(g, "HISTORIQUE (du plus récent au plus ancien)", y + 4);
        if (v.txs.isEmpty()) return wrap(g, "Aucun mouvement pour le moment.", L, y, 9, R - L, MUTED);
        for (GangView.TxInfo t : v.txs) {
            box(g, L, y, R, y + 36, 10, CARD);
            text(g, t.when(), L + 10, y + 6, 8, 120, MUTED);
            right(g, (t.delta() >= 0 ? "+" : "") + MineNorthStyle.euros(t.delta()), R - 10, y + 6, 9.5f, t.delta() >= 0 ? OK : ALERT);
            text(g, t.who() + " - " + t.label(), L + 10, y + 21, 8.5f, R - L - 20, TEXT);
            y += 40;
        }
        return y;
    }

    // ------------------------------------------------------------------ paramètres, fonder, invitations

    private float swatches(GuiGraphics g, float y) {
        float sw = (R - L - 5 * 6) / 6;
        for (int i = 0; i < SWATCHES.length; i++) {
            final int c = SWATCHES[i];
            float l = L + (i % 6) * (sw + 6), t = y + (i / 6) * 30;
            if (c == fColor) box(g, l - 2, t - 2, l + sw + 2, t + 26, 8, WHITE);
            box(g, l, t, l + sw, t + 24, 7, 0xFF000000 | c);
            hit(l, t, l + sw, t + 24, () -> fColor = c);
        }
        return y + 62;
    }

    private String styleArg() { return fTag.value.toUpperCase(Locale.ROOT) + "|" + String.format("#%06X", fColor); }

    private float settings(GuiGraphics g, int mx, int my, float y) {
        boolean chef = v.myRank == 0;
        y = section(g, "GANG", y);
        if (chef) {
            text(g, "Nom", L, y, 8.5f, 100, MUTED);
            field(g, mx, my, fName, L, y + 12, R);
            text(g, "Tag (2-4 lettres)", L, y + 42, 8.5f, 150, MUTED);
            field(g, mx, my, fTag, L, y + 54, L + 100);
            text(g, "Couleur", L, y + 84, 8.5f, 100, MUTED);
            y = swatches(g, y + 96);
            button(g, mx, my, L, y, R, y + 26, "Enregistrer", 10.5f, GREEN, true, () -> act(GangGui.SETTINGS, fName.value, styleArg(), 0));
            y += 36;
        } else {
            box(g, L, y, R, y + 30, 12, CARD);
            stripe(g, y + 6, y + 24, v.color);
            text(g, v.name + "  [" + v.tag + "]", L + 14, y + 9, 11, R - L - 26, WHITE);
            y = wrap(g, "Seul le chef modifie le nom, le tag et la couleur.", L, y + 36, 9, R - L, MUTED) + 8;
        }
        text(g, "DANGER", L, y, 9, 100, ALERT);
        y += 16;
        if (!leaveStage) button(g, mx, my, L, y, R, y + 24, "Quitter le gang", 10, DARK, true, () -> leaveStage = true);
        else {
            text(g, "Quitter vraiment ?", L, y - 0.5f, 9, R - L, WARN);
            y += 14;
            float mid = (L + R) / 2;
            button(g, mx, my, L, y, mid - 3, y + 24, "Oui, quitter", 9.5f, PINK, true, () -> act(GangGui.LEAVE, "", "", 0));
            button(g, mx, my, mid + 3, y, R, y + 24, "Annuler", 9.5f, DARK, true, () -> leaveStage = false);
        }
        y += 32;
        if (chef) {
            if (!dissolveStage) {
                button(g, mx, my, L, y, R, y + 24, "Dissoudre le gang", 10, PINK, true, () -> { dissolveStage = true; fConfirm.value = ""; });
                y += 32;
            } else {
                y = wrap(g, "Définitif (coffre vide, portes libérées). Tapez le tag « " + v.tag + " » :", L, y, 9, R - L, WARN) + 2;
                field(g, mx, my, fConfirm, L, y, L + 100);
                y += 28;
                float mid = (L + R) / 2;
                button(g, mx, my, L, y, mid - 3, y + 24, "Dissoudre pour de bon", 9, PINK, fConfirm.value.equalsIgnoreCase(v.tag),
                        () -> act(GangGui.DISSOLVE, "", "1", 0));
                button(g, mx, my, mid + 3, y, R, y + 24, "Annuler", 9.5f, DARK, true, () -> dissolveStage = false);
                y += 32;
            }
        }
        return y;
    }

    private float create(GuiGraphics g, int mx, int my, float y) {
        y = section(g, "FONDER UN GANG", y);
        text(g, "Nom", L, y, 8.5f, 100, MUTED);
        field(g, mx, my, fName, L, y + 12, R);
        text(g, "Tag (2-4 lettres)", L, y + 42, 8.5f, 150, MUTED);
        field(g, mx, my, fTag, L, y + 54, L + 100);
        text(g, "Couleur", L, y + 84, 8.5f, 100, MUTED);
        y = swatches(g, y + 96);
        y = wrap(g, "Coût : " + (v.createCost > 0 ? MineNorthStyle.euros(v.createCost) + " (carte bancaire sur vous)" : "gratuit"), L, y, 9.5f, R - L, TEXT) + 4;
        button(g, mx, my, L, y, R, y + 28, "Fonder le gang", 11, GREEN, true, () -> act(GangGui.CREATE, fName.value, styleArg(), 0));
        return wrap(g, "Un gang : un chef, des lieutenants, des membres. Une base avec ses portes et un coffre commun, et des territoires à tenir pour toucher un petit revenu.",
                L, y + 38, 8.5f, R - L, MUTED);
    }

    private float invites(GuiGraphics g, int mx, int my, float y) {
        y = section(g, "INVITATIONS REÇUES", y);
        if (v.invites.isEmpty()) return wrap(g, "Aucune invitation. Un chef ou un lieutenant peut vous inviter s'il est près de vous.", L, y, 9, R - L, MUTED);
        for (GangView.Invite inv : v.invites) {
            box(g, L, y, R, y + 62, 12, CARD);
            stripe(g, y + 6, y + 30, inv.color());
            text(g, "[" + inv.tag() + "] " + inv.name(), L + 14, y + 7, 11, R - L - 26, WHITE);
            text(g, "Invité par " + inv.inviter(), L + 14, y + 23, 8.5f, R - L - 26, MUTED);
            float mid = (L + R) / 2;
            button(g, mx, my, L + 10, y + 36, mid - 3, y + 56, "Accepter", 9, GREEN, true, () -> act(GangGui.ACCEPT, inv.tag(), "", 0));
            button(g, mx, my, mid + 3, y + 36, R - 10, y + 56, "Refuser", 9, DARK, true, () -> act(GangGui.DECLINE, inv.tag(), "", 0));
            y += 68;
        }
        return y;
    }
}
