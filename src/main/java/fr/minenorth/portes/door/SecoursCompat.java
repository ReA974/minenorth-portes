package fr.minenorth.portes.door;

import fr.minenorth.api.MineNorth;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;

/** Pompiers et SAMU via MineNorth API (sans réflexion) : les portes POMPIER s'ouvrent pour eux. Faux si le mod Secours est absent. */
public final class SecoursCompat {
    private SecoursCompat() {}

    private static boolean tried;
    private static Method force;

    public static boolean isSecours(ServerPlayer p) { return MineNorth.secours().isSecours(p); }

    /**
     * Pompier qui clique une porte qu'il ne peut pas ouvrir, hache ou halligan en main : le mod Secours décide (en service, près
     * d'un incendie ou d'un blessé inconscient) et force la porte après quelques secondes. Vrai si le clic a été pris en charge.
     * Par réflexion : sans le mod Secours, renvoie toujours faux.
     */
    public static boolean tryForce(ServerPlayer p, BlockPos pos) {
        if (!tried) {
            tried = true;
            try {
                force = Class.forName("fr.minenorth.secours.pompier.PompierTools").getMethod("portesForce", ServerPlayer.class, BlockPos.class);
            } catch (Throwable t) {
                force = null;
            }
        }
        if (force == null) return false;
        try {
            return Boolean.TRUE.equals(force.invoke(null, p, pos));
        } catch (Throwable t) {
            return false;
        }
    }
}
