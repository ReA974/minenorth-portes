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
 * terrain-debloquer &lt;nom&gt; (remise à nu impossible : photo perdue), diagnostic &lt;joueur&gt; (pourquoi l'appli Déco le voit chez lui ou non).
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
                .then(Commands.literal("diagnostic").then(Commands.argument("joueur", EntityArgument.player()).executes(c -> {
                    ServerPlayer t = EntityArgument.getPlayer(c, "joueur");
                    Zones.invalidate();
                    Zones.Home home = Zones.homeOf(t);
                    CommandSourceStack src = c.getSource();
                    src.sendSuccess(() -> Component.literal("§bDéco, " + t.getGameProfile().getName() + " en " + t.blockPosition().toShortString() + " : "
                            + (home == null ? "§cpas chez lui" : "§achez lui (« " + home.occ().houseName() + " »)")), false);
                    int n = 0;
                    for (Zones.Home h : Zones.homes(t.server)) {
                        if (!h.livesHere(t.getUUID())) continue;
                        n++;
                        boolean dim = t.level().dimension().location().toString().equals(h.occ().dimension());
                        String zone = h.occ().category() == fr.minenorth.portes.agency.PropertyCategory.LAND ? "zone du terrain"
                                : h.fromRegion() ? "région WorldGuard " + fr.minenorth.portes.door.WorldGuardBridge.regionName(h.occ().houseName())
                                : "pas de région lisible, abords des portes";
                        String near = h.occ().doors().isEmpty() ? "aucune porte" : "porte la plus proche à "
                                + (int) Math.sqrt(h.occ().doors().stream().mapToDouble(dp -> dp.distSqr(t.blockPosition())).min().orElse(0)) + " blocs";
                        String verdict = !dim ? "§cautre dimension" : h.contains(t.level(), t.blockPosition()) ? "§adans la zone"
                                : h.around(t.level(), t.blockPosition()) ? "§aaux abords des portes" : "§chors zone";
                        src.sendSuccess(() -> Component.literal(" §7- §f" + h.occ().houseName() + " §7(" + zone + ", " + near + ") : " + verdict), false);
                    }
                    if (n == 0) src.sendSuccess(() -> Component.literal(" §cOccupant d'aucun bien : ni propriétaire, ni locataire, ni colocataire (droits donnés depuis le menu de la porte)."), false);
                    return n;
                })))
                .then(Commands.literal("terrain-debloquer").then(Commands.argument("nom", StringArgumentType.greedyString()).executes(c -> {
                    boolean ok = TerrainService.unlock(c.getSource().getServer(), StringArgumentType.getString(c, "nom"));
                    c.getSource().sendSuccess(() -> Component.literal(ok ? "§aTerrain débloqué." : "§cTerrain introuvable."), true);
                    return ok ? 1 : 0;
                }))));
    }
}
