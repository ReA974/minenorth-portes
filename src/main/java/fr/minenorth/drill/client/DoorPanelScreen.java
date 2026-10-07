package fr.minenorth.portes.client;

import fr.minenorth.portes.network.ModNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * MineNorth door management UI.
 * The layout deliberately uses one vertical flow per screen so labels and
 * buttons never overlap when the player changes GUI scale.
 */
public class DoorPanelScreen extends Screen {
    private final ModNetwork.DoorPanelPacket data;
    private EditBox name, price, days, permission, house;
    private int selectedType = 0;
    /** -1 = regular panel, 0 = permanent listing, 1 = temporary listing. */
    private int listingSetupMode = -1;
    private static final int PANEL_W = 560;
    private static final int PANEL_H = 460;

    public DoorPanelScreen(ModNetwork.DoorPanelPacket data) {
        super(Component.literal("Gestion de porte"));
        this.data = data;
        this.selectedType = switch (data.type()) {
            case "POLICE" -> 1;
            case "POMPIER" -> 2;
            case "ENTREPRISE" -> 3;
            case "ORGANISATION" -> 4;
            default -> 0;
        };
        this.sHouse = data.houseName() == null ? "" : data.houseName();
        this.sPrice = data.listingPrice() > 0 ? MineNorthStyle.euros(data.listingPrice()).replace(" €", "") : "";
        this.sDays = data.listingDays() > 0 ? String.valueOf(data.listingDays()) : "";
        this.rental = "RENTAL".equals(data.listingMode());
        int[] linked = link(data.permission());
        if (linked != null) {
            for (int i = 0; i < data.companies().size(); i++) {
                if (data.companies().get(i).startsWith(linked[0] + "|")) this.selCompany = i;
            }
        }
        this.aPerm = data.permission() == null ? "" : data.permission();
        var me = net.minecraft.client.Minecraft.getInstance().player;
        this.aName = me == null ? "" : me.getGameProfile().getName();
    }

    private int left() { return (width - PANEL_W) / 2; }
    private int top() { return Math.max(8, (height - PANEL_H) / 2); }

    private void send(int action, String text, boolean flag, String extra) {
        ModNetwork.CHANNEL.sendToServer(new ModNetwork.DoorActionPacket(action,
                text == null ? "" : text, flag, extra == null ? "" : extra));
        onClose();
    }

    private MineNorthButton btn(int x, int y, int w, int h, String label, int color, Runnable r) {
        return addRenderableWidget(new MineNorthButton(x, y, w, h, Component.literal(label), color, r));
    }

    private EditBox box(int x, int y, int w, String hint, String value) {
        EditBox b = new EditBox(font, x, y, w, 20, Component.literal(hint));
        b.setHint(Component.literal(hint));
        b.setValue(value == null ? "" : value);
        addRenderableWidget(b);
        return b;
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        labels.clear();
        int x = left(), y = top(), w = PANEL_W;
        switch (data.mode()) {
            case 0 -> buildSplit(x, y, w);
            case 1 -> buildOwner(x, y, w);
            case 2 -> buildRequest(x, y, w);
            case 3 -> buildListing(x, y, w);
            default -> { if (splitLayout()) buildSplit(x, y, w); else buildAdmin(x, y, w); }
        }
        btn(x + w - 100, y + 10, 86, 16, "Fermer", MineNorthButton.GHOST, this::onClose);
    }

    // ------------------------------------------------------------------
    // Porte sans propriétaire : deux espaces séparés sur la même interface.
    //  A) VENTE / LOCATION : uniquement la maison (aucun nom de joueur).
    //  B) ATTRIBUTION      : à un joueur ou à un service.
    // ------------------------------------------------------------------
    private EditBox fHouse, fPrice, fDays, fPlayer, fPlayerHouse, fPerm;
    private String sHouse, sPrice, sDays, aName, aHouse = "", aPerm;
    private boolean rental;
    private String error = "";

    /** true quand la porte n'a aucun propriétaire (nouvelle porte ou maison gérée par les OP). */
    private boolean splitLayout() {
        return data.mode() == 0 || (data.mode() == 4 && (data.ownerName() == null || data.ownerName().isBlank()));
    }

    /** Mémorise les saisies avant de reconstruire l'écran (changement d'onglet). */
    private void keep() {
        if (fHouse != null) sHouse = fHouse.getValue();
        if (fPrice != null) sPrice = fPrice.getValue();
        if (fDays != null) sDays = fDays.getValue();
        if (fPlayer != null) aName = fPlayer.getValue();
        if (fPlayerHouse != null) aHouse = fPlayerHouse.getValue();
        if (fPerm != null && selectedType >= 3) aPerm = fPerm.getValue();
    }

    private void switchTo(Runnable change) { keep(); error = ""; change.run(); rebuildWidgets(); }

    // ---------- Lien avec le mod Entreprises ----------
    /** Index de l'entreprise choisie dans data.companies() (entrées "id|nom"). */
    private int selCompany = 0;

    /** {id, gradeMin} d'une permission "entreprise:<id>:<gradeMin>", ou null. */
    private static int[] link(String permission) {
        if (permission == null || !permission.startsWith("entreprise:")) return null;
        try {
            String[] p = permission.split(":");
            return new int[]{Integer.parseInt(p[1].trim()), p.length > 2 ? Integer.parseInt(p[2].trim()) : 99};
        } catch (RuntimeException e) { return null; }
    }

    /** Type ENTREPRISE + au moins une entreprise active : on choisit dans la liste au lieu de taper une permission. */
    private boolean companyMode() { return selectedType == 3 && !data.companies().isEmpty(); }

    private String[] company() {
        selCompany = Math.max(0, Math.min(data.companies().size() - 1, selCompany));
        String[] p = data.companies().get(selCompany).split("\\|", 2);
        return p.length == 2 ? p : new String[]{p[0], p[0]};
    }

    /** Permission à enregistrer pour l'entreprise choisie (on garde le réglage de grade si c'est déjà la même). */
    private String companyPerm() {
        String id = company()[0];
        int[] current = link(data.permission());
        int min = current != null && String.valueOf(current[0]).equals(id) ? current[1] : 99;
        return "entreprise:" + id + ":" + min;
    }

    /** Sélecteur  <  Nom de l'entreprise  >  */
    private void companyPicker(int x, int y, int w) {
        int n = data.companies().size();
        String name = font.plainSubstrByWidth(company()[1], w - 60);
        btn(x, y, 20, 20, "<", MineNorthStyle.DARK, () -> switchTo(() -> selCompany = (selCompany - 1 + n) % n)).enabled(n > 1);
        btn(x + w - 20, y, 20, 20, ">", MineNorthStyle.DARK, () -> switchTo(() -> selCompany = (selCompany + 1) % n)).enabled(n > 1);
        drawLabelLater(name, x + w / 2 - font.width(name) / 2, y + 6, MineNorthStyle.WHITE);
        String count = (selCompany + 1) + " / " + n;
        drawLabelLater(count, x + w - 26 - font.width(count), y + 6, MineNorthStyle.MUTED);
    }

    private void buildSplit(int x, int y, int w) {
        fDays = null; fPlayer = null; fPlayerHouse = null; fPerm = null;
        boolean existing = data.mode() == 4;
        boolean listed = !"NONE".equals(data.listingMode());
        int ix = x + 30, iw = w - 60, half = (iw - 10) / 2;

        // ---------- A) VENTE / LOCATION ----------
        drawLabelLater("VENTE / LOCATION DE LA MAISON", ix, y + 72, MineNorthStyle.WHITE);
        if (listed) {
            String info = ("RENTAL".equals(data.listingMode()) ? "EN LOCATION • " : "EN VENTE • ") + MineNorthStyle.euros(data.listingPrice())
                    + ("RENTAL".equals(data.listingMode()) ? " / " + data.listingDays() + " j" : "");
            drawLabelLater(info, ix + iw - font.width(info), y + 72, MineNorthStyle.OK);
        }
        drawLabelLater("NOM DE LA MAISON", ix, y + 88, MineNorthStyle.BLUE);
        fHouse = box(ix, y + 99, iw, "Nom de la maison", sHouse);
        btn(ix, y + 125, half, 20, "VENTE", !rental ? MineNorthStyle.GREEN : MineNorthStyle.DARK, () -> switchTo(() -> rental = false));
        btn(ix + half + 10, y + 125, half, 20, "LOCATION", rental ? MineNorthStyle.CYAN : MineNorthStyle.DARK, () -> switchTo(() -> rental = true));
        drawLabelLater(rental ? "PRIX DE LA LOCATION (€)" : "PRIX DE VENTE (€)", ix, y + 152, MineNorthStyle.BLUE);
        fPrice = box(ix, y + 163, rental ? half : iw, "Prix en euros", sPrice);
        if (rental) {
            drawLabelLater("DURÉE (JOURS)", ix + half + 10, y + 152, MineNorthStyle.BLUE);
            fDays = box(ix + half + 10, y + 163, half, "Nombre de jours", sDays);
        }
        String go = listed ? "MODIFIER L'ANNONCE" : rental ? "METTRE EN LOCATION" : "METTRE EN VENTE";
        if (existing && listed) {
            btn(ix, y + 190, half, 22, go, MineNorthStyle.GREEN, this::submitListing);
            btn(ix + half + 10, y + 190, half, 22, "RETIRER L'ANNONCE", MineNorthStyle.PINK, () -> send(14, "", false, ""));
        } else {
            btn(ix, y + 190, iw, 22, go, MineNorthStyle.GREEN, this::submitListing);
        }

        // ---------- B) ATTRIBUTION ----------
        int by = y + 228;
        drawLabelLater("ATTRIBUER À UN JOUEUR OU À UN SERVICE", ix, by + 8, MineNorthStyle.WHITE);
        String[] types = {"JOUEUR", "POLICE", "POMPIER", "ENTREPRISE", "ORGA"};
        int bw = (iw - 40) / 5;
        for (int i = 0; i < types.length; i++) {
            final int t = i;
            btn(ix + i * (bw + 10), by + 22, bw, 20, types[i], selectedType == i ? MineNorthStyle.CYAN : MineNorthStyle.DARK,
                    () -> switchTo(() -> selectedType = t));
        }
        int buttonY;
        if (selectedType == 0) {
            drawLabelLater("JOUEUR À ATTRIBUER", ix, by + 50, MineNorthStyle.BLUE);
            fPlayer = box(ix, by + 61, iw, "Nom Minecraft du joueur", aName);
            if (!existing) {
                drawLabelLater("NOM DE LA MAISON (OPTIONNEL)", ix, by + 88, MineNorthStyle.BLUE);
                fPlayerHouse = box(ix, by + 99, iw, "Nom de la maison", aHouse);
                buttonY = by + 126;
            } else buttonY = by + 88;
        } else if (companyMode()) {
            drawLabelLater("ENTREPRISE", ix, by + 50, MineNorthStyle.BLUE);
            companyPicker(ix, by + 61, iw);
            buttonY = by + 88;
        } else {
            drawLabelLater("PERMISSION", ix, by + 50, MineNorthStyle.BLUE);
            fPerm = box(ix, by + 61, iw, "Permission",
                    selectedType == 1 ? "grade.police" : selectedType == 2 ? "grade.pompier" : aPerm);
            fPerm.setEditable(selectedType >= 3);
            buttonY = by + 88;
        }
        btn(ix, buttonY, iw, 22, "ATTRIBUER LA PORTE", MineNorthStyle.CYAN, this::submitAssign);

        if (existing) btn(x + 14, y + PANEL_H - 30, 130, 20, "LIBÉRER LA PORTE", MineNorthStyle.PINK, () -> send(13, "", false, ""));
    }

    private static boolean positiveNumber(String v, boolean integer) {
        try {
            String t = v.trim().replace(',', '.').replace("€", "").trim();
            return integer ? Integer.parseInt(t) > 0 : Double.parseDouble(t) > 0;
        } catch (Exception e) { return false; }
    }

    private void submitListing() {
        String h = fHouse.getValue().trim(), p = fPrice.getValue().trim(), d = fDays == null ? "0" : fDays.getValue().trim();
        if (h.isEmpty()) { error = "Indiquez le nom de la maison."; return; }
        if (!positiveNumber(p, false)) { error = "Indiquez un prix valide."; return; }
        if (rental && !positiveNumber(d, true)) { error = "Indiquez une durée de location en jours."; return; }
        if (data.mode() == 4) {
            if (rental) send(8, p, false, d + "|" + h); else send(7, p, false, h);
        } else {
            send(18, p, false, (rental ? "RENTAL" : "SALE") + "|" + (rental ? d : "0") + "|" + h);
        }
    }

    private void submitAssign() {
        boolean existing = data.mode() == 4;
        if (selectedType == 0) {
            String n = fPlayer.getValue().trim();
            if (n.isEmpty()) { error = "Indiquez le nom du joueur."; return; }
            if (existing) send(12, n, false, "PERSONAL|");
            else send(0, n, false, "PERSONAL|" + fPlayerHouse.getValue().trim());
        } else {
            String type = selectedType == 1 ? "POLICE" : selectedType == 2 ? "POMPIER" : selectedType == 3 ? "ENTREPRISE" : "ORGANISATION";
            String perm = companyMode() ? companyPerm() : fPerm.getValue().trim();
            if (selectedType >= 3 && perm.isEmpty()) { error = "Indiquez la permission."; return; }
            send(existing ? 12 : 0, "", false, type + "|" + perm);
        }
    }

    private String headerHint = "";
    private final java.util.List<Label> labels = new java.util.ArrayList<>();
    private record Label(String text, int x, int y, int color) {}

    private void drawLabelLater(String text, int x, int y, int color) {
        labels.add(new Label(text, x, y, color));
    }

    private void buildOwner(int x, int y, int w) {
        labels.clear();
        String type = data.type().toLowerCase(Locale.ROOT);
        headerHint = "Type : " + type + (data.ownerName() == null || data.ownerName().isBlank() ? "" : "  •  Propriétaire : " + data.ownerName());
        boolean personal = "PERSONAL".equals(data.type());

        if (personal) {
            drawLabelLater("AUTORISATIONS", x + 20, y + 70, MineNorthStyle.BLUE);
            name = box(x + 20, y + 86, w - 40, "Joueur à autoriser", "");
            int half = (w - 50) / 2;
            btn(x + 20, y + 112, half, 22, "AJOUTER L'ACCÈS", MineNorthStyle.CYAN, () -> send(1, name.getValue(), false, ""));
            btn(x + 30 + half, y + 112, half, 22, "RETIRER L'ACCÈS", MineNorthStyle.DARK, () -> send(2, name.getValue(), false, ""));

            drawLabelLater("MAISON", x + 20, y + 146, MineNorthStyle.BLUE);
            house = box(x + 20, y + 162, w - 40, "Nom de la maison", data.houseName());
            btn(x + 20, y + 188, w - 40, 22, "ENREGISTRER LE NOM DE LA MAISON", MineNorthStyle.DARK,
                    () -> send(15, house.getValue(), false, ""));
            btn(x + 20, y + 216, w - 40, 22, "LIBÉRER LA PORTE", MineNorthStyle.PINK, () -> send(3, "", false, ""));

            if (data.purchasePrice() > 0) {
                btn(x + 20, y + 246, w - 40, 22, "REVENDRE LA MAISON • 50 %", MineNorthStyle.GREEN, () -> send(17, "", false, ""));
            }

            if (!data.listingMode().equals("NONE")) {
                drawLabelLater("STATUT COMMERCIAL", x + 20, y + 282, MineNorthStyle.BLUE);
                String mode = "SALE".equals(data.listingMode()) ? "EN VENTE" : "EN LOCATION";
                String info = mode + " • " + MineNorthStyle.euros(data.listingPrice()) +
                        ("RENTAL".equals(data.listingMode()) ? " / " + data.listingDays() + " jours" : "");
                drawLabelLater(info, x + 170, y + 282, MineNorthStyle.OK);
            }

            int reqY = y + 310;
            drawLabelLater("DEMANDES D'ACCÈS", x + 20, reqY, MineNorthStyle.BLUE);
            int count = 0;
            for (String r : data.requests()) {
                String[] parts = r.split("\\|", 2);
                if (parts.length < 2) continue;
                int row = reqY + 18 + (count / 2) * 22;
                int col = x + 20 + (count % 2) * 190;
                btn(col, row, 170, 20, "✓ " + parts[0], MineNorthStyle.GREEN, () -> send(5, "", false, parts[1]));
                count++;
                if (row > y + 390) break;
            }
        } else if ("ENTREPRISE".equals(data.type()) && link(data.permission()) != null) {
            // Porte d'entreprise : le patron choisit à partir de quel grade ses employés peuvent l'ouvrir.
            int min = link(data.permission())[1];
            java.util.List<String> grades = data.grades();
            int last = grades.size() - 1;
            drawLabelLater("QUI PEUT OUVRIR CETTE PORTE ?", x + 20, y + 70, MineNorthStyle.BLUE);
            drawLabelLater("Le patron peut toujours l'ouvrir.", x + 20, y + 82, MineNorthStyle.MUTED);
            btn(x + 20, y + 98, w - 40, 20, "PATRON UNIQUEMENT", min < 0 ? MineNorthStyle.CYAN : MineNorthStyle.DARK, () -> send(19, "-1", false, ""));
            for (int i = 0; i <= last && i < 6; i++) {
                final int g = i;
                boolean all = i == last;
                boolean selected = all ? min >= last : min == i;
                String label = all ? "TOUS LES EMPLOYÉS" : "À PARTIR DU GRADE : " + grades.get(i).toUpperCase(Locale.ROOT);
                btn(x + 20, y + 122 + i * 24, w - 40, 20, label, selected ? MineNorthStyle.CYAN : MineNorthStyle.DARK,
                        () -> send(19, all ? "99" : String.valueOf(g), false, ""));
            }
        }
    }

    private void buildRequest(int x, int y, int w) {
        btn(x + 70, y + 115, w - 140, 28, "DEMANDER L'ACCÈS", MineNorthStyle.CYAN, () -> send(4, "", false, ""));
    }

    private void buildAdmin(int x, int y, int w) {
        labels.clear();
        boolean unownedPersonal = data.ownerName() == null || data.ownerName().isBlank();
        headerHint = unownedPersonal
                ? "MAISON MINE NORTH • gestion OP • aucun propriétaire avant l'achat"
                : "GESTION OP • porte actuellement attribuée à " + data.ownerName();

        drawLabelLater("PROPRIÉTAIRE MINECRAFT", x + 20, y + 68, MineNorthStyle.BLUE);
        name = box(x + 20, y + 84, w - 40, "Propriétaire Minecraft", data.ownerName());

        int bw = 88;
        btn(x + 20, y + 112, bw, 22, "PERSONNEL", selectedType == 0 ? MineNorthStyle.CYAN : MineNorthStyle.DARK, () -> { selectedType = 0; rebuildWidgets(); });
        btn(x + 112, y + 112, bw, 22, "POLICE", selectedType == 1 ? MineNorthStyle.CYAN : MineNorthStyle.DARK, () -> { selectedType = 1; rebuildWidgets(); });
        btn(x + 204, y + 112, bw, 22, "POMPIER", selectedType == 2 ? MineNorthStyle.CYAN : MineNorthStyle.DARK, () -> { selectedType = 2; rebuildWidgets(); });
        btn(x + 296, y + 112, bw, 22, "ENTREPRISE", selectedType == 3 ? MineNorthStyle.CYAN : MineNorthStyle.DARK, () -> { selectedType = 3; rebuildWidgets(); });
        btn(x + 388, y + 112, bw, 22, "ORGA", selectedType == 4 ? MineNorthStyle.CYAN : MineNorthStyle.DARK, () -> { selectedType = 4; rebuildWidgets(); });

        drawLabelLater("NOM DE LA MAISON", x + 20, y + 146, MineNorthStyle.BLUE);
        house = box(x + 20, y + 162, w - 40, "Nom de la maison", data.houseName());
        int next = 190;
        permission = null;
        if (companyMode()) {
            drawLabelLater("ENTREPRISE", x + 20, y + 190, MineNorthStyle.BLUE);
            companyPicker(x + 20, y + 206, w - 40);
            next = 234;
        } else if (selectedType >= 3) {
            drawLabelLater("PERMISSION", x + 20, y + 190, MineNorthStyle.BLUE);
            permission = box(x + 20, y + 206, w - 40, "Permission", data.permission());
            permission.setEditable(true);
            next = 234;
        }
        String type = selectedType == 1 ? "POLICE" : selectedType == 2 ? "POMPIER" : selectedType == 3 ? "ENTREPRISE" : selectedType == 4 ? "ORGANISATION" : "PERSONAL";
        btn(x + 20, y + next, w - 40, 24, "ENREGISTRER LES RÉGLAGES", MineNorthStyle.CYAN,
                () -> send(12, name.getValue(), false, type + "|" + (companyMode() ? companyPerm() : permission == null ? "" : permission.getValue())));
        btn(x + 20, y + next + 30, w - 40, 22, "ENREGISTRER LE NOM DE LA MAISON", MineNorthStyle.DARK,
                () -> send(15, house.getValue(), false, ""));

        // A house with no owner can be put on the market. An already attributed
        // door remains fully editable by OP, but the owner keeps the resale action.
        if (unownedPersonal) {
            if (listingSetupMode < 0) {
                if (!data.listingMode().equals("NONE")) {
                    String label = "SALE".equals(data.listingMode()) ? "MODIFIER LA VENTE" : "MODIFIER LA LOCATION";
                    btn(x + 20, y + next + 60, (w - 50) / 2, 22, label, MineNorthStyle.GREEN, () -> { listingSetupMode = "RENTAL".equals(data.listingMode()) ? 1 : 0; rebuildWidgets(); });
                    btn(x + 30 + (w - 50) / 2, y + next + 60, (w - 50) / 2, 22, "RETIRER L'ANNONCE", MineNorthStyle.PINK, () -> send(14, "", false, ""));
                } else {
                    btn(x + 20, y + next + 60, w - 40, 24, "METTRE LA MAISON EN VENTE", MineNorthStyle.GREEN, () -> { listingSetupMode = 0; rebuildWidgets(); });
                }
            } else {
                buildListingSetup(x, y, w, next + 60);
            }
        } else {
            drawLabelLater("ACCÈS ADMINISTRATEUR", x + 20, y + next + 62, MineNorthStyle.BLUE);
            btn(x + 20, y + next + 78, (w - 50) / 2, 22, "LIBÉRER LA PORTE", MineNorthStyle.PINK, () -> send(13, "", false, ""));
            btn(x + 30 + (w - 50) / 2, y + next + 78, (w - 50) / 2, 22, "RETIRER L'ANNONCE", MineNorthStyle.DARK, () -> send(14, "", false, ""));
        }
    }

    private void buildListingSetup(int x, int y, int w, int startY) {
        int half = (w - 50) / 2;
        btn(x + 20, y + startY, half, 22, "PERMANENTE", listingSetupMode == 0 ? MineNorthStyle.GREEN : MineNorthStyle.DARK,
                () -> { listingSetupMode = 0; rebuildWidgets(); });
        btn(x + 30 + half, y + startY, half, 22, "TEMPORAIRE", listingSetupMode == 1 ? MineNorthStyle.CYAN : MineNorthStyle.DARK,
                () -> { listingSetupMode = 1; rebuildWidgets(); });

        drawLabelLater("PRIX DE LA MAISON (€)", x + 20, y + startY + 32, MineNorthStyle.BLUE);
        price = box(x + 20, y + startY + 47, w - 40, "Prix en euros", data.listingPrice() > 0 ? String.valueOf(data.listingPrice() / 100.0) : "");

        int buttonY;
        if (listingSetupMode == 1) {
            drawLabelLater("DURÉE DE LA LOCATION (JOURS)", x + 20, y + startY + 74, MineNorthStyle.BLUE);
            days = box(x + 20, y + startY + 89, w - 40, "Nombre de jours", data.listingDays() > 0 ? String.valueOf(data.listingDays()) : "");
            buttonY = startY + 118;
        } else {
            buttonY = startY + 76;
        }
        btn(x + 20, y + buttonY, half, 22, "CONFIRMER", MineNorthStyle.GREEN, () -> {
            if (listingSetupMode == 1) send(8, price.getValue(), false, days.getValue() + "|" + house.getValue());
            else send(7, price.getValue(), false, house.getValue());
        });
        btn(x + 30 + half, y + buttonY, half, 22, "ANNULER", MineNorthStyle.PINK, () -> { listingSetupMode = -1; rebuildWidgets(); });
    }

    private void buildListing(int x, int y, int w) {
        boolean rental = "RENTAL".equals(data.listingMode());
        String action = rental ? (data.renter() == null || data.renter().isBlank() ? "LOUER LA MAISON" : "LOCATION DÉJÀ ACTIVE") : "ACHETER LA MAISON";
        btn(x + 50, y + 205, w - 100, 28, action, rental ? MineNorthStyle.CYAN : MineNorthStyle.GREEN,
                () -> send(rental ? 11 : 10, "", false, ""))
                .enabled(rental ? (data.renter() == null || data.renter().isBlank()) : true);
    }

    @Override
    protected void rebuildWidgets() {
        labels.clear();
        clearWidgets();
        init();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        int x = left(), y = top(), w = PANEL_W;
        String title = switch (data.mode()) {
            case 0 -> "CONFIGURER LA PORTE";
            case 1 -> "GESTION DE LA PORTE";
            case 2 -> "PORTE PROTÉGÉE";
            case 3 -> "PORTE À VENDRE / À LOUER";
            default -> "ADMINISTRATION DE LA PORTE";
        };
        String sub = data.mode() == 3
                ? ("RENTAL".equals(data.listingMode()) ? "LOCATION TEMPORAIRE" : "VENTE")
                : splitLayout() ? "OP • VENTE / LOCATION OU ATTRIBUTION"
                : data.mode() == 4 ? "OP • MODIFICATION DES RÉGLAGES" : data.type().toLowerCase(Locale.ROOT);
        MineNorthStyle.panel(g, x, y, w, PANEL_H, title, sub);

        if (splitLayout()) {
            // Deux blocs distincts : vente/location (vert) et attribution (bleu).
            MineNorthStyle.card(g, x + 20, y + 64, w - 40, 156, false, MineNorthStyle.GREEN);
            MineNorthStyle.card(g, x + 20, y + 228, w - 40, 178, false, MineNorthStyle.CYAN);
            if (!error.isEmpty()) g.drawString(font, error, x + 24, y + 412, MineNorthStyle.ALERT, false);
        } else if (data.mode() == 1) {
            g.drawString(font, headerHint, x + 20, y + 48, MineNorthStyle.TEXT, false);
        } else if (data.mode() == 2) {
            centeredNoShadow(g, "Cette porte est protégée.", width / 2, y + 78, MineNorthStyle.TEXT);
            centeredNoShadow(g, "Propriétaire : " + data.ownerName(), width / 2, y + 98, MineNorthStyle.MUTED);
        } else if (data.mode() == 4) {
            g.drawString(font, headerHint, x + 20, y + 48, MineNorthStyle.MUTED, false);
            g.drawString(font, "Type actuel : " + data.type(), x + 20, y + PANEL_H - 55, MineNorthStyle.BLUE, false);
            if (data.ownerName() != null && !data.ownerName().isBlank()) {
                g.drawString(font, "Propriétaire actuel : " + data.ownerName(), x + 220, y + PANEL_H - 55, MineNorthStyle.MUTED, false);
            }
        } else {
            boolean rental = "RENTAL".equals(data.listingMode());
            centeredNoShadow(g, rental ? "Location temporaire" : "Vente définitive", width / 2, y + 78, MineNorthStyle.BLUE);
            centeredNoShadow(g, MineNorthStyle.euros(data.listingPrice()) + (rental ? " / " + data.listingDays() + " jours" : ""), width / 2, y + 106, MineNorthStyle.TEXT);
            centeredNoShadow(g, "Paiement sécurisé par compte bancaire MineNorth.", width / 2, y + 132, MineNorthStyle.MUTED);
            if (!data.houseName().isBlank()) centeredNoShadow(g, "Maison : " + data.houseName(), width / 2, y + 156, MineNorthStyle.TEXT);
            if (rental && data.renter() != null && !data.renter().isBlank()) centeredNoShadow(g, "Cette location est déjà active.", width / 2, y + 180, MineNorthStyle.ALERT);
        }

        for (Label l : labels) g.drawString(font, l.text(), l.x(), l.y(), l.color(), false);
        super.render(g, mx, my, pt);
    }

    private void centeredNoShadow(GuiGraphics g, String text, int centerX, int y, int color) {
        g.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
