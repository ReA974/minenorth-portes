package fr.minenorth.portes.agency;

import java.text.Collator;
import java.util.Locale;

/** Ordre alphabétique des annonces : sans casse ni accents, et les nombres dans l'ordre naturel (« Villa 2 » avant « Villa 10 »). */
public final class NameOrder {
    private NameOrder() {}

    private static final Collator COLLATOR = Collator.getInstance(Locale.FRENCH);
    static { COLLATOR.setStrength(Collator.PRIMARY); }

    public static int compare(String a, String b) {
        String x = a == null ? "" : a.trim(), y = b == null ? "" : b.trim();
        int i = 0, j = 0;
        while (i < x.length() && j < y.length()) {
            boolean dx = Character.isDigit(x.charAt(i)), dy = Character.isDigit(y.charAt(j));
            if (dx && dy) {
                int ei = i, ej = j;
                while (ei < x.length() && Character.isDigit(x.charAt(ei))) ei++;
                while (ej < y.length() && Character.isDigit(y.charAt(ej))) ej++;
                String nx = x.substring(i, ei).replaceFirst("^0+(?=.)", ""), ny = y.substring(j, ej).replaceFirst("^0+(?=.)", "");
                if (nx.length() != ny.length()) return nx.length() - ny.length();
                int c = nx.compareTo(ny);
                if (c != 0) return c;
                i = ei; j = ej;
            } else {
                int ei = i, ej = j;
                while (ei < x.length() && !Character.isDigit(x.charAt(ei))) ei++;
                while (ej < y.length() && !Character.isDigit(y.charAt(ej))) ej++;
                int c = COLLATOR.compare(x.substring(i, ei), y.substring(j, ej));
                if (c != 0) return c;
                i = ei; j = ej;
            }
        }
        return (x.length() - i) - (y.length() - j);
    }
}
