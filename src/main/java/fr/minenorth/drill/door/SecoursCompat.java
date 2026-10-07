package fr.minenorth.portes.door;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/** Lien avec le mod MineNorth Secours, par réflexion : les portes POMPIER s'ouvrent pour les pompiers et le SAMU enregistrés. */
public final class SecoursCompat {
    private SecoursCompat() {}

    private static Method isSecours;

    public static boolean isSecours(ServerPlayer p) {
        if (!ModList.get().isLoaded("minenorthsecours")) return false;
        try {
            if (isSecours == null) isSecours = Class.forName("fr.minenorth.secours.api.SecoursApi").getMethod("isSecours", ServerPlayer.class);
            return Boolean.TRUE.equals(isSecours.invoke(null, p));
        } catch (Throwable t) {
            return false;
        }
    }
}
