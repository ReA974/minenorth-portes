package fr.minenorth.portes.door;

import fr.minenorth.api.MineNorth;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Police via MineNorth API :
 * - les portes POLICE s'ouvrent pour les policiers enregistrés sur la tablette ;
 * - pendant une perquisition acceptée, les portes personnelles du citoyen s'ouvrent pour la police.
 */
public final class PoliceCompat {
    private PoliceCompat() {}

    public static boolean isPolice(ServerPlayer p) { return MineNorth.police().isPolice(p); }

    public static boolean canSearch(ServerPlayer officer, UUID owner) {
        return owner != null && MineNorth.police().canSearch(officer, owner);
    }
}
