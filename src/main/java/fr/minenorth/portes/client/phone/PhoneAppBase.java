package fr.minenorth.portes.client.phone;

import com.mikasrevs.newphone.client.gui.PhoneModernText;
import com.mikasrevs.newphone.client.zphone.gui.AbstractZPhoneScreen;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * Base des applications MineNorth dessinées DANS l'écran du téléphone MikasRevs Phone 2 : barre d'état, grand titre, onglets, zone de contenu
 * qui défile à la molette, bandeau de message, barre d'accueil et cadre du téléphone (retour à l'accueil par la barre du bas ou Échap).
 * Coordonnées en unités du téléphone (300 de large), converties par x() / y() / size(). Champs de saisie dessinés avec la police du téléphone.
 * Ne charger une sous-classe que si le mod « newphone » est présent.
 */
public abstract class PhoneAppBase extends AbstractZPhoneScreen {
    protected static final int BG = 0xFF161048, CARD = 0xFF0E0A34, HOVER = 0xFF2E2480, CYAN = 0xFF20AAEB, PINK = 0xFFC83CF0, GREEN = 0xFF1E9E52,
            DARK = 0xFF4A3CB4, RED = 0xFFC8344E, TEXT = 0xFFCFE3FF, MUTED = 0xFF8FA8E0, WARN = 0xFFFFE066, OK = 0xFF6CFF9A, ALERT = 0xFFFF6A9A,
            WHITE = 0xFFFFFFFF, DISABLED = 0xFF2A2468;
    protected static final float L = 14, R = 286, CB = 580;

    private record Hit(float l, float t, float r, float b, Runnable action) {}

    /** Champ de saisie (texte conservé entre deux rendus). */
    protected static final class Field {
        public String value;
        final String hint;
        final int max;
        public Field(String hint, String value, int max) { this.hint = hint; this.value = value == null ? "" : value; this.max = max; }
    }

    private final List<Hit> hits = new ArrayList<>();
    private Field focused;
    protected int tab;
    protected float scroll;
    private float contentBottom, contentTop;
    /** Bornes verticales où les boutons sont cliquables (la partie qui défile est coupée en haut et en bas). */
    private float clipT = 0, clipB = 1000;

    protected PhoneAppBase(String title) { super(Component.literal(title)); }

    // ------------------------------------------------------------------ à fournir

    protected abstract String title();

    protected abstract String[] tabs();

    /** Dessine l'onglet courant à partir de y (déjà décalé du défilement) ; renvoie le y sous le dernier élément. */
    protected abstract float content(GuiGraphics g, int mx, int my, float y);

    /** Petit texte à droite du titre (ou vide). */
    protected String badge() { return ""; }

    protected int badgeColor() { return GREEN; }

    /** Message du serveur (résultat de la dernière action) ou vide. */
    protected String message() { return ""; }

    protected boolean messageError() { return false; }

    protected void tabChanged() {}

    // ------------------------------------------------------------------ thème (une appli peut changer d'ambiance : l'appli Déco change par magasin)

    protected int bg() { return BG; }

    protected int titleColor() { return WHITE; }

    protected int tabOn() { return CYAN; }

    protected int tabOff() { return HOVER; }

    /** Couleur du texte des boutons actifs. */
    protected int buttonText() { return WHITE; }

    /** Dessin libre derrière le titre (bandeau, motif, logo), juste après le fond. */
    protected void decor(GuiGraphics g) {}

    // ------------------------------------------------------------------ outils de dessin (unités du téléphone)

    protected void box(GuiGraphics g, float l, float t, float r, float b, float rad, int c) {
        drawRoundedRect(g, x(l), y(t), x(r), y(b), size(rad), c);
    }

    protected void text(GuiGraphics g, String str, float tx, float ty, float sz, float maxW, int c) {
        PhoneModernText.drawColoredFitted(g, str, x(tx), y(ty), size(sz), size(maxW), c);
    }

    protected void center(GuiGraphics g, String str, float cx, float ty, float sz, float maxW, int c) {
        PhoneModernText.drawCenteredColoredFitted(g, str, x(cx), y(ty), size(sz), size(maxW), c);
    }

    protected void right(GuiGraphics g, String str, float rx, float ty, float sz, int c) {
        int w = PhoneModernText.width(str, size(sz));
        PhoneModernText.drawColoredFitted(g, str, x(rx) - w, y(ty), size(sz), w + 2, c);
    }

    /** Texte sur plusieurs lignes ; renvoie la position sous la dernière ligne. */
    protected float wrap(GuiGraphics g, String str, float tx, float ty, float sz, float maxW, int c) {
        for (String line : PhoneModernText.wrap(str, size(sz), size(maxW))) {
            text(g, line, tx, ty, sz, maxW, c);
            ty += sz + 3;
        }
        return ty;
    }

    protected boolean visible(float t, float b) { return b > clipT && t < clipB; }

    protected boolean hover(int mx, int my, float l, float t, float r, float b) {
        return visible(t, b) && inside(mx, my, l, Math.max(t, clipT), r, Math.min(b, clipB));
    }

    /** Zone cliquable sans dessin (carte, case de couleur…). */
    protected void hit(float l, float t, float r, float b, Runnable act) {
        if (visible(t, b)) hits.add(new Hit(l, Math.max(t, clipT), r, Math.min(b, clipB), act));
    }

    protected void button(GuiGraphics g, int mx, int my, float l, float t, float r, float b, String label, float sz, int color, boolean active, Runnable act) {
        boolean hov = active && hover(mx, my, l, t, r, b);
        box(g, l, t, r, b, Math.min(10, (b - t) / 2), !active ? DISABLED : hov ? lighten(color) : color);
        center(g, label, (l + r) / 2, t + (b - t - sz) / 2, sz, r - l - 6, active ? buttonText() : MUTED);
        if (active) hit(l, t, r, b, act);
    }

    /** Champ de saisie : clic pour écrire, Retour arrière pour effacer, Ctrl+V pour coller, Échap pour quitter le champ. */
    protected void field(GuiGraphics g, int mx, int my, Field f, float l, float t, float r) {
        float b = t + 22;
        boolean on = focused == f;
        box(g, l, t, r, b, 8, on ? CYAN : hover(mx, my, l, t, r, b) ? HOVER : DARK);
        box(g, l + 1.5f, t + 1.5f, r - 1.5f, b - 1.5f, 7, CARD);
        boolean empty = f.value.isEmpty();
        String shown = empty ? f.hint : f.value;
        int maxPx = size(r - l - 16);
        if (!empty && PhoneModernText.width(shown, size(9.5f)) > maxPx) {
            while (shown.length() > 1 && PhoneModernText.width("…" + shown, size(9.5f)) > maxPx) shown = shown.substring(1);
            shown = "…" + shown;
        }
        text(g, shown, l + 8, t + 6.5f, 9.5f, r - l - 16, empty ? MUTED : WHITE);
        if (on && isCaretVisible()) {
            int cx = x(l + 8) + (empty ? 0 : PhoneModernText.width(shown, size(9.5f))) + 1;
            g.fill(cx, y(t + 5), cx + Math.max(1, size(1)), y(b - 5), WHITE);
        }
        hit(l, t, r, b, () -> focused = f);
    }

    protected static int lighten(int c) {
        int r = Math.min(255, ((c >> 16) & 255) + 35), gg = Math.min(255, ((c >> 8) & 255) + 35), b = Math.min(255, (c & 255) + 35);
        return 0xFF000000 | (r << 16) | (gg << 8) | b;
    }

    protected void resetScroll() { scroll = 0; }

    // ------------------------------------------------------------------ rendu

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        clipT = 0;
        clipB = 1000;
        renderBackground(g);
        g.fill(x(3), y(8), x(297), y(606), bg());
        decor(g);
        drawStatusBar(g);
        drawText(g, title(), 22, 56, 20, titleColor(), false);
        String badge = badge();
        if (!badge.isEmpty()) {
            box(g, 186, 58, 284, 76, 9, badgeColor());
            center(g, badge, 235, 63, 8.5f, 94, WHITE);
        }
        String[] tabs = tabs();
        if (tab >= tabs.length) tab = 0;
        int perRow = tabs.length <= 4 ? tabs.length : (tabs.length + 1) / 2;
        int rows = (tabs.length + perRow - 1) / perRow;
        float tabW = (R - L - (perRow - 1) * 5) / perRow;
        for (int i = 0; i < tabs.length; i++) {
            final int idx = i;
            float l = L + (i % perRow) * (tabW + 5), t = 90 + (i / perRow) * 26;
            button(g, mx, my, l, t, l + tabW, t + 22, tabs[i], 8.5f, tab == i ? tabOn() : tabOff(), true, () -> {
                if (tab != idx) { tab = idx; scroll = 0; focused = null; tabChanged(); }
            });
        }
        contentTop = 90 + rows * 26 + 8;
        String msg = message();
        float bottom = msg.isEmpty() ? CB : CB - 30;
        clipT = contentTop;
        clipB = bottom;
        g.enableScissor(x(3), y(clipT), x(297), y(clipB));
        contentBottom = content(g, mx, my, contentTop + 2 - scroll) + scroll;
        g.disableScissor();
        clipT = 0;
        clipB = 1000;
        if (!msg.isEmpty()) {
            box(g, L, CB - 26, R, CB - 2, 10, messageError() ? RED : GREEN);
            center(g, msg, 150, CB - 19, 8.5f, R - L - 12, WHITE);
        }
        drawHomeIndicator(g);
        drawFrame(g);
    }

    // ------------------------------------------------------------------ souris et clavier

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            focused = null;
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (inside(mx, my, h.l, h.t, h.r, h.b)) {
                    h.action.run();
                    Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        float view = (message().isEmpty() ? CB : CB - 30) - contentTop;
        float max = Math.max(0, contentBottom - contentTop - view + 8);
        scroll = Math.max(0, Math.min(scroll - (float) delta * 24, max));
        return true;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (focused != null) {
            if (SharedConstants.isAllowedChatCharacter(c) && focused.value.length() < focused.max) focused.value += c;
            return true;
        }
        return super.charTyped(c, mods);
    }

    @Override
    public boolean keyPressed(int key, int sc, int mods) {
        if (focused != null) {
            if (key == 256 || key == 257 || key == 335) { focused = null; return true; }
            if (key == 259 && !focused.value.isEmpty()) focused.value = focused.value.substring(0, focused.value.length() - 1);
            else if (Screen.isPaste(key)) {
                String clip = Minecraft.getInstance().keyboardHandler.getClipboard().replaceAll("[\\r\\n]", " ");
                StringBuilder b = new StringBuilder(focused.value);
                for (char c : clip.toCharArray()) if (b.length() < focused.max && SharedConstants.isAllowedChatCharacter(c)) b.append(c);
                focused.value = b.toString();
            }
            return true;   // les touches tapées dans un champ ne déclenchent rien d'autre (inventaire, chat…)
        }
        return super.keyPressed(key, sc, mods);
    }
}
