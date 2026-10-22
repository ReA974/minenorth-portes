package fr.minenorth.portes.gang;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

/** Sauvegarde serveur (monde principal) : gangs, territoires, délais après départ. Tout est indexé par UUID. */
public final class GangData extends SavedData {
    private static final String DATA_NAME = "minenorthportes_gangs";

    private final Map<UUID, Gang> gangs = new LinkedHashMap<>();
    /** joueur -> gang (index reconstruit au chargement, tenu à jour par les méthodes ci-dessous). */
    private final Map<UUID, UUID> memberOf = new HashMap<>();
    private final Map<String, Territory> territories = new LinkedHashMap<>();
    private final Map<String, Factory> factories = new LinkedHashMap<>();
    /** joueur -> instant (ms) de son dernier départ d'un gang. */
    private final Map<UUID, Long> leftAt = new HashMap<>();

    public static GangData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(GangData::load, GangData::new, DATA_NAME);
    }

    // ------------------------------------------------------------------ lecture

    public Collection<Gang> gangs() { return Collections.unmodifiableCollection(gangs.values()); }
    public Gang gang(UUID id) { return id == null ? null : gangs.get(id); }
    public Gang gangOf(UUID player) { UUID g = player == null ? null : memberOf.get(player); return g == null ? null : gangs.get(g); }

    public Gang byName(String name) {
        for (Gang g : gangs.values()) if (GangRules.sameName(g.name, name) || g.tag.equalsIgnoreCase(name == null ? "" : name.trim())) return g;
        return null;
    }

    public boolean nameOrTagTaken(String name, String tag) {
        for (Gang g : gangs.values()) if (g.name.equalsIgnoreCase(name) || g.tag.equalsIgnoreCase(tag)) return true;
        return false;
    }

    // ---- fabriques d'armes
    public Factory factory(String dim, long pos) { return factories.get(Factory.key(dim, pos)); }
    public Collection<Factory> factories() { return Collections.unmodifiableCollection(factories.values()); }

    public List<Factory> factoriesOf(UUID gang) {
        List<Factory> out = new ArrayList<>();
        for (Factory f : factories.values()) if (f.gang.equals(gang)) out.add(f);
        return out;
    }

    void addFactory(Factory f) { factories.put(f.key(), f); setDirty(); }
    void removeFactory(Factory f) { if (factories.remove(f.key()) != null) setDirty(); }

    public Collection<Territory> territories() { return Collections.unmodifiableCollection(territories.values()); }
    public Territory territory(String name) { return name == null ? null : territories.get(name.toLowerCase(Locale.ROOT)); }

    public long leftAt(UUID player) { return leftAt.getOrDefault(player, 0L); }

    // ------------------------------------------------------------------ écriture (réservée à GangService / TerritoryManager)

    void add(Gang g) {
        gangs.put(g.id, g);
        for (UUID m : g.members.keySet()) memberOf.put(m, g.id);
        setDirty();
    }

    void remove(Gang g) {
        gangs.remove(g.id);
        for (UUID m : g.members.keySet()) memberOf.remove(m, g.id);
        factories.values().removeIf(f -> f.gang.equals(g.id));
        for (Territory t : territories.values()) if (g.id.equals(t.holder)) t.holder = null;
        setDirty();
    }

    void join(Gang g, UUID player, GangRank rank) {
        g.members.put(player, rank);
        memberOf.put(player, g.id);
        setDirty();
    }

    void leave(Gang g, UUID player, boolean markLeft) {
        g.members.remove(player);
        memberOf.remove(player, g.id);
        if (markLeft) leftAt.put(player, System.currentTimeMillis());
        setDirty();
    }

    void addTerritory(Territory t) { territories.put(t.name.toLowerCase(Locale.ROOT), t); setDirty(); }
    boolean removeTerritory(String name) { boolean r = territories.remove(name.toLowerCase(Locale.ROOT)) != null; if (r) setDirty(); return r; }

    // ------------------------------------------------------------------ SavedData

    private static GangData load(CompoundTag tag) {
        GangData d = new GangData();
        ListTag gl = tag.getList("Gangs", Tag.TAG_COMPOUND);
        for (int i = 0; i < gl.size(); i++) {
            Gang g = Gang.load(gl.getCompound(i));
            if (g == null) continue;
            // Un joueur ne peut être que dans un gang : en cas de fichier abîmé, le premier gang chargé garde le joueur.
            g.members.keySet().removeIf(m -> d.memberOf.containsKey(m));
            if (g.members.isEmpty()) continue;
            if (g.chef() == null) g.members.put(g.members.keySet().iterator().next(), GangRank.CHEF);
            d.gangs.put(g.id, g);
            for (UUID m : g.members.keySet()) d.memberOf.put(m, g.id);
        }
        ListTag tl = tag.getList("Territories", Tag.TAG_COMPOUND);
        for (int i = 0; i < tl.size(); i++) {
            Territory t = Territory.load(tl.getCompound(i));
            if (t == null) continue;
            if (t.holder != null && !d.gangs.containsKey(t.holder)) t.holder = null;
            d.territories.put(t.name.toLowerCase(Locale.ROOT), t);
        }
        ListTag fl = tag.getList("Factories", Tag.TAG_COMPOUND);
        for (int i = 0; i < fl.size(); i++) {
            Factory f = Factory.load(fl.getCompound(i));
            if (f != null && d.gangs.containsKey(f.gang)) d.factories.put(f.key(), f);
        }
        ListTag ll = tag.getList("Left", Tag.TAG_COMPOUND);
        for (int i = 0; i < ll.size(); i++) {
            CompoundTag e = ll.getCompound(i);
            if (e.hasUUID("Uuid")) d.leftAt.put(e.getUUID("Uuid"), e.getLong("Time"));
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag gl = new ListTag();
        for (Gang g : gangs.values()) gl.add(g.save());
        tag.put("Gangs", gl);
        ListTag tl = new ListTag();
        for (Territory t : territories.values()) tl.add(t.save());
        tag.put("Territories", tl);
        ListTag fl = new ListTag();
        for (Factory f : factories.values()) fl.add(f.save());
        tag.put("Factories", fl);
        ListTag ll = new ListTag();
        long now = System.currentTimeMillis();
        for (var e : leftAt.entrySet()) {
            if (now - e.getValue() > 86_400_000L) continue; // plus d'un jour : inutile de le garder
            CompoundTag c = new CompoundTag();
            c.putUUID("Uuid", e.getKey());
            c.putLong("Time", e.getValue());
            ll.add(c);
        }
        tag.put("Left", ll);
        return tag;
    }
}
