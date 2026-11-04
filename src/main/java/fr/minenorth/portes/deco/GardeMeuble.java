package fr.minenorth.portes.deco;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Garde-meuble de chaque joueur : objets rangés (meubles et leur contenu), regroupés par objet identique (NBT compris), sans limite de pile.
 * Sauvegardé avec le monde (minenorthportes_garde_meuble). La capacité ({@link VipPerks#capacity}) ne bloque que les rangements à la main :
 * un déménagement automatique est toujours accepté. Thread serveur uniquement.
 */
public class GardeMeuble extends SavedData {
    private static final String NAME = "minenorthportes_garde_meuble";

    /** Un lot : l'objet (quantité 1) et combien on en a. */
    public static final class Lot {
        public final ItemStack item;
        public int count;
        Lot(ItemStack item, int count) { this.item = item; this.count = count; }
    }

    private final Map<UUID, List<Lot>> stores = new HashMap<>();
    /** Lots dont l'objet n'existe plus (mod retiré) : recopiés tels quels à la sauvegarde, jamais perdus. */
    private final Map<UUID, List<CompoundTag>> orphans = new HashMap<>();

    public static GardeMeuble get(MinecraftServer s) {
        return s.overworld().getDataStorage().computeIfAbsent(GardeMeuble::load, GardeMeuble::new, NAME);
    }

    public List<Lot> lots(UUID player) { return stores.getOrDefault(player, List.of()); }

    public int total(UUID player) {
        int n = 0;
        for (Lot l : lots(player)) n += l.count;
        return n;
    }

    /** Ajoute (regroupe avec un lot identique). */
    public void add(UUID player, ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemStack one = stack.copyWithCount(1);
        List<Lot> list = stores.computeIfAbsent(player, k -> new ArrayList<>());
        for (Lot l : list) {
            if (ItemStack.isSameItemSameTags(l.item, one)) { l.count += stack.getCount(); setDirty(); return; }
        }
        list.add(new Lot(one, stack.getCount()));
        setDirty();
    }

    /** Retire jusqu'à n objets du lot index ; renvoie la pile retirée (vide si impossible). */
    public ItemStack take(UUID player, int index, int n) {
        List<Lot> list = stores.get(player);
        if (list == null || index < 0 || index >= list.size() || n <= 0) return ItemStack.EMPTY;
        Lot l = list.get(index);
        int k = Math.min(n, l.count);
        l.count -= k;
        if (l.count <= 0) list.remove(index);
        if (list.isEmpty()) stores.remove(player);
        setDirty();
        return l.item.copyWithCount(k);
    }

    private static GardeMeuble load(CompoundTag tag) {
        GardeMeuble g = new GardeMeuble();
        for (Tag t : tag.getList("Stores", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            if (!c.hasUUID("Player")) continue;
            List<Lot> list = new ArrayList<>();
            for (Tag it : c.getList("Lots", Tag.TAG_COMPOUND)) {
                CompoundTag lc = (CompoundTag) it;
                ItemStack st = ItemStack.of(lc.getCompound("Item"));
                if (st.isEmpty()) { g.orphans.computeIfAbsent(c.getUUID("Player"), k -> new ArrayList<>()).add(lc.copy()); continue; }
                list.add(new Lot(st.copyWithCount(1), Math.max(1, lc.getInt("Count"))));
            }
            if (!list.isEmpty()) g.stores.put(c.getUUID("Player"), list);
        }
        return g;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag stores = new ListTag();
        java.util.Set<UUID> ids = new java.util.LinkedHashSet<>(this.stores.keySet());
        ids.addAll(orphans.keySet());
        for (UUID id : ids) {
            List<Lot> list = this.stores.getOrDefault(id, List.of());
            CompoundTag c = new CompoundTag();
            c.putUUID("Player", id);
            ListTag lots = new ListTag();
            for (CompoundTag o : orphans.getOrDefault(id, List.of())) lots.add(o.copy());
            for (Lot l : list) {
                CompoundTag lc = new CompoundTag();
                lc.put("Item", l.item.save(new CompoundTag()));
                lc.putInt("Count", l.count);
                lots.add(lc);
            }
            c.put("Lots", lots);
            stores.add(c);
        }
        tag.put("Stores", stores);
        return tag;
    }
}
