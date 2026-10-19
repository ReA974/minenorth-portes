package fr.minenorth.portes.gang;

import java.util.Locale;

/** Grades d'un gang : plus le niveau est petit, plus le grade est haut (même logique que les grades d'entreprise). */
public enum GangRank {
    CHEF(0, "Chef"),
    LIEUTENANT(1, "Lieutenant"),
    MEMBRE(2, "Membre");

    private final int level;
    private final String label;

    GangRank(int level, String label) { this.level = level; this.label = label; }

    public int level() { return level; }
    public String label() { return label; }

    /** Vrai si ce grade est au moins aussi élevé que {@code other}. */
    public boolean atLeast(GangRank other) { return level <= other.level; }

    public static GangRank ofLevel(int level) {
        for (GangRank r : values()) if (r.level == level) return r;
        return MEMBRE;
    }

    /** Lit un grade saisi (français ou technique, accents et casse ignorés) ; null si inconnu. */
    public static GangRank parse(String s) {
        if (s == null) return null;
        String v = s.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "chef", "boss", "leader", "0" -> CHEF;
            case "lieutenant", "lieut", "lt", "bras-droit", "1" -> LIEUTENANT;
            case "membre", "member", "recrue", "2" -> MEMBRE;
            default -> null;
        };
    }
}
