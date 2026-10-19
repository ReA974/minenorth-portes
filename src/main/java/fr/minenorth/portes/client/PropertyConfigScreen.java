package fr.minenorth.portes.client;

import fr.minenorth.portes.agency.PropertyCategory;
import fr.minenorth.portes.network.ModNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Fiche d'agence d'un bien (admin) : catégorie, meublé, description, photos d'aperçu. S'ouvre depuis le panneau de porte. */
public class PropertyConfigScreen extends Screen {
    private static final int W = 420, H = 300, PER_ROW = 4;
    private final ModNetwork.DoorPanelPacket data;
    private String category;
    private boolean furnished;
    private EditBox description;
    private MineNorthButton photoButton;

    public PropertyConfigScreen(ModNetwork.DoorPanelPacket data) {
        super(Component.literal("Fiche d'agence"));
        this.data = data;
        this.category = data.category();
        this.furnished = data.furnished();
    }

    private int left() { return (width - W) / 2; }
    private int top() { return Math.max(8, (height - H) / 2); }

    private void send(int action, String text, String extra) {
        ModNetwork.CHANNEL.sendToServer(new ModNetwork.DoorActionPacket(action, text, false, extra));
        onClose();
    }

    @Override
    protected void init() {
        super.init();
        int x = left(), y = top();
        int bw = (W - 40 - (PER_ROW - 1) * 8) / PER_ROW;
        PropertyCategory[] cats = PropertyCategory.values();
        for (int i = 0; i < cats.length; i++) {
            final PropertyCategory c = cats[i];
            addRenderableWidget(new MineNorthButton(x + 20 + (i % PER_ROW) * (bw + 8), y + 62 + (i / PER_ROW) * 26, bw, 22, Component.literal(c.shortLabel()),
                    category.equals(c.name()) ? MineNorthStyle.GREEN : MineNorthStyle.DARK, () -> { category = c.name(); rebuildWidgets(); }));
        }
        addRenderableWidget(new MineNorthButton(x + 20, y + 120, W - 40, 22, Component.literal(furnished ? "MEUBLÉ : OUI" : "MEUBLÉ : NON"),
                furnished ? MineNorthStyle.GREEN : MineNorthStyle.DARK, () -> { furnished = !furnished; rebuildWidgets(); }));
        String keep = description == null ? data.description() : description.getValue();
        description = new EditBox(font, x + 20, y + 162, W - 40, 20, Component.literal("Description"));
        description.setMaxLength(200);
        description.setHint(Component.literal("Configuration, pièces, remarques…"));
        description.setValue(keep == null ? "" : keep);
        addRenderableWidget(description);
        int half = (W - 48) / 2;
        photoButton = addRenderableWidget(new MineNorthButton(x + 20, y + 196, half, 22, Component.literal("PRENDRE LES 3 PHOTOS"), MineNorthStyle.CYAN, this::capture)
                .enabled(!dirty()));
        description.setResponder(s -> photoButton.enabled(!dirty()));
        addRenderableWidget(new MineNorthButton(x + 28 + half, y + 196, half, 22, Component.literal("EFFACER LES CAPTURES"), MineNorthStyle.PINK,
                () -> send(23, "", "")).enabled(data.imageCount() > 0));
        addRenderableWidget(new MineNorthButton(x + 20, y + 262, half, 28, Component.literal("ENREGISTRER"), MineNorthStyle.GREEN,
                () -> send(22, category + "|" + (furnished ? "1" : "0"), description.getValue())));
        addRenderableWidget(new MineNorthButton(x + 28 + half, y + 262, half, 28, Component.literal("FERMER"), MineNorthButton.GHOST, this::onClose));
    }

    /** Vrai si la fiche modifiée à l'écran n'est pas encore enregistrée. */
    private boolean dirty() {
        String desc = description == null ? data.description() : description.getValue();
        return !category.equals(data.category()) || furnished != data.furnished() || !desc.trim().equals(data.description().trim());
    }

    /** Ferme l'écran et passe en mode photo : un clic droit par photo (voir {@link PropertyCapture}). */
    private void capture() {
        onClose();
        PropertyCapture.start();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        int x = left(), y = top();
        MineNorthStyle.panel(g, x, y, W, H, "FICHE D'AGENCE", data.houseName().toUpperCase());
        g.drawString(font, "CATÉGORIE", x + 20, y + 50, MineNorthStyle.BLUE, false);
        g.drawString(font, "DESCRIPTION", x + 20, y + 150, MineNorthStyle.BLUE, false);
        g.drawString(font, "Photos : " + data.imageCount() + " / 3 — enregistrez la fiche, placez-vous devant le bien,", x + 20, y + 226, MineNorthStyle.MUTED, false);
        g.drawString(font, "puis clic droit pour chaque photo (les 3 remplacent les anciennes).", x + 20, y + 237, MineNorthStyle.MUTED, false);
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
