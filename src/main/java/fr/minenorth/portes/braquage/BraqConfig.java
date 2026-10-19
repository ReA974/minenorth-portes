package fr.minenorth.portes.braquage;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/** Réglages du braquage des supérettes et stations-service : config/minenorth_braquages.toml. */
public final class BraqConfig {
    private BraqConfig() {}

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ARMES;
    public static final ForgeConfigSpec.IntValue DUREE_SECONDES, DISTANCE_MAX, VIE_MINIMUM;
    public static final ForgeConfigSpec.IntValue MONTANT_MIN, MONTANT_MAX, PLAFOND_BRAQUAGE, PLAFOND_HEURE, PAR_JOUEUR_HEURE;
    public static final ForgeConfigSpec.IntValue COOLDOWN_MIN, COOLDOWN_ECHEC_MIN;
    public static final ForgeConfigSpec.IntValue POLICIERS_MIN, ALERTE_DELAI, ALERTE_DUREE, POLICE_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue SERVICE_SEULEMENT, OPS_IGNORENT, ANNONCE_GLOBALE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("arme");
        ARMES = b.comment("Objets qui comptent comme « arme » pour braquer (tenus en main principale).",
                        "Syntaxe : modid:objet, ou modid:* pour tous les objets d'un mod. Modifiable en jeu : /braquage arme ajouter (objet en main).")
                .defineListAllowEmpty("objets", List.of("tacz:modern_kinetic_gun"), o -> o instanceof String s && !s.isBlank());
        b.pop();

        b.push("deroulement");
        DUREE_SECONDES = b.comment("Durée du braquage : le vendeur remplit la caisse pendant ce temps (secondes).")
                .defineInRange("duree_secondes", 30, 5, 600);
        DISTANCE_MAX = b.comment("Le braqueur doit rester à moins de N blocs du vendeur, sinon le braquage échoue.")
                .defineInRange("distance_max_blocs", 6, 2, 32);
        VIE_MINIMUM = b.comment("Points de vie (1 cœur = 2) en dessous desquels le braqueur est considéré assommé : le braquage échoue.")
                .defineInRange("vie_minimum", 4, 0, 20);
        b.pop();

        b.push("butin");
        MONTANT_MIN = b.comment("Butin minimum (euros, en espèces).").defineInRange("montant_min", 200, 1, 1_000_000);
        MONTANT_MAX = b.comment("Butin maximum (euros, en espèces).").defineInRange("montant_max", 800, 1, 1_000_000);
        PLAFOND_BRAQUAGE = b.comment("Plafond absolu d'un braquage (euros).").defineInRange("plafond_par_braquage", 1500, 1, 1_000_000);
        PLAFOND_HEURE = b.comment("Total maximum distribué par tous les braquages du serveur sur une heure glissante (euros).")
                .defineInRange("plafond_par_heure_serveur", 6000, 1, 10_000_000);
        PAR_JOUEUR_HEURE = b.comment("Braquages réussis maximum par joueur sur une heure glissante.")
                .defineInRange("braquages_par_joueur_par_heure", 2, 1, 100);
        b.pop();

        b.push("cooldown");
        COOLDOWN_MIN = b.comment("Minutes avant de pouvoir braquer à nouveau le même magasin (après une réussite).")
                .defineInRange("apres_reussite_minutes", 30, 0, 100_000);
        COOLDOWN_ECHEC_MIN = b.comment("Minutes de verrouillage de la caisse après un braquage raté.")
                .defineInRange("apres_echec_minutes", 10, 0, 100_000);
        b.pop();

        b.push("police");
        POLICIERS_MIN = b.comment("Policiers minimum pour qu'un braquage soit possible (0 = aucun minimum).")
                .defineInRange("policiers_minimum", 1, 0, 100);
        SERVICE_SEULEMENT = b.comment("true = seuls les policiers en service comptent pour le minimum.").define("en_service_seulement", true);
        OPS_IGNORENT = b.comment("true = les OP ignorent le minimum de policiers (tests).").define("ops_ignorent_minimum", true);
        ALERTE_DELAI = b.comment("Délai (secondes) entre le début du braquage et l'alerte à la police (le vendeur « appelle »).")
                .defineInRange("alerte_delai_secondes", 10, 0, 600);
        ALERTE_DUREE = b.comment("Durée de l'alerte sur la tablette et la carte des policiers (minutes).")
                .defineInRange("alerte_duree_minutes", 10, 1, 120);
        POLICE_DISTANCE = b.comment("Un policier à moins de N blocs du vendeur fait fuir le braqueur : braquage raté, caisse verrouillée.")
                .defineInRange("distance_arrivee_police", 12, 3, 64);
        ANNONCE_GLOBALE = b.comment("true = le braquage est aussi annoncé à tout le serveur (la police reçoit toujours l'alerte détaillée).")
                .define("annonce_globale", false);
        b.pop();
        SPEC = b.build();
    }
}
