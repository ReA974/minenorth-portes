package fr.minenorth.portes.api;

import fr.minenorth.portes.agency.PropertyAdmin;
import net.minecraft.server.MinecraftServer;

import java.util.List;

/**
 * API de gestion des propriétés pour le panneau MineNorth Admin, à appeler PAR RÉFLEXION : ne pas changer les noms ni les paramètres.
 * Thread serveur uniquement. Aucun contrôle de droit ici : l'appelant vérifie que l'auteur est du staff. Voir {@link PropertyAdmin} pour les formats.
 */
public final class PropertyAdminApi {
    private PropertyAdminApi() {}

    /** Une ligne par propriété (champs séparés par une tabulation). */
    public static List<String> list(MinecraftServer s) { return PropertyAdmin.list(s); }

    /** La ligne d'une propriété, ou chaîne vide si elle n'existe plus. */
    public static String one(MinecraftServer s, String dimension, String houseName) { return PropertyAdmin.one(s, dimension, houseName); }

    /** Colocataires du titulaire du bien (uuid par ligne). */
    public static List<String> trusted(MinecraftServer s, String dimension, String houseName) { return PropertyAdmin.trusted(s, dimension, houseName); }

    /** Journal du bien, du plus récent au plus ancien : « date en ms » TAB « texte ». */
    public static List<String> log(MinecraftServer s, String dimension, String houseName) { return PropertyAdmin.log(s, dimension, houseName); }

    /** Action sur un bien ; renvoie « OK » ou « KO » + TAB + message. */
    public static String act(MinecraftServer s, String action, String dimension, String houseName, String playerUuid, String playerName,
                             long n, int days, String text, String actor) {
        return PropertyAdmin.act(s, action, dimension, houseName, playerUuid, playerName, n, days, text, actor);
    }
}
