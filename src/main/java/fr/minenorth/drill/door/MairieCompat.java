package fr.minenorth.portes.door;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/** Lien avec le mod MineNorth État, par réflexion : les portes MAIRIE s'ouvrent pour le maire et les agents municipaux. */
public final class MairieCompat {
    private MairieCompat() {}

    private static Method isStaff;

    public static boolean isStaff(ServerPlayer p) {
        if (!ModList.get().isLoaded("minenorthetat")) return false;
        try {
            if (isStaff == null) isStaff = Class.forName("fr.minenorth.etat.api.EtatApi").getMethod("isMairieStaff", ServerPlayer.class);
            return Boolean.TRUE.equals(isStaff.invoke(null, p));
        } catch (Throwable t) {
            return false;
        }
    }
}
