package fr.minenorth.portes.config;

import fr.minenorth.portes.door.OwnerDoorData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.core.registries.Registries;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public final class SystemeConfigFiles {
    private static final Path ROOT = FMLPaths.CONFIGDIR.get().resolve("Minenorth-portes");
    private static final Path BANQUE = ROOT.resolve("banque.toml");
    private static final Path COFFRE = ROOT.resolve("coffre.toml");
    private static final Path PORTE = ROOT.resolve("porte.toml");
    private static final Path DOCUMENTS = ROOT.resolve("documents.toml");
    // Listings temporarily parsed from porte.toml while the file is reloaded.
    // They are applied to OwnerDoorData after all door entries have been restored.
    private static final java.util.Map<OwnerDoorData.DoorKey, OwnerDoorData.DoorListing> PENDING_LISTINGS = new java.util.LinkedHashMap<>();

    private SystemeConfigFiles() {}

    public static void init() {
        try {
            Files.createDirectories(ROOT);
            if (!Files.exists(BANQUE)) Files.writeString(BANQUE, "# Configuration du système bancaire MineNorth\n\n", StandardCharsets.UTF_8);
            if (!Files.exists(COFFRE)) Files.writeString(COFFRE, "[bank_vault]\n# Liste des objets pouvant apparaitre dans un coffre de banque.\n# Syntaxe : item_id|chance_en_pourcentage|min-max\n# La chance est comprise entre 0 et 100.\n# Chaque ligne est testée indépendamment.\nloot_entries = [\"minecraft:gold_ingot|75|2-8\", \"minecraft:golden_apple|55|4-16\", \"minecraft:emerald|40|1-5\", \"minecraft:diamond|20|1-3\", \"minecraft:gold_nugget|60|4-16\"]\n", StandardCharsets.UTF_8);
                        if (!Files.exists(DOCUMENTS)) Files.writeString(DOCUMENTS, "[identite]\nobligatoire = true\nnationalite_par_defaut = \"Française\"\nmodification_joueur = false\ndate_expiration = false\n\n[identite.creation]\ncommande = \"cidmenu <joueur>\"\n\n[identite.photo]\nsource = \"skin_minecraft\"\n\n[permissions]\nstaff = \"cid.staff\"\n", StandardCharsets.UTF_8);
            if (!Files.exists(PORTE)) Files.writeString(PORTE, "# Portes protégées MineNorth\n# Ce fichier est généré automatiquement.\n\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    public static synchronized void writeDoors(MinecraftServer server, OwnerDoorData data) {
        init();
        List<String> lines = new ArrayList<>();
        lines.add("# Portes protégées MineNorth");
        lines.add("# Généré automatiquement. Ne pas modifier pendant le serveur.");
        lines.add("");
        int i = 0;
        for (var entry : data.allDoors()) {
            var key = entry.getKey();
            var door = entry.getValue();
            lines.add("[[porte]]");
            lines.add("index = " + i++);
            lines.add("dimension = \"" + key.dimension().location() + "\"");
            lines.add("x = " + key.pos().getX());
            lines.add("y = " + key.pos().getY());
            lines.add("z = " + key.pos().getZ());
            lines.add("type = \"" + door.type().name().toLowerCase() + "\"");
            lines.add("proprietaire = \"" + escape(door.ownerName()) + "\"");
            lines.add("uuid = \"" + (door.owner() == null ? "" : door.owner()) + "\"");
            lines.add("temporaire = " + door.temporary());
            lines.add("permission = \"" + escape(door.permission()) + "\"");
            lines.add("maison = \"" + escape(door.houseName()) + "\"");
            var listing = data.listing(key);
            if (listing != null && listing.active()) {
                lines.add("vente_mode = \"" + listing.mode().name().toLowerCase() + "\"");
                lines.add("vente_prix = " + listing.price());
                lines.add("vente_jours = " + listing.days());
                lines.add("locataire_uuid = \"" + (listing.renter() == null ? "" : listing.renter()) + "\"");
                lines.add("location_expiration = " + listing.expiryMs());
            }
            lines.add("");
        }
        try { Files.write(PORTE, lines, StandardCharsets.UTF_8); } catch (IOException ignored) {}
    }

    public static synchronized boolean reloadDoors() {
        init();
        return Files.exists(PORTE);
    }



    public static int reloadDoors(MinecraftServer server) {
        init();
        List<java.util.Map.Entry<OwnerDoorData.DoorKey, OwnerDoorData.DoorEntry>> entries = new ArrayList<>();
        try {
            if (!Files.exists(PORTE)) return 0;
            PENDING_LISTINGS.clear();
            String dim = null, owner = "", uuid = "", type = "PERSONAL", permission = "", house = "", venteMode = "";
            String locataireUuid = ""; long ventePrix = 0, locationExpiration = 0; int venteJours = 0;
            int x = 0, y = 0, z = 0; boolean temporary = false; boolean active = false;
            for (String raw : Files.readAllLines(PORTE, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.equals("[[porte]]")) {
                    if (active) addParsed(entries, dim, x, y, z, owner, uuid, type, permission, house, temporary, venteMode, ventePrix, venteJours, locataireUuid, locationExpiration);
                    active = true; dim = null; owner = ""; uuid = ""; type = "PERSONAL"; permission = ""; house = ""; venteMode = ""; locataireUuid = ""; ventePrix = 0; venteJours = 0; locationExpiration = 0; x = y = z = 0; temporary = false;
                } else if (active && line.startsWith("dimension")) dim = quoted(line);
                else if (active && line.startsWith("proprietaire")) owner = quoted(line);
                else if (active && line.startsWith("uuid")) uuid = quoted(line);
                else if (active && line.startsWith("type")) type = quoted(line);
                else if (active && line.startsWith("permission")) permission = quoted(line);
                else if (active && line.startsWith("maison")) house = quoted(line);
                else if (active && line.startsWith("x")) x = integer(line);
                else if (active && line.startsWith("y")) y = integer(line);
                else if (active && line.startsWith("z")) z = integer(line);
                else if (active && line.startsWith("temporaire")) temporary = line.toLowerCase().endsWith("true");
                else if (active && line.startsWith("vente_mode")) venteMode = quoted(line);
                else if (active && line.startsWith("vente_prix")) ventePrix = longValue(line);
                else if (active && line.startsWith("vente_jours")) venteJours = integer(line);
                else if (active && line.startsWith("locataire_uuid")) locataireUuid = quoted(line);
                else if (active && line.startsWith("location_expiration")) locationExpiration = longValue(line);
            }
            if (active) addParsed(entries, dim, x, y, z, owner, uuid, type, permission, house, temporary, venteMode, ventePrix, venteJours, locataireUuid, locationExpiration);
        } catch (Exception ignored) { return -1; }
        OwnerDoorData data = OwnerDoorData.get(server.overworld());
        data.replaceDoors(entries);
        for (var e : PENDING_LISTINGS.entrySet()) {
            ServerLevel level = server.getLevel(e.getKey().dimension());
            if (level == null || data.get(level, e.getKey().pos()) == null) continue;
            var l = e.getValue();
            if (l.mode() == OwnerDoorData.ListingMode.SALE) data.setSale(level, e.getKey().pos(), l.price());
            else if (l.mode() == OwnerDoorData.ListingMode.RENTAL) {
                data.setRental(level, e.getKey().pos(), l.price(), l.days());
                if (l.renter() != null && l.expiryMs() > System.currentTimeMillis()) data.rent(level, e.getKey().pos(), l.renter(), l.expiryMs());
            }
        }
        writeDoors(server, data);
        return entries.size();
    }

    private static void addParsed(List<java.util.Map.Entry<OwnerDoorData.DoorKey, OwnerDoorData.DoorEntry>> out, String dim, int x, int y, int z, String owner, String uuid, String type, String permission, String house, boolean temporary, String venteMode, long ventePrix, int venteJours, String locataireUuid, long locationExpiration) {
        if (dim == null || dim.isBlank()) return;
        ResourceLocation rl = ResourceLocation.tryParse(dim);
        if (rl == null) return;
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, rl);
        java.util.UUID id = null;
        try { if (!uuid.isBlank()) id = java.util.UUID.fromString(uuid); } catch (Exception ignored) {}
        BlockPos pos = new BlockPos(x, y, z);
        OwnerDoorData.DoorEntry entry = new OwnerDoorData.DoorEntry(id, temporary, owner, OwnerDoorData.DoorType.from(type), permission, house);
        OwnerDoorData.DoorKey dk = new OwnerDoorData.DoorKey(key, pos);
        out.add(new java.util.AbstractMap.SimpleEntry<>(dk, entry));
        // Les informations de vente/location sont appliquées après le remplacement global des portes dans reloadDoors.
        if (!venteMode.isBlank() && ventePrix > 0) PENDING_LISTINGS.put(dk, new OwnerDoorData.DoorListing(OwnerDoorData.ListingMode.from(venteMode), ventePrix, venteJours, parseUuid(locataireUuid), locationExpiration, house));
    }
    private static String quoted(String line) { int a=line.indexOf('"'), b=line.lastIndexOf('"'); return a>=0 && b>a ? line.substring(a+1,b) : ""; }
    private static int integer(String line) { int a=line.indexOf('='); try { return Integer.parseInt(line.substring(a+1).trim()); } catch (Exception e) { return 0; } }
    private static long longValue(String line) { int a=line.indexOf('='); try { return Long.parseLong(line.substring(a+1).trim()); } catch (Exception e) { return 0L; } }
    private static UUID parseUuid(String s) { try { return UUID.fromString(s); } catch (Exception e) { return null; } }

    public static Set<String> readConfiguredPermissions() {
        init();
        Set<String> result = new LinkedHashSet<>();
        try {
            if (!Files.exists(PORTE)) return result;
            for (String line : Files.readAllLines(PORTE, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("permission")) continue;
                int first = trimmed.indexOf('"');
                int last = trimmed.lastIndexOf('"');
                if (first >= 0 && last > first) {
                    String value = trimmed.substring(first + 1, last).trim();
                    if (!value.isBlank()) result.add(value);
                }
            }
        } catch (IOException ignored) {}
        return result;
    }

    public static Path getRoot() { return ROOT; }
    private static String escape(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
