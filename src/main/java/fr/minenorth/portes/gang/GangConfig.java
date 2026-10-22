package fr.minenorth.portes.gang;

import fr.minenorth.portes.config.SystemeConfigFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        section("portes_max", "BASE ET PORTES");
        def("portes_max", "12", "Nombre maximum de portes qu'un gang peut posséder.");
        def("base_rayon", "32", "Distance en blocs autour de la base du gang (point défini par le chef) : une porte libre ne peut rejoindre le gang que dans ce rayon, ou dans une zone que le gang contrôle.");
        def("journal_max", "200", "Nombre de lignes gardées dans le journal du gang (portes, fabrique…).");
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
        def("revenu_destination", "gang", "Où va le revenu des zones : gang = compte du gang (solde propre, sert à acheter pour la fabrique), chef = compte bancaire personnel du chef (ancien fonctionnement).");
        section("fabrique_active", "FABRIQUE D'ARMES (avantage des zones contrôlées)");
        def("fabrique_active", "true", "true = les gangs peuvent poser une Fabrique d'armes dans une zone qu'ils contrôlent. false = désactivée (les fabriques posées s'arrêtent).");
        def("fabrique_max_par_gang", "1", "Nombre maximum de fabriques par gang (une seule par zone contrôlée dans tous les cas).");
        def("fabrique_stock_max", "10", "Nombre maximum de lots prêts + en fabrication dans une fabrique (plafond de stock).");
        def("fabrique_file_max", "5", "Nombre maximum de fabrications en attente dans la file.");
        def("fabrique_grade_recuperer", "lieutenant", "Grade minimum pour récupérer le stock : membre, lieutenant ou chef.");
        def("fabrique_matiere_max", "200", "Réserve maximum de chaque matière première dans une fabrique.");
    }

    /** Recettes de la fabrique (lignes « Libellé | objet | quantité | secondes | euros ») : modifiables en jeu, écrites en fin de fichier. */
    private static final List<String> RECIPES = new ArrayList<>(defaultRecipes());

    private static List<String> defaultRecipes() {
        return List.of(
                "Munitions 9 mm | tacz:ammo{AmmoId:'tacz:9mm'} | 30 | 90 | 0 | laiton:2;poudre:1",
                "Munitions .45 ACP | tacz:ammo{AmmoId:'tacz:45acp'} | 30 | 90 | 0 | laiton:2;poudre:2",
                "Pistolet Glock 17 | tacz:modern_kinetic_gun{GunId:'tacz:glock_17',GunCurrentAmmoCount:0} | 1 | 600 | 100 | acier:5");
    }

    /** Matières premières (lignes « Libellé | identifiant | quantité par achat | prix en euros ») : modifiables en jeu. */
    private static final List<String> MATERIALS = new ArrayList<>(defaultMaterials());

    private static List<String> defaultMaterials() {
        return List.of("Acier | acier | 10 | 60", "Laiton | laiton | 20 | 30", "Poudre | poudre | 20 | 40");
    }

    private static Path file() { return SystemeConfigFiles.getRoot().resolve("minenorth_gangs.toml"); }

    public static synchronized void load() {
        VALUES.clear();
        List<String> found = new ArrayList<>();
        List<String> foundMats = new ArrayList<>();
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            if (!Files.exists(f)) write(f);
            for (String raw : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String v = line.substring(eq + 1).trim();
                int hash = v.indexOf('#');
                if (hash >= 0) v = v.substring(0, hash).trim();
                String key = line.substring(0, eq).trim();
                if (key.startsWith("fabrique_recette_")) found.add(v.replace("\"", "").trim());
                else if (key.startsWith("fabrique_matiere_") && !key.equals("fabrique_matiere_max")) foundMats.add(v.replace("\"", "").trim());
                else VALUES.put(key, v.replace("\"", ""));
            }
            // Recettes : celles du fichier si elles sont lisibles, sinon (ancien fichier sans recettes) celles par défaut.
            List<String> ok = new ArrayList<>();
            for (String l : found) if (FactoryRecipe.parse(l) != null) ok.add(l);
            RECIPES.clear();
            RECIPES.addAll(found.isEmpty() ? defaultRecipes() : ok);
            MATERIALS.clear();
            List<String> okMats = new ArrayList<>();
            for (String l : foundMats) if (FactoryMaterial.parse(l) != null) okMats.add(l);
            MATERIALS.addAll(foundMats.isEmpty() ? defaultMaterials() : okMats);
        } catch (IOException ignored) {
            // valeurs par défaut
        }
    }

    /** Écrit le fichier complet (commentaires, sections) avec les valeurs courantes (ou celles par défaut si rien n'a été lu). */
    private static void write(Path f) throws IOException {
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("# ===== GANGS MINENORTH =====");
        lines.add("# Modifie seulement le nombre (ou true / false) après le « = ». Les prix sont en euros.");
        lines.add("# Après modification : /gang recharger (opérateur), pas besoin de redémarrer.");
        lines.add("# Le revenu des zones se règle aussi EN JEU : écran « Mon gang » > Admin > Revenus (par zone, et valeurs par défaut ci-dessous).");
        lines.add("");
        for (var e : DEFAULTS.entrySet()) {
            String sec = SECTIONS.get(e.getKey());
            if (sec != null) { lines.add("# ------------------------------------------------------------"); lines.add("# " + sec); lines.add("# ------------------------------------------------------------"); lines.add(""); }
            lines.add("# " + e.getValue()[1] + "  (défaut : " + e.getValue()[0] + ")");
            lines.add(e.getKey() + " = " + raw(e.getKey()));
            lines.add("");
        }
        lines.add("# ------------------------------------------------------------");
        lines.add("# FABRIQUE D'ARMES : RECETTES (une par ligne, modifiables aussi en jeu)");
        lines.add("# ------------------------------------------------------------");
        lines.add("# Format : \"Libellé | objet | quantité par lot | secondes | prix en euros\"");
        lines.add("# L'objet est « modid:objet » suivi si besoin de ses données entre accolades (apostrophes pour les textes), comme TACZ :");
        lines.add("#   armes     : tacz:modern_kinetic_gun{GunId:'tacz:glock_17',GunCurrentAmmoCount:0}");
        lines.add("#   munitions : tacz:ammo{AmmoId:'tacz:9mm'}");
        lines.add("# Le prix est prélevé sur le compte bancaire du chef quand on lance la fabrication.");
        lines.add("");
        int n = 1;
        for (String r : RECIPES) lines.add("fabrique_recette_" + n++ + " = \"" + r + "\"");
        lines.add("");
        lines.add("# Colonne facultative « besoins » : matières consommées au lancement, ex. acier:5;poudre:1 (identifiants des matières ci-dessous).");
        lines.add("");
        lines.add("# ------------------------------------------------------------");
        lines.add("# FABRIQUE D'ARMES : MATIÈRES PREMIÈRES (achetées avec l'argent du compte du gang, modifiables aussi en jeu)");
        lines.add("# ------------------------------------------------------------");
        lines.add("# Format : \"Libellé | identifiant | quantité reçue par achat | prix d'un achat en euros\"");
        lines.add("");
        int mi = 1;
        for (String m : MATERIALS) lines.add("fabrique_matiere_" + mi++ + " = \"" + m + "\"");
        lines.add("");
        Files.write(f, lines, StandardCharsets.UTF_8);
    }

    /** Change un réglage depuis le jeu (valeur déjà vérifiée par l'appelant) et le garde dans le fichier. */
    private static synchronized void set(String key, String value) {
        VALUES.put(key, value);
        try { write(file()); } catch (IOException ignored) { /* le réglage reste actif jusqu'au redémarrage */ }
    }

    public static final long INCOME_ZONE_MAX_EUROS = 10_000L, INCOME_CAP_MAX_EUROS = 100_000L;

    public static void setIncomePerZoneEuros(long euros) { set("revenu_par_zone_euros", Long.toString(Math.max(0, Math.min(INCOME_ZONE_MAX_EUROS, euros)))); }
    public static void setIncomeCapEuros(long euros) { set("revenu_plafond_heure_euros", Long.toString(Math.max(0, Math.min(INCOME_CAP_MAX_EUROS, euros)))); }
    public static void setIncomeIntervalMinutes(long minutes) { set("revenu_intervalle_minutes", Long.toString(Math.max(1, Math.min(1440, minutes)))); }
    public static void setIncomeNeedsOnline(boolean on) { set("revenu_necessite_en_ligne", Boolean.toString(on)); }

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
    public static int baseRadius() { return (int) num("base_rayon", 4, 128); }
    public static boolean lockpickGangDoors() { return flag("crochetage_portes_gang"); }
    public static int lockpickChance() { return (int) num("crochetage_chance_pourcent", 1, 100); }
    public static int logMax() { return (int) num("journal_max", 20, 1000); }
    public static int captureSeconds() { return (int) num("capture_secondes", 10, 3600); }
    public static int captureMinMembers() { return (int) num("capture_min_membres", 1, 20); }
    public static int captureDecay() { return (int) num("capture_recul_secondes", 0, 60); }
    public static long captureProtectionMs() { return num("capture_protection_minutes", 0, 1440) * 60_000L; }
    public static long incomeIntervalMs() { return num("revenu_intervalle_minutes", 1, 1440) * 60_000L; }
    public static long incomePerZone() { return num("revenu_par_zone_euros", 0, 100_000L) * 100L; }
    public static long incomeCapPerHour() { return num("revenu_plafond_heure_euros", 0, 1_000_000L) * 100L; }
    public static boolean incomeNeedsOnline() { return flag("revenu_necessite_en_ligne"); }
    public static boolean factoryEnabled() { return flag("fabrique_active"); }
    public static int factoryMaxPerGang() { return (int) num("fabrique_max_par_gang", 1, 10); }
    public static int factoryStockMax() { return (int) num("fabrique_stock_max", 1, 100); }
    public static int factoryQueueMax() { return (int) num("fabrique_file_max", 1, 20); }
    /** Grade minimum pour récupérer le stock : 0 chef, 1 lieutenant, 2 membre. */
    public static GangRank factoryCollectRank() { GangRank r = GangRank.parse(raw("fabrique_grade_recuperer")); return r == null ? GangRank.LIEUTENANT : r; }

    public static boolean incomeToGang() { return !raw("revenu_destination").equalsIgnoreCase("chef"); }
    public static void setIncomeToGang(boolean gang) { set("revenu_destination", gang ? "gang" : "chef"); }
    public static int materialCap() { return (int) num("fabrique_matiere_max", 1, 100_000); }
    public static void setMaterialCap(long n) { set("fabrique_matiere_max", Long.toString(Math.max(1, Math.min(100_000, n)))); }

    public static synchronized List<FactoryMaterial> materials() { return FactoryMaterial.parseAll(MATERIALS); }

    public static synchronized void setMaterials(List<FactoryMaterial> list) {
        MATERIALS.clear();
        for (FactoryMaterial m : list) MATERIALS.add(m.format());
        try { write(file()); } catch (IOException ignored) { /* reste actif jusqu'au redémarrage */ }
    }

    public static synchronized List<FactoryRecipe> recipes() { return FactoryRecipe.parseAll(RECIPES); }

    public static synchronized void setRecipes(List<FactoryRecipe> list) {
        RECIPES.clear();
        for (FactoryRecipe r : list) RECIPES.add(r.format());
        try { write(file()); } catch (IOException ignored) { /* reste actif jusqu'au redémarrage */ }
    }

    public static void setFactoryEnabled(boolean on) { set("fabrique_active", Boolean.toString(on)); }
    public static void setFactoryMaxPerGang(long n) { set("fabrique_max_par_gang", Long.toString(Math.max(1, Math.min(10, n)))); }
    public static void setFactoryStockMax(long n) { set("fabrique_stock_max", Long.toString(Math.max(1, Math.min(100, n)))); }
    public static void setFactoryQueueMax(long n) { set("fabrique_file_max", Long.toString(Math.max(1, Math.min(20, n)))); }
    public static void setFactoryCollectRank(GangRank r) { set("fabrique_grade_recuperer", r.label().toLowerCase(java.util.Locale.ROOT)); }

    public static long alertMs() { return num("territoire_alerte_secondes", 5, 600) * 1000L; }
}
