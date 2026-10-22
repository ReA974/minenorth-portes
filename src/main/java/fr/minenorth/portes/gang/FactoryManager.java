package fr.minenorth.portes.gang;

import fr.minenorth.api.MineNorth;
import fr.minenorth.api.PayResult;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fabrique d'armes : avantage des zones contrôlées. Le serveur décide de tout : où on peut la poser, qui la lance, qui la vide, quand elle tourne.
 * Elle ne produit que tant que le gang qui la possède CONTRÔLE la zone où elle se trouve ; sinon elle s'arrête et le gang qui tient la zone peut la saisir ou la casser.
 * Le prix de chaque fabrication est prélevé sur le compte bancaire du chef (l'argent du gang) au lancement.
 */
@Mod.EventBusSubscriber
public final class FactoryManager {
    private FactoryManager() {}

    private static final double REACH2 = 64.0;
    private static final long ALERT_MS = 30_000L;
    private static final int RECIPES_MAX = 30;

    // ------------------------------------------------------------------ zones et pose

    static Territory zoneAt(GangData data, String dim, BlockPos pos) {
        for (Territory t : data.territories()) if (t.contains(dim, pos.getX(), pos.getY(), pos.getZ())) return t;
        return null;
    }

    /** Raison du refus de pose, ou null. */
    public static String placementError(ServerPlayer p, String dim, BlockPos pos) {
        if (!GangConfig.factoryEnabled()) return "La Fabrique d'armes est désactivée sur ce serveur.";
        GangData data = GangData.get(p.server);
        Gang g = data.gangOf(p.getUUID());
        if (g == null) return "Il faut être dans un gang pour poser une Fabrique d'armes.";
        if (!g.rankOf(p.getUUID()).atLeast(GangRank.LIEUTENANT)) return "Seuls le chef et les lieutenants posent la fabrique du gang.";
        Territory t = zoneAt(data, dim, pos);
        if (t == null) return "La fabrique se pose dans une zone (territoire) que votre gang contrôle.";
        if (!g.id.equals(t.holder())) return "Votre gang doit CONTRÔLER la zone « " + t.name + " » pour y poser une fabrique.";
        List<Factory> mine = data.factoriesOf(g.id);
        if (mine.size() >= GangConfig.factoryMaxPerGang()) return "Votre gang a déjà " + GangConfig.factoryMaxPerGang() + " fabrique(s).";
        for (Factory f : mine) {
            if (f.dim.equals(dim) && t.contains(dim, BlockPos.of(f.pos).getX(), BlockPos.of(f.pos).getY(), BlockPos.of(f.pos).getZ()))
                return "Votre gang a déjà une fabrique dans la zone « " + t.name + " ».";
        }
        return null;
    }

    /** Enregistre la fabrique qui vient d'être posée. Faux (et le bloc est retiré) si les conditions ne sont plus réunies. */
    public static boolean register(ServerPlayer p, String dim, BlockPos pos) { return registerError(p, dim, pos) == null; }

    /** Enregistre la fabrique posée ; renvoie la raison du refus (en français) ou null si c'est fait. */
    public static String registerError(ServerPlayer p, String dim, BlockPos pos) {
        String why = placementError(p, dim, pos);
        if (why != null) return why;
        GangData data = GangData.get(p.server);
        Gang g = data.gangOf(p.getUUID());
        data.addFactory(new Factory(dim, pos.asLong(), g.id));
        g.addLog(p.getUUID(), "pose une fabrique d'armes", GangConfig.logMax());
        data.setDirty();
        GangService.notifyGang(p.server, g, "§6Fabrique d'armes installée dans la zone. Clic droit dessus pour lancer des fabrications.");
        return null;
    }

    /** Le bloc a disparu (cassé, explosion…) : son stock tombe au sol, la fabrique est effacée. */
    public static void onRemoved(MinecraftServer s, String dim, BlockPos pos, net.minecraft.world.level.Level level) {
        GangData data = GangData.get(s);
        Factory f = data.factory(dim, pos.asLong());
        if (f == null) return;
        dropStock(level, f);
        data.removeFactory(f);
        Gang g = data.gang(f.gang);
        if (g != null) GangService.notifyGang(s, g, "§c§lVotre fabrique d'armes a été détruite !");
    }

    private static void dropStock(net.minecraft.world.level.Level l, Factory f) {
        if (l == null) return;
        BlockPos pos = BlockPos.of(f.pos);
        for (Factory.Lot lot : new ArrayList<>(f.stock)) {
            for (ItemStack st : FactoryItems.stacks(lot.spec(), lot.qty())) {
                l.addFreshEntity(new ItemEntity(l, pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5, st));
            }
        }
        f.stock.clear();
    }

    private static ServerLevel levelOf(MinecraftServer s, String dim) {
        for (ServerLevel l : s.getAllLevels()) if (l.dimension().location().toString().equals(dim)) return l;
        return null;
    }

    /** Le gang est dissous : ses fabriques disparaissent, leur stock tombe au sol. */
    public static void destroyAllOf(MinecraftServer s, Gang g) {
        GangData data = GangData.get(s);
        for (Factory f : data.factoriesOf(g.id)) {
            ServerLevel l = levelOf(s, f.dim);
            dropStock(l, f);
            data.removeFactory(f);
            if (l != null && l.getBlockState(BlockPos.of(f.pos)).getBlock() instanceof FactoryBlock) l.removeBlock(BlockPos.of(f.pos), false);
        }
    }

    // ------------------------------------------------------------------ écran

    private static Factory resolve(ServerPlayer p, long pos) {
        String dim = p.level().dimension().location().toString();
        BlockPos bp = BlockPos.of(pos);
        if (p.distanceToSqr(bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5) > REACH2) return null;
        if (!(p.level().getBlockState(bp).getBlock() instanceof FactoryBlock)) return null;
        return GangData.get(p.server).factory(dim, pos);
    }

    public static void open(ServerPlayer p, String dim, BlockPos pos) {
        Factory f = GangData.get(p.server).factory(dim, pos.asLong());
        if (f == null) {
            p.displayClientMessage(net.minecraft.network.chat.Component.literal("§7Cette fabrique n'est liée à aucun gang."), true);
            return;
        }
        GangView v = GangGui.build(p, true, 0, 0, 0, null, false);
        v.factory = info(p, f);
        fr.minenorth.portes.network.ModNetwork.sendGangView(p, v);
    }

    static GangView.FactoryInfo info(ServerPlayer p, Factory f) {
        GangData data = GangData.get(p.server);
        Gang owner = data.gang(f.gang);
        Gang me = data.gangOf(p.getUUID());
        GangRank rank = me == null ? null : me.rankOf(p.getUUID());
        BlockPos bp = BlockPos.of(f.pos);
        Territory t = zoneAt(data, f.dim, bp);
        boolean held = t != null && f.gang.equals(t.holder());
        boolean enabled = GangConfig.factoryEnabled();
        GangView.FactoryInfo i = new GangView.FactoryInfo();
        i.dim = f.dim; i.pos = f.pos;
        i.gangName = owner == null ? "" : owner.name(); i.gangTag = owner == null ? "" : owner.tag(); i.gangColor = owner == null ? 0x9E9E9E : owner.color();
        i.zone = t == null ? "(hors zone)" : t.name;
        i.mine = me != null && me.id.equals(f.gang);
        i.running = enabled && held && !f.queue.isEmpty();
        i.canSpend = i.mine && GangService.canSpend(me, p.getUUID());
        i.balance = i.mine ? me.balance() : 0;
        i.spendRank = me == null ? "" : GangService.spendRule(me);
        i.canQueue = i.canSpend && enabled && held;
        i.canCollect = i.mine && rank.atLeast(GangConfig.factoryCollectRank());
        Gang holder = t == null ? null : data.gang(t.holder());
        i.canSeize = !i.mine && me != null && holder != null && holder.id.equals(me.id) && rank.atLeast(GangRank.LIEUTENANT)
                && data.factoriesOf(me.id).size() < GangConfig.factoryMaxPerGang() && enabled;
        i.collectRank = GangConfig.factoryCollectRank().label().toLowerCase(java.util.Locale.ROOT);
        i.stockMax = GangConfig.factoryStockMax(); i.queueMax = GangConfig.factoryQueueMax();
        if (!enabled) i.status = "Fabrication désactivée par l'administration.";
        else if (t == null) i.status = "ARRÊTÉE : la fabrique n'est plus dans une zone de gang.";
        else if (!held) i.status = "ARRÊTÉE : la zone « " + t.name + " » n'est plus tenue par " + (owner == null ? "ce gang" : owner.name()) + (holder == null ? "." : " (elle est à " + holder.name() + ").");
        else if (f.queue.isEmpty()) i.status = "En attente : lancez une fabrication.";
        else i.status = "En fabrication.";
        for (Factory.Job j : f.queue) i.queue.add(new GangView.JobInfo(j.label, j.qty, j.totalSeconds, j.remaining));
        for (Factory.Lot l : f.stock) i.stock.add(new GangView.LotInfo(l.label(), l.qty()));
        for (FactoryRecipe r : GangConfig.recipes()) i.recipes.add(new GangView.Recipe(r.label(), "", r.qty(), r.seconds(), r.cents(), r.needs()));
        for (FactoryMaterial m : GangConfig.materials()) i.materials.add(new GangView.MatInfo(m.id(), m.label(), f.materials.getOrDefault(m.id(), 0), GangConfig.materialCap(), m.pack(), m.cents()));
        return i;
    }

    /** Renvoie l'écran de la fabrique à jour avec un message (succès ou erreur). */
    private static void reply(ServerPlayer p, long pos, String err, String ok) {
        GangView v = GangGui.build(p, true, 0, 0, 0, err != null ? err : ok, err != null);
        Factory f = GangData.get(p.server).factory(p.level().dimension().location().toString(), pos);
        if (f != null) v.factory = info(p, f);
        fr.minenorth.portes.network.ModNetwork.sendGangView(p, v);
    }

    // ------------------------------------------------------------------ actions du joueur

    public static void handle(ServerPlayer p, int action, String a, String b, long pos) {
        String err;
        String ok;
        switch (action) {
            case GangGui.FACTORY_QUEUE -> { err = queue(p, pos, a, b); ok = "Fabrication lancée."; }
            case GangGui.FACTORY_CANCEL -> { err = cancel(p, pos, a); ok = "Fabrication annulée."; }
            case GangGui.FACTORY_BUY -> { err = buy(p, pos, a, b); ok = "Achat effectué : la réserve de la fabrique est à jour."; }
            case GangGui.FACTORY_COLLECT -> { err = collect(p, pos); ok = null; if (err == null) return; }
            case GangGui.FACTORY_SEIZE -> { err = seize(p, pos); ok = "Fabrique saisie : elle est à vous."; }
            case GangGui.FACTORY_VIEW -> { err = resolve(p, pos) == null ? "Approchez-vous de la fabrique." : null; ok = null; if (err != null) return; }
            default -> { return; }
        }
        reply(p, pos, err, ok);
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return -1; }
    }

    private static String materialLabel(String id) {
        for (FactoryMaterial m : GangConfig.materials()) if (m.id().equals(id)) return m.label();
        return id;
    }

    static String queue(ServerPlayer p, long pos, String indexText, String label) {
        Factory f = resolve(p, pos);
        if (f == null) return "Approchez-vous de la fabrique.";
        GangData data = GangData.get(p.server);
        Gang me = data.gangOf(p.getUUID());
        if (me == null || !me.id.equals(f.gang)) return "Cette fabrique n'est pas à votre gang.";
        if (!GangService.canSpend(me, p.getUUID())) return "Le chef n'autorise que " + GangService.spendRule(me) + " à lancer une fabrication (elle utilise l'argent du gang).";
        if (!GangConfig.factoryEnabled()) return "La fabrication est désactivée.";
        Territory t = zoneAt(data, f.dim, BlockPos.of(f.pos));
        if (t == null || !f.gang.equals(t.holder())) return "La zone n'est plus tenue par votre gang : la fabrique est arrêtée.";
        if (!GangService.allow(p.getUUID(), "fabrique-queue", 600)) return "Doucement.";
        List<FactoryRecipe> recipes = GangConfig.recipes();
        int idx = parseInt(indexText);
        if (idx < 0 || idx >= recipes.size() || !recipes.get(idx).label().equals(label)) return "Cette recette n'existe plus : rouvrez la fabrique.";
        FactoryRecipe r = recipes.get(idx);
        String bad = FactoryItems.validate(r.spec());
        if (bad != null) return bad;
        if (f.queue.size() >= GangConfig.factoryQueueMax()) return "La file est pleine (" + GangConfig.factoryQueueMax() + " fabrications).";
        if (f.stock.size() + f.queue.size() >= GangConfig.factoryStockMax()) return "Stock plein (" + GangConfig.factoryStockMax() + " lots) : récupérez d'abord ce qui est prêt.";
        java.util.Map<String, Integer> needs = FactoryRecipe.parseNeeds(r.needs());
        if (needs == null) return "Cette recette est mal réglée (besoins illisibles) : prévenez un administrateur.";
        // 1) matières en réserve dans la fabrique ; 2) argent du gang (le tout avant de rien consommer)
        for (var n : needs.entrySet()) {
            int have = f.materials.getOrDefault(n.getKey(), 0);
            if (have < n.getValue()) return "Il manque de la matière « " + materialLabel(n.getKey()) + " » : " + have + "/" + n.getValue() + ". Achetez-en dans l'onglet Achats.";
        }
        String err = GangService.spend(p.server, me, p.getUUID(), r.cents(), "Fabrication : " + r.label(), "gang:fabrique");
        if (err != null) return err;
        for (var n : needs.entrySet()) {
            int left = f.materials.getOrDefault(n.getKey(), 0) - n.getValue();
            if (left > 0) f.materials.put(n.getKey(), left); else f.materials.remove(n.getKey());
        }
        f.queue.add(new Factory.Job(r.label(), r.spec(), r.qty(), r.seconds(), r.seconds(), r.cents(), r.needs()));
        me.addLog(p.getUUID(), "lance la fabrication de " + r.label() + (r.cents() > 0 ? " (" + GangService.money(r.cents()) + ")" : ""), GangConfig.logMax());
        data.setDirty();
        return null;
    }

    /** Achat de matières premières avec l'argent du compte du gang (grade autorisé par le chef seulement). */
    static String buy(ServerPlayer p, long pos, String materialId, String packsText) {
        Factory f = resolve(p, pos);
        if (f == null) return "Approchez-vous de la fabrique.";
        GangData data = GangData.get(p.server);
        Gang me = data.gangOf(p.getUUID());
        if (me == null || !me.id.equals(f.gang)) return "Cette fabrique n'est pas à votre gang.";
        if (!GangService.canSpend(me, p.getUUID())) return "Le chef n'autorise que " + GangService.spendRule(me) + " à dépenser l'argent du gang.";
        if (!GangConfig.factoryEnabled()) return "La fabrique est désactivée.";
        FactoryMaterial mat = null;
        for (FactoryMaterial m : GangConfig.materials()) if (m.id().equals(materialId)) mat = m;
        if (mat == null) return "Cette matière n'est plus vendue : rouvrez la fabrique.";
        int packs = parseInt(packsText);
        if (packs < 1 || packs > 10) return "Quantité d'achats : 1 à 10.";
        long add = (long) mat.pack() * packs;
        int have = f.materials.getOrDefault(mat.id(), 0);
        if (have + add > GangConfig.materialCap()) return "Réserve pleine pour « " + mat.label() + " » (" + have + "/" + GangConfig.materialCap() + ").";
        if (!GangService.allow(p.getUUID(), "fabrique-buy", 500)) return "Doucement.";
        long cost = mat.cents() * packs;
        String err = GangService.spend(p.server, me, p.getUUID(), cost, "Achat : " + add + " x " + mat.label(), "gang:fabrique");
        if (err != null) return err;
        f.materials.put(mat.id(), (int) (have + add));
        me.addLog(p.getUUID(), "achète " + add + " x " + mat.label() + " (" + GangService.money(cost) + ")", GangConfig.logMax());
        data.setDirty();
        return null;
    }

    static String cancel(ServerPlayer p, long pos, String jobText) {
        Factory f = resolve(p, pos);
        if (f == null) return "Approchez-vous de la fabrique.";
        GangData data = GangData.get(p.server);
        Gang me = data.gangOf(p.getUUID());
        if (me == null || !me.id.equals(f.gang) || !GangService.canSpend(me, p.getUUID())) return "Seuls ceux qui peuvent dépenser l'argent du gang annulent une fabrication.";
        int idx = parseInt(jobText);
        if (idx < 0 || idx >= f.queue.size()) return "Cette fabrication n'existe plus.";
        Factory.Job j = f.queue.get(idx);
        boolean untouched = j.remaining >= j.totalSeconds;
        f.queue.remove(idx);
        String msg = "Fabrication annulée.";
        if (untouched) {
            // Pas commencée : matières et argent reviennent à la fabrique et au compte du gang.
            java.util.Map<String, Integer> needs = FactoryRecipe.parseNeeds(j.needs);
            if (needs != null) for (var n : needs.entrySet()) f.materials.merge(n.getKey(), n.getValue(), Integer::sum);
            if (j.cents > 0) GangService.refundToGang(p.server, me, j.cents, p.getUUID(), "Fabrication annulée : " + j.label, "gang:fabrique");
            msg = "Fabrication annulée : matières et argent rendus au gang.";
        }
        me.addLog(p.getUUID(), "annule la fabrication de " + j.label, GangConfig.logMax());
        data.setDirty();
        GangService.tell(p, "§7" + msg);
        return null;
    }

    /** Récupère tous les lots prêts (selon le grade réglé). En cas de succès, renvoie null et l'écran est mis à jour ici. */
    static String collect(ServerPlayer p, long pos) {
        Factory f = resolve(p, pos);
        if (f == null) return "Approchez-vous de la fabrique.";
        GangData data = GangData.get(p.server);
        Gang me = data.gangOf(p.getUUID());
        if (me == null || !me.id.equals(f.gang)) return "Cette fabrique n'est pas à votre gang.";
        GangRank need = GangConfig.factoryCollectRank();
        if (!me.rankOf(p.getUUID()).atLeast(need)) return "Il faut être " + need.label().toLowerCase(java.util.Locale.ROOT) + " au moins pour récupérer le stock.";
        if (!GangService.allow(p.getUUID(), "fabrique-collect", 800)) return "Doucement.";
        if (f.stock.isEmpty()) return "Rien de prêt pour le moment.";
        int lots = 0;
        List<String> names = new ArrayList<>();
        for (Factory.Lot lot : new ArrayList<>(f.stock)) {
            List<ItemStack> stacks = FactoryItems.stacks(lot.spec(), lot.qty());
            if (stacks.isEmpty()) continue; // objet devenu invalide (mod retiré) : le lot reste, rien n'est perdu
            f.stock.remove(lot); // retiré AVANT d'être donné : impossible de le récupérer deux fois
            for (ItemStack st : stacks) {
                if (!p.getInventory().add(st) || !st.isEmpty()) p.drop(st, false);
            }
            lots++;
            names.add(lot.qty() + " x " + lot.label());
        }
        if (lots == 0) return "Les objets du stock sont indisponibles (mod retiré ?).";
        me.addLog(p.getUUID(), "récupère " + String.join(", ", names), GangConfig.logMax());
        data.setDirty();
        reply(p, pos, null, "Récupéré : " + String.join(", ", names));
        return null;
    }

    static String seize(ServerPlayer p, long pos) {
        Factory f = resolve(p, pos);
        if (f == null) return "Approchez-vous de la fabrique.";
        GangData data = GangData.get(p.server);
        Gang me = data.gangOf(p.getUUID());
        if (me == null) return "Il faut être dans un gang.";
        if (me.id.equals(f.gang)) return "Cette fabrique est déjà à votre gang.";
        if (!me.rankOf(p.getUUID()).atLeast(GangRank.LIEUTENANT)) return "Seuls le chef et les lieutenants saisissent une fabrique.";
        Territory t = zoneAt(data, f.dim, BlockPos.of(f.pos));
        if (t == null || !me.id.equals(t.holder())) return "Votre gang doit contrôler la zone pour saisir cette fabrique.";
        if (data.factoriesOf(me.id).size() >= GangConfig.factoryMaxPerGang()) return "Votre gang a déjà " + GangConfig.factoryMaxPerGang() + " fabrique(s) : détruisez-la plutôt.";
        if (!GangConfig.factoryEnabled()) return "La Fabrique d'armes est désactivée.";
        if (!GangService.allow(p.getUUID(), "fabrique-seize", 1500)) return "Doucement.";
        Gang old = data.gang(f.gang);
        f.queue.clear(); // les fabrications de l'ancien propriétaire sont perdues, le stock déjà prêt est pour le nouveau
        f.gang = me.id;
        data.setDirty();
        me.addLog(p.getUUID(), "saisit la fabrique de " + (old == null ? "?" : old.name()), GangConfig.logMax());
        if (old != null) GangService.notifyGang(p.server, old, "§c§lVotre fabrique d'armes a été saisie par " + me.label() + " !");
        GangService.notifyGang(p.server, me, "§6Fabrique d'armes saisie dans la zone « " + t.name + " » !");
        return null;
    }

    // ------------------------------------------------------------------ réglages en jeu (OP)

    /** a = « actif;maxParGang;stockMax;fileMax;gradeRécupération;réserveMaxParMatière ». Tout est vérifié avant d'appliquer. */
    public static String adminGlobal(String a) {
        String[] p = a.split(";", -1);
        if (p.length != 6) return "Réglages incomplets.";
        long max, stock, queue, mat;
        try {
            max = Long.parseLong(p[1].trim()); stock = Long.parseLong(p[2].trim()); queue = Long.parseLong(p[3].trim()); mat = Long.parseLong(p[5].trim());
        } catch (NumberFormatException e) {
            return "Entrez des nombres entiers.";
        }
        GangRank rank = GangRank.parse(p[4]);
        if (rank == null) return "Grade inconnu (membre, lieutenant ou chef).";
        if (max < 1 || max > 10) return "Fabriques par gang : 1 à 10.";
        if (stock < 1 || stock > 100) return "Stock maximum : 1 à 100 lots.";
        if (queue < 1 || queue > 20) return "File d'attente : 1 à 20.";
        if (mat < 1 || mat > 100_000) return "Réserve par matière : 1 à 100 000.";
        GangConfig.setFactoryEnabled("1".equals(p[0].trim()));
        GangConfig.setFactoryMaxPerGang(max);
        GangConfig.setFactoryStockMax(stock);
        GangConfig.setFactoryQueueMax(queue);
        GangConfig.setFactoryCollectRank(rank);
        GangConfig.setMaterialCap(mat);
        return null;
    }

    /** a = numéro de recette (-1 = nouvelle), b = « Libellé | objet | quantité | secondes | euros | besoins ». */
    public static String adminRecipe(String a, String b) {
        int idx;
        try { idx = Integer.parseInt(a.trim()); } catch (NumberFormatException e) { return "Recette inconnue."; }
        String[] f = b.split("\\|", -1);
        if (f.length != 6) return "Format : Libellé | objet | quantité | secondes | euros | besoins.";
        long qty, sec, cents;
        try {
            qty = Long.parseLong(f[2].trim());
            sec = Long.parseLong(f[3].trim());
            cents = GangRules.parseEuros(f[4], FactoryRecipe.CENTS_MAX);
        } catch (NumberFormatException e) {
            return "Quantité et durée : des nombres entiers.";
        }
        if (cents < 0) return "Prix invalide : un nombre d'euros positif (ex. 25 ou 12,50), 100 000 € au plus.";
        String needs = f[5].trim();
        String err = FactoryRecipe.check(f[0], f[1], qty, sec, cents, needs);
        if (err != null) return err;
        err = FactoryItems.validate(f[1].trim());
        if (err != null) return err;
        java.util.Set<String> known = new java.util.HashSet<>();
        for (FactoryMaterial m : GangConfig.materials()) known.add(m.id());
        for (String id : FactoryRecipe.parseNeeds(needs).keySet()) if (!known.contains(id)) return "Matière inconnue : « " + id + " » (créez-la dans l'onglet Matières).";
        List<FactoryRecipe> list = new ArrayList<>(GangConfig.recipes());
        FactoryRecipe r = new FactoryRecipe(f[0].trim(), f[1].trim(), (int) qty, (int) sec, cents, needs);
        if (idx == -1) {
            if (list.size() >= RECIPES_MAX) return "Trop de recettes (" + RECIPES_MAX + " au plus).";
            for (FactoryRecipe o : list) if (o.label().equalsIgnoreCase(r.label())) return "Une recette porte déjà ce libellé.";
            list.add(r);
        } else {
            if (idx < 0 || idx >= list.size()) return "Recette inconnue.";
            for (int i = 0; i < list.size(); i++) if (i != idx && list.get(i).label().equalsIgnoreCase(r.label())) return "Une recette porte déjà ce libellé.";
            list.set(idx, r);
        }
        GangConfig.setRecipes(list);
        return null;
    }

    public static String adminDelete(String a) {
        int idx;
        try { idx = Integer.parseInt(a.trim()); } catch (NumberFormatException e) { return "Recette inconnue."; }
        List<FactoryRecipe> list = new ArrayList<>(GangConfig.recipes());
        if (idx < 0 || idx >= list.size()) return "Recette inconnue.";
        list.remove(idx);
        GangConfig.setRecipes(list);
        return null;
    }

    /** a = numéro de matière (-1 = nouvelle), b = « Libellé | identifiant | quantité par achat | euros ». */
    public static String adminMaterial(String a, String b) {
        int idx;
        try { idx = Integer.parseInt(a.trim()); } catch (NumberFormatException e) { return "Matière inconnue."; }
        String[] f = b.split("\\|", -1);
        if (f.length != 4) return "Format : Libellé | identifiant | quantité par achat | euros.";
        long pack, cents;
        try {
            pack = Long.parseLong(f[2].trim());
            cents = GangRules.parseEuros(f[3], FactoryMaterial.CENTS_MAX);
        } catch (NumberFormatException e) {
            return "Quantité : un nombre entier.";
        }
        if (cents < 0) return "Prix invalide : un nombre d'euros positif (ex. 30 ou 12,50).";
        String err = FactoryMaterial.check(f[0], f[1], pack, cents);
        if (err != null) return err;
        List<FactoryMaterial> list = new ArrayList<>(GangConfig.materials());
        FactoryMaterial m = new FactoryMaterial(f[0].trim(), f[1].trim(), (int) pack, cents);
        if (idx == -1) {
            if (list.size() >= 20) return "Trop de matières (20 au plus).";
            for (FactoryMaterial o : list) if (o.id().equals(m.id()) || o.label().equalsIgnoreCase(m.label())) return "Une matière porte déjà ce libellé ou cet identifiant.";
            list.add(m);
        } else {
            if (idx < 0 || idx >= list.size()) return "Matière inconnue.";
            FactoryMaterial old = list.get(idx);
            for (int i = 0; i < list.size(); i++) if (i != idx && (list.get(i).id().equals(m.id()) || list.get(i).label().equalsIgnoreCase(m.label()))) return "Une matière porte déjà ce libellé ou cet identifiant.";
            if (!old.id().equals(m.id())) return "L'identifiant d'une matière ne change pas (des recettes et des réserves l'utilisent) : créez-en une nouvelle.";
            list.set(idx, m);
        }
        GangConfig.setMaterials(list);
        return null;
    }

    public static String adminMaterialDelete(String a) {
        int idx;
        try { idx = Integer.parseInt(a.trim()); } catch (NumberFormatException e) { return "Matière inconnue."; }
        List<FactoryMaterial> list = new ArrayList<>(GangConfig.materials());
        if (idx < 0 || idx >= list.size()) return "Matière inconnue.";
        String id = list.get(idx).id();
        for (FactoryRecipe r : GangConfig.recipes()) {
            var needs = FactoryRecipe.parseNeeds(r.needs());
            if (needs != null && needs.containsKey(id)) return "La recette « " + r.label() + " » utilise cette matière : modifiez-la d'abord.";
        }
        list.remove(idx);
        GangConfig.setMaterials(list);
        return null;
    }

    // ------------------------------------------------------------------ boucle (1 fois par seconde)

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.getServer().getTickCount() % 20 != 0) return;
        MinecraftServer s = e.getServer();
        GangData data = GangData.get(s);
        if (data.factories().isEmpty()) return;
        boolean enabled = GangConfig.factoryEnabled();
        long second = s.getTickCount() / 20;
        for (Factory f : new ArrayList<>(data.factories())) {
            Gang g = data.gang(f.gang);
            if (g == null) continue;
            BlockPos bp = BlockPos.of(f.pos);
            Territory t = zoneAt(data, f.dim, bp);
            boolean held = t != null && f.gang.equals(t.holder());
            boolean working = enabled && held && !f.queue.isEmpty();
            if (working) {
                Factory.Job j = f.queue.get(0);
                if (--j.remaining <= 0) {
                    f.queue.remove(0);
                    f.stock.add(new Factory.Lot(j.label, j.spec, j.qty));
                    GangService.notifyGang(s, g, "§aFabrique : §f" + j.qty + " x " + j.label + " §aprêt(s) à récupérer.");
                }
                data.setDirty();
            }
            // Voyant : clignote tant que la fabrique produit.
            boolean lit = working && (second % 3 != 2);
            if (lit != f.lit) {
                ServerLevel l = levelOf(s, f.dim);
                if (l != null && l.isLoaded(bp)) {
                    BlockState st = l.getBlockState(bp);
                    if (st.getBlock() instanceof FactoryBlock && st.getValue(FactoryBlock.LIT) != lit) l.setBlock(bp, st.setValue(FactoryBlock.LIT, lit), 3);
                    f.lit = lit;
                }
            }
        }
    }

    /** La zone est attaquée (capture en cours) : prévient le gang propriétaire des fabriques qui s'y trouvent (au plus toutes les 30 s). */
    public static void threat(MinecraftServer s, Territory t) {
        if (t.holder() == null) return;
        GangData data = GangData.get(s);
        long now = System.currentTimeMillis();
        Gang g = data.gang(t.holder());
        if (g == null) return;
        for (Factory f : data.factoriesOf(g.id)) {
            BlockPos bp = BlockPos.of(f.pos);
            if (!t.contains(f.dim, bp.getX(), bp.getY(), bp.getZ()) || now - f.lastAlert < ALERT_MS) continue;
            f.lastAlert = now;
            GangService.notifyGang(s, g, "§c§l⚠ Votre fabrique d'armes de « " + t.name + " » est menacée : la zone est attaquée !");
        }
    }

    /** Une zone vient de changer de main : les fabriques des autres gangs qui s'y trouvent s'arrêtent et peuvent être saisies. */
    public static void onCapture(MinecraftServer s, Territory t, Gang newHolder) {
        GangData data = GangData.get(s);
        boolean enemy = false;
        for (Factory f : data.factories()) {
            BlockPos bp = BlockPos.of(f.pos);
            if (!t.contains(f.dim, bp.getX(), bp.getY(), bp.getZ()) || f.gang.equals(newHolder.id)) continue;
            enemy = true;
            Gang old = data.gang(f.gang);
            if (old != null) GangService.notifyGang(s, old, "§c§lVotre fabrique d'armes de « " + t.name + " » est arrêtée : la zone est perdue !");
        }
        if (enemy) GangService.notifyGang(s, newHolder, "§6Une fabrique ennemie se trouve dans votre nouvelle zone : clic droit dessus pour la saisir, ou cassez-la.");
    }
}
