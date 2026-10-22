package fr.minenorth.portes.gang;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Une Fabrique d'armes posée dans une zone contrôlée. Tout est dans la sauvegarde du monde (pas de coffre, pas de bloc-entité) :
 * la file de fabrication et le stock prêt. Les recettes sont copiées dans les lots au moment du lancement, donc modifier la
 * configuration ne change pas ce qui est déjà en cours ni ce qui est prêt.
 */
public final class Factory {
    /** Une fabrication en cours ou en attente. */
    public static final class Job {
        public final String label, spec;
        public final int qty, totalSeconds;
        public final long cents;
        public int remaining;

        Job(String label, String spec, int qty, int totalSeconds, int remaining, long cents) {
            this.label = label; this.spec = spec; this.qty = qty; this.totalSeconds = totalSeconds; this.remaining = remaining; this.cents = cents;
        }
    }

    /** Un lot prêt à être récupéré. */
    public record Lot(String label, String spec, int qty) {}

    public final String dim;
    public final long pos;
    UUID gang;
    final List<Job> queue = new ArrayList<>();
    final List<Lot> stock = new ArrayList<>();
    /** Dernière alerte « fabrique menacée » (mémoire seulement). */
    transient long lastAlert;
    transient boolean lit;

    Factory(String dim, long pos, UUID gang) { this.dim = dim; this.pos = pos; this.gang = gang; }

    public UUID gang() { return gang; }
    public List<Job> queue() { return queue; }
    public List<Lot> stock() { return stock; }

    static String key(String dim, long pos) { return dim + "|" + pos; }
    String key() { return key(dim, pos); }

    CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("Dim", dim); t.putLong("Pos", pos); t.putUUID("Gang", gang);
        ListTag q = new ListTag();
        for (Job j : queue) {
            CompoundTag c = new CompoundTag();
            c.putString("Label", j.label); c.putString("Spec", j.spec); c.putInt("Qty", j.qty); c.putInt("Total", j.totalSeconds); c.putInt("Left", j.remaining); c.putLong("Cents", j.cents);
            q.add(c);
        }
        t.put("Queue", q);
        ListTag s = new ListTag();
        for (Lot l : stock) {
            CompoundTag c = new CompoundTag();
            c.putString("Label", l.label); c.putString("Spec", l.spec); c.putInt("Qty", l.qty);
            s.add(c);
        }
        t.put("Stock", s);
        return t;
    }

    static Factory load(CompoundTag t) {
        if (!t.hasUUID("Gang") || t.getString("Dim").isBlank()) return null;
        Factory f = new Factory(t.getString("Dim"), t.getLong("Pos"), t.getUUID("Gang"));
        ListTag q = t.getList("Queue", Tag.TAG_COMPOUND);
        for (int i = 0; i < q.size(); i++) {
            CompoundTag c = q.getCompound(i);
            f.queue.add(new Job(c.getString("Label"), c.getString("Spec"), Math.max(1, c.getInt("Qty")), Math.max(1, c.getInt("Total")), Math.max(0, c.getInt("Left")), Math.max(0, c.getLong("Cents"))));
        }
        ListTag s = t.getList("Stock", Tag.TAG_COMPOUND);
        for (int i = 0; i < s.size(); i++) {
            CompoundTag c = s.getCompound(i);
            f.stock.add(new Lot(c.getString("Label"), c.getString("Spec"), Math.max(1, c.getInt("Qty"))));
        }
        return f;
    }
}
