package fr.minenorth.portes.gang;

import fr.minenorth.portes.config.SystemeConfigFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Réglages des gangs : config/Minenorth-portes/minenorth_gangs.toml (créé au premier lancement, relu par /gang admin recharger).
 * Les montants sont en CENTIMES. Valeurs hors limites : ramenées dans une plage raisonnable.
 */
public final class GangConfig {
    private GangConfig() {}

    private static final Map<String, String[]> DEFAULTS = new LinkedHashMap<>();
    private static final Map<String, String> VALUES = new LinkedHashMap<>();

    private static void def(String key, String value, String comment) { DEFAULTS.put(key, new String[]{value, comment}); }

    static {
        def("creation_cout_centimes", "50000", "Prix de création d'un gang, prélevé sur la carte du créateur (0 = gratuit)");
        def("creation_min_joueurs_connectes", "0", "Nombre minimum de joueurs connectés sur le serveur pour créer un gang (0 = aucun)");
        def("membres_max", "30", "Nombre maximum de membres par gang");
        def("invitation_secondes", "120", "Durée de validité d'une invitation");
        def("delai_apres_depart_minutes", "5", "Attente avant de rejoindre ou créer un autre gang après en être parti (anti-aller-retour)");
        def("portes_max", "12", "Nombre maximum de portes par gang");
        def("coffres_max", "3", "Nombre maximum de blocs « Coffre de gang » par gang (ils ouvrent tous le même coffre commun)");
        def("base_rayon", "32", "Une porte libre ne peut rejoindre un gang que si un Coffre de gang du gang est à moins de ce rayon (blocs)");
        def("crochetage_portes_gang", "true", "Les portes de gang peuvent être crochetées par d'autres joueurs (le gang est prévenu)");
        def("crochetage_chance_pourcent", "35", "Chance de réussite du crochetage d'une porte de gang (si le mini-jeu est gagné)");
        def("journal_coffre_max", "200", "Nombre de lignes gardées dans le journal du coffre commun");
        def("capture_secondes", "120", "Temps à tenir une zone (secondes) pour la capturer");
        def("capture_min_membres", "2", "Membres du même gang à présenter dans la zone pour la capturer");
        def("capture_recul_secondes", "2", "Vitesse à laquelle la capture redescend quand le gang attaquant est parti ou trop peu nombreux");
        def("capture_protection_minutes", "10", "Après une prise, la zone ne peut pas être reprise pendant ce temps");
        def("revenu_intervalle_minutes", "10", "Un gang qui tient une zone touche son revenu à cet intervalle");
        def("revenu_par_zone_centimes", "2000", "Revenu versé par zone et par intervalle sur le compte bancaire du chef (0 = aucun revenu)");
        def("revenu_plafond_heure_centimes", "12000", "Plafond de revenu par gang et par heure (0 = pas de plafond)");
        def("revenu_necessite_en_ligne", "true", "Le revenu n'est versé que si au moins un membre du gang est connecté");
        def("territoire_alerte_secondes", "30", "Délai minimum entre deux messages « contesté / il vous manque des membres » pour un même joueur");
    }

    private static Path file() { return SystemeConfigFiles.getRoot().resolve("minenorth_gangs.toml"); }

    public static synchronized void load() {
        VALUES.clear();
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            if (!Files.exists(f)) {
                java.util.List<String> lines = new java.util.ArrayList<>();
                lines.add("# Gangs MineNorth. Montants en centimes. Rechargement : /gang admin recharger");
                lines.add("");
                for (var e : DEFAULTS.entrySet()) {
                    lines.add("# " + e.getValue()[1]);
                    String v = e.getValue()[0];
                    lines.add(e.getKey() + " = " + v);
                    lines.add("");
                }
                Files.write(f, lines, StandardCharsets.UTF_8);
            }
            for (String raw : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String v = line.substring(eq + 1).trim();
                int hash = v.indexOf('#');
                if (hash >= 0) v = v.substring(0, hash).trim();
                VALUES.put(line.substring(0, eq).trim(), v.replace("\"", ""));
            }
        } catch (IOException ignored) {
            // valeurs par défaut
        }
    }

    private static String raw(String key) {
        String v = VALUES.get(key);
        return v != null ? v : DEFAULTS.get(key)[0];
    }

    private static long num(String key, long min, long max) {
        long v;
        try { v = Long.parseLong(raw(key)); } catch (NumberFormatException e) { v = Long.parseLong(DEFAULTS.get(key)[0]); }
        return Math.max(min, Math.min(max, v));
    }

    private static boolean flag(String key) { return raw(key).equalsIgnoreCase("true"); }

    public static long creationCost() { return num("creation_cout_centimes", 0, 100_000_000L); }
    public static int creationMinOnline() { return (int) num("creation_min_joueurs_connectes", 0, 200); }
    public static int membersMax() { return (int) num("membres_max", 2, 200); }
    public static long inviteMs() { return num("invitation_secondes", 10, 3600) * 1000L; }
    public static long leaveDelayMs() { return num("delai_apres_depart_minutes", 0, 1440) * 60_000L; }
    public static int doorsMax() { return (int) num("portes_max", 1, 200); }
    public static int chestsMax() { return (int) num("coffres_max", 1, 20); }
    public static int baseRadius() { return (int) num("base_rayon", 4, 128); }
    public static boolean lockpickGangDoors() { return flag("crochetage_portes_gang"); }
    public static int lockpickChance() { return (int) num("crochetage_chance_pourcent", 1, 100); }
    public static int logMax() { return (int) num("journal_coffre_max", 20, 1000); }
    public static int captureSeconds() { return (int) num("capture_secondes", 10, 3600); }
    public static int captureMinMembers() { return (int) num("capture_min_membres", 1, 20); }
    public static int captureDecay() { return (int) num("capture_recul_secondes", 0, 60); }
    public static long captureProtectionMs() { return num("capture_protection_minutes", 0, 1440) * 60_000L; }
    public static long incomeIntervalMs() { return num("revenu_intervalle_minutes", 1, 1440) * 60_000L; }
    public static long incomePerZone() { return num("revenu_par_zone_centimes", 0, 10_000_000L); }
    public static long incomeCapPerHour() { return num("revenu_plafond_heure_centimes", 0, 100_000_000L); }
    public static boolean incomeNeedsOnline() { return flag("revenu_necessite_en_ligne"); }
    public static long alertMs() { return num("territoire_alerte_secondes", 5, 600) * 1000L; }
}
