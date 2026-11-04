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
 */
public class PhoneDecoScreen extends PhoneAppBase {
    private static final int GOLD = 0xFFE0B040;

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
        if (++ticks % 40 == 0) DecoClient.send(DecoService.REFRESH, 0, 0);   // « chez moi » suit le joueur qui se déplace
    }

    @Override protected String title() { return "Déco"; }

    @Override protected String[] tabs() { return new String[]{"Catalogue", "Garde-meuble", "Chez moi"}; }

    @Override protected String badge() { return v.home().isEmpty() ? "Hors de chez vous" : "Chez vous"; }

    @Override protected int badgeColor() { return v.home().isEmpty() ? DARK : GREEN; }

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

    @Override
    protected float content(GuiGraphics g, int mx, int my, float y) {
        return switch (tab) {
            case 0 -> catalog(g, mx, my, y);
            case 1 -> storage(g, mx, my, y);
            default -> home(g, mx, my, y);
        };
    }

    private float homeBanner(GuiGraphics g, float y) {
        boolean home = !v.home().isEmpty();
        box(g, L, y, R, y + 30, 12, home ? 0xFF15603A : 0xFF3A2A60);
        text(g, home ? "Livraison à : " + v.home() : "Livraison et sortie seulement chez vous", L + 12, y + 6, 9, R - L - 24, WHITE);
        text(g, home ? v.homeCategory() : "Entrez dans votre logement (propriétaire, locataire, coloc).", L + 12, y + 18, 7.5f, R - L - 24, MUTED);
        return y + 38;
    }

    // ------------------------------------------------------------------ catalogue

    private float catalog(GuiGraphics g, int mx, int my, float y) {
        y = homeBanner(g, y);
        var shops = DecoClient.shops;
        if (shops.isEmpty()) { text(g, "Catalogue en cours de chargement…", L, y, 9, R - L, MUTED); return y + 20; }
        if (shop >= shops.size()) shop = 0;
        float w = (R - L - 5) / 2;
        for (int i = 0; i < shops.size(); i++) {
            final int idx = i;
            DecoNet.Shop s = shops.get(i);
            float l = L + (i % 2) * (w + 5), t = y + (i / 2) * 26;
            int col = shop == i ? (s.vip() > 0 ? GOLD : CYAN) : HOVER;
            button(g, mx, my, l, t, l + w, t + 22, (s.vip() > 0 ? "★ " : "") + s.name(), 8, col, true, () -> { shop = idx; resetScroll(); });
        }
        y += ((shops.size() + 1) / 2) * 26 + 6;
        DecoNet.Shop cur = shops.get(shop);
        if (cur.locked()) {
            box(g, L, y, R, y + 26, 10, 0xFF4A3A10);
            center(g, "Collection réservée au grade " + (cur.vip() >= 2 ? "VIP+" : "VIP"), 150, y + 9, 8.5f, R - L - 12, GOLD);
            y += 32;
        }
        String rub = null;
        boolean canBuy = !v.home().isEmpty() && !cur.locked();
        for (DecoNet.Article a : DecoClient.articles) {
            if (a.shop() != shop) continue;
            if (!a.category().equals(rub)) {
                rub = a.category();
                text(g, rub.toUpperCase(Locale.ROOT), L, y + 2, 8.5f, R - L, cur.vip() > 0 ? GOLD : CYAN);
                y += 16;
            }
            box(g, L, y, R, y + 34, 12, CARD);
            icon(g, a.item(), L + 8, y + 7, 20);
            text(g, a.item().getHoverName().getString(), L + 36, y + 6, 8.5f, 150, WHITE);
            text(g, euros(a.price()), L + 36, y + 19, 8, 150, OK);
            button(g, mx, my, R - 76, y + 7, R - 8, y + 27, "Acheter", 8, GREEN, canBuy, () -> DecoClient.send(DecoService.BUY, a.id(), 0));
            y += 38;
        }
        return y;
    }

    // ------------------------------------------------------------------ garde-meuble

    private float storage(GuiGraphics g, int mx, int my, float y) {
        y = homeBanner(g, y);
        int cap = Math.max(1, v.capacity());
        box(g, L, y, R, y + 34, 12, CARD);
        text(g, "Garde-meuble : " + v.total() + " / " + v.capacity() + " objets", L + 12, y + 6, 9, R - L - 24, WHITE);
        float fill = Math.min(1f, v.total() / (float) cap);
        box(g, L + 12, y + 21, R - 12, y + 27, 3, DISABLED);
        if (fill > 0) box(g, L + 12, y + 21, L + 12 + (R - L - 24) * fill, y + 27, 3, v.total() >= cap ? ALERT : CYAN);
        y += 42;
        if (v.lots().isEmpty()) {
            y = wrap(g, "Vide. Rangez un meuble posé chez vous depuis l'onglet Chez moi : il revient ici sans être cassé. En cas de vente ou de fin de bail, "
                    + "vos meubles y arrivent tout seuls.", L, y, 8, R - L, MUTED);
            return y;
        }
        boolean home = !v.home().isEmpty();
        for (int i = 0; i < v.lots().size(); i++) {
            final int idx = i;
            DecoNet.Lot l = v.lots().get(i);
            box(g, L, y, R, y + 34, 12, CARD);
            icon(g, l.item(), L + 8, y + 7, 20);
            text(g, l.item().getHoverName().getString(), L + 36, y + 6, 8.5f, 140, WHITE);
            text(g, "x" + l.count(), L + 36, y + 19, 8, 140, MUTED);
            button(g, mx, my, R - 76, y + 7, R - 8, y + 27, "Sortir", 8, CYAN, home,
                    () -> DecoClient.send(DecoService.TAKE, idx, Screen.hasShiftDown() ? 64 : 1));
            y += 38;
        }
        return wrap(g, "Maj + Sortir : une pile entière.", L, y + 2, 7.5f, R - L, MUTED);
    }

    // ------------------------------------------------------------------ chez moi

    private float home(GuiGraphics g, int mx, int my, float y) {
        y = homeBanner(g, y);
        boolean home = !v.home().isEmpty();
        if (home) {
            box(g, L, y, R, y + 30, 12, CARD);
            text(g, v.placed() + " meuble(s) ou bloc(s) posé(s) ici depuis votre arrivée", L + 12, y + 10, 8.5f, R - L - 24, TEXT);
            y += 38;
        }
        button(g, mx, my, L, y, R, y + 30, "Ranger le meuble visé", 10, PINK, home, () -> DecoClient.send(DecoService.STORE, 0, 0));
        y += 40;
        y = wrap(g, "Regardez le meuble (à moins de 5 blocs) puis appuyez : il part au garde-meuble sans être cassé. Seuls les meubles posés par vous ou "
                + "vos colocataires se rangent, et seulement vides ; le logement lui-même ne bouge pas.", L, y, 8, R - L, MUTED);
        y += 8;
        text(g, "DÉMÉNAGEMENT", L, y, 9, R - L, CYAN);
        y += 14;
        y = wrap(g, "À la vente, à la fin du bail, à l'expulsion ou quand le staff libère le bien, tout ce que vous avez posé (et le contenu des "
                + "coffres) part dans le garde-meuble de celui qui l'a posé." + (v.terrain() ? " Votre terrain est ensuite remis à nu comme à l'achat." : ""),
                L, y, 8, R - L, MUTED);
        if (v.vipLevel() > 0) {
            y += 6;
            y = wrap(g, "★ Avantages VIP : collection exclusive, garde-meuble agrandi, un logement de plus.", L, y, 8, R - L, GOLD);
        }
        return y;
    }
}
