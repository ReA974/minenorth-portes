package fr.minenorth.portes.agency;

import fr.minenorth.portes.door.OwnerDoorData;
import fr.minenorth.portes.door.OwnerDoorData.DoorEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Journal des événements d'une propriété (achat, vente, location, expulsion, ouvertures par un tiers…). Les 100 derniers par bien. Nouvelle sauvegarde : aucune ancienne donnée touchée. */
public class PropertyLog extends SavedData {
    private static final String DATA_NAME = "minenorthportes_property_log";
    public static final int MAX_PER_PROPERTY = 100;
    private static final long OPEN_COOLDOWN_MS = 5 * 60_000L;
    private static final Map<String, Long> LAST_OPEN = new HashMap<>();

    public record Entry(long time, String text) {}

    private final Map<String, List<Entry>> logs = new HashMap<>();

    public static PropertyLog get(MinecraftServer s) {
        return s.overworld().getDataStorage().computeIfAbsent(PropertyLog::load, PropertyLog::new, DATA_NAME);
    }

    /** Ajoute une ligne au journal du bien (dimension + nom de maison). */
    public static void add(MinecraftServer s, String dimension, String houseName, String text) {
        PropertyLog l = get(s);
        List<Entry> list = l.logs.computeIfAbsent(PropertyInfoData.key(dimension, houseName), k -> new ArrayList<>());
        list.add(new Entry(System.currentTimeMillis(), text.length() > 200 ? text.substring(0, 200) : text));
        while (list.size() > MAX_PER_PROPERTY) list.remove(0);
        l.setDirty();
    }

    /** Du plus récent au plus ancien. */
    public static List<Entry> entries(MinecraftServer s, String dimension, String houseName) {
        List<Entry> src = get(s).logs.get(PropertyInfoData.key(dimension, houseName));
        if (src == null) return List.of();
        List<Entry> out = new ArrayList<>(src);
        java.util.Collections.reverse(out);
        return out;
    }

    /** Une porte de maison est ouverte par quelqu'un d'autre que le propriétaire ou le locataire (invité, police, crochetage…). Une ligne par joueur et par bien toutes les 5 minutes. */
    public static void opened(ServerPlayer p, ServerLevel level, BlockPos pos, DoorEntry entry) {
        if (entry == null || entry.houseName() == null || entry.houseName().isBlank() || entry.type() != OwnerDoorData.DoorType.PERSONAL) return;
        UUID id = p.getUUID();
        if (id.equals(entry.owner())) return;
        OwnerDoorData d = OwnerDoorData.get(level);
        if (id.equals(d.activeRenter(level, pos))) return;
        String dim = level.dimension().location().toString();
        String k = id + "|" + PropertyInfoData.key(dim, entry.houseName());
        long now = System.currentTimeMillis();
        Long last = LAST_OPEN.get(k);
        if (last != null && now - last < OPEN_COOLDOWN_MS) return;
        if (LAST_OPEN.size() > 2000) LAST_OPEN.clear();
        LAST_OPEN.put(k, now);
        add(p.server, dim, entry.houseName(), p.getGameProfile().getName() + " ouvre la porte (sans en être propriétaire ni locataire)");
    }

    private static PropertyLog load(CompoundTag tag) {
        PropertyLog l = new PropertyLog();
        ListTag all = tag.getList("Logs", 10);
        for (int i = 0; i < all.size(); i++) {
            CompoundTag c = all.getCompound(i);
            List<Entry> list = new ArrayList<>();
            ListTag es = c.getList("Entries", 10);
            for (int j = 0; j < es.size(); j++) list.add(new Entry(es.getCompound(j).getLong("T"), es.getCompound(j).getString("X")));
            l.logs.put(c.getString("Key"), list);
        }
        return l;
    }

    @Override public CompoundTag save(CompoundTag tag) {
        ListTag all = new ListTag();
        for (var en : logs.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putString("Key", en.getKey());
            ListTag es = new ListTag();
            for (Entry e : en.getValue()) {
                CompoundTag t = new CompoundTag();
                t.putLong("T", e.time());
                t.putString("X", e.text());
                es.add(t);
            }
            c.put("Entries", es);
            all.add(c);
        }
        tag.put("Logs", all);
        return tag;
    }
}
