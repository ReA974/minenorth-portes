package fr.minenorth.portes.deco;

import fr.minenorth.portes.door.WorldGuardBridge;
import net.minecraft.server.MinecraftServer;

/**
 * Création d'une région WorldGuard cuboïde (terrain à bâtir) par l'API Java de WorldGuard 7, par réflexion à travers Bukkit (serveur Arclight) :
 * aucune dépendance de compilation. Sans WorldGuard, ou si l'appel échoue, renvoie un message qui donne les commandes à taper à la main.
 */
public final class WgRegions {
    private WgRegions() {}

    /** Renvoie null si la région existe (créée ou déjà là), sinon la raison de l'échec. */
    public static String createCuboid(MinecraftServer s, String dim, String id, int x1, int y1, int z1, int x2, int y2, int z2) {
        try {
            Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
            Object pm = bukkit.getMethod("getPluginManager").invoke(null);
            Object plugin = pm.getClass().getMethod("getPlugin", String.class).invoke(pm, "WorldGuard");
            if (plugin == null) return "plugin WorldGuard absent";
            ClassLoader cl = plugin.getClass().getClassLoader();
            Object world = bukkit.getMethod("getWorld", String.class).invoke(null, WorldGuardBridge.worldName(s, dim));
            if (world == null) return "monde Bukkit « " + WorldGuardBridge.worldName(s, dim) + " » introuvable";
            Class<?> wgC = Class.forName("com.sk89q.worldguard.WorldGuard", true, cl);
            Object wg = wgC.getMethod("getInstance").invoke(null);
            Object platform = wgC.getMethod("getPlatform").invoke(wg);
            Class<?> platC = Class.forName("com.sk89q.worldguard.internal.platform.WorldGuardPlatform", true, cl);
            Object container = platC.getMethod("getRegionContainer").invoke(platform);
            Class<?> rcC = Class.forName("com.sk89q.worldguard.protection.regions.RegionContainer", true, cl);
            Class<?> weWorldC = Class.forName("com.sk89q.worldedit.world.World", true, cl);
            Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", true, cl);
            Object weWorld = adapter.getMethod("adapt", Class.forName("org.bukkit.World", true, cl)).invoke(null, world);
            Object rm = rcC.getMethod("get", weWorldC).invoke(container, weWorld);
            if (rm == null) return "WorldGuard n'a pas de gestionnaire de régions pour ce monde";
            Class<?> rmC = Class.forName("com.sk89q.worldguard.protection.managers.RegionManager", true, cl);
            if (Boolean.TRUE.equals(rmC.getMethod("hasRegion", String.class).invoke(rm, id))) return null;
            Class<?> bv3 = Class.forName("com.sk89q.worldedit.math.BlockVector3", true, cl);
            Object min = bv3.getMethod("at", int.class, int.class, int.class).invoke(null, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2));
            Object max = bv3.getMethod("at", int.class, int.class, int.class).invoke(null, Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
            Class<?> cub = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion", true, cl);
            Object region = cub.getConstructor(String.class, bv3, bv3).newInstance(id, min, max);
            Class<?> prC = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion", true, cl);
            rmC.getMethod("addRegion", prC).invoke(rm, region);
            rmC.getMethod("saveChanges").invoke(rm);
            return null;
        } catch (ClassNotFoundException e) {
            return "Bukkit / WorldGuard absent";
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            return "erreur WorldGuard : " + c;
        }
    }

    /** Commandes à taper (en jeu, par un membre du staff) pour créer la région à la main. */
    public static String manual(String id, int x1, int y1, int z1, int x2, int y2, int z2) {
        return "//pos1 " + x1 + "," + y1 + "," + z1 + " puis //pos2 " + x2 + "," + y2 + "," + z2 + " puis /rg define " + id;
    }
}
