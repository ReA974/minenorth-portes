package fr.minenorth.portes.item;

import fr.minenorth.portes.MineNorthPortes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    private ModItems() {}

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MineNorthPortes.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MineNorthPortes.MOD_ID);

    public static final RegistryObject<Item> LOCKPICK = ITEMS.register("lockpick", () -> new LockpickItem(new Item.Properties().stacksTo(1).durability(LockpickItem.USES)));
    public static final RegistryObject<Item> LOCK_NORMAL = ITEMS.register("serrure_normal", () -> new LockUpgradeItem(0, new Item.Properties().stacksTo(16)));
    public static final RegistryObject<Item> LOCK_ADVANCED = ITEMS.register("serrure_avancee", () -> new LockUpgradeItem(1, new Item.Properties().stacksTo(16)));
    public static final RegistryObject<Item> LOCK_EXPERT = ITEMS.register("serrure_expert", () -> new LockUpgradeItem(2, new Item.Properties().stacksTo(16)));

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("portes", () -> CreativeModeTab.builder()
            .title(Component.literal("MineNorth Portes"))
            .icon(() -> LOCKPICK.get().getDefaultInstance())
            .displayItems((params, out) -> {
                out.accept(LOCKPICK.get());
                out.accept(LOCK_NORMAL.get());
                out.accept(LOCK_ADVANCED.get());
                out.accept(LOCK_EXPERT.get());
            })
            .build());

    /** Item de serrure correspondant à un niveau de sécurité (1 Normal, 2 Avancé, 3 Expert). */
    public static Item lockFor(int security) {
        return security >= 3 ? LOCK_EXPERT.get() : security == 2 ? LOCK_ADVANCED.get() : LOCK_NORMAL.get();
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
        TABS.register(bus);
    }
}
