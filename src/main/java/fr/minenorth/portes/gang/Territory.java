package fr.minenorth.portes.gang;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** Zone de gang définie par l'admin : cuboïde dans une dimension, tenue par au plus un gang. */
public final class Territory {
    public final String name;
    public final String dim;
    public final int x1, y1, z1, x2, y2, z2;
    /** Gang qui tient la zone (null = libre) et fin de la protection après une prise. */
    UUID holder;
    long protectedUntil;

    public Territory(String name, String dim, int ax, int ay, int az, int bx, int by, int bz) {
        this.name = name; this.dim = dim;
        this.x1 = Math.min(ax, bx); this.y1 = Math.min(ay, by); this.z1 = Math.min(az, bz);
        this.x2 = Math.max(ax, bx); this.y2 = Math.max(ay, by); this.z2 = Math.max(az, bz);
    }

    public UUID holder() { return holder; }
    public long protectedUntil() { return protectedUntil; }
    public int centerX() { return (x1 + x2) / 2; }
    public int centerY() { return (y1 + y2) / 2; }
    public int centerZ() { return (z1 + z2) / 2; }
    public int volume() { return (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1); }

    public boolean contains(String dimension, double x, double y, double z) {
        return dim.equals(dimension) && GangRules.inBox((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), x1, y1, z1, x2, y2, z2);
    }

    CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("Name", name); t.putString("Dim", dim);
        t.putInt("X1", x1); t.putInt("Y1", y1); t.putInt("Z1", z1);
        t.putInt("X2", x2); t.putInt("Y2", y2); t.putInt("Z2", z2);
        if (holder != null) t.putUUID("Holder", holder);
        t.putLong("ProtectedUntil", protectedUntil);
        return t;
    }

    static Territory load(CompoundTag t) {
        if (t.getString("Name").isBlank() || t.getString("Dim").isBlank()) return null;
        Territory z = new Territory(t.getString("Name"), t.getString("Dim"), t.getInt("X1"), t.getInt("Y1"), t.getInt("Z1"), t.getInt("X2"), t.getInt("Y2"), t.getInt("Z2"));
        if (t.hasUUID("Holder")) z.holder = t.getUUID("Holder");
        z.protectedUntil = t.getLong("ProtectedUntil");
        return z;
    }
}
