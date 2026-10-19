package fr.minenorth.portes.braquage;

import fr.minenorth.api.MineNorth;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/** Ponts par réflexion vers les autres mods : le braquage fonctionne même si l'un d'eux est absent. */
final class BraqCompat {
    private BraqCompat() {}

    private static Method shopOf, giveCash, raise;
    private static boolean shopTried, cashTried, raiseTried;

    /** Boutique liée à ce PNJ (minenorth_shops) : -1 = pas vendeur. Sans le mod boutiques, tout PNJ est accepté (0). */
    static int shopOf(Entity e) {
        if (!ModList.get().isLoaded("minenorth_shops")) return 0;
        try {
            if (!shopTried) {
                shopTried = true;
                shopOf = Class.forName("com.minenorth_shops.EntityLinks").getMethod("shopOf", Entity.class);
            }
            return shopOf == null ? 0 : (Integer) shopOf.invoke(null, e);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Remet l'argent en espèces (billets du mod eurobank). Sinon, repli : crédit du compte. Faux si rien n'a pu être versé. */
    static boolean payCash(ServerPlayer p, long cents) {
        if (cents <= 0) return false;
        try {
            if (!cashTried) {
                cashTried = true;
                if (ModList.get().isLoaded("minenorth_eurobank"))
                    giveCash = Class.forName("com.minenorth_eurobank.Money").getMethod("giveCash", Player.class, long.class);
            }
            if (giveCash != null) {
                giveCash.invoke(null, p, cents);
                return true;
            }
        } catch (Throwable t) {
            giveCash = null;
        }
        try {
            if (MineNorth.bank().hasAccount(p.server, p.getUUID())
                    && MineNorth.bank().adjust(p.server, p.getUUID(), cents, "Braquage (butin)", "braquage") >= 0) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /** Alerte police (chat, repère de carte, onglet ALERTES de la tablette). Repli : message aux policiers connectés. */
    static void alertPolice(MinecraftServer s, String label, String description, String dim, int x, int y, int z, int minutes) {
        try {
            if (!raiseTried) {
                raiseTried = true;
                if (ModList.get().isLoaded("minenorthpolice")) {
                    raise = Class.forName("fr.minenorth.police.RadarHack").getMethod("raise", MinecraftServer.class, String.class, String.class,
                            String.class, int.class, int.class, int.class, long.class);
                }
            }
            if (raise != null) {
                raise.invoke(null, s, label, description, dim, x, y, z, (long) minutes);
                return;
            }
        } catch (Throwable t) {
            raise = null;
        }
        Component c = Component.literal("§c§l[ALERTE] §r§c" + label + " en §f" + x + " " + y + " " + z + " §7— " + description);
        for (ServerPlayer cop : MineNorth.police().online(s)) cop.sendSystemMessage(c);
    }
}
