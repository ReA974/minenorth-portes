package fr.minenorth.portes.agency;

import java.util.Locale;

/** Catégorie d'un bien affichée dans l'agence (filtre du catalogue). */
public enum PropertyCategory {
    /** LOCAL : local particulier (garage, boutique, box…) : comme une maison, ouvert à tous les joueurs (pas réservé aux entreprises). */
    HOUSE, APARTMENT, BUSINESS, LOCAL;

    public static PropertyCategory from(String value) {
        try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); } catch (Exception e) { return HOUSE; }
    }
}
