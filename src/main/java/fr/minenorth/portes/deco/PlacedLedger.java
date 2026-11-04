package fr.minenorth.portes.deco;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Registre des blocs posés par les occupants dans leur bien (maison, appartement, terrain…) : position, bloc, objet exact utilisé (NBT compris)
 * et joueur qui l'a posé. Seuls ces blocs peuvent être rangés au garde-meuble ou déménagés : la maison elle-même (murs, meubles du staff
 * d'un bien meublé, blocs posés avant la mise en place du registre) n'en sort jamais. Un bloc cassé, soufflé ou poussé par un piston sort du
 * registre ; au déménagement, un bloc qui n'est plus celui enregistré est ignoré (aucune duplication possible).
 * Sauvegardé avec le monde (minenorthportes_deco_ledger). Thread serveur uniquement.
 */
public class PlacedLedger extends SavedData {
    private static final String NAME = "minenorthportes_deco_ledger";

    /** Autre position d'un bloc en plusieurs parties (lit, porte, meuble sur plusieurs blocs). */
    public record Part(long pos, String block) {}

    public record Placed(String block, ItemStack item, UUID placer, long time, List<Part> parts) {}

    /** Entrée trouvée : bien, position principale, données. */
    public record Found(String house, long main, Placed placed) {}

    private final Map<String, Map<Long, Placed>> byHouse = new HashMap<>();
    /** dimension -> position (principale ou partie) -> position principale */
    private final Map<String, Map<Long, Long>> toMain = new HashMap<>();
    /** dimension -> position principale -> bien */
    private final Map<String, Map<Long, String>> mainHouse = new HashMap<>();

    public static PlacedLedger get(MinecraftServer s) {
        return s.overworld().getDataStorage().computeIfAbsent(PlacedLedger::load, PlacedLedger::new, NAME);
    }

    static String dimOf(String houseKey) { return houseKey.substring(0, houseKey.indexOf('|')); }

    public void record(String house, long main, Placed p) {
        String dim = dimOf(house);
        forget(dim, main);
        for (Part part : p.parts()) forget(dim, part.pos());
        byHouse.computeIfAbsent(house, k -> new LinkedHashMap<>()).put(main, p);
        Map<Long, Long> idx = toMain.computeIfAbsent(dim, k -> new HashMap<>());
        idx.put(main, main);
        for (Part part : p.parts()) idx.put(part.pos(), main);
        mainHouse.computeIfAbsent(dim, k -> new HashMap<>()).put(main, house);
        setDirty();
    }

    public Found find(String dim, long pos) {
        Map<Long, Long> idx = toMain.get(dim);
        Long main = idx == null ? null : idx.get(pos);
        if (main == null) return null;
        String house = mainHouse.get(dim).get(main);
        Map<Long, Placed> m = house == null ? null : byHouse.get(house);
        Placed p = m == null ? null : m.get(main);
        return p == null ? null : new Found(house, main, p);
    }

    /** Retire l'entrée qui contient cette position (principale ou partie). */
    public Found forget(String dim, long pos) {
        Found f = find(dim, pos);
        if (f == null) return null;
        Map<Long, Placed> m = byHouse.get(f.house());
        m.remove(f.main());
        if (m.isEmpty()) byHouse.remove(f.house());
        Map<Long, Long> idx = toMain.get(dim);
        idx.remove(f.main());
        for (Part part : f.placed().parts()) idx.remove(part.pos());
        mainHouse.get(dim).remove(f.main());
        setDirty();
        return f;
    }

    /** Copie des entrées d'un bien (position principale -> données). */
    public Map<Long, Placed> entries(String house) {
        Map<Long, Placed> m = byHouse.get(house);
        return m == null ? Map.of() : new LinkedHashMap<>(m);
    }

    public int count(String house) {
        Map<Long, Placed> m = byHouse.get(house);
        return m == null ? 0 : m.size();
    }

    /** Biens qui ont au moins un bloc enregistré. */
    public List<String> houses() { return new ArrayList<>(byHouse.keySet()); }

    // ------------------------------------------------------------------ sauvegarde

    private static PlacedLedger load(CompoundTag tag) {
        PlacedLedger l = new PlacedLedger();
        for (Tag t : tag.getList("Entries", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            List<Part> parts = new ArrayList<>();
            for (Tag pt : c.getList("Parts", Tag.TAG_COMPOUND)) parts.add(new Part(((CompoundTag) pt).getLong("P"), ((CompoundTag) pt).getString("B")));
            ItemStack item = ItemStack.of(c.getCompound("I"));
            UUID placer = c.hasUUID("U") ? c.getUUID("U") : null;
            if (placer == null || c.getString("H").indexOf('|') < 0) continue;
            l.record(c.getString("H"), c.getLong("P"), new Placed(c.getString("B"), item, placer, c.getLong("T"), List.copyOf(parts)));
        }
        return l;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        byHouse.forEach((house, m) -> m.forEach((pos, p) -> {
            CompoundTag c = new CompoundTag();
            c.putString("H", house);
            c.putLong("P", pos);
            c.putString("B", p.block());
            c.put("I", p.item().save(new CompoundTag()));
            c.putUUID("U", p.placer());
            c.putLong("T", p.time());
            ListTag parts = new ListTag();
            for (Part part : p.parts()) {
                CompoundTag pc = new CompoundTag();
                pc.putLong("P", part.pos());
                pc.putString("B", part.block());
                parts.add(pc);
            }
            c.put("Parts", parts);
            list.add(c);
        }));
        tag.put("Entries", list);
        return tag;
    }
}
