package fr.minenorth.portes.deco;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Terrains à bâtir créés par le staff : zone (= région WorldGuard), prix au m², hauteur et profondeur autorisées autour du sol, fichier de la
 * photo d'origine (remise à nu) et état « remise à nu en cours ». Sauvegardé avec le monde (minenorthportes_terrains).
 */
public class TerrainData extends SavedData {
    private static final String NAME = "minenorthportes_terrains";

    public static final class Land {
        public final String key, dimension, name, snapshot, createdBy;
        public final int minX, minY, minZ, maxX, maxY, maxZ, groundY, height, depth;
        public long pricePerM2;
        public final long createdMs;
        /** Remise à nu en cours : le terrain ne peut pas être acquis. */
        public boolean resetting;
        /** Joueur qui reçoit les objets trouvés pendant la remise à nu (ancien titulaire), ou vide. */
        public String resetFor = "";

        Land(String key, String dimension, String name, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int groundY, int height, int depth,
             long pricePerM2, String snapshot, String createdBy, long createdMs) {
            this.key = key; this.dimension = dimension; this.name = name;
            this.minX = minX; this.minY = minY; this.minZ = minZ; this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
            this.groundY = groundY; this.height = height; this.depth = depth; this.pricePerM2 = pricePerM2;
            this.snapshot = snapshot; this.createdBy = createdBy; this.createdMs = createdMs;
        }

        public RegionShape shape() { return RegionShape.box(minX, minY, minZ, maxX, maxY, maxZ); }

        public int area() { return (maxX - minX + 1) * (maxZ - minZ + 1); }
    }

    private final Map<String, Land> lands = new HashMap<>();

    public static TerrainData get(MinecraftServer s) {
        return s.overworld().getDataStorage().computeIfAbsent(TerrainData::load, TerrainData::new, NAME);
    }

    public Land land(String key) { return lands.get(key); }

    public List<Land> all() { return new ArrayList<>(lands.values()); }

    void put(Land l) { lands.put(l.key, l); setDirty(); }

    private static TerrainData load(CompoundTag tag) {
        TerrainData d = new TerrainData();
        for (Tag t : tag.getList("Lands", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            Land l = new Land(c.getString("Key"), c.getString("Dim"), c.getString("Name"), c.getInt("X1"), c.getInt("Y1"), c.getInt("Z1"),
                    c.getInt("X2"), c.getInt("Y2"), c.getInt("Z2"), c.getInt("Ground"), c.getInt("Height"), c.getInt("Depth"), c.getLong("PricePerM2"),
                    c.getString("Snapshot"), c.getString("By"), c.getLong("Created"));
            l.resetting = c.getBoolean("Resetting");
            l.resetFor = c.getString("ResetFor");
            d.lands.put(l.key, l);
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Land l : lands.values()) {
            CompoundTag c = new CompoundTag();
            c.putString("Key", l.key);
            c.putString("Dim", l.dimension);
            c.putString("Name", l.name);
            c.putInt("X1", l.minX); c.putInt("Y1", l.minY); c.putInt("Z1", l.minZ);
            c.putInt("X2", l.maxX); c.putInt("Y2", l.maxY); c.putInt("Z2", l.maxZ);
            c.putInt("Ground", l.groundY); c.putInt("Height", l.height); c.putInt("Depth", l.depth);
            c.putLong("PricePerM2", l.pricePerM2);
            c.putString("Snapshot", l.snapshot);
            c.putString("By", l.createdBy);
            c.putLong("Created", l.createdMs);
            c.putBoolean("Resetting", l.resetting);
            c.putString("ResetFor", l.resetFor);
            list.add(c);
        }
        tag.put("Lands", list);
        return tag;
    }
}
