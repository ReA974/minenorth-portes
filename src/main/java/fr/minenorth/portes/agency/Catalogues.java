package fr.minenorth.portes.agency;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Catalogues d'annonces. Un bien sans catalogue est dans l'agence ; avec un catalogue (ex. « casino ») il n'apparaît que
 * chez le PNJ de ce catalogue (tag « agence:casino »). Le nom est normalisé : minuscules, sans accents, 24 caractères au plus.
 */
public final class Catalogues {
    private Catalogues() {}

    public static final int MAX_LENGTH = 24;
    public static final String NPC_TAG = "agence";

    public static String normalize(String name) {
        if (name == null) return "";
        String n = Normalizer.normalize(name.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        n = n.replaceAll("\\s+", "_").replaceAll("[^a-z0-9_-]", "").replaceAll("^_+|_+$", "");
        return n.length() > MAX_LENGTH ? n.substring(0, MAX_LENGTH) : n;
    }

    public static boolean same(String a, String b) { return normalize(a).equals(normalize(b)); }

    /** Catalogue désigné par le tag d'un PNJ : « agence » = l'agence (""), « agence:nom » = ce catalogue ; null si ce n'est pas un tag d'agence. */
    public static String fromNpcTag(String tag) {
        if (tag == null) return null;
        if (tag.equals(NPC_TAG)) return "";
        return tag.startsWith(NPC_TAG + ":") ? normalize(tag.substring(NPC_TAG.length() + 1)) : null;
    }
}
