package fr.minenorth.portes.agency;

import fr.minenorth.portes.door.OwnerDoorData;
import fr.minenorth.portes.door.OwnerDoorManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Captures d'aperçu des biens : stockage sur le disque du serveur et réception depuis le client d'un admin.
 * Le client envoie exactement {@link #PHOTOS} photos, l'une après l'autre ; elles ne sont enregistrées sur l'annonce
 * (en remplaçant les anciennes) que lorsque la dernière est arrivée.
 */
public final class PropertyImages {
    private PropertyImages() {}

    public static final int PHOTOS = 3;
    public static final int MAX_PER_PROPERTY = PHOTOS;
    public static final int MAX_BYTES = 150_000;
    /** Taille maximale d'un morceau (limite d'un paquet vers le serveur : 32 767 octets). */
    public static final int CHUNK_BYTES = 30_000;
    private static final int MAX_CHUNKS = MAX_BYTES / CHUNK_BYTES + 1;
    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final class Session {
        final List<byte[]> done = new ArrayList<>();
        ByteArrayOutputStream current = new ByteArrayOutputStream();
        int nextChunk;
    }
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private static Path dir(MinecraftServer s) { return s.getWorldPath(LevelResource.ROOT).resolve("minenorth_immo").resolve("images"); }

    private static boolean looksLikeJpeg(byte[] b) { return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8; }

    public static boolean save(MinecraftServer s, String imageId, byte[] jpeg) {
        if (!ID.matcher(imageId).matches() || jpeg == null || jpeg.length > MAX_BYTES || !looksLikeJpeg(jpeg)) return false;
        try {
            Files.createDirectories(dir(s));
            Files.write(dir(s).resolve(imageId + ".jpg"), jpeg);
            return true;
        } catch (IOException e) { return false; }
    }

    /** Octets de l'image, ou null si absente. */
    public static byte[] load(MinecraftServer s, String imageId) {
        if (!ID.matcher(imageId).matches()) return null;
        try {
            Path p = dir(s).resolve(imageId + ".jpg");
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (IOException e) { return null; }
    }

    public static void delete(MinecraftServer s, String imageId) {
        if (!ID.matcher(imageId).matches()) return;
        try { Files.deleteIfExists(dir(s).resolve(imageId + ".jpg")); } catch (IOException ignored) {}
    }

    public static void forget(UUID player) { SESSIONS.remove(player); }

    /** Reçoit un morceau de la photo n° {@code photo} (0 à PHOTOS-1) d'un admin ; enregistre les photos quand la dernière est complète. */
    public static void receive(ServerPlayer p, int photo, int index, int total, byte[] chunk) {
        if (!p.hasPermissions(2)) return;
        if (photo < 0 || photo >= PHOTOS || index < 0 || total < 1 || total > MAX_CHUNKS || index >= total || chunk == null || chunk.length > CHUNK_BYTES) { SESSIONS.remove(p.getUUID()); return; }
        Session s = SESSIONS.get(p.getUUID());
        if (index == 0) {
            if (photo == 0) { s = new Session(); SESSIONS.put(p.getUUID(), s); }
            if (s == null || s.done.size() != photo) { SESSIONS.remove(p.getUUID()); return; }
            s.current = new ByteArrayOutputStream();
            s.nextChunk = 0;
        }
        if (s == null || s.done.size() != photo || s.nextChunk != index || s.current.size() + chunk.length > MAX_BYTES) { SESSIONS.remove(p.getUUID()); return; }
        s.current.write(chunk, 0, chunk.length);
        s.nextChunk++;
        if (index != total - 1) return;
        byte[] bytes = s.current.toByteArray();
        if (!looksLikeJpeg(bytes)) { SESSIONS.remove(p.getUUID()); p.displayClientMessage(Component.literal("§cPhoto refusée (format invalide)."), true); return; }
        s.done.add(bytes);
        if (s.done.size() < PHOTOS) return;
        SESSIONS.remove(p.getUUID());
        commit(p, s.done);
    }

    private static void commit(ServerPlayer p, List<byte[]> photos) {
        OwnerDoorData.DoorKey key = OwnerDoorManager.contextKey(p);
        if (key == null) return;
        var level = p.server.getLevel(key.dimension());
        if (level == null) return;
        OwnerDoorData doors = OwnerDoorData.get(level);
        OwnerDoorData.DoorEntry entry = doors.entry(key);
        if (entry == null || entry.houseName().isBlank()) { p.displayClientMessage(Component.literal("§cDonnez d'abord un nom de maison à cette porte."), true); return; }
        String dim = key.dimension().location().toString();
        PropertyInfoData infos = PropertyInfoData.get(level);
        List<String> ids = new ArrayList<>();
        for (byte[] jpeg : photos) {
            String id = UUID.randomUUID().toString();
            if (!save(p.server, id, jpeg)) {
                for (String done : ids) delete(p.server, done);
                p.displayClientMessage(Component.literal("§cPhotos refusées (taille ou format invalide) : les anciennes sont conservées."), true);
                return;
            }
            ids.add(id);
        }
        clear(p.server, infos, dim, entry.houseName());
        for (String id : ids) infos.addImage(dim, entry.houseName(), id);
        p.displayClientMessage(Component.literal("§a" + PHOTOS + " photos enregistrées sur l'annonce « " + entry.houseName() + " »."), true);
    }

    /** Efface les captures d'un bien (fiches et fichiers). */
    public static void clear(MinecraftServer s, PropertyInfoData infos, String dim, String houseName) {
        for (String id : infos.clearImages(dim, houseName)) delete(s, id);
    }
}
