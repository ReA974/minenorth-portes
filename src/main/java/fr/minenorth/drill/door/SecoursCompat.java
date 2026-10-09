package fr.minenorth.portes.door;

import fr.minenorth.api.MineNorth;
import net.minecraft.server.level.ServerPlayer;

/** Pompiers et SAMU via MineNorth API (sans réflexion) : les portes POMPIER s'ouvrent pour eux. Faux si le mod Secours est absent. */
public final class SecoursCompat {
    private SecoursCompat() {}

    public static boolean isSecours(ServerPlayer p) { return MineNorth.secours().isSecours(p); }
}
