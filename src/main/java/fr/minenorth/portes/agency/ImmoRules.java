package fr.minenorth.portes.agency;

/** Règles pures de l'agence (sans Minecraft) : revente, limites, prolongation. */
public final class ImmoRules {
    private ImmoRules() {}

    public static final int RESALE_PERCENT = 65;
    public static final int MAX_HOUSES = 2;
    public static final int MAX_APARTMENTS = 2;
    public static final int MAX_BUSINESSES = 2;
    private static final long DAY_MS = 86_400_000L;

    /** Remboursement à la revente : 65 % du prix d'achat, arrondi au centime inférieur. */
    public static long resaleCents(long purchaseCents) { return purchaseCents * RESALE_PERCENT / 100; }

    public static int limit(PropertyCategory c) {
        return switch (c) {
            case HOUSE -> MAX_HOUSES;
            case APARTMENT -> MAX_APARTMENTS;
            case BUSINESS -> MAX_BUSINESSES;
        };
    }

    /** Vrai si le titulaire (joueur ou entreprise) peut encore acquérir un bien de cette catégorie. */
    public static boolean canAcquire(PropertyCategory c, int heldNow) { return heldNow < limit(c); }

    /** Nouvelle fin de location : les jours s'ajoutent à ce qui reste ; une location expirée repart de maintenant. */
    public static long extendedExpiryMs(long nowMs, long currentExpiryMs, int days) {
        return Math.max(nowMs, currentExpiryMs) + days * DAY_MS;
    }
}
