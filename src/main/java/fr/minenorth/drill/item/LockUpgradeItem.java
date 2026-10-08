package fr.minenorth.portes.item;

import net.minecraft.world.item.Item;

/** Serrure renforcée : level 0 = Normal, 1 = Avancé, 2 = Expert. La pose est gérée par OwnerDoorManager. */
public class LockUpgradeItem extends Item {
    private final int level;

    public LockUpgradeItem(int level, Properties properties) {
        super(properties);
        this.level = level;
    }

    public int level() { return level; }
}
