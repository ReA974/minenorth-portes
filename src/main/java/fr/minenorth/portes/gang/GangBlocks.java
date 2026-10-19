package fr.minenorth.portes.gang;

import fr.minenorth.portes.MineNorthPortes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Blocs du système de gangs. */
public final class GangBlocks {
    private GangBlocks() {}

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MineNorthPortes.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MineNorthPortes.MOD_ID);

    public static final RegistryObject<Block> GANG_CHEST = BLOCKS.register("coffre_gang", GangChestBlock::new);
    public static final RegistryObject<Block> TERRITORY_SIGN = BLOCKS.register("pancarte_territoire", TerritorySignBlock::new);

    public static final RegistryObject<Item> GANG_CHEST_ITEM = ITEMS.register("coffre_gang", () -> new BlockItem(GANG_CHEST.get(), new Item.Properties().stacksTo(16)));
    public static final RegistryObject<Item> TERRITORY_SIGN_ITEM = ITEMS.register("pancarte_territoire", () -> new BlockItem(TERRITORY_SIGN.get(), new Item.Properties().stacksTo(16)));

    public static final RegistryObject<Item> GANG_BOOK = ITEMS.register("carnet_gang", GangBookItem::new);

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }
}
