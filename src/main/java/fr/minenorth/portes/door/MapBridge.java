package fr.minenorth.portes.door;

import net.minecraft.server.MinecraftServer;

/**
 * Lien avec le mod Carte, par réflexion : sans lui (ou s'il est ancien), rien ne se passe. Le mod Carte recalcule de toute façon
 * les points de locaux d'entreprise toutes les 30 secondes ; cet appel le fait seulement tout de suite après un achat ou une revente.
 */
public final class MapBridge {
    private MapBridge() {}

    public static void refresh(MinecraftServer s) {
        try {
            Class.forName("fr.minenorth.map.api.MapApi").getMethod("refreshBuildings", MinecraftServer.class).invoke(null, s);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // mod Carte absent ou ancien
        }
    }
}
