package fr.minenorth.portes.door;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lien avec le mod MineNorth Entreprises, par réflexion : le mod Portes compile et fonctionne
 * même si le mod Entreprises est absent (dans ce cas, aucune entreprise n'est proposée).
 *
 * Une porte reliée à une entreprise stocke dans son champ « permission » : entreprise:<id>:<gradeMin>
 * gradeMin : -1 = patron uniquement, 0 = grade le plus haut, 99 = tous les employés.
 */
public final class EntrepriseCompat {
    private EntrepriseCompat() {}

    public static final String PREFIX = "entreprise:";
    private static final String API = "fr.minenorth.entreprises.api.EntrepriseApi";
    private static final Map<String, Method> METHODS = new HashMap<>();

    public static boolean loaded() { return ModList.get().isLoaded("minenorthentreprises"); }

    private static Object call(String name, Class<?>[] types, Object... args) {
        if (!loaded()) return null;
        try {
            Method m = METHODS.get(name);
            if (m == null) { m = Class.forName(API).getMethod(name, types); METHODS.put(name, m); }
            return m.invoke(null, args);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean linked(String permission) { return permission != null && permission.startsWith(PREFIX) && id(permission) > 0; }

    private static int part(String permission, int index, int fallback) {
        try { return Integer.parseInt(permission.split(":")[index].trim()); } catch (RuntimeException e) { return fallback; }
    }
    public static int id(String permission) { return permission == null || !permission.startsWith(PREFIX) ? -1 : part(permission, 1, -1); }
    public static int minGrade(String permission) { return part(permission, 2, 99); }
    public static String permission(int id, int minGrade) { return PREFIX + id + ":" + Math.max(-1, Math.min(99, minGrade)); }

    /** Entreprises actives, au format "id|nom". */
    @SuppressWarnings("unchecked")
    public static List<String> companies(MinecraftServer s) {
        Object r = call("listCompanies", new Class<?>[]{MinecraftServer.class}, s);
        return r instanceof List<?> l ? (List<String>) l : List.of();
    }
    @SuppressWarnings("unchecked")
    public static List<String> grades(MinecraftServer s, int id) {
        Object r = call("gradeNames", new Class<?>[]{MinecraftServer.class, int.class}, s, id);
        return r instanceof List<?> l ? (List<String>) l : List.of();
    }
    public static String name(MinecraftServer s, int id) {
        Object r = call("companyName", new Class<?>[]{MinecraftServer.class, int.class}, s, id);
        return r instanceof String v ? v : "";
    }
    public static boolean isOwner(ServerPlayer p, int id) {
        return Boolean.TRUE.equals(call("isOwner", new Class<?>[]{ServerPlayer.class, int.class}, p, id));
    }
    public static boolean canOpen(ServerPlayer p, int id, int minGrade) {
        return Boolean.TRUE.equals(call("canOpen", new Class<?>[]{ServerPlayer.class, int.class, int.class}, p, id, minGrade));
    }
}
