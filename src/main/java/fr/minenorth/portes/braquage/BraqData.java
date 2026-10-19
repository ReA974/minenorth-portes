package fr.minenorth.portes.braquage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Magasins braquables, cooldowns et registre des butins versés (plafond par heure). Tout est côté serveur :
 * le client ne reçoit ni ne fournit rien. Heures en horloge réelle (le cooldown survit aux redémarrages).
 */
public final class BraqData extends SavedData {
    private static final String NAME = "minenorth_braquages";
    private static final long HOUR = 3_600_000L;

    public static final class Store {
        public String name;
        public boolean station;
        public UUID entity;
        public String dim;
        public int x, y, z;
        public long lockedUntil;

        public String typeLabel() { return station ? "station-service" : "supérette"; }
    }

    private record Payout(long time, long cents, UUID player) {}

    private final Map<String, Store> stores = new LinkedHashMap<>();
    private final List<Payout> ledger = new ArrayList<>();

    public static BraqData get(MinecraftServer s) {
        return s.overworld().getDataStorage().computeIfAbsent(BraqData::load, BraqData::new, NAME);
    }

    public static String key(String name) { return name.trim().toLowerCase(Locale.ROOT); }

    public Store byName(String name) { return stores.get(key(name)); }

    public Store byEntity(UUID id) {
        for (Store st : stores.values()) if (st.entity.equals(id)) return st;
        return null;
    }

    public List<Store> all() { return new ArrayList<>(stores.values()); }

    public boolean add(Store st) {
        if (stores.containsKey(key(st.name)) || byEntity(st.entity) != null) return false;
        stores.put(key(st.name), st);
        setDirty();
        return true;
    }

    public boolean remove(String name) {
        boolean r = stores.remove(key(name)) != null;
        if (r) setDirty();
        return r;
    }

    public void lock(Store st, long minutes) {
        st.lockedUntil = minutes <= 0 ? 0 : System.currentTimeMillis() + minutes * 60_000L;
        setDirty();
    }

    public long lockLeftMinutes(Store st) {
        long left = st.lockedUntil - System.currentTimeMillis();
        return left <= 0 ? 0 : (left + 59_999L) / 60_000L;
    }

    private void prune() {
        long limit = System.currentTimeMillis() - HOUR;
        ledger.removeIf(p -> p.time < limit);
    }

    public long paidLastHour() {
        prune();
        long t = 0;
        for (Payout p : ledger) t += p.cents;
        return t;
    }

    public int countLastHour(UUID player) {
        prune();
        int n = 0;
        for (Payout p : ledger) if (p.player.equals(player)) n++;
        return n;
    }

    public void record(UUID player, long cents) {
        prune();
        ledger.add(new Payout(System.currentTimeMillis(), cents, player));
        setDirty();
    }

    public void forget(UUID player) {
        if (ledger.removeIf(p -> p.player.equals(player))) setDirty();
    }

    // ------------------------------------------------------------------ persistance

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag ls = new ListTag();
        for (Store st : stores.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("Name", st.name);
            t.putBoolean("Station", st.station);
            t.putUUID("Entity", st.entity);
            t.putString("Dim", st.dim);
            t.putInt("X", st.x); t.putInt("Y", st.y); t.putInt("Z", st.z);
            t.putLong("Until", st.lockedUntil);
            ls.add(t);
        }
        tag.put("Stores", ls);
        prune();
        ListTag lg = new ListTag();
        for (Payout p : ledger) {
            CompoundTag t = new CompoundTag();
            t.putLong("T", p.time); t.putLong("C", p.cents); t.putUUID("P", p.player);
            lg.add(t);
        }
        tag.put("Ledger", lg);
        return tag;
    }

    public static BraqData load(CompoundTag tag) {
        BraqData d = new BraqData();
        ListTag ls = tag.getList("Stores", Tag.TAG_COMPOUND);
        for (int i = 0; i < ls.size(); i++) {
            CompoundTag t = ls.getCompound(i);
            if (!t.hasUUID("Entity")) continue;
            Store st = new Store();
            st.name = t.getString("Name");
            st.station = t.getBoolean("Station");
            st.entity = t.getUUID("Entity");
            st.dim = t.getString("Dim");
            st.x = t.getInt("X"); st.y = t.getInt("Y"); st.z = t.getInt("Z");
            st.lockedUntil = t.getLong("Until");
            d.stores.put(key(st.name), st);
        }
        ListTag lg = tag.getList("Ledger", Tag.TAG_COMPOUND);
        for (int i = 0; i < lg.size(); i++) {
            CompoundTag t = lg.getCompound(i);
            if (t.hasUUID("P")) d.ledger.add(new Payout(t.getLong("T"), t.getLong("C"), t.getUUID("P")));
        }
        return d;
    }
}
