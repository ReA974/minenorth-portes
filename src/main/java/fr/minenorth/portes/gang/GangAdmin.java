package fr.minenorth.portes.gang;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Gestion admin des gangs pour le panneau MineNorth Admin (appelée par réflexion via GangAdminApi). Aucun contrôle de droit ici : l'appelant (panneau) vérifie
 * que l'auteur est du staff, mais TOUT le reste est vérifié ici (valeurs bornées, existence, hiérarchie). L'objet du catalogue vient de l'inventaire RÉEL de l'admin.
 * Formats de lignes : champs séparés par une tabulation.
 */
public final class GangAdmin {
    private GangAdmin() {}

    static final int CATALOGUE_MAX = 60;

    private static String clean(String s) { return s == null ? "" : s.replaceAll("[\\t\\r\\n\\p{Cntrl}]", " ").trim(); }

    private static String ok(String msg) { return "OK\t" + msg; }
    private static String ko(String msg) { return "KO\t" + msg; }

    private static UUID uuid(String s) {
        try { return s == null || s.isBlank() ? null : UUID.fromString(s.trim()); } catch (IllegalArgumentException e) { return null; }
    }

    private static Gang gang(MinecraftServer s, String id) {
        UUID u = uuid(id);
        return u == null ? null : GangData.get(s).gang(u);
    }

    private static long parse(String s) {
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return -1; }
    }

    // ------------------------------------------------------------------ lectures

    public static List<String> gangs(MinecraftServer s) {
        GangData d = GangData.get(s);
        List<String> out = new ArrayList<>();
        for (Gang g : d.gangs()) {
            long online = g.members().keySet().stream().filter(m -> s.getPlayerList().getPlayer(m) != null).count();
            int zones = 0;
            for (Territory t : d.territories()) if (g.id.equals(t.holder())) zones++;
            out.add(String.join("\t", g.id.toString(), clean(g.name()), g.tag(), Integer.toString(g.color()), Integer.toString(g.members().size()), Long.toString(online),
                    Long.toString(g.balance()), Integer.toString(zones), Integer.toString(d.factoriesOf(g.id).size()), Integer.toString(g.incomePct())));
        }
        out.sort((a, b) -> a.split("\t")[1].compareToIgnoreCase(b.split("\t")[1]));
        return out;
    }

    public static List<String> detail(MinecraftServer s, String gangId) {
        List<String> out = new ArrayList<>();
        Gang g = gang(s, gangId);
        if (g == null) return out;
        GangData d = GangData.get(s);
        out.add(String.join("\t", "G", g.id.toString(), clean(g.name()), g.tag(), Integer.toString(g.color()), Long.toString(g.balance()), Integer.toString(g.incomePct()),
                Integer.toString(g.spendRank().level()), Integer.toString(g.members().size()), g.hasBase() ? "1" : "0"));
        for (var e : g.members().entrySet()) {
            out.add(String.join("\t", "M", e.getKey().toString(), clean(GangService.nameOf(s, e.getKey())), Integer.toString(e.getValue().level()),
                    s.getPlayerList().getPlayer(e.getKey()) != null ? "1" : "0"));
        }
        long now = System.currentTimeMillis();
        for (Territory t : d.territories()) {
            if (g.id.equals(t.holder())) out.add(String.join("\t", "T", clean(t.name), clean(TerritoryManager.status(d, t, now)), Long.toString(t.effectiveIncome())));
        }
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM HH:mm");
        List<Gang.Tx> txs = g.txs();
        for (int i = txs.size() - 1, n = 0; i >= 0 && n < 30; i--, n++) {
            Gang.Tx x = txs.get(i);
            out.add(String.join("\t", "X", fmt.format(new Date(x.timeMs())), x.actor() == null ? "Automatique" : clean(GangService.nameOf(s, x.actor())), Long.toString(x.delta()), clean(x.label())));
        }
        for (Factory f : d.factoriesOf(g.id)) {
            BlockPos p = BlockPos.of(f.pos);
            Territory t = FactoryManager.zoneAt(d, f.dim, p);
            int mats = 0;
            for (int v : f.materials().values()) mats += v;
            out.add(String.join("\t", "F", f.dim, Integer.toString(p.getX()), Integer.toString(p.getY()), Integer.toString(p.getZ()), t == null ? "(hors zone)" : clean(t.name),
                    Integer.toString(f.stock().size()), Integer.toString(f.queue().size()), Integer.toString(mats)));
        }
        return out;
    }

    public static List<String> territories(MinecraftServer s) {
        GangData d = GangData.get(s);
        long now = System.currentTimeMillis();
        List<String> out = new ArrayList<>();
        for (Territory t : d.territories()) {
            Gang h = d.gang(t.holder());
            out.add(String.join("\t", clean(t.name), t.dim, Integer.toString(t.centerX()), Integer.toString(t.centerY()), Integer.toString(t.centerZ()),
                    h == null ? "" : h.id.toString(), h == null ? "" : h.tag(), h == null ? "" : clean(h.name()), Long.toString(t.effectiveIncome()), t.customIncome() ? "1" : "0",
                    clean(TerritoryManager.status(d, t, now))));
        }
        return out;
    }

    /** « numéro TAB libellé TAB objet TAB quantité TAB secondes TAB prix en centimes TAB besoins TAB active TAB limite par jour ». */
    public static List<String> catalog(MinecraftServer s) {
        List<String> out = new ArrayList<>();
        List<FactoryRecipe> all = GangConfig.recipes();
        for (int i = 0; i < all.size(); i++) {
            FactoryRecipe r = all.get(i);
            out.add(String.join("\t", Integer.toString(i), r.label(), r.spec(), Integer.toString(r.qty()), Integer.toString(r.seconds()), Long.toString(r.cents()), r.needs(),
                    r.enabled() ? "1" : "0", Integer.toString(r.daily())));
        }
        return out;
    }

    /** Matières premières : « libellé TAB identifiant TAB quantité par achat TAB prix en centimes ». */
    public static List<String> materials(MinecraftServer s) {
        List<String> out = new ArrayList<>();
        for (FactoryMaterial m : GangConfig.materials()) out.add(String.join("\t", m.label(), m.id(), Integer.toString(m.pack()), Long.toString(m.cents())));
        return out;
    }

    // ------------------------------------------------------------------ actions

    /** Action admin ; renvoie « OK » ou « KO » + TAB + message. actor = l'admin (position et inventaire réels). */
    public static String act(MinecraftServer s, ServerPlayer actor, String action, String a, String b, long n) {
        try {
            String r = run(s, actor, action == null ? "" : action, a == null ? "" : a, b == null ? "" : b, n);
            return r == null ? ko("Action inconnue.") : r;
        } catch (RuntimeException e) {
            return ko("Erreur : " + e.getClass().getSimpleName());
        }
    }

    private static String run(MinecraftServer s, ServerPlayer actor, String action, String a, String b, long n) {
        GangData d = GangData.get(s);
        switch (action) {
            case "gang.create" -> {
                String[] p = b.split("\\|", -1);
                if (p.length != 3) return ko("Format : tag | couleur | chef.");
                String name = GangRules.cleanName(a), tag = GangRules.cleanTag(p[0]);
                int color = p[1].isBlank() ? 0xE53935 : GangRules.parseColor(p[1]);
                UUID chef = uuid(p[2]);
                if (name == null) return ko("Nom invalide : " + GangRules.NAME_MIN + " à " + GangRules.NAME_MAX + " caractères.");
                if (tag == null) return ko("Tag invalide : " + GangRules.TAG_MIN + " à " + GangRules.TAG_MAX + " lettres ou chiffres.");
                if (color < 0) return ko("Couleur inconnue.");
                if (chef == null) return ko("Chef introuvable.");
                if (d.gangOf(chef) != null) return ko("Ce joueur est déjà dans un gang.");
                if (d.nameOrTagTaken(name, tag)) return ko("Ce nom ou ce tag est déjà pris.");
                Gang g = new Gang(UUID.randomUUID(), name, tag, color, System.currentTimeMillis());
                g.members.put(chef, GangRank.CHEF);
                d.add(g);
                GangService.afterMembersChanged(s);
                return ok("Gang « " + name + " » créé (chef : " + GangService.nameOf(s, chef) + ").");
            }
            case "gang.dissolve" -> {
                Gang g = gang(s, a);
                if (g == null) return ko("Gang introuvable.");
                if (n != 1) return ko("Confirmation manquante.");
                if (actor == null) return ko("Auteur introuvable.");
                String name = g.name();
                String err = GangService.adminDissolve(actor, g.tag(), true);
                return err != null ? ko(err) : ok("Gang « " + name + " » dissous.");
            }
            case "gang.setChef" -> {
                Gang g = gang(s, a);
                UUID t = uuid(b);
                if (g == null) return ko("Gang introuvable.");
                if (t == null || !g.isMember(t)) return ko("Ce joueur n'est pas membre du gang.");
                UUID old = g.chef();
                if (t.equals(old)) return ko("Il est déjà chef.");
                if (old != null) g.members.put(old, GangRank.LIEUTENANT);
                g.members.put(t, GangRank.CHEF);
                d.setDirty();
                GangService.notifyGang(s, g, "§6" + GangService.nameOf(s, t) + " devient chef du gang (décision de l'administration).");
                GangService.afterMembersChanged(s);
                return ok(GangService.nameOf(s, t) + " est maintenant chef.");
            }
            case "gang.setRank" -> {
                Gang g = gang(s, a);
                String[] p = b.split("\\|", -1);
                if (g == null || p.length != 2) return ko("Demande invalide.");
                UUID t = uuid(p[0]);
                GangRank r = GangRank.parse(p[1]);
                if (t == null || !g.isMember(t)) return ko("Ce joueur n'est pas membre du gang.");
                if (r == null || r == GangRank.CHEF) return ko("Grade : lieutenant ou membre (pour le chef, utilisez « Changer de chef »).");
                if (g.rankOf(t) == GangRank.CHEF) return ko("C'est le chef : changez d'abord de chef.");
                g.members.put(t, r);
                d.setDirty();
                GangService.afterMembersChanged(s);
                return ok(GangService.nameOf(s, t) + " est maintenant " + r.label().toLowerCase(Locale.ROOT) + ".");
            }
            case "gang.addMember" -> {
                Gang g = gang(s, a);
                UUID t = uuid(b);
                if (g == null) return ko("Gang introuvable.");
                if (t == null) return ko("Joueur introuvable.");
                if (d.gangOf(t) != null) return ko("Ce joueur est déjà dans un gang.");
                if (g.members.size() >= GangConfig.membersMax()) return ko("Le gang est complet (" + GangConfig.membersMax() + ").");
                d.join(g, t, GangRank.MEMBRE);
                GangService.notifyGang(s, g, "§e" + GangService.nameOf(s, t) + " rejoint le gang (administration).");
                GangService.afterMembersChanged(s);
                return ok(GangService.nameOf(s, t) + " ajouté au gang.");
            }
            case "gang.removeMember" -> {
                Gang g = gang(s, a);
                UUID t = uuid(b);
                if (g == null) return ko("Gang introuvable.");
                if (t == null || !g.isMember(t)) return ko("Ce joueur n'est pas membre du gang.");
                if (g.rankOf(t) == GangRank.CHEF) return ko("C'est le chef : changez de chef (ou dissolvez le gang) d'abord.");
                String name = GangService.nameOf(s, t);
                d.leave(g, t, false);
                ServerPlayer tp = s.getPlayerList().getPlayer(t);
                if (tp != null) GangService.tell(tp, "§cVous avez été retiré de " + g.label() + "§c par l'administration.");
                GangService.notifyGang(s, g, "§e" + name + " a été retiré du gang (administration).");
                GangService.afterMembersChanged(s);
                return ok(name + " retiré du gang.");
            }
            case "gang.credit", "gang.debit" -> {
                Gang g = gang(s, a);
                if (g == null) return ko("Gang introuvable.");
                boolean credit = action.equals("gang.credit");
                if (n < 1 || n > 10_000_000_000L) return ko("Montant : de 0,01 € à 100 000 000 €.");
                String motif = clean(b);
                if (motif.length() > 60) motif = motif.substring(0, 60);
                String label = "Ajustement admin" + (motif.isEmpty() ? "" : " : " + motif);
                UUID who = actor == null ? null : actor.getUUID();
                if (credit) {
                    if (g.balance + n > GangService.BALANCE_MAX) return ko("Le compte du gang serait plein.");
                    g.balance += n;
                    g.addTx(who, n, label);
                } else {
                    if (g.balance < n) return ko("Le compte n'a que " + GangService.money(g.balance) + ".");
                    g.balance -= n;
                    g.addTx(who, -n, label);
                }
                d.setDirty();
                return ok((credit ? "Crédité de " : "Débité de ") + GangService.money(n) + " : nouveau solde " + GangService.money(g.balance) + ".");
            }
            case "gang.incomePct" -> {
                Gang g = gang(s, a);
                if (g == null) return ko("Gang introuvable.");
                if (n < 0 || n > 300) return ko("Part des revenus : 0 à 300 %.");
                g.incomePct = (int) n;
                d.setDirty();
                return ok("Part des revenus de zones de « " + g.name() + " » : " + n + " %.");
            }
            case "terr.create" -> {
                if (actor == null) return ko("Auteur introuvable.");
                if (n < 2 || n > 200) return ko("Rayon : 2 à 200 blocs.");
                BlockPos o = actor.blockPosition();
                int r = (int) n;
                String err = TerritoryManager.create(s, a, actor.level().dimension().location().toString(), o.getX() - r, actor.level().getMinBuildHeight(), o.getZ() - r,
                        o.getX() + r, actor.level().getMaxBuildHeight() - 1, o.getZ() + r);
                return err != null ? ko(err) : ok("Territoire créé autour de vous (rayon " + r + ").");
            }
            case "terr.delete" -> {
                String err = TerritoryManager.delete(s, a);
                return err != null ? ko(err) : ok("Territoire supprimé.");
            }
            case "terr.free" -> {
                String err = TerritoryManager.free(s, a);
                return err != null ? ko(err) : ok("Territoire libéré.");
            }
            case "terr.assign" -> {
                Territory t = d.territory(a);
                Gang g = gang(s, b);
                if (t == null) return ko("Territoire introuvable.");
                if (g == null) return ko("Gang introuvable.");
                t.holder = g.id;
                t.protectedUntil = 0;
                d.setDirty();
                TerritoryManager.requestMapRefresh();
                GangService.notifyGang(s, g, "§6Zone « " + t.name + " » attribuée au gang par l'administration.");
                return ok("« " + t.name + " » appartient maintenant à " + g.name() + ".");
            }
            case "terr.income" -> {
                long cents = GangRules.parseEuros(b, Territory.MAX_INCOME);
                if (cents == -2) return ko("Montant invalide : un nombre d'euros positif, ou « défaut ».");
                String err = TerritoryManager.setIncome(s, a, cents);
                return err != null ? ko(err) : ok(cents == -1 ? "Revenu par défaut." : "Revenu de la zone : " + GangService.money(cents) + ".");
            }
            case "factory.clear" -> {
                Gang g = gang(s, a);
                if (g == null) return ko("Gang introuvable.");
                if (n != 1) return ko("Confirmation manquante.");
                int c = FactoryManager.adminClear(s, g);
                return c == 0 ? ko("Ce gang n'a pas de fabrique.") : ok(c + " fabrique(s) vidée(s) : le stock prêt est tombé au sol, la file et les matières sont effacées.");
            }
            case "cat.addSlot" -> {
                if (actor == null) return ko("Auteur introuvable.");
                return addFromInventory(actor, (int) n);
            }
            case "cat.set" -> {
                return catalogSet(a, b);
            }
            case "cat.remove" -> {
                if (n != 1) return ko("Confirmation manquante.");
                List<FactoryRecipe> list = new ArrayList<>(GangConfig.recipes());
                int idx = (int) parse(a);
                if (idx < 0 || idx >= list.size()) return ko("Entrée inconnue.");
                String label = list.remove(idx).label();
                GangConfig.setRecipes(list);
                return ok("« " + label + " » retiré du catalogue.");
            }
            case "cat.move" -> {
                List<FactoryRecipe> list = new ArrayList<>(GangConfig.recipes());
                int idx = (int) parse(a), to = idx + (n < 0 ? -1 : 1);
                if (idx < 0 || idx >= list.size() || to < 0 || to >= list.size()) return ko("Déplacement impossible.");
                FactoryRecipe r = list.remove(idx);
                list.add(to, r);
                GangConfig.setRecipes(list);
                return ok("Entrée déplacée.");
            }
            default -> {
                return null;
            }
        }
    }

    /** Ajoute au catalogue l'objet qui est RÉELLEMENT dans la case d'inventaire de l'admin (le client n'envoie que le numéro de case). */
    private static String addFromInventory(ServerPlayer admin, int slot) {
        if (slot < 0 || slot > 35) return ko("Case d'inventaire invalide.");
        ItemStack st = admin.getInventory().getItem(slot);
        if (st.isEmpty()) return ko("Cette case est vide.");
        List<FactoryRecipe> list = new ArrayList<>(GangConfig.recipes());
        if (list.size() >= CATALOGUE_MAX) return ko("Catalogue plein (" + CATALOGUE_MAX + " entrées).");
        var key = BuiltInRegistries.ITEM.getKey(st.getItem());
        String spec = key.toString();
        CompoundTag tag = st.getTag();
        if (tag != null && !tag.isEmpty()) spec += tag.toString();
        if (spec.length() > FactoryRecipe.SPEC_MAX) return ko("Les données de cet objet sont trop longues pour le catalogue.");
        String label = clean(st.getHoverName().getString()).replace("|", " ").replace("\"", "").replace("#", "");
        if (label.length() < 2) label = key.getPath();
        if (label.length() > FactoryRecipe.LABEL_MAX) label = label.substring(0, FactoryRecipe.LABEL_MAX).trim();
        final String base = label;
        String unique = base;
        int k = 2;
        while (true) {
            final String cand = unique;
            if (list.stream().noneMatch(r -> r.label().equalsIgnoreCase(cand))) break;
            String suffix = " (" + k++ + ")";
            unique = (base.length() + suffix.length() > FactoryRecipe.LABEL_MAX ? base.substring(0, FactoryRecipe.LABEL_MAX - suffix.length()) : base) + suffix;
        }
        FactoryRecipe r = new FactoryRecipe(unique, spec, 1, 60, 0, "", true, 0);
        String err = FactoryRecipe.check(r.label(), r.spec(), r.qty(), r.seconds(), r.cents(), r.needs());
        if (err != null) return ko(err);
        err = FactoryItems.validate(spec);
        if (err != null) return ko(err);
        list.add(r);
        GangConfig.setRecipes(list);
        return ok("« " + unique + " » ajouté au catalogue (données conservées) : réglez maintenant son prix, ses besoins et sa durée.");
    }

    /** a = numéro, b = « libellé | quantité | secondes | euros | besoins | limite par jour | active (1/0) ». L'objet ne change pas (on retire et on rajoute). */
    private static String catalogSet(String a, String b) {
        String[] f = b.split("\\|", -1);
        if (f.length != 7) return ko("Format : libellé | quantité | secondes | euros | besoins | limite par jour | active.");
        List<FactoryRecipe> list = new ArrayList<>(GangConfig.recipes());
        int idx = (int) parse(a);
        if (idx < 0 || idx >= list.size()) return ko("Entrée inconnue.");
        long qty = parse(f[1]), sec = parse(f[2]), daily = parse(f[5]);
        long cents = GangRules.parseEuros(f[3], FactoryRecipe.CENTS_MAX);
        if (cents < 0) return ko("Prix invalide : un nombre d'euros positif (ex. 25 ou 12,50), 100 000 € au plus.");
        if (daily < 0 || daily > 1000) return ko("Limite par jour : 0 (aucune) à 1000.");
        String needs = f[4].trim();
        FactoryRecipe old = list.get(idx);
        String err = FactoryRecipe.check(f[0], old.spec(), qty, sec, cents, needs);
        if (err != null) return ko(err);
        java.util.Set<String> known = new java.util.HashSet<>();
        for (FactoryMaterial m : GangConfig.materials()) known.add(m.id());
        for (String id : FactoryRecipe.parseNeeds(needs).keySet()) if (!known.contains(id)) return ko("Matière inconnue : « " + id + " ».");
        for (int i = 0; i < list.size(); i++) if (i != idx && list.get(i).label().equalsIgnoreCase(f[0].trim())) return ko("Une entrée porte déjà ce libellé.");
        list.set(idx, new FactoryRecipe(f[0].trim(), old.spec(), (int) qty, (int) sec, cents, needs, "1".equals(f[6].trim()), (int) daily));
        GangConfig.setRecipes(list);
        return ok("Entrée enregistrée.");
    }
}
