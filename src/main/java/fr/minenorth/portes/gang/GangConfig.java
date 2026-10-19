package fr.minenorth.portes.gang;

import fr.minenorth.portes.config.SystemeConfigFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Réglages des gangs : config/Minenorth-portes/minenorth_gangs.toml (créé au premier lancement, relu par /gang recharger (OP)).
 * Les prix sont écrits en EUROS dans le fichier (convertis en centimes ici). Valeurs hors limites : ramenées dans une plage raisonnable.
 */
public final class GangConfig {
    private GangConfig() {}

    private static final Map<String, String[]> DEFAULTS = new LinkedHashMap<>();
    private static final Map<String, String> VALUES = new LinkedHashMap<>();

    private static void def(String key, String value, String comment) { DEFAULTS.put(key, new String[]{value, comment}); }

    private static final Map<String, String> SECTIONS = new LinkedHashMap<>();

    private static void section(String firstKey, String title) { SECTIONS.put(firstKey, title); }

    static {
        section("creation_prix_euros", "CRÉATION ET MEMBRES");
        def("creation_prix_euros", "500", "Prix pour créer un gang, en euros (0 = gratuit). Prélevé sur la carte du créateur.");
        def("creation_min_joueurs_connectes", "0", "Joueurs minimum connectés sur le serveur pour pouvoir créer un gang (0 = pas de minimum).");
        def("membres_max", "30", "Nombre maximum de membres par gang.");
        def("invitation_secondes", "120", "Combien de temps une invitation reste valable, en secondes.");
        def("delai_apres_depart_minutes", "5", "Minutes à attendre avant de rejoindre ou créer un autre gang après être parti d'un gang.");
        section("portes_max", "BASE, PORTES ET COFFRES");
        def("portes_max", "12", "Nombre maximum de portes qu'un gang peut posséder.");
        def("coffres_max", "3", "Nombre maximum de « Coffres de gang » par gang (ils ouvrent tous le même coffre commun).");
        def("base_rayon", "32", "Distance en blocs : une porte ne peut rejoindre un gang que si un Coffre de gang est à moins de cette distance.");
        def("journal_coffre_max", "200", "Nombre de lignes gardées dans l'historique du coffre commun.");
        section("crochetage_portes_gang", "CROCHETAGE DES PORTES DE GANG");
        def("crochetage_portes_gang", "true", "true = les autres joueurs peuvent crocheter les portes d'un gang (le gang est prévenu). false = impossible.");
        def("crochetage_chance_pourcent", "35", "Chance de réussite en % (1 à 100), une fois le mini-jeu gagné.");
        section("capture_secondes", "ZONES (TERRITOIRES) : CAPTURE");
        def("capture_secondes", "120", "Secondes à tenir la zone pour la capturer.");
        def("capture_min_membres", "2", "Nombre de membres du même gang à présenter dans la zone pour la capturer.");
        def("capture_recul_secondes", "2", "Vitesse à laquelle la capture redescend quand les attaquants partent (plus petit = plus lent).");
        def("capture_protection_minutes", "10", "Minutes pendant lesquelles une zone ne peut pas être reprise après une capture.");
        def("territoire_alerte_secondes", "30", "Secondes minimum entre deux messages d'alerte de zone pour un même joueur.");
        section("revenu_intervalle_minutes", "ZONES (TERRITOIRES) : REVENUS");
        def("revenu_intervalle_minutes", "10", "Toutes les X minutes, un gang qui tient une zone touche son revenu.");
        def("revenu_par_zone_euros", "20", "Revenu en euros par zone et par versement, sur le compte du chef (0 = aucun revenu).");
        def("revenu_plafond_heure_euros", "120", "Maximum en euros qu'un gang peut toucher par heure (0 = pas de plafond).");
        def("revenu_necessite_en_ligne", "true", "true = le revenu n'est versé que si au moins un membre du gang est connecté.");
    }

    private static Path file() { return SystemeConfigFiles.getRoot().resolve("minenorth_gangs.toml"); }

    public static synchronized void load() {
        VALUES.clear();
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            if (!Files.exists(f)) {
                java.util.List<String> lines = new java.util.ArrayList<>();
                lines.add("# ===== GANGS MINENORTH =====");
                lines.add("# Modifie seulement le nombre (ou true / false) après le « = ». Les prix sont en euros.");
                lines.add("# Après modification : /gang recharger (opérateur), pas besoin de redémarrer.");
                lines.add("");
                for (var e : DEFAULTS.entrySet()) {
                    String sec = SECTIONS.get(e.getKey());
                    if (sec != null) { lines.add("# ------------------------------------------------------------"); lines.add("# " + sec); lines.add("# ------------------------------------------------------------"); lines.add(""); }
                    lines.add("# " + e.getValue()[1] + "  (défaut : " + e.getValue()[0] + ")");
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

    public static long creationCost() { return num("creation_prix_euros", 0, 1_000_000L) * 100L; }
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
    public static long incomePerZone() { return num("revenu_par_zone_euros", 0, 100_000L) * 100L; }
    public static long incomeCapPerHour() { return num("revenu_plafond_heure_euros", 0, 1_000_000L) * 100L; }
    public static boolean incomeNeedsOnline() { return flag("revenu_necessite_en_ligne"); }
    public static long alertMs() { return num("territoire_alerte_secondes", 5, 600) * 1000L; }
}
