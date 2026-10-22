package fr.minenorth.portes.gang;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Une recette de la Fabrique d'armes : libellé affiché, objet (« modid:objet » suivi si besoin de ses données NBT entre accolades,
 * avec des apostrophes pour les textes), quantité par lot, durée de fabrication et prix en centimes. Logique pure (sans Minecraft) : testée à part.
 * Ligne du fichier : « Libellé | objet | quantité | secondes | euros | besoins » (besoins facultatif : « acier:5;poudre:1 » = matières consommées, achetées avec l'argent du gang).
 */
public record FactoryRecipe(String label, String spec, int qty, int seconds, long cents, String needs) {
    public static final int LABEL_MAX = 32, SPEC_MAX = 220, QTY_MAX = 512, SECONDS_MIN = 5, SECONDS_MAX = 86_400;
    public static final long CENTS_MAX = 10_000_000L; // 100 000 €

    /** Besoins « id:quantité;id:quantité » -> table ordonnée ; null si le texte est invalide (identifiant, quantité 1 à 1000, 6 matières au plus). */
    public static java.util.LinkedHashMap<String, Integer> parseNeeds(String needs) {
        java.util.LinkedHashMap<String, Integer> out = new java.util.LinkedHashMap<>();
        String n = needs == null ? "" : needs.trim();
        if (n.isEmpty()) return out;
        for (String part : n.split(";", -1)) {
            String[] kv = part.trim().split(":");
            if (kv.length != 2 || !kv[0].trim().matches("[a-z0-9_]{1,16}")) return null;
            try {
                int q = Integer.parseInt(kv[1].trim());
                if (q < 1 || q > 1000 || out.size() >= 6) return null;
                out.merge(kv[0].trim(), q, Integer::sum);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    /** Erreur de saisie lisible, ou null si les champs sont acceptables (l'existence de l'objet est vérifiée à part, côté Minecraft). */
    public static String check(String label, String spec, long qty, long seconds, long cents, String needs) {
        if (parseNeeds(needs) == null) return "Besoins : « identifiant:quantité » séparés par des points-virgules (ex. acier:5;poudre:1), 6 matières au plus.";
        return check(label, spec, qty, seconds, cents);
    }

    /** Erreur de saisie lisible, ou null si les champs sont acceptables (l'existence de l'objet est vérifiée à part, côté Minecraft). */
    public static String check(String label, String spec, long qty, long seconds, long cents) {
        String l = label == null ? "" : label.trim();
        if (l.length() < 2 || l.length() > LABEL_MAX) return "Libellé : 2 à " + LABEL_MAX + " caractères.";
        if (l.matches(".*[|\"#\\p{Cntrl}].*")) return "Libellé : pas de barre verticale, de guillemet, de # ni de caractère spécial.";
        String s = spec == null ? "" : spec.trim();
        if (s.isEmpty() || s.length() > SPEC_MAX) return "Objet : 1 à " + SPEC_MAX + " caractères (ex. tacz:ammo{AmmoId:'tacz:9mm'}).";
        if (s.matches(".*[|\"#\\p{Cntrl}].*")) return "Objet : utilisez des apostrophes (') pour les textes, jamais de guillemets, de | ni de #.";
        if (!s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+(\\{.*})?")) return "Objet : « modid:objet » éventuellement suivi de {données}.";
        if (qty < 1 || qty > QTY_MAX) return "Quantité par lot : 1 à " + QTY_MAX + ".";
        if (seconds < SECONDS_MIN || seconds > SECONDS_MAX) return "Durée : " + SECONDS_MIN + " à " + SECONDS_MAX + " secondes.";
        if (cents < 0 || cents > CENTS_MAX) return "Prix : 0 à " + (CENTS_MAX / 100) + " €.";
        return null;
    }

    /** Lit une ligne « Libellé | objet | quantité | secondes | euros » ; null si elle est mal formée. */
    public static FactoryRecipe parse(String line) {
        if (line == null) return null;
        String[] p = line.split("\\|", -1);
        if (p.length != 5 && p.length != 6) return null;
        String needs = p.length == 6 ? p[5].trim() : "";
        try {
            long cents = Math.round(Double.parseDouble(p[4].trim().replace(',', '.')) * 100.0);
            long qty = Long.parseLong(p[2].trim()), sec = Long.parseLong(p[3].trim());
            if (check(p[0], p[1], qty, sec, cents, needs) != null) return null;
            return new FactoryRecipe(p[0].trim(), p[1].trim(), (int) qty, (int) sec, cents, needs);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Ligne telle qu'écrite dans le fichier de configuration. */
    public String format() {
        return label + " | " + spec + " | " + qty + " | " + seconds + " | " + eurosText(cents) + (needs.isEmpty() ? "" : " | " + needs);
    }

    public static String eurosText(long cents) {
        return cents % 100 == 0 ? Long.toString(cents / 100) : String.format(Locale.ROOT, "%d.%02d", cents / 100, cents % 100);
    }

    /** Recettes lues dans une liste de lignes ; les lignes illisibles sont ignorées. */
    public static List<FactoryRecipe> parseAll(List<String> lines) {
        List<FactoryRecipe> out = new ArrayList<>();
        for (String l : lines) {
            FactoryRecipe r = parse(l);
            if (r != null) out.add(r);
        }
        return out;
    }
}
