package fr.minenorth.portes.agency;

import fr.minenorth.portes.MineNorthPortes;
import fr.minenorth.portes.door.OwnerDoorData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Repère « Mon bien » sur la carte (mod Carte, par réflexion) : chaque occupant d'un bien (propriétaire, locataire, colocataires, patron pour un
 * local d'entreprise) voit sa maison sur SA carte seulement, à la première porte. Icône « maison » pour un bien acheté, « location » (clé) pour un
 * bien loué. Recalculé toutes les 5 s et juste après un achat, une location, une revente ou une fin de bail : le repère disparaît avec le bien.
 */
@Mod.EventBusSubscriber(modid = MineNorthPortes.MOD_ID)
public final class HomeMarkers {
    private HomeMarkers() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final String OWNED = "Mon bien · ", RENTED = "Ma location · ";
    private static final int OWNED_COLOR = 0x8D6E63, RENTED_COLOR = 0x26A6C9;

    private record Marker(String dim, int x, int y, int z, int color, String icon) {}

    /** Repères déjà envoyés à chaque joueur (on ne renvoie que ce qui change, on retire ce qui ne vaut plus). */
    private static final Map<UUID, Map<String, Marker>> SENT = new HashMap<>();
    private static Method set, remove;
    private static boolean tried;
    private static int counter;
    private static boolean dirty = true;

    /** Occupation changée (achat, location, revente, fin de bail…) : recalcul au prochain tick. */
    public static void dirty() { dirty = true; }

    private static boolean map() {
        if (!tried) {
            tried = true;
            try {
                Class<?> c = Class.forName("fr.minenorth.map.api.MapApi");
                set = c.getMethod("setMarker", ServerPlayer.class, String.class, String.class, int.class, int.class, int.class, int.class, boolean.class, String.class);
                remove = c.getMethod("removeMarker", ServerPlayer.class, String.class);
            } catch (Throwable t) {
                set = null;
            }
        }
        return set != null;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (++counter < 100 && !dirty) return;
        counter = 0;
        dirty = false;
        try {
            update(e.getServer());
        } catch (RuntimeException ex) {
            LOG.warn("[Portes] Repères « Mon bien » non mis à jour : {}", ex.toString());
        }
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent e) {
        SENT.remove(e.getEntity().getUUID());
        dirty = true;
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent e) { SENT.remove(e.getEntity().getUUID()); }

    private static void update(MinecraftServer s) {
        if (!map()) return;
        OwnerDoorData d = OwnerDoorData.get(s.overworld());
        Map<UUID, Map<String, Marker>> want = new HashMap<>();
        for (AgencyService.Occupancy o : AgencyService.occupancies(s)) {
            if (o.holder() == null || o.occupants().isEmpty() || o.doors().isEmpty()) continue;
            AgencyService.House h = AgencyService.find(d, o.dimension(), o.houseName());
            OwnerDoorData.DoorListing l = h == null ? null : AgencyService.listing(d, h);
            boolean rented = l != null && l.mode() == OwnerDoorData.ListingMode.RENTAL && l.rentalActive();
            BlockPos p = o.doors().get(0);
            String name = (rented ? RENTED : OWNED) + o.houseName();
            Marker m = new Marker(o.dimension(), p.getX(), p.getY(), p.getZ(), rented ? RENTED_COLOR : OWNED_COLOR, rented ? "location" : "maison");
            for (UUID u : o.occupants()) want.computeIfAbsent(u, k -> new HashMap<>()).put(name, m);
        }
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            Map<String, Marker> now = want.getOrDefault(p.getUUID(), Map.of());
            Map<String, Marker> before = SENT.getOrDefault(p.getUUID(), Map.of());
            try {
                for (String old : before.keySet()) if (!now.containsKey(old)) remove.invoke(null, p, old);
                for (Map.Entry<String, Marker> en : now.entrySet()) {
                    Marker m = en.getValue();
                    if (m.equals(before.get(en.getKey()))) continue;
                    set.invoke(null, p, en.getKey(), m.dim(), m.x(), m.y(), m.z(), m.color(), false, m.icon());
                }
            } catch (ReflectiveOperationException | RuntimeException ex) {
                LOG.warn("[Portes] Repère « Mon bien » non envoyé : {}", ex.toString());
            }
            if (now.isEmpty()) SENT.remove(p.getUUID()); else SENT.put(p.getUUID(), new HashMap<>(now));
        }
    }
}
