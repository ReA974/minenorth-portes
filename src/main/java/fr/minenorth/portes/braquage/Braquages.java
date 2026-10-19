package fr.minenorth.portes.braquage;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/** Point d'entrée du braquage des supérettes et stations-service (sous-ensemble du mod portes/gangs). */
public final class Braquages {
    private Braquages() {}

    public static void init(IEventBus modBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, BraqConfig.SPEC, "minenorth_braquages.toml");
        BraqRegistry.BLOCKS.register(modBus);
        BraqRegistry.ITEMS.register(modBus);
    }
}
