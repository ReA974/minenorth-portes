package fr.minenorth.portes.agency;

import java.util.Locale;

/** Catégorie d'un bien affichée dans l'agence (filtre du catalogue). */
public enum PropertyCategory {
    HOUSE, APARTMENT, BUSINESS;

    public static PropertyCategory from(String value) {
        try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); } catch (Exception e) { return HOUSE; }
    }
}
