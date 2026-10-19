package fr.minenorth.portes.agency;

import fr.minenorth.portes.door.EntrepriseCompat;
import fr.minenorth.portes.door.OwnerDoorData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Informations d'agence d'un bien (une maison = toutes les portes de même nom) : catégorie, meublé, description, images. */
public class PropertyInfoData extends SavedData {
    private static final String DATA_NAME = "minenorthportes_property_info";
    public static final int MAX_DESCRIPTION = 200;

    /** catalogue : "" = agence ; payeeCompany : entreprise qui encaisse les paiements du bien (0 = règle habituelle : propriétaire ou trésor). */
    public record PropertyInfo(PropertyCategory category, boolean furnished, String description, List<String> imageIds, String catalogue, int payeeCompany) {}

    private record Stored(PropertyCategory category, boolean furnished, String description, List<String> imageIds, String catalogue, int payeeCompany) {}

    private final Map<String, Stored> infos = new HashMap<>();

    public static PropertyInfoData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(PropertyInfoData::load, PropertyInfoData::new, DATA_NAME);
    }

    public static String key(String dimension, String houseName) {
        return dimension + "|" + (houseName == null ? "" : houseName.trim().toLowerCase(Locale.ROOT));
    }

    /** Fiche du bien. Sans fiche enregistrée : entreprise si une porte de la maison est reliée à une entreprise, sinon maison. */
    public PropertyInfo info(OwnerDoorData doors, String dimension, String houseName) {
        Stored s = infos.get(key(dimension, houseName));
        if (s != null) return new PropertyInfo(s.category, s.furnished, s.description, List.copyOf(s.imageIds), s.catalogue, s.payeeCompany);
        PropertyCategory fallback = PropertyCategory.HOUSE;
        String wanted = houseName == null ? "" : houseName.trim();
        for (var e : doors.allDoors()) {
            if (!e.getKey().dimension().location().toString().equals(dimension)) continue;
            if (!wanted.equalsIgnoreCase(e.getValue().houseName())) continue;
            if (EntrepriseCompat.linked(e.getValue().permission())) { fallback = PropertyCategory.BUSINESS; break; }
        }
        return new PropertyInfo(fallback, false, "", List.of(), "", 0);
    }

    public void set(String dimension, String houseName, PropertyCategory category, boolean furnished, String description, String catalogue, int payeeCompany) {
        String k = key(dimension, houseName);
        Stored old = infos.get(k);
        String d = description == null ? "" : description.trim();
        if (d.length() > MAX_DESCRIPTION) d = d.substring(0, MAX_DESCRIPTION);
        infos.put(k, new Stored(category, furnished, d, old == null ? new ArrayList<>() : old.imageIds, Catalogues.normalize(catalogue), Math.max(0, payeeCompany)));
        setDirty();
    }

    public void addImage(String dimension, String houseName, String imageId) {
        String k = key(dimension, houseName);
        Stored old = infos.get(k);
        if (old == null) {
            infos.put(k, new Stored(PropertyCategory.HOUSE, false, "", new ArrayList<>(List.of(imageId)), "", 0));
        } else {
            old.imageIds.add(imageId);
        }
        setDirty();
    }

    /** Retire les images de la fiche et renvoie leurs identifiants (les fichiers sont supprimés par l'appelant). */
    public List<String> clearImages(String dimension, String houseName) {
        Stored old = infos.get(key(dimension, houseName));
        if (old == null) return List.of();
        List<String> removed = new ArrayList<>(old.imageIds);
        old.imageIds.clear();
        setDirty();
        return removed;
    }

    private static PropertyInfoData load(CompoundTag tag) {
        PropertyInfoData d = new PropertyInfoData();
        ListTag list = tag.getList("Infos", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            List<String> images = new ArrayList<>();
            ListTag il = e.getList("Images", 8);
            for (int j = 0; j < il.size(); j++) images.add(il.getString(j));
            d.infos.put(e.getString("Key"), new Stored(PropertyCategory.from(e.getString("Category")), e.getBoolean("Furnished"), e.getString("Description"), images, e.getString("Catalogue"), e.getInt("PayeeCompany")));
        }
        return d;
    }

    @Override public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (var en : infos.entrySet()) {
            CompoundTag e = new CompoundTag();
            e.putString("Key", en.getKey());
            e.putString("Category", en.getValue().category.name());
            e.putBoolean("Furnished", en.getValue().furnished);
            e.putString("Description", en.getValue().description);
            e.putString("Catalogue", en.getValue().catalogue);
            e.putInt("PayeeCompany", en.getValue().payeeCompany);
            ListTag il = new ListTag();
            for (String id : en.getValue().imageIds) il.add(StringTag.valueOf(id));
            e.put("Images", il);
            list.add(e);
        }
        tag.put("Infos", list);
        return tag;
    }
}
