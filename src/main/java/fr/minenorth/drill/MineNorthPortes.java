package fr.minenorth.portes;

import fr.minenorth.portes.config.SystemeConfigFiles;
import fr.minenorth.portes.item.ModItems;
import fr.minenorth.portes.network.ModNetwork;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(MineNorthPortes.MOD_ID)
public class MineNorthPortes {
    public static final String MOD_ID = "minenorthportes";

    public MineNorthPortes() {
        SystemeConfigFiles.init();
        ModNetwork.register();
        ModItems.register(FMLJavaModLoadingContext.get().getModEventBus());
    }
}
