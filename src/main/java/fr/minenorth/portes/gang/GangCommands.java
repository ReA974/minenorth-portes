package fr.minenorth.portes.gang;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Tout se fait dans l'écran « Mon gang » (touche G ou Carnet de gang). Il ne reste qu'une commande de secours pour les OP :
 * relire minenorth_gangs.toml sans redémarrer le serveur.
 */
@Mod.EventBusSubscriber
public final class GangCommands {
    private GangCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("gang").requires(s -> s.hasPermission(2))
                .then(Commands.literal("recharger").executes(c -> {
                    GangConfig.load();
                    c.getSource().sendSuccess(() -> Component.literal("§aConfiguration des gangs rechargée."), false);
                    return 1;
                })));
    }
}
