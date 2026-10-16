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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Captures d'aperçu des biens : stockage sur le disque du serveur et réception par morceaux depuis le client d'un admin. */
public final class PropertyImages {
    private PropertyImages() {}

    public static final int MAX_PER_PROPERTY = 4;
    public static final int MAX_BYTES = 150_000;
    /** Taille maximale d'un morceau (limite d'un paquet vers le serveur : 32 767 octets). */
    public static final int CHUNK_BYTES = 30_000;
    private static final int MAX_CHUNKS = MAX_BYTES / CHUNK_BYTES + 1;
    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final class Upload { final ByteArrayOutputStream data = new ByteArrayOutputStream(); int next; }
    private static final Map<UUID, Upload> UPLOADS = new HashMap<>();

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

    public static void forget(UUID player) { UPLOADS.remove(player); }

    /** Reçoit un morceau de capture d'un admin pour la maison de la porte qu'il vient d'ouvrir. */
    public static void receive(ServerPlayer p, int index, int total, byte[] chunk) {
        if (!p.hasPermissions(2)) return;
        if (index < 0 || total < 1 || total > MAX_CHUNKS || index >= total || chunk == null || chunk.length > CHUNK_BYTES) { UPLOADS.remove(p.getUUID()); return; }
        Upload up = index == 0 ? new Upload() : UPLOADS.get(p.getUUID());
        if (up == null || up.next != index || up.data.size() + chunk.length > MAX_BYTES) { UPLOADS.remove(p.getUUID()); return; }
        up.data.write(chunk, 0, chunk.length);
        up.next++;
        UPLOADS.put(p.getUUID(), up);
        if (index != total - 1) return;
        UPLOADS.remove(p.getUUID());
        OwnerDoorData.DoorKey key = OwnerDoorManager.contextKey(p);
        if (key == null) return;
        var level = p.server.getLevel(key.dimension());
        if (level == null) return;
        OwnerDoorData doors = OwnerDoorData.get(level);
        OwnerDoorData.DoorEntry entry = doors.entry(key);
        if (entry == null || entry.houseName().isBlank()) { p.displayClientMessage(Component.literal("§cDonnez d'abord un nom de maison à cette porte."), true); return; }
        String dim = key.dimension().location().toString();
        PropertyInfoData infos = PropertyInfoData.get(level);
        if (infos.info(doors, dim, entry.houseName()).imageIds().size() >= MAX_PER_PROPERTY) {
            p.displayClientMessage(Component.literal("§cMaximum " + MAX_PER_PROPERTY + " captures par bien : effacez-les d'abord."), true);
            return;
        }
        String id = UUID.randomUUID().toString();
        if (!save(p.server, id, up.data.toByteArray())) { p.displayClientMessage(Component.literal("§cCapture refusée (taille ou format invalide)."), true); return; }
        infos.addImage(dim, entry.houseName(), id);
        p.displayClientMessage(Component.literal("§aCapture enregistrée pour « " + entry.houseName() + " »."), true);
    }

    /** Efface les captures d'un bien (fiches et fichiers). */
    public static void clear(MinecraftServer s, PropertyInfoData infos, String dim, String houseName) {
        for (String id : infos.clearImages(dim, houseName)) delete(s, id);
    }
}
