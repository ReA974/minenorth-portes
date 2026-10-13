package fr.minenorth.portes.command;

import fr.minenorth.portes.door.WorldGuardBridge;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** /portewg resync : renvoie à WorldGuard tous les propriétaires et locataires de maisons (région créée après coup, région supprimée…). */
@Mod.EventBusSubscriber
public final class WgCommands {
    private WgCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("portewg").requires(s -> s.hasPermission(2))
                .then(Commands.literal("resync").executes(c -> {
                    int n = WorldGuardBridge.resync(c.getSource().getServer());
                    c.getSource().sendSystemMessage(Component.literal("§aWorldGuard : " + n + " maison(s) seront renvoyées dans quelques secondes."));
                    return 1;
                })));
    }
}
