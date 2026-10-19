package fr.minenorth.portes.agency;

import java.util.Locale;

/**
 * Catégorie d'un bien (filtre du catalogue, réglée dans la fiche d'agence de la porte).
 * Seul BUSINESS est réservé aux entreprises ; toutes les autres sont ouvertes à tous les joueurs.
 * L'ordre ne doit pas changer : le filtre du catalogue suit cet ordre.
 */
public enum PropertyCategory {
    HOUSE("Maison", "MAISON"),
    APARTMENT("Appartement", "APPART."),
    BUSINESS("Local d'entreprise", "ENTREPRISE"),
    LOCAL("Local particulier", "LOCAL"),
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

    public static PropertyCategory from(String value) {
        try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); } catch (Exception e) { return HOUSE; }
    }
}
