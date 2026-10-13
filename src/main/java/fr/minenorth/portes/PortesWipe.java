package fr.minenorth.portes;

import fr.minenorth.api.PlayerWipeEvent;
import fr.minenorth.portes.config.SystemeConfigFiles;
import fr.minenorth.portes.door.OwnerDoorData;
import fr.minenorth.portes.network.ModNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Suppression d'un joueur depuis le panneau admin : ses maisons achetées sont remises en vente à leur prix,
 * ses autres portes sont libérées, ses locations annulées et il est retiré des accès accordés.
 */
@Mod.EventBusSubscriber(modid = MineNorthPortes.MOD_ID)
public final class PortesWipe {
    private PortesWipe() {}

    @SubscribeEvent
    public static void onWipe(PlayerWipeEvent e) {
        MinecraftServer s = e.server();
        UUID id = e.player();
        OwnerDoorData d = OwnerDoorData.get(s.overworld());
        boolean any = false;
        Set<UUID> owners = new HashSet<>();
        for (Map.Entry<OwnerDoorData.DoorKey, OwnerDoorData.DoorEntry> en : d.allDoors()) {
            OwnerDoorData.DoorKey k = en.getKey();
            OwnerDoorData.DoorEntry door = en.getValue();
            ServerLevel level = s.getLevel(k.dimension());
            if (level == null) continue;
            if (door.owner() != null) owners.add(door.owner());
            if (id.equals(door.owner())) {
                if (door.purchasePrice() > 0) d.returnHouseToMarket(k, door.purchasePrice());
                else d.unassign(level, k.pos());
                any = true;
            }
            OwnerDoorData.DoorListing listing = d.listing(k);
            if (listing != null && id.equals(listing.renter())) {
                d.clearRental(level, k.pos());
                any = true;
            }
        }
        for (UUID t : new ArrayList<>(d.trusted(id))) any |= d.removeTrusted(id, t);
        for (UUID owner : owners) any |= d.removeTrusted(owner, id);
        if (!any) return;
        d.setDirty();
        SystemeConfigFiles.writeDoors(s, d);
        ModNetwork.syncDoorLocks(s);
        e.cleaned("portes");
    }
}
