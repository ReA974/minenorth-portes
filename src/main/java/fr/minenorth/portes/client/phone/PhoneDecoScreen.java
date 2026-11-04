package fr.minenorth.portes.client.phone;

import fr.minenorth.portes.client.DecoClient;
import fr.minenorth.portes.deco.DecoService;
import fr.minenorth.portes.network.DecoNet;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/**
 * Application « Déco » dans l'écran du téléphone : catalogue des magasins de meubles (et collections VIP), garde-meuble, « Chez moi »
 * (ranger le meuble visé). Le client ne décide de rien : le serveur vérifie qu'on est chez soi, le grade, le paiement et chaque objet.
 * Chaque magasin a sa direction artistique (fond, bandeau, logo, cartes, boutons, typographie), appliquée à toute l'appli quand on le choisit :
 * Maison Nordik clair et bois blond, Atelier Prestige noir et or, Brocante du Port papier kraft et couleurs chaudes, collections VIP néon.
 */
public class PhoneDecoScreen extends PhoneAppBase {

    /**
     * Direction artistique d'un magasin. head : bandeau du haut (toujours sombre : l'heure et la batterie du téléphone restent lisibles) ;
     * caps : titres en capitales espacées ; logo : 0 maison et sapin, 1 losange, 2 macaron rétro, 3 étoile néon.
     */
    private record Theme(String name, String tagline, int bg, int bg2, int head, int title, int card, int edge, int text, int muted, int accent,
                         int accentText, int price, int tabOff, int logo, boolean caps, boolean glow) {}

    private static final Theme NORDIK = new Theme("MAISON NORDIK", "Bien chez soi, petit prix", 0xFFF4EFE6, 0xFFE9DDC8, 0xFF23324A, 0xFFFFFFFF,
            0xFFFFFFFF, 0xFFD9B98A, 0xFF23324A, 0xFF7A8496, 0xFF2F5D8A, 0xFFFFFFFF, 0xFF23324A, 0xFFC9B48C, 0, false, false);
    private static final Theme PRESTIGE = new Theme("ATELIER PRESTIGE", "Le design d'exception", 0xFF0D0B08, 0xFF16120C, 0xFF050403, 0xFFE0B860,
            0xFF17130D, 0xFFC9A24A, 0xFFF2E6C8, 0xFF9A8A6A, 0xFFC9A24A, 0xFF0D0B08, 0xFFE0B860, 0xFF2A2318, 1, true, false);
    private static final Theme BROCANTE = new Theme("BROCANTE DU PORT", "Objets chinés, pièces uniques", 0xFFC9A46E, 0xFFB58A55, 0xFF4A2E1A, 0xFFF4E4C4,
            0xFFF1E3C6, 0xFF8A5A30, 0xFF4A2E1A, 0xFF8A6A48, 0xFFB5552E, 0xFFFFF4E0, 0xFF3E5A2E, 0xFF9A7448, 2, false, false);
    private static final Theme NEON = new Theme("COLLECTION VIP", "Exclusif, réservé au grade", 0xFF0B0620, 0xFF150A36, 0xFF07031A, 0xFFFF3EA5,
            0xFF160C34, 0xFFFF3EA5, 0xFFF2E8FF, 0xFF9A8AC8, 0xFFFF3EA5, 0xFFFFFFFF, 0xFF2FD4E0, 0xFF2A1A5A, 3, true, true);

    private DecoNet.View v;
    private int shop;
    private int ticks;

    public PhoneDecoScreen(DecoNet.View view) {
        super("Déco");
        this.v = view;
    }

    public void update(DecoNet.View nv) { this.v = nv; }

    @Override
    public void tick() {
        ticks++;
        if (ticks % 40 == 0) DecoClient.send(DecoService.REFRESH, 0, 0);   // « chez moi » suit le joueur qui se déplace
    }

    // ------------------------------------------------------------------ thème du magasin choisi

    private DecoNet.Shop current() {
        var shops = DecoClient.shops;
        if (shops.isEmpty()) return null;
        if (shop >= shops.size()) shop = 0;
        return shops.get(shop);
    }

    private Theme theme() {
        DecoNet.Shop s = current();
        if (s == null) return NORDIK;
        if (s.vip() > 0) return NEON;
        String n = s.name().toLowerCase(Locale.ROOT);
        if (n.contains("prestige")) return PRESTIGE;
        if (n.contains("brocante")) return BROCANTE;
        return NORDIK;
    }

    @Override protected int bg() { return theme().bg(); }

    @Override protected int titleColor() { return theme().title(); }

    @Override protected int tabOn() { return theme().accent(); }

    @Override protected int tabOff() { return theme().tabOff(); }

    @Override protected int buttonText() { return theme().accentText(); }

    @Override
    protected void decor(GuiGraphics g) {
        Theme th = theme();
        box(g, 3, 8, 297, 86, 0, th.head());                                          // bandeau sombre (heure, titre)
        box(g, 3, 86, 297, 606, 0, th.bg());
        switch (th.logo()) {
            case 0 -> { for (int i = 0; i < 6; i++) box(g, 3, 120 + i * 80, 297, 122 + i * 80, 0, th.bg2()); }    // lames de parquet blond
            case 1 -> { box(g, 3, 84, 297, 86, 0, th.edge()); }                         // filet doré sous le bandeau
            case 2 -> { for (int i = 0; i < 40; i++) box(g, 3 + (i * 37) % 290, 100 + (i * 53) % 500, 7 + (i * 37) % 290, 102 + (i * 53) % 500, 1, th.bg2()); }   // grain du papier
            default -> { box(g, 3, 84, 297, 86, 0, th.accent()); box(g, 3, 86, 297, 87, 0, 0xFF2FD4E0); }   // néon rose + cyan
        }
    }

    private String caps(Theme th, String s) {
        if (!th.caps()) return s;
        StringBuilder b = new StringBuilder();
        for (char c : s.toUpperCase(Locale.ROOT).toCharArray()) b.append(c).append(c == ' ' ? "" : " ");
        return b.toString().trim();
    }

    @Override protected String title() { return "Déco"; }

    @Override protected String[] tabs() { return new String[]{"Catalogue", "Garde-meuble", "Chez moi"}; }

    @Override protected String badge() { return v.home().isEmpty() ? "Hors de chez vous" : "Chez vous"; }

    @Override protected int badgeColor() { return v.home().isEmpty() ? theme().tabOff() : GREEN; }

    @Override protected String message() { return v.message() == null ? "" : v.message(); }

    @Override protected boolean messageError() { return v.error(); }

    private static String euros(long cents) { return String.format(Locale.FRANCE, "%,.2f €", cents / 100.0).replace(' ', ' '); }

    private void icon(GuiGraphics g, ItemStack st, float l, float t, float sz) {
        if (!visible(t, t + sz)) return;
        float s = size(sz) / 16f;
        g.pose().pushPose();
        g.pose().translate(x(l), y(t), 0);
        g.pose().scale(s, s, 1);
        g.renderItem(st, 0, 0);
        g.pose().popPose();
    }

    /** Carte d'article : bord (lueur néon pour le VIP, filet doré pour Prestige, liseré bois / kraft sinon) puis fond. */
    private void card(GuiGraphics g, Theme th, float l, float t, float r, float b) {
        if (th.glow()) box(g, l - 2, t - 2, r + 2, b + 2, 14, 0x55FF3EA5);
        box(g, l, t, r, b, 12, th.edge());
        box(g, l + 1.5f, t + 1.5f, r - 1.5f, b - 1.5f, 11, th.card());
    }

    @Override
    protected float content(GuiGraphics g, int mx, int my, float y) {
        return switch (tab) {
            case 0 -> catalog(g, mx, my, y);
            case 1 -> storage(g, mx, my, y);
            default -> home(g, mx, my, y);
        };
    }

    private float homeBanner(GuiGraphics g, float y) {
        Theme th = theme();
        boolean home = !v.home().isEmpty();
        card(g, th, L, y, R, y + 30);
        text(g, home ? "Livraison à : " + v.home() : "Livraison et sortie seulement chez vous", L + 12, y + 6, 9, R - L - 24, home ? th.text() : th.accent());
        text(g, home ? v.homeCategory() : "Entrez dans votre logement (propriétaire, locataire, coloc).", L + 12, y + 18, 7.5f, R - L - 24, th.muted());
        return y + 38;
    }

    // ------------------------------------------------------------------ en-tête du magasin (logo + nom + accroche)

    private float shopHeader(GuiGraphics g, Theme th, float y) {
        if (th.glow()) box(g, L - 2, y - 2, R + 2, y + 62, 18, 0x66FF3EA5);
        box(g, L, y, R, y + 60, 16, th.head());
        float lx = L + 14, ly = y + 12;
        switch (th.logo()) {
            case 0 -> {   // maison au toit pointu + sapin, bois blond
                box(g, lx, ly + 14, lx + 26, ly + 36, 3, 0xFFD9B98A);
                for (int i = 0; i < 7; i++) box(g, lx - 2 + i * 2, ly + 14 - i * 2, lx + 28 - i * 2, ly + 16 - i * 2, 1, 0xFFF4EFE6);
                box(g, lx + 10, ly + 24, lx + 16, ly + 36, 1, 0xFF23324A);
                for (int i = 0; i < 4; i++) box(g, lx + 32 - i * 1.5f, ly + 12 + i * 6, lx + 40 + i * 1.5f, ly + 18 + i * 6, 2, 0xFF5E8A5A);
            }
            case 1 -> {   // losange doré
                for (int i = 0; i < 9; i++) {
                    float w = i < 5 ? i * 3 : (8 - i) * 3;
                    box(g, lx + 18 - w, ly + 4 + i * 3.5f, lx + 18 + w, ly + 7.5f + i * 3.5f, 1, i % 2 == 0 ? 0xFFE0B860 : 0xFFC9A24A);
                }
            }
            case 2 -> {   // macaron rétro
                box(g, lx, ly, lx + 36, ly + 36, 18, 0xFFB5552E);
                box(g, lx + 4, ly + 4, lx + 32, ly + 32, 14, 0xFFF1E3C6);
                center(g, "1952", lx + 18, ly + 14, 8, 30, 0xFF4A2E1A);
            }
            default -> {  // étoile néon
                box(g, lx + 14, ly, lx + 22, ly + 36, 4, 0xFFFF3EA5);
                box(g, lx, ly + 14, lx + 36, ly + 22, 4, 0xFF2FD4E0);
                box(g, lx + 12, ly + 12, lx + 24, ly + 24, 6, 0xFFFFFFFF);
            }
        }
        text(g, caps(th, th.name()), L + 66, y + 16, th.caps() ? 10 : 12, R - L - 80, th.logo() == 0 ? 0xFFFFFFFF : th.title());
        text(g, th.tagline(), L + 66, y + 34, 8, R - L - 80, th.logo() == 1 ? 0xFFC9A24A : th.logo() == 3 ? 0xFF2FD4E0 : 0xFFD9C8A8);
        return y + 70;
    }

    // ------------------------------------------------------------------ catalogue

    private float catalog(GuiGraphics g, int mx, int my, float y) {
        var shops = DecoClient.shops;
        Theme th = theme();
        if (shops.isEmpty()) { text(g, "Catalogue en cours de chargement…", L, y, 9, R - L, th.muted()); return y + 20; }
        DecoNet.Shop cur = current();
        // sélecteur de magasins : chaque bouton porte la couleur de son magasin
        float w = (R - L - 5) / 2;
        for (int i = 0; i < shops.size(); i++) {
            final int idx = i;
            DecoNet.Shop s = shops.get(i);
            int keep = shop;
            shop = i;
            Theme ts = theme();
            shop = keep;
            float l = L + (i % 2) * (w + 5), t = y + (i / 2) * 26;
            button(g, mx, my, l, t, l + w, t + 22, (s.vip() > 0 ? "★ " : "") + s.name(), 8, shop == i ? ts.accent() : ts.tabOff(), true,
                    () -> { shop = idx; resetScroll(); });
        }
        y += ((shops.size() + 1) / 2) * 26 + 6;
        y = shopHeader(g, th, y);
        y = homeBanner(g, y);
        if (cur.locked()) {
            card(g, th, L, y, R, y + 26);
            center(g, "Collection réservée au grade " + (cur.vip() >= 2 ? "VIP+" : "VIP"), 150, y + 9, 8.5f, R - L - 12, th.accent());
            y += 32;
        }
        String rub = null;
        boolean canBuy = !v.home().isEmpty() && !cur.locked();
        for (DecoNet.Article a : DecoClient.articles) {
            if (a.shop() != shop) continue;
            if (!a.category().equals(rub)) {
                rub = a.category();
                text(g, caps(th, th.caps() ? rub : rub), L + 4, y + 2, 8.5f, R - L, th.logo() == 0 ? 0xFF23324A : th.title());
                box(g, L + 4, y + 13, L + 40, y + 14.5f, 1, th.accent());
                y += 20;
            }
            card(g, th, L, y, R, y + 36);
            box(g, L + 6, y + 6, L + 30, y + 30, 8, th.bg2());                      // pastille de l'objet
            icon(g, a.item(), L + 8, y + 8, 20);
            text(g, a.item().getHoverName().getString(), L + 38, y + 7, 8.5f, 150, th.text());
            text(g, euros(a.price()), L + 38, y + 20, 8.5f, 150, th.price());
            button(g, mx, my, R - 76, y + 8, R - 8, y + 28, "Acheter", 8, th.accent(), canBuy, () -> DecoClient.send(DecoService.BUY, a.id(), 0));
            y += 40;
        }
        return y;
    }

    // ------------------------------------------------------------------ garde-meuble

    private float storage(GuiGraphics g, int mx, int my, float y) {
        Theme th = theme();
        y = homeBanner(g, y);
        int cap = Math.max(1, v.capacity());
        card(g, th, L, y, R, y + 34);
        text(g, "Garde-meuble : " + v.total() + " / " + v.capacity() + " objets", L + 12, y + 6, 9, R - L - 24, th.text());
        float fill = Math.min(1f, v.total() / (float) cap);
        box(g, L + 12, y + 21, R - 12, y + 27, 3, th.bg2());
        if (fill > 0) box(g, L + 12, y + 21, L + 12 + (R - L - 24) * fill, y + 27, 3, v.total() >= cap ? ALERT : th.accent());
        y += 42;
        if (v.lots().isEmpty()) {
            return wrap(g, "Vide. Rangez un meuble posé chez vous depuis l'onglet Chez moi : il revient ici sans être cassé. En cas de vente ou de fin de bail, "
                    + "vos meubles y arrivent tout seuls.", L, y, 8, R - L, th.muted());
        }
        boolean home = !v.home().isEmpty();
        for (int i = 0; i < v.lots().size(); i++) {
            final int idx = i;
            DecoNet.Lot l = v.lots().get(i);
            card(g, th, L, y, R, y + 36);
            box(g, L + 6, y + 6, L + 30, y + 30, 8, th.bg2());
            icon(g, l.item(), L + 8, y + 8, 20);
            text(g, l.item().getHoverName().getString(), L + 38, y + 7, 8.5f, 140, th.text());
            text(g, "x" + l.count(), L + 38, y + 20, 8, 140, th.muted());
            button(g, mx, my, R - 76, y + 8, R - 8, y + 28, "Sortir", 8, th.accent(), home,
                    () -> DecoClient.send(DecoService.TAKE, idx, Screen.hasShiftDown() ? 64 : 1));
            y += 40;
        }
        return wrap(g, "Maj + Sortir : une pile entière.", L, y + 2, 7.5f, R - L, th.muted());
    }

    // ------------------------------------------------------------------ chez moi

    private float home(GuiGraphics g, int mx, int my, float y) {
        Theme th = theme();
        y = homeBanner(g, y);
        boolean home = !v.home().isEmpty();
        if (home) {
            card(g, th, L, y, R, y + 30);
            text(g, v.placed() + " meuble(s) ou bloc(s) posé(s) ici depuis votre arrivée", L + 12, y + 10, 8.5f, R - L - 24, th.text());
            y += 38;
        }
        button(g, mx, my, L, y, R, y + 30, "Ranger le meuble visé", 10, th.accent(), home, () -> DecoClient.send(DecoService.STORE, 0, 0));
        y += 40;
        y = wrap(g, "Regardez le meuble (à moins de 5 blocs) puis appuyez : il part au garde-meuble sans être cassé. Seuls les meubles posés par vous ou "
                + "vos colocataires se rangent, et seulement vides ; le logement lui-même ne bouge pas.", L, y, 8, R - L, th.muted());
        y += 8;
        text(g, caps(th, "Déménagement"), L, y, 9, R - L, th.logo() == 0 ? 0xFF23324A : th.title());
        y += 14;
        y = wrap(g, "À la vente, à la fin du bail, à l'expulsion ou quand le staff libère le bien, tout ce que vous avez posé (et le contenu des "
                + "coffres) part dans le garde-meuble de celui qui l'a posé." + (v.terrain() ? " Votre terrain est ensuite remis à nu comme à l'achat." : ""),
                L, y, 8, R - L, th.muted());
        if (v.vipLevel() > 0) {
            y += 6;
            y = wrap(g, "★ Avantages VIP : collection exclusive, garde-meuble agrandi, un logement de plus.", L, y, 8, R - L, 0xFFE0B040);
        }
        return y;
    }
}
