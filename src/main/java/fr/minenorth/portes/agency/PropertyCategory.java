package fr.minenorth.portes.agency;

import java.util.Locale;

/**
 * Catégorie d'un bien (filtre du catalogue, réglée dans la fiche d'agence de la porte).
 * BUSINESS est réservé aux entreprises ; LOCAL, STORAGE et GARAGE s'achètent au choix en personnel ou par une entreprise ;
 * les autres sont pour les joueurs seulement.
 * L'ordre ne doit pas changer : le filtre du catalogue suit cet ordre.
 */
public enum PropertyCategory {
    HOUSE("Maison", "MAISON"),
    APARTMENT("Appartement", "APPART."),
    BUSINESS("Local d'entreprise", "ENTREPRISE"),
    LOCAL("Local", "LOCAL"),
    SMALL_SHOP("Petit commerce", "COMMERCE"),
    STORAGE("Stockage", "STOCKAGE"),
    GARAGE("Garage", "GARAGE"),
    OTHER("Autres", "AUTRES");

    private final String label;
    private final String shortLabel;

    PropertyCategory(String label, String shortLabel) { this.label = label; this.shortLabel = shortLabel; }

    /** Nom complet en français (fiche d'un bien, messages). */
    public String label() { return label; }

    /** Nom court en majuscules (boutons). */
    public String shortLabel() { return shortLabel; }

    /** Vrai si seul le patron d'une entreprise peut acquérir ce bien (avec le compte de l'entreprise). */
    public boolean companyOnly() { return this == BUSINESS; }

    /** Vrai si le bien peut s'acheter ou se louer au choix par un joueur (compte perso) ou par une entreprise dont il est patron. */
    public boolean companyOptional() { return this == LOCAL || this == STORAGE || this == GARAGE; }

    /** Vrai si une entreprise peut acquérir ce bien (obligatoirement ou au choix). */
    public boolean companyAllowed() { return companyOnly() || companyOptional(); }

    public static PropertyCategory from(String value) {
        try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); } catch (Exception e) { return HOUSE; }
    }
}
