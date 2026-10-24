package fr.minenorth.portes.api;

import fr.minenorth.portes.gang.GangAdmin;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * API de gestion des gangs pour le panneau MineNorth Admin, à appeler PAR RÉFLEXION : ne pas changer les noms ni les paramètres.
 * Thread serveur uniquement. Aucun contrôle de droit ici : l'appelant (panneau) vérifie que l'auteur est du staff ; les valeurs sont bornées et vérifiées par le mod.
 * Lectures : une ligne par élément, champs séparés par une tabulation (voir GangAdmin). Actions : « OK » ou « KO » + TAB + message.
 */
public final class GangAdminApi {
    private GangAdminApi() {}

    /** Un gang par ligne : id, nom, tag, couleur, membres, en ligne, solde (centimes), zones, fabriques, part des revenus (%). */
    public static List<String> gangs(MinecraftServer s) { return GangAdmin.gangs(s); }

    /** Détail d'un gang : lignes G (fiche), M (membres), T (zones), X (compte), F (fabriques). */
    public static List<String> detail(MinecraftServer s, String gangId) { return GangAdmin.detail(s, gangId); }

    /** Territoires : nom, dimension, x, y, z, id gang, tag, nom du gang, revenu (centimes), revenu propre (0/1), statut. */
    public static List<String> territories(MinecraftServer s) { return GangAdmin.territories(s); }

    /** Catalogue de la fabrique : numéro, libellé, objet, quantité, secondes, prix (centimes), besoins, active, limite par jour. */
    public static List<String> catalog(MinecraftServer s) { return GangAdmin.catalog(s); }

    /** Matières premières : libellé, identifiant, quantité par achat, prix (centimes). */
    public static List<String> materials(MinecraftServer s) { return GangAdmin.materials(s); }

    /** Réglages généraux : lignes « clé TAB valeur ». */
    public static List<String> settings(MinecraftServer s) { return GangAdmin.settings(s); }

    /** Action admin (voir GangAdmin.act) ; actor = l'administrateur réel (position, inventaire). */
    public static String act(MinecraftServer s, ServerPlayer actor, String action, String a, String b, long n) { return GangAdmin.act(s, actor, action, a, b, n); }
}
