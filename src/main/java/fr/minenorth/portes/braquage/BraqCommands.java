package fr.minenorth.portes.braquage;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.minenorth.portes.MineNorthPortes;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/** /braquage : gestion des magasins braquables et des réglages (OP). Les valeurs sont enregistrées dans config/minenorth_braquages.toml. */
@Mod.EventBusSubscriber(modid = MineNorthPortes.MOD_ID)
public final class BraqCommands {
    private BraqCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("braquage").requires(s -> s.hasPermission(2));

        root.then(Commands.literal("magasin")
                .then(Commands.literal("ajouter").then(Commands.argument("nom", StringArgumentType.word())
                        .executes(c -> add(c, false))
                        .then(Commands.literal("superette").executes(c -> add(c, false)))
                        .then(Commands.literal("station").executes(c -> add(c, true)))))
                .then(Commands.literal("supprimer").then(Commands.argument("nom", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(names(c), b)).executes(BraqCommands::remove)))
                .then(Commands.literal("reinitialiser").then(Commands.argument("nom", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(names(c), b)).executes(BraqCommands::reset)))
                .then(Commands.literal("liste").executes(BraqCommands::list)));

        root.then(Commands.literal("cooldown").then(Commands.argument("minutes", IntegerArgumentType.integer(0, 100_000)).executes(c -> {
            BraqConfig.COOLDOWN_MIN.set(IntegerArgumentType.getInteger(c, "minutes"));
            return saved(c, "Cooldown après réussite : " + BraqConfig.COOLDOWN_MIN.get() + " min.");
        })));
        root.then(Commands.literal("montant")
                .then(Commands.argument("min", IntegerArgumentType.integer(1, 1_000_000))
                        .then(Commands.argument("max", IntegerArgumentType.integer(1, 1_000_000)).executes(c -> {
                            int mn = IntegerArgumentType.getInteger(c, "min"), mx = IntegerArgumentType.getInteger(c, "max");
                            if (mx < mn) { c.getSource().sendFailure(Component.literal("Le maximum doit être au moins égal au minimum.")); return 0; }
                            BraqConfig.MONTANT_MIN.set(mn);
                            BraqConfig.MONTANT_MAX.set(mx);
                            return saved(c, "Butin : " + mn + " à " + mx + " € (plafond par braquage : " + BraqConfig.PLAFOND_BRAQUAGE.get() + " €).");
                        }))));
        root.then(Commands.literal("policiers").then(Commands.argument("nombre", IntegerArgumentType.integer(0, 100)).executes(c -> {
            BraqConfig.POLICIERS_MIN.set(IntegerArgumentType.getInteger(c, "nombre"));
            return saved(c, "Policiers minimum : " + BraqConfig.POLICIERS_MIN.get() + ".");
        })));
        root.then(Commands.literal("arme")
                .then(Commands.literal("ajouter").executes(c -> weapon(c, true)))
                .then(Commands.literal("retirer").executes(c -> weapon(c, false)))
                .then(Commands.literal("liste").executes(c -> {
                    c.getSource().sendSuccess(() -> Component.literal("§6Armes : §f" + String.join(", ", BraqConfig.ARMES.get())), false);
                    return 1;
                })));
        root.then(Commands.literal("info").executes(c -> {
            String t = "§6[Braquages] §fbutin " + BraqConfig.MONTANT_MIN.get() + "-" + BraqConfig.MONTANT_MAX.get() + " € (max " + BraqConfig.PLAFOND_BRAQUAGE.get()
                    + " €, " + BraqConfig.PLAFOND_HEURE.get() + " €/h serveur) | cooldown " + BraqConfig.COOLDOWN_MIN.get() + " min | policiers min "
                    + BraqConfig.POLICIERS_MIN.get() + " | durée " + BraqConfig.DUREE_SECONDES.get() + " s";
            c.getSource().sendSuccess(() -> Component.literal(t), false);
            return 1;
        }));
        d.register(root);
    }

    private static List<String> names(CommandContext<CommandSourceStack> c) {
        List<String> out = new ArrayList<>();
        for (BraqData.Store s : BraqData.get(c.getSource().getServer()).all()) out.add(s.name);
        return out;
    }

    private static int saved(CommandContext<CommandSourceStack> c, String text) {
        try { BraqConfig.SPEC.save(); } catch (Throwable ignored) {}
        c.getSource().sendSuccess(() -> Component.literal("§a[Braquages] " + text), true);
        return 1;
    }

    private static Entity aimed(ServerPlayer p) {
        Vec3 eye = p.getEyePosition(), look = p.getViewVector(1.0F), end = eye.add(look.scale(8));
        AABB box = p.getBoundingBox().expandTowards(look.scale(8)).inflate(1.0);
        EntityHitResult r = ProjectileUtil.getEntityHitResult(p, eye, end, box, x -> !x.isSpectator() && x.isPickable() && x != p, 64.0);
        return r == null ? null : r.getEntity();
    }

    private static int add(CommandContext<CommandSourceStack> c, boolean station) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(c, "nom");
        Entity t = aimed(p);
        if (t == null || t instanceof ServerPlayer) { c.getSource().sendFailure(Component.literal("Vise le PNJ vendeur (à moins de 8 blocs).")); return 0; }
        if (BraqCompat.shopOf(t) < 0) { c.getSource().sendFailure(Component.literal("Ce PNJ n'est pas un vendeur (outil de liaison des boutiques).")); return 0; }
        BraqData.Store st = new BraqData.Store();
        st.name = name;
        st.station = station;
        st.entity = t.getUUID();
        st.dim = t.level().dimension().location().toString();
        st.x = t.getBlockX(); st.y = t.getBlockY(); st.z = t.getBlockZ();
        if (!BraqData.get(c.getSource().getServer()).add(st)) {
            c.getSource().sendFailure(Component.literal("Ce nom est déjà pris, ou ce PNJ est déjà braquable."));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("§a[Braquages] « " + name + " » (" + st.typeLabel() + ") est braquable. Accroupi + clic droit avec une arme."), true);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "nom");
        BraqData data = BraqData.get(c.getSource().getServer());
        BraqData.Store st = data.byName(name);
        if (st == null) { c.getSource().sendFailure(Component.literal("Magasin inconnu.")); return 0; }
        Heists.cancelFor(c.getSource().getServer(), st.entity);
        data.remove(name);
        c.getSource().sendSuccess(() -> Component.literal("§a[Braquages] « " + st.name + " » n'est plus braquable."), true);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> c) {
        BraqData data = BraqData.get(c.getSource().getServer());
        BraqData.Store st = data.byName(StringArgumentType.getString(c, "nom"));
        if (st == null) { c.getSource().sendFailure(Component.literal("Magasin inconnu.")); return 0; }
        data.lock(st, 0);
        c.getSource().sendSuccess(() -> Component.literal("§a[Braquages] Caisse de « " + st.name + " » déverrouillée."), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        BraqData data = BraqData.get(c.getSource().getServer());
        List<BraqData.Store> all = data.all();
        if (all.isEmpty()) { c.getSource().sendSuccess(() -> Component.literal("§7Aucun magasin braquable."), false); return 0; }
        for (BraqData.Store s : all) {
            long left = data.lockLeftMinutes(s);
            c.getSource().sendSuccess(() -> Component.literal("§6" + s.name + " §7(" + s.typeLabel() + ") " + s.x + " " + s.y + " " + s.z
                    + (left > 0 ? " §c— verrouillé " + left + " min" : " §a— prêt")), false);
        }
        return all.size();
    }

    private static int weapon(CommandContext<CommandSourceStack> c, boolean add) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        var id = ForgeRegistries.ITEMS.getKey(p.getMainHandItem().getItem());
        if (p.getMainHandItem().isEmpty() || id == null) { c.getSource().sendFailure(Component.literal("Tiens l'objet en main.")); return 0; }
        List<String> l = new ArrayList<>(BraqConfig.ARMES.get());
        if (add ? !l.contains(id.toString()) && l.add(id.toString()) : l.remove(id.toString())) {
            BraqConfig.ARMES.set(l);
            return saved(c, (add ? "Arme ajoutée : " : "Arme retirée : ") + id);
        }
        c.getSource().sendFailure(Component.literal(add ? "Déjà dans la liste." : "Pas dans la liste."));
        return 0;
    }
}
