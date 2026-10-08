package fr.minenorth.portes.item;

import net.minecraft.world.item.Item;

/** Crochet : l'usage (clic droit sur une porte) est géré par LockpickManager. */
public class LockpickItem extends Item {
    public static final int USES = 5;

    public LockpickItem(Properties properties) { super(properties); }
}
