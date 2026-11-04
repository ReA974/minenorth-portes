package fr.minenorth.portes.deco;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /deco (OP) : recharger (deco.json + catalogue), garde-meuble &lt;joueur&gt; (contenu, pour le support), verifier (déménagements en attente),
 * terrain-debloquer &lt;nom&gt; (remise à nu impossible : photo perdue).
 */
public final class DecoCommands {
    private DecoCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("deco").requires(s -> s.hasPermission(2))
                .then(Commands.literal("recharger").executes(c -> {
                    DecoConfig.load();
                    int n = DecoCatalog.load();
                    c.getSource().sendSuccess(() -> Component.literal("§aDéco rechargée : " + n + " articles au catalogue."), true);
                    return n;
                }))
                .then(Commands.literal("verifier").executes(c -> {
                    MoveWatcher.check(c.getSource().getServer());
                    c.getSource().sendSuccess(() -> Component.literal("§aDéménagements vérifiés (voir logs/minenorth-deco.log)."), true);
                    return 1;
                }))
                .then(Commands.literal("garde-meuble").then(Commands.argument("joueur", EntityArgument.player()).executes(c -> {
                    ServerPlayer t = EntityArgument.getPlayer(c, "joueur");
                    GardeMeuble g = GardeMeuble.get(t.server);
                    c.getSource().sendSuccess(() -> Component.literal("§bGarde-meuble de " + t.getGameProfile().getName() + " : " + g.total(t.getUUID()) + " / "
                            + VipPerks.capacity(t.server, t.getUUID()) + " objets"), false);
                    for (GardeMeuble.Lot l : g.lots(t.getUUID()))
                        c.getSource().sendSuccess(() -> Component.literal(" - " + l.item.getHoverName().getString() + " x" + l.count), false);
                    return 1;
                })))
                .then(Commands.literal("terrain-debloquer").then(Commands.argument("nom", StringArgumentType.greedyString()).executes(c -> {
                    boolean ok = TerrainService.unlock(c.getSource().getServer(), StringArgumentType.getString(c, "nom"));
                    c.getSource().sendSuccess(() -> Component.literal(ok ? "§aTerrain débloqué." : "§cTerrain introuvable."), true);
                    return ok ? 1 : 0;
                }))));
    }
}
