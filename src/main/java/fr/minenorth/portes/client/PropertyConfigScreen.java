package fr.minenorth.portes.client;

import fr.minenorth.portes.agency.Catalogues;
import fr.minenorth.portes.agency.PropertyCategory;
import fr.minenorth.portes.network.ModNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Fiche d'agence d'un bien (admin) : catégorie, meublé, catalogue, entreprise bénéficiaire, description, photos. S'ouvre depuis le panneau de porte. */
public class PropertyConfigScreen extends Screen {
    private static final int W = 420, H = 340, PER_ROW = 4;
    private final ModNetwork.DoorPanelPacket data;
    private String category;
    private boolean furnished;
    /** Index dans data.companies() de l'entreprise bénéficiaire, -1 = aucune (le trésor encaisse). */
    private int payee;
    private String catalogueText;
    private EditBox description, catalogue;
    private MineNorthButton photoButton;

    public PropertyConfigScreen(ModNetwork.DoorPanelPacket data) {
        super(Component.literal("Fiche d'agence"));
        this.data = data;
        this.category = data.category();
        this.furnished = data.furnished();
        this.catalogueText = data.catalogue();
        this.payee = -1;
        List<String> companies = data.companies();
        for (int i = 0; i < companies.size(); i++) if (companies.get(i).startsWith(data.payeeCompany() + "|")) this.payee = i;
    }

    private int left() { return (width - W) / 2; }
    private int top() { return Math.max(8, (height - H) / 2); }

    private int payeeId() {
        if (payee < 0 || payee >= data.companies().size()) return 0;
        String c = data.companies().get(payee);
        try { return Integer.parseInt(c.substring(0, c.indexOf('|'))); } catch (RuntimeException e) { return 0; }
    }

    private String payeeName() {
        if (payee < 0 || payee >= data.companies().size()) return "AUCUNE (trésor)";
        String c = data.companies().get(payee);
        return c.substring(c.indexOf('|') + 1);
    }

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
                    category.equals(c.name()) ? MineNorthStyle.GREEN : MineNorthStyle.DARK, () -> { keep(); category = c.name(); rebuildWidgets(); }));
        }
        addRenderableWidget(new MineNorthButton(x + 20, y + 120, W - 40, 22, Component.literal(furnished ? "MEUBLÉ : OUI" : "MEUBLÉ : NON"),
                furnished ? MineNorthStyle.GREEN : MineNorthStyle.DARK, () -> { keep(); furnished = !furnished; rebuildWidgets(); }));

        int half = (W - 48) / 2;
        catalogue = new EditBox(font, x + 20, y + 164, half, 20, Component.literal("Catalogue"));
        catalogue.setMaxLength(Catalogues.MAX_LENGTH);
        catalogue.setHint(Component.literal("vide = agence"));
        catalogue.setValue(catalogueText == null ? "" : catalogueText);
        addRenderableWidget(catalogue);
        addRenderableWidget(new MineNorthButton(x + 28 + half, y + 164, half, 20, Component.literal(payeeName().toUpperCase()),
                payee >= 0 ? MineNorthStyle.GREEN : MineNorthStyle.DARK, () -> {
                    keep();
                    payee = payee + 1 >= data.companies().size() ? -1 : payee + 1;
                    rebuildWidgets();
                }).enabled(!data.companies().isEmpty()));

        String keepDesc = description == null ? data.description() : description.getValue();
        description = new EditBox(font, x + 20, y + 206, W - 40, 20, Component.literal("Description"));
        description.setMaxLength(200);
        description.setHint(Component.literal("Configuration, pièces, remarques…"));
        description.setValue(keepDesc == null ? "" : keepDesc);
        addRenderableWidget(description);

        photoButton = addRenderableWidget(new MineNorthButton(x + 20, y + 240, half, 22, Component.literal("PRENDRE LES 3 PHOTOS"), MineNorthStyle.CYAN, this::capture)
                .enabled(!dirty()));
        description.setResponder(s -> photoButton.enabled(!dirty()));
        catalogue.setResponder(s -> photoButton.enabled(!dirty()));
        addRenderableWidget(new MineNorthButton(x + 28 + half, y + 240, half, 22, Component.literal("EFFACER LES CAPTURES"), MineNorthStyle.PINK,
                () -> send(23, "", "")).enabled(data.imageCount() > 0));
        addRenderableWidget(new MineNorthButton(x + 20, y + 304, half, 28, Component.literal("ENREGISTRER"), MineNorthStyle.GREEN,
                () -> send(22, category + "|" + (furnished ? "1" : "0") + "|" + catalogue.getValue() + "|" + payeeId(), description.getValue())));
        addRenderableWidget(new MineNorthButton(x + 28 + half, y + 304, half, 28, Component.literal("FERMER"), MineNorthButton.GHOST, this::onClose));
    }

    /** Mémorise la saisie du catalogue avant de reconstruire l'écran. */
    private void keep() { if (catalogue != null) catalogueText = catalogue.getValue(); }

    /** Vrai si la fiche modifiée à l'écran n'est pas encore enregistrée. */
    private boolean dirty() {
        String desc = description == null ? data.description() : description.getValue();
        String cat = catalogue == null ? data.catalogue() : catalogue.getValue();
        return !category.equals(data.category()) || furnished != data.furnished() || !desc.trim().equals(data.description().trim())
                || !Catalogues.same(cat, data.catalogue()) || payeeId() != data.payeeCompany();
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
        g.drawString(font, "CATALOGUE (vide = agence)", x + 20, y + 152, MineNorthStyle.BLUE, false);
        g.drawString(font, "ENTREPRISE QUI ENCAISSE", x + 28 + (W - 48) / 2, y + 152, MineNorthStyle.BLUE, false);
        g.drawString(font, "DESCRIPTION", x + 20, y + 194, MineNorthStyle.BLUE, false);
        g.drawString(font, "Photos : " + data.imageCount() + " / 3 — enregistrez la fiche, placez-vous devant le bien,", x + 20, y + 270, MineNorthStyle.MUTED, false);
        g.drawString(font, "puis clic droit pour chaque photo (les 3 remplacent les anciennes).", x + 20, y + 281, MineNorthStyle.MUTED, false);
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
