package fr.minenorth.portes.client;

import fr.minenorth.portes.network.ModNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Fiche d'agence d'un bien (admin) : catégorie, meublé, description, captures d'aperçu. S'ouvre depuis le panneau de porte. */
public class PropertyConfigScreen extends Screen {
    private static final int W = 420, H = 270;
    private final ModNetwork.DoorPanelPacket data;
    private String category;
    private boolean furnished;
    private EditBox description;

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
        int third = (W - 40 - 16) / 3;
        String[][] cats = {{"HOUSE", "MAISON"}, {"APARTMENT", "APPARTEMENT"}, {"BUSINESS", "ENTREPRISE"}};
        for (int i = 0; i < 3; i++) {
            final String id = cats[i][0];
            addRenderableWidget(new MineNorthButton(x + 20 + i * (third + 8), y + 62, third, 22, Component.literal(cats[i][1]),
                    category.equals(id) ? MineNorthStyle.GREEN : MineNorthStyle.DARK, () -> { category = id; rebuildWidgets(); }));
        }
        addRenderableWidget(new MineNorthButton(x + 20, y + 98, W - 40, 22, Component.literal(furnished ? "MEUBLÉ : OUI" : "MEUBLÉ : NON"),
                furnished ? MineNorthStyle.GREEN : MineNorthStyle.DARK, () -> { furnished = !furnished; rebuildWidgets(); }));
        String keep = description == null ? data.description() : description.getValue();
        description = new EditBox(font, x + 20, y + 144, W - 40, 20, Component.literal("Description"));
        description.setMaxLength(200);
        description.setHint(Component.literal("Configuration, pièces, remarques…"));
        description.setValue(keep == null ? "" : keep);
        addRenderableWidget(description);
        int half = (W - 48) / 2;
        photoButton = addRenderableWidget(new MineNorthButton(x + 20, y + 178, half, 22, Component.literal("PRENDRE LES 3 PHOTOS"), MineNorthStyle.CYAN, this::capture)
                .enabled(!dirty()));
        description.setResponder(s -> photoButton.enabled(!dirty()));
        addRenderableWidget(new MineNorthButton(x + 28 + half, y + 178, half, 22, Component.literal("EFFACER LES CAPTURES"), MineNorthStyle.PINK,
                () -> send(23, "", "")).enabled(data.imageCount() > 0));
        addRenderableWidget(new MineNorthButton(x + 20, y + 234, half, 28, Component.literal("ENREGISTRER"), MineNorthStyle.GREEN,
                () -> send(22, category + "|" + (furnished ? "1" : "0"), description.getValue())));
        addRenderableWidget(new MineNorthButton(x + 28 + half, y + 234, half, 28, Component.literal("FERMER"), MineNorthButton.GHOST, this::onClose));
    }

    private MineNorthButton photoButton;

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
        g.drawString(font, "DESCRIPTION", x + 20, y + 132, MineNorthStyle.BLUE, false);
        g.drawString(font, "Photos : " + data.imageCount() + " / 3 — enregistrez la fiche, placez-vous devant le bien,", x + 20, y + 204, MineNorthStyle.MUTED, false);
        g.drawString(font, "puis clic droit pour chaque photo (les 3 remplacent les anciennes).", x + 20, y + 214, MineNorthStyle.MUTED, false);
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
