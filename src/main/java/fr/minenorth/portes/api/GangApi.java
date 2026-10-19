package fr.minenorth.portes.api;

import fr.minenorth.portes.gang.Gang;
import fr.minenorth.portes.gang.GangData;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * API des gangs pour les autres mods MineNorth (tablette police, journal…), à appeler PAR RÉFLEXION : ne pas changer les noms ni les paramètres.
 * Thread serveur uniquement. Lecture seule.
 */
public final class GangApi {
    private GangApi() {}

    /** Gang du joueur au format « nom|tag|couleurRGB|grade » (grade : chef, lieutenant, membre), ou chaîne vide s'il n'est dans aucun gang. */
    public static String gangOf(MinecraftServer s, UUID player) {
        Gang g = GangData.get(s).gangOf(player);
        if (g == null) return "";
        return g.name() + "|" + g.tag() + "|" + g.color() + "|" + g.rankOf(player).label().toLowerCase(java.util.Locale.ROOT);
    }

    /** Vrai si les deux joueurs sont dans le même gang. */
    public static boolean sameGang(MinecraftServer s, UUID a, UUID b) {
        Gang g = GangData.get(s).gangOf(a);
        return g != null && g.isMember(b);
    }

    /** Tous les gangs, une ligne par gang : « nom|tag|couleurRGB|nombreDeMembres ». */
    public static List<String> gangs(MinecraftServer s) {
        List<String> out = new ArrayList<>();
        for (Gang g : GangData.get(s).gangs()) out.add(g.name() + "|" + g.tag() + "|" + g.color() + "|" + g.members().size());
        return out;
    }
}
