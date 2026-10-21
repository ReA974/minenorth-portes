package fr.minenorth.portes.gang;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Règles pures des gangs (sans Minecraft) : validation des saisies, présence sur un territoire, revenu plafonné. Testées à part. */
public final class GangRules {
    private GangRules() {}

    public static final int NAME_MIN = 3, NAME_MAX = 20, TAG_MIN = 2, TAG_MAX = 4;

    /** Nom de gang propre : lettres, chiffres, espaces, tiret et apostrophe, 3 à 20 caractères ; null si invalide. */
    public static String cleanName(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").replaceAll("\\s+", " ").trim();
        if (s.length() < NAME_MIN || s.length() > NAME_MAX) return null;
        if (!s.matches("[\\p{L}\\p{N}][\\p{L}\\p{N} '\\-]*")) return null;
        return s;
    }

    /** Tag court en majuscules : 2 à 4 lettres/chiffres ; null si invalide. */
    public static String cleanTag(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.length() < TAG_MIN || s.length() > TAG_MAX || !s.matches("[A-Z0-9]+")) return null;
        return s;
    }

    /** Couleur RGB : nom français courant ou « #RRGGBB » ; -1 si inconnue. */
    public static int parseColor(String raw) {
        if (raw == null) return -1;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "rouge": return 0xE53935;
            case "bleu": return 0x1E88E5;
            case "vert": return 0x43A047;
            case "jaune": return 0xFDD835;
            case "orange": return 0xFB8C00;
            case "violet": return 0x8E24AA;
            case "rose": return 0xEC407A;
            case "cyan": return 0x00ACC1;
            case "blanc": return 0xF5F5F5;
            case "noir": return 0x212121;
            case "gris": return 0x757575;
            case "marron": return 0x6D4C41;
            default: break;
        }
        if (v.startsWith("#")) v = v.substring(1);
        if (v.length() == 6 && v.matches("[0-9a-f]{6}")) return Integer.parseInt(v, 16);
        return -1;
    }

    /** Couleur « §x » la plus proche pour le chat (16 couleurs vanilla). */
    public static char chatColor(int rgb) {
        int[] vals = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
                0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
        char[] codes = "0123456789abcdef".toCharArray();
        int best = 15; long bd = Long.MAX_VALUE;
        for (int i = 0; i < vals.length; i++) {
            int dr = ((rgb >> 16) & 255) - ((vals[i] >> 16) & 255), dg = ((rgb >> 8) & 255) - ((vals[i] >> 8) & 255), db = (rgb & 255) - (vals[i] & 255);
            long d = (long) dr * dr + (long) dg * dg + (long) db * db;
            if (d < bd) { bd = d; best = i; }
        }
        return codes[best];
    }

    /** Résultat de la présence sur un territoire à un instant donné. */
    public enum Presence { VIDE, UN_SEUL_GANG, CONTESTE }

    public static Presence presence(Map<UUID, Integer> countsByGang) {
        int gangs = 0;
        for (int c : countsByGang.values()) if (c > 0) gangs++;
        return gangs == 0 ? Presence.VIDE : gangs == 1 ? Presence.UN_SEUL_GANG : Presence.CONTESTE;
    }

    /**
     * Nouvelle progression de capture (en secondes) après une seconde.
     * attaquant = le gang qui tient le terrain seul ; defenseurPresent = le gang qui tient déjà la zone est dans la zone.
     * Pas de gain si trop peu de membres ; contestée (ou défendue) : la progression est gelée ; vide : elle redescend doucement.
     */
    public static int nextProgress(int progress, Presence presence, int attackerCount, int minPlayers, boolean holderIsAttacker, int decayPerSecond) {
        if (presence == Presence.CONTESTE) return progress;
        if (presence == Presence.VIDE) return Math.max(0, progress - decayPerSecond);
        if (holderIsAttacker) return Math.max(0, progress - decayPerSecond);
        if (attackerCount < Math.max(1, minPlayers)) return Math.max(0, progress - decayPerSecond);
        return progress + 1;
    }

    /** Montant à verser maintenant : jamais plus que ce qu'il reste sous le plafond de l'heure (plafond ≤ 0 : aucun plafond). */
    public static long cappedPayout(long wanted, long alreadyPaidThisHour, long capPerHour) {
        if (wanted <= 0) return 0;
        if (capPerHour <= 0) return wanted;
        return Math.max(0, Math.min(wanted, capPerHour - alreadyPaidThisHour));
    }

    /** Vrai si le point est dans le cuboïde (bornes incluses ; les deux coins peuvent être donnés dans n'importe quel ordre). */
    public static boolean inBox(int x, int y, int z, int x1, int y1, int z1, int x2, int y2, int z2) {
        return x >= Math.min(x1, x2) && x <= Math.max(x1, x2)
                && y >= Math.min(y1, y2) && y <= Math.max(y1, y2)
                && z >= Math.min(z1, z2) && z <= Math.max(z1, z2);
    }

    /**
     * Euros saisis -> centimes. -1 = « défaut » (ou vide), -2 = invalide (négatif, lettres, plus de 2 décimales, au-delà de maxCents).
     * Accepte la virgule ou le point comme séparateur.
     */
    public static long parseEuros(String s, long maxCents) {
        String t = s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replace(',', '.');
        if (t.isEmpty() || t.equals("defaut") || t.equals("défaut")) return -1;
        if (t.length() > 12 || !t.matches("\\d+(\\.\\d{1,2})?")) return -2;
        try {
            long cents = new java.math.BigDecimal(t).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
            return cents > maxCents ? -2 : cents;
        } catch (ArithmeticException | NumberFormatException e) {
            return -2;
        }
    }

    /** Un nom (pseudo ou nom RP) correspond-il à la saisie ? Casse et espaces autour ignorés. */
    public static boolean sameName(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    /** Ensemble de noms déjà pris, comparaison insensible à la casse. */
    public static boolean taken(Set<String> existingLowercase, String candidate) {
        return existingLowercase.contains(candidate.toLowerCase(Locale.ROOT));
    }
}
