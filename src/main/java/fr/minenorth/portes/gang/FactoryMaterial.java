package fr.minenorth.portes.gang;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Une matière première de la Fabrique d'armes, achetée avec l'argent du compte du gang : identifiant court (lettres minuscules, chiffres, _),
 * libellé, quantité reçue par achat et prix d'un achat en centimes. Logique pure (sans Minecraft), testée à part.
 * Ligne du fichier : « Libellé | identifiant | quantité par achat | prix en euros ».
 */
public record FactoryMaterial(String label, String id, int pack, long cents) {
    public static final int PACK_MAX = 1000;
    public static final long CENTS_MAX = 10_000_000L; // 100 000 € par achat

    public static String check(String label, String id, long pack, long cents) {
        String l = label == null ? "" : label.trim();
        if (l.length() < 2 || l.length() > 24) return "Matière : libellé de 2 à 24 caractères.";
        if (l.matches(".*[|\"#:;\\p{Cntrl}].*")) return "Matière : pas de | \" # : ; ni de caractère spécial dans le libellé.";
        String i = id == null ? "" : id.trim();
        if (!i.matches("[a-z0-9_]{1,16}")) return "Matière : identifiant de 1 à 16 caractères (a-z, 0-9, _).";
        if (pack < 1 || pack > PACK_MAX) return "Matière : quantité par achat de 1 à " + PACK_MAX + ".";
        if (cents < 0 || cents > CENTS_MAX) return "Matière : prix de 0 à " + (CENTS_MAX / 100) + " €.";
        return null;
    }

    public static FactoryMaterial parse(String line) {
        if (line == null) return null;
        String[] p = line.split("\\|", -1);
        if (p.length != 4) return null;
        try {
            long cents = Math.round(Double.parseDouble(p[3].trim().replace(',', '.')) * 100.0);
            long pack = Long.parseLong(p[2].trim());
            if (check(p[0], p[1], pack, cents) != null) return null;
            return new FactoryMaterial(p[0].trim(), p[1].trim(), (int) pack, cents);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String format() { return label + " | " + id + " | " + pack + " | " + FactoryRecipe.eurosText(cents); }

    public static List<FactoryMaterial> parseAll(List<String> lines) {
        List<FactoryMaterial> out = new ArrayList<>();
        for (String l : lines) {
            FactoryMaterial m = parse(l);
            if (m != null && out.stream().noneMatch(o -> o.id.equals(m.id))) out.add(m);
        }
        return out;
    }

    public String idLower() { return id.toLowerCase(Locale.ROOT); }
}
