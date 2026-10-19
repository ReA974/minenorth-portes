package fr.minenorth.portes.gang;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import fr.minenorth.api.MineNorth;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Function;

/** Commandes /gang (en français). Le serveur revérifie tout : aucune commande ne fait confiance à ce que le joueur prétend être. */
@Mod.EventBusSubscriber
public final class GangCommands {
    private GangCommands() {}

    private static final int PAGE = 12;

    private static final SuggestionProvider<CommandSourceStack> MEMBERS = (ctx, b) -> {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            Gang g = GangData.get(p.server).gangOf(p.getUUID());
            if (g != null) {
                List<String> names = new ArrayList<>();
                for (UUID m : g.members().keySet()) names.add(MineNorth.pseudo(p.server, m));
                return SharedSuggestionProvider.suggest(names, b);
            }
        } catch (CommandSyntaxException ignored) { }
        return b.buildFuture();
    };
    private static final SuggestionProvider<CommandSourceStack> RANKS = (ctx, b) -> SharedSuggestionProvider.suggest(List.of("chef", "lieutenant", "membre"), b);
    private static final SuggestionProvider<CommandSourceStack> GANGS = (ctx, b) -> {
        List<String> tags = new ArrayList<>();
        for (Gang g : GangData.get(ctx.getSource().getServer()).gangs()) tags.add(g.tag());
        return SharedSuggestionProvider.suggest(tags, b);
    };
    private static final SuggestionProvider<CommandSourceStack> TERRITORIES = (ctx, b) -> {
        List<String> names = new ArrayList<>();
        for (Territory t : GangData.get(ctx.getSource().getServer()).territories()) names.add(t.name);
        return SharedSuggestionProvider.suggest(names, b);
    };

    private static LiteralArgumentBuilder<CommandSourceStack> lit(String s) { return Commands.literal(s); }
    private static <T> RequiredArgumentBuilder<CommandSourceStack, T> arg(String n, com.mojang.brigadier.arguments.ArgumentType<T> t) { return Commands.argument(n, t); }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();
        d.register(lit("gang")
                .then(lit("creer").then(arg("nom", StringArgumentType.string()).then(arg("tag", StringArgumentType.word())
                        .executes(c -> act(c, p -> GangService.create(p, StringArgumentType.getString(c, "nom"), StringArgumentType.getString(c, "tag"), null)))
                        .then(arg("couleur", StringArgumentType.word())
                                .executes(c -> act(c, p -> GangService.create(p, StringArgumentType.getString(c, "nom"), StringArgumentType.getString(c, "tag"), StringArgumentType.getString(c, "couleur"))))))))
                .then(lit("inviter").then(arg("joueur", EntityArgument.player())
                        .executes(c -> { ServerPlayer target = EntityArgument.getPlayer(c, "joueur"); return act(c, p -> GangService.invite(p, target)); })))
                .then(lit("accepter").executes(c -> act(c, p -> GangService.accept(p, null)))
                        .then(arg("gang", StringArgumentType.string()).suggests(GANGS).executes(c -> act(c, p -> GangService.accept(p, StringArgumentType.getString(c, "gang"))))))
                .then(lit("refuser").executes(c -> act(c, p -> GangService.decline(p, null)))
                        .then(arg("gang", StringArgumentType.string()).suggests(GANGS).executes(c -> act(c, p -> GangService.decline(p, StringArgumentType.getString(c, "gang"))))))
                .then(lit("exclure").then(arg("membre", StringArgumentType.string()).suggests(MEMBERS)
                        .executes(c -> act(c, p -> GangService.kick(p, StringArgumentType.getString(c, "membre"))))))
                .then(lit("quitter").executes(c -> act(c, GangService::leave)))
                .then(lit("grade").then(arg("membre", StringArgumentType.string()).suggests(MEMBERS).then(arg("grade", StringArgumentType.word()).suggests(RANKS)
                        .executes(c -> act(c, p -> {
                            GangRank r = GangRank.parse(StringArgumentType.getString(c, "grade"));
                            return r == null ? "Grade inconnu : chef, lieutenant ou membre." : GangService.setRank(p, StringArgumentType.getString(c, "membre"), r);
                        })))))
                .then(lit("info").executes(c -> info(c, null))
                        .then(arg("gang", StringArgumentType.string()).suggests(GANGS).executes(c -> info(c, StringArgumentType.getString(c, "gang")))))
                .then(lit("liste").executes(GangCommands::list))
                .then(lit("dissoudre").executes(c -> act(c, p -> GangService.dissolve(p, false)))
                        .then(lit("confirmer").executes(c -> act(c, p -> GangService.dissolve(p, true)))))
                .then(lit("coffre").executes(GangCommands::chestInfo)
                        .then(lit("journal").executes(c -> chestLog(c, 1))
                                .then(arg("page", IntegerArgumentType.integer(1)).executes(c -> chestLog(c, IntegerArgumentType.getInteger(c, "page"))))))
                .then(lit("porte")
                        .then(lit("ajouter").executes(c -> doorAdd(c, GangRank.MEMBRE, null))
                                .then(arg("grade", StringArgumentType.word()).suggests(RANKS)
                                        .executes(c -> doorAdd(c, rank(c), null))
                                        .then(arg("pos", BlockPosArgument.blockPos()).executes(c -> doorAdd(c, rank(c), BlockPosArgument.getLoadedBlockPos(c, "pos"))))))
                        .then(lit("retirer").executes(c -> doorRemove(c, null))
                                .then(arg("pos", BlockPosArgument.blockPos()).executes(c -> doorRemove(c, BlockPosArgument.getLoadedBlockPos(c, "pos")))))
                        .then(lit("grade").then(arg("grade", StringArgumentType.word()).suggests(RANKS)
                                .executes(c -> doorRank(c, null))
                                .then(arg("pos", BlockPosArgument.blockPos()).executes(c -> doorRank(c, BlockPosArgument.getLoadedBlockPos(c, "pos"))))))
                        .then(lit("liste").executes(GangCommands::doorList)))
                .then(lit("territoire").executes(GangCommands::territories)
                        .then(lit("creer").requires(s -> s.hasPermission(2)).then(arg("nom", StringArgumentType.string())
                                .then(arg("rayon", IntegerArgumentType.integer(2, 200)).executes(GangCommands::territoryAround))
                                .then(arg("de", BlockPosArgument.blockPos()).then(arg("a", BlockPosArgument.blockPos()).executes(GangCommands::territoryBox)))))
                        .then(lit("supprimer").requires(s -> s.hasPermission(2)).then(arg("nom", StringArgumentType.string()).suggests(TERRITORIES)
                                .executes(c -> admin(c, s -> TerritoryManager.delete(s.getServer(), StringArgumentType.getString(c, "nom")), "Territoire supprimé."))))
                        .then(lit("liberer").requires(s -> s.hasPermission(2)).then(arg("nom", StringArgumentType.string()).suggests(TERRITORIES)
                                .executes(c -> admin(c, s -> TerritoryManager.free(s.getServer(), StringArgumentType.getString(c, "nom")), "Territoire libéré.")))))
                .then(lit("admin").requires(s -> s.hasPermission(2))
                        .then(lit("dissoudre").then(arg("gang", StringArgumentType.string()).suggests(GANGS)
                                .executes(c -> adminDissolve(c, false))
                                .then(lit("forcer").executes(c -> adminDissolve(c, true)))))
                        .then(lit("recharger").executes(c -> {
                            GangConfig.load();
                            c.getSource().sendSuccess(() -> Component.literal("§aConfiguration des gangs rechargée."), false);
                            return 1;
                        }))));
    }

    // ------------------------------------------------------------------ exécution

    private static int act(CommandContext<CommandSourceStack> c, Function<ServerPlayer, String> fn) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        String err = fn.apply(p);
        if (err != null) { GangService.tell(p, "§c" + err); return 0; }
        return 1;
    }

    private static int admin(CommandContext<CommandSourceStack> c, Function<CommandSourceStack, String> fn, String ok) {
        String err = fn.apply(c.getSource());
        if (err != null) { c.getSource().sendFailure(Component.literal(err)); return 0; }
        c.getSource().sendSuccess(() -> Component.literal("§a" + ok), true);
        return 1;
    }

    private static GangRank rank(CommandContext<CommandSourceStack> c) {
        GangRank r = GangRank.parse(StringArgumentType.getString(c, "grade"));
        return r == null ? GangRank.MEMBRE : r;
    }

    private static int info(CommandContext<CommandSourceStack> c, String query) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        GangData data = GangData.get(p.server);
        Gang g = query == null ? data.gangOf(p.getUUID()) : data.byName(query);
        if (g == null) { GangService.tell(p, query == null ? "§cVous n'êtes dans aucun gang. Fondez-en un avec /gang creer <nom> <tag>." : "§cGang introuvable."); return 0; }
        for (String line : GangService.infoLines(p.server, g)) GangService.tell(p, line);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        GangData data = GangData.get(p.server);
        if (data.gangs().isEmpty()) { GangService.tell(p, "§7Aucun gang pour le moment."); return 1; }
        GangService.tell(p, "§6§lGangs de la ville");
        for (Gang g : data.gangs()) {
            long online = g.members().keySet().stream().filter(m -> p.server.getPlayerList().getPlayer(m) != null).count();
            GangService.tell(p, " " + g.label() + " §7- " + g.members().size() + " membre(s), " + online + " en ligne");
        }
        return 1;
    }

    // ------------------------------------------------------------------ coffre

    private static int chestInfo(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        Gang g = GangData.get(p.server).gangOf(p.getUUID());
        if (g == null) { GangService.tell(p, "§cVous n'êtes dans aucun gang."); return 0; }
        int stacks = 0, items = 0;
        for (int i = 0; i < g.vault().getContainerSize(); i++) {
            if (!g.vault().getItem(i).isEmpty()) { stacks++; items += g.vault().getItem(i).getCount(); }
        }
        GangService.tell(p, "§6Coffre commun de " + g.name() + " §7: §f" + items + " §7objet(s) sur §f" + stacks + "/" + Gang.VAULT_SLOTS + " §7cases.");
        GangService.tell(p, "§7Blocs posés : §f" + g.chests().size() + "/" + GangConfig.chestsMax() + " §7(posez un Coffre de gang près de votre base ; tous ouvrent le même coffre).");
        if (g.rankOf(p.getUUID()) == GangRank.CHEF) GangService.tell(p, "§7Journal des retraits : /gang coffre journal");
        return 1;
    }

    private static int chestLog(CommandContext<CommandSourceStack> c, int page) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        Gang g = GangData.get(p.server).gangOf(p.getUUID());
        if (g == null) { GangService.tell(p, "§cVous n'êtes dans aucun gang."); return 0; }
        if (g.rankOf(p.getUUID()) != GangRank.CHEF && !p.hasPermissions(2)) { GangService.tell(p, "§cSeul le chef consulte le journal du coffre."); return 0; }
        List<Gang.LogEntry> log = g.log();
        if (log.isEmpty()) { GangService.tell(p, "§7Le journal est vide."); return 1; }
        int pages = (log.size() + PAGE - 1) / PAGE;
        int pg = Math.min(page, pages);
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM HH:mm");
        GangService.tell(p, "§6Journal du coffre §7(page " + pg + "/" + pages + ", du plus récent au plus ancien)");
        int from = log.size() - (pg - 1) * PAGE;
        for (int i = from - 1; i >= Math.max(0, from - PAGE); i--) {
            Gang.LogEntry le = log.get(i);
            GangService.tell(p, "§8" + fmt.format(new Date(le.timeMs())) + " §f" + GangService.nameOf(p.server, le.actor()) + " §7" + le.text());
        }
        return 1;
    }

    // ------------------------------------------------------------------ portes

    private static BlockPos target(ServerPlayer p, BlockPos given) {
        return given != null ? given : GangDoors.aimedDoor(p);
    }

    private static int doorAdd(CommandContext<CommandSourceStack> c, GangRank rank, BlockPos pos) throws CommandSyntaxException {
        return act(c, p -> {
            BlockPos t = target(p, pos);
            if (t == null) return "Visez une porte (à moins de 6 blocs) ou accroupissez-vous dessus.";
            String err = GangDoors.claim(p, t, rank);
            if (err == null) GangService.tell(p, "§aPorte attribuée au gang (ouverte aux " + rank.label().toLowerCase(Locale.ROOT) + "s et au-dessus).");
            return err;
        });
    }

    private static int doorRemove(CommandContext<CommandSourceStack> c, BlockPos pos) throws CommandSyntaxException {
        return act(c, p -> {
            BlockPos t = target(p, pos);
            if (t == null) return "Visez une porte du gang (à moins de 6 blocs).";
            String err = GangDoors.unclaim(p, t);
            if (err == null) GangService.tell(p, "§7La porte n'appartient plus au gang.");
            return err;
        });
    }

    private static int doorRank(CommandContext<CommandSourceStack> c, BlockPos pos) throws CommandSyntaxException {
        return act(c, p -> {
            BlockPos t = target(p, pos);
            if (t == null) return "Visez une porte du gang (à moins de 6 blocs).";
            GangRank r = rank(c);
            String err = GangDoors.setMinRank(p, t, r);
            if (err == null) GangService.tell(p, "§aCette porte s'ouvre maintenant pour : " + r.label().toLowerCase(Locale.ROOT) + " et au-dessus.");
            return err;
        });
    }

    private static int doorList(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return act(c, p -> {
            Gang g = GangData.get(p.server).gangOf(p.getUUID());
            if (g == null) return "Vous n'êtes dans aucun gang.";
            var keys = GangDoors.doorsOf(p.server, g);
            GangService.tell(p, "§6Portes du gang : §f" + keys.size() + "/" + GangConfig.doorsMax());
            int shown = 0;
            for (var k : keys) {
                if (shown++ >= 20) { GangService.tell(p, "§7…"); break; }
                var e = p.server.getLevel(k.dimension());
                String rk = "?";
                if (e != null) {
                    var en = fr.minenorth.portes.door.OwnerDoorData.get(e).entry(k);
                    if (en != null) rk = GangDoors.minRank(en.permission()).label().toLowerCase(Locale.ROOT);
                }
                GangService.tell(p, " §7" + k.pos().getX() + " " + k.pos().getY() + " " + k.pos().getZ() + " §8(" + k.dimension().location() + ") §7grade : " + rk);
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ territoires

    private static int territories(CommandContext<CommandSourceStack> c) {
        List<String> lines = TerritoryManager.listLines(c.getSource().getServer());
        if (lines.isEmpty()) { c.getSource().sendSystemMessage(Component.literal("§7Aucun territoire n'est défini pour le moment.")); return 1; }
        c.getSource().sendSystemMessage(Component.literal("§6§lTerritoires"));
        for (String l : lines) c.getSource().sendSystemMessage(Component.literal(" " + l));
        return 1;
    }

    private static int territoryAround(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        int r = IntegerArgumentType.getInteger(c, "rayon");
        BlockPos o = p.blockPosition();
        String err = TerritoryManager.create(p.server, StringArgumentType.getString(c, "nom"), p.level().dimension().location().toString(),
                o.getX() - r, p.level().getMinBuildHeight(), o.getZ() - r, o.getX() + r, p.level().getMaxBuildHeight() - 1, o.getZ() + r);
        return reply(c, err, "Territoire créé autour de vous (toute la hauteur).");
    }

    private static int territoryBox(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        BlockPos a = BlockPosArgument.getBlockPos(c, "de"), b = BlockPosArgument.getBlockPos(c, "a");
        String err = TerritoryManager.create(p.server, StringArgumentType.getString(c, "nom"), p.level().dimension().location().toString(),
                a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
        return reply(c, err, "Territoire créé.");
    }

    private static int reply(CommandContext<CommandSourceStack> c, String err, String ok) {
        if (err != null) { c.getSource().sendFailure(Component.literal(err)); return 0; }
        c.getSource().sendSuccess(() -> Component.literal("§a" + ok), true);
        return 1;
    }

    private static int adminDissolve(CommandContext<CommandSourceStack> c, boolean force) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        String err = GangService.adminDissolve(p, StringArgumentType.getString(c, "gang"), force);
        return reply(c, err, "Gang dissous.");
    }
}
