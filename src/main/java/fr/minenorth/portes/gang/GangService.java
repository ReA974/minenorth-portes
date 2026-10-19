package fr.minenorth.portes.gang;

import fr.minenorth.api.MineNorth;
import fr.minenorth.api.PayResult;
import fr.minenorth.portes.network.ModNetwork;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/**
 * Logique des gangs, côté serveur uniquement. Chaque méthode vérifie TOUT (grade, limites, état) : le client n'est jamais cru.
 * Les méthodes qui peuvent échouer renvoient le message d'erreur (null = succès).
 */
public final class GangService {
    private GangService() {}

    private record Invite(UUID gang, UUID inviter, long expiryMs) {}

    /** invité -> (gang -> invitation). Mémoire seulement : une invitation ne survit pas au redémarrage. */
    private static final Map<UUID, Map<UUID, Invite>> INVITES = new HashMap<>();
    private static final Map<String, Long> COOLDOWNS = new HashMap<>();
    private static final int[] PALETTE = {0xE53935, 0x1E88E5, 0x43A047, 0xFDD835, 0xFB8C00, 0x8E24AA, 0xEC407A, 0x00ACC1};

    // ------------------------------------------------------------------ utilitaires

    /** Limiteur de cadence par joueur et par action : vrai si l'action est permise maintenant. */
    public static boolean allow(UUID player, String action, long minGapMs) {
        long now = System.currentTimeMillis();
        String k = player + "|" + action;
        Long last = COOLDOWNS.get(k);
        if (last != null && now - last < minGapMs) return false;
        COOLDOWNS.put(k, now);
        if (COOLDOWNS.size() > 4000) COOLDOWNS.values().removeIf(t -> now - t > 60_000L);
        return true;
    }

    public static void tell(ServerPlayer p, String text) { p.sendSystemMessage(Component.literal(text)); }

    public static void notifyGang(MinecraftServer s, Gang g, String text) {
        for (UUID m : g.members.keySet()) {
            ServerPlayer sp = s.getPlayerList().getPlayer(m);
            if (sp != null) tell(sp, "§8[" + "§r" + g.label() + "§8] §r" + text);
        }
    }

    /** Membre du gang désigné par un nom (pseudo ou nom RP). Seuls les membres sont cherchés. */
    public static UUID memberByName(MinecraftServer s, Gang g, String query) {
        if (query == null || query.isBlank()) return null;
        for (UUID m : g.members.keySet()) {
            if (GangRules.sameName(MineNorth.pseudo(s, m), query) || GangRules.sameName(MineNorth.displayName(s, m), query)) return m;
        }
        return null;
    }

    public static String nameOf(MinecraftServer s, UUID id) { return MineNorth.displayName(s, id); }

    private static void membershipChanged(MinecraftServer s, Gang ignored) {
        // Les portes du gang se recalculent tout de suite (un membre exclu ne doit pas rester une seconde de plus),
        // et tout coffre ouvert par quelqu'un qui n'a plus le droit se referme.
        GangData data = GangData.get(s);
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (p.containerMenu instanceof GangVaultMenu m) {
                Gang g = data.gang(m.gangId());
                if (g == null || !g.isMember(p.getUUID())) p.closeContainer();
            }
        }
        ModNetwork.syncDoorLocks(s);
        TerritoryManager.requestMapRefresh();
    }

    // ------------------------------------------------------------------ création / invitations

    public static String create(ServerPlayer p, String nameRaw, String tagRaw, String colorRaw) {
        MinecraftServer s = p.server;
        GangData data = GangData.get(s);
        if (!allow(p.getUUID(), "creer", 3000)) return "Doucement, réessayez dans quelques secondes.";
        if (data.gangOf(p.getUUID()) != null) return "Vous êtes déjà dans un gang. Quittez-le d'abord (/gang quitter).";
        long wait = data.leftAt(p.getUUID()) + GangConfig.leaveDelayMs() - System.currentTimeMillis();
        if (wait > 0) return "Vous venez de quitter un gang : patientez encore " + (wait / 60000 + 1) + " min.";
        String name = GangRules.cleanName(nameRaw);
        if (name == null) return "Nom invalide : " + GangRules.NAME_MIN + " à " + GangRules.NAME_MAX + " caractères (lettres, chiffres, espace, tiret).";
        String tag = GangRules.cleanTag(tagRaw);
        if (tag == null) return "Tag invalide : " + GangRules.TAG_MIN + " à " + GangRules.TAG_MAX + " lettres ou chiffres.";
        int color = colorRaw == null || colorRaw.isBlank() ? PALETTE[Math.floorMod(name.toLowerCase(Locale.ROOT).hashCode(), PALETTE.length)] : GangRules.parseColor(colorRaw);
        if (color < 0) return "Couleur inconnue : essayez rouge, bleu, vert, jaune, orange, violet, rose, cyan, blanc, noir, gris, marron ou #RRGGBB.";
        if (data.nameOrTagTaken(name, tag)) return "Ce nom ou ce tag est déjà pris par un autre gang.";
        int minOnline = GangConfig.creationMinOnline();
        if (s.getPlayerList().getPlayerCount() < minOnline) return "Il faut au moins " + minOnline + " joueurs connectés pour fonder un gang.";
        long cost = GangConfig.creationCost();
        if (cost > 0) {
            PayResult r = MineNorth.bank().charge(p, cost, "gang:creation");
            if (!r.ok()) return r.message() + " (fonder un gang coûte " + money(cost) + ")";
        }
        Gang g = new Gang(UUID.randomUUID(), name, tag, color, System.currentTimeMillis());
        g.members.put(p.getUUID(), GangRank.CHEF);
        data.add(g);
        attach(data, g);
        tell(p, "§aVotre gang est fondé : " + g.label() + "§a. Invitez vos amis avec /gang inviter <joueur>.");
        membershipChanged(s, g);
        return null;
    }

    /** Branche la sauvegarde sur le coffre commun : tout changement de contenu marque la donnée à écrire. */
    static void attach(GangData data, Gang g) { g.vault.addListener(c -> data.setDirty()); }

    public static String invite(ServerPlayer inviter, ServerPlayer target) {
        MinecraftServer s = inviter.server;
        GangData data = GangData.get(s);
        Gang g = data.gangOf(inviter.getUUID());
        if (g == null) return "Vous n'êtes dans aucun gang.";
        if (!g.rankOf(inviter.getUUID()).atLeast(GangRank.LIEUTENANT)) return "Seuls le chef et les lieutenants peuvent inviter.";
        if (!allow(inviter.getUUID(), "inviter", 2000)) return "Doucement, une invitation à la fois.";
        if (target.getUUID().equals(inviter.getUUID())) return "Vous êtes déjà dans le gang !";
        if (data.gangOf(target.getUUID()) != null) return "Ce joueur est déjà dans un gang.";
        if (g.members.size() >= GangConfig.membersMax()) return "Le gang est complet (" + GangConfig.membersMax() + " membres).";
        Map<UUID, Invite> mine = INVITES.computeIfAbsent(target.getUUID(), k -> new HashMap<>());
        long now = System.currentTimeMillis();
        mine.values().removeIf(i -> i.expiryMs < now || data.gang(i.gang) == null);
        if (mine.size() >= 5 && !mine.containsKey(g.id)) return "Ce joueur a déjà trop d'invitations en attente.";
        mine.put(g.id, new Invite(g.id, inviter.getUUID(), now + GangConfig.inviteMs()));
        tell(inviter, "§aInvitation envoyée à " + MineNorth.displayName(target) + ".");
        Component accept = Component.literal(" §a§l[Accepter]").withStyle(st -> st
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/gang accepter " + g.tag))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Rejoindre " + g.name))));
        Component refuse = Component.literal(" §c[Refuser]").withStyle(st -> st
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/gang refuser " + g.tag)));
        target.sendSystemMessage(Component.literal("§e" + MineNorth.displayName(inviter) + " vous invite à rejoindre " + g.label() + "§e.").append(accept).append(refuse));
        return null;
    }

    public static String accept(ServerPlayer p, String gangQuery) {
        MinecraftServer s = p.server;
        GangData data = GangData.get(s);
        if (!allow(p.getUUID(), "accepter", 1500)) return "Doucement.";
        Map<UUID, Invite> mine = INVITES.get(p.getUUID());
        long now = System.currentTimeMillis();
        if (mine != null) mine.values().removeIf(i -> i.expiryMs < now || data.gang(i.gang) == null);
        if (mine == null || mine.isEmpty()) return "Vous n'avez aucune invitation en cours.";
        Invite inv = null;
        if (gangQuery == null || gangQuery.isBlank()) {
            if (mine.size() > 1) return "Plusieurs invitations : précisez le gang (/gang accepter <tag>).";
            inv = mine.values().iterator().next();
        } else {
            Gang q = data.byName(gangQuery);
            if (q != null) inv = mine.get(q.id);
            if (inv == null) return "Aucune invitation de ce gang.";
        }
        if (data.gangOf(p.getUUID()) != null) return "Vous êtes déjà dans un gang.";
        Gang g = data.gang(inv.gang);
        if (g == null) return "Ce gang n'existe plus.";
        long wait = data.leftAt(p.getUUID()) + GangConfig.leaveDelayMs() - now;
        if (wait > 0) return "Vous venez de quitter un gang : patientez encore " + (wait / 60000 + 1) + " min.";
        if (g.members.size() >= GangConfig.membersMax()) return "Le gang est complet.";
        data.join(g, p.getUUID(), GangRank.MEMBRE);
        INVITES.remove(p.getUUID());
        tell(p, "§aBienvenue chez " + g.label() + "§a !");
        notifyGang(s, g, "§e" + MineNorth.displayName(p) + " rejoint le gang.");
        membershipChanged(s, g);
        return null;
    }

    public static String decline(ServerPlayer p, String gangQuery) {
        GangData data = GangData.get(p.server);
        Map<UUID, Invite> mine = INVITES.get(p.getUUID());
        if (mine == null || mine.isEmpty()) return "Vous n'avez aucune invitation en cours.";
        Gang q = gangQuery == null || gangQuery.isBlank() ? null : data.byName(gangQuery);
        if (q != null) mine.remove(q.id); else mine.clear();
        tell(p, "§7Invitation refusée.");
        return null;
    }

    // ------------------------------------------------------------------ départ, exclusion, grades

    public static String leave(ServerPlayer p) {
        MinecraftServer s = p.server;
        GangData data = GangData.get(s);
        Gang g = data.gangOf(p.getUUID());
        if (g == null) return "Vous n'êtes dans aucun gang.";
        if (g.rankOf(p.getUUID()) == GangRank.CHEF) {
            if (g.members.size() > 1) return "Vous êtes le chef : donnez d'abord le commandement (/gang grade <membre> chef) ou dissolvez le gang.";
            return dissolve(p, true);
        }
        data.leave(g, p.getUUID(), true);
        tell(p, "§7Vous avez quitté " + g.label() + "§7.");
        notifyGang(s, g, "§e" + MineNorth.displayName(p) + " a quitté le gang.");
        membershipChanged(s, g);
        return null;
    }

    public static String kick(ServerPlayer actor, String query) {
        MinecraftServer s = actor.server;
        GangData data = GangData.get(s);
        Gang g = data.gangOf(actor.getUUID());
        if (g == null) return "Vous n'êtes dans aucun gang.";
        GangRank mine = g.rankOf(actor.getUUID());
        if (!mine.atLeast(GangRank.LIEUTENANT)) return "Seuls le chef et les lieutenants peuvent exclure.";
        UUID target = memberByName(s, g, query);
        if (target == null) return "Aucun membre de votre gang ne porte ce nom.";
        if (target.equals(actor.getUUID())) return "Pour partir, utilisez /gang quitter.";
        GangRank theirs = g.rankOf(target);
        if (theirs.level() <= mine.level()) return "Vous ne pouvez exclure que quelqu'un de grade inférieur au vôtre.";
        String name = nameOf(s, target);
        data.leave(g, target, false);
        ServerPlayer tp = s.getPlayerList().getPlayer(target);
        if (tp != null) tell(tp, "§cVous avez été exclu de " + g.label() + "§c.");
        notifyGang(s, g, "§e" + name + " a été exclu par " + MineNorth.displayName(actor) + ".");
        membershipChanged(s, g);
        if (tp != null && tp.containerMenu instanceof GangVaultMenu) tp.closeContainer();
        return null;
    }

    public static String setRank(ServerPlayer actor, String query, GangRank rank) {
        MinecraftServer s = actor.server;
        GangData data = GangData.get(s);
        Gang g = data.gangOf(actor.getUUID());
        if (g == null) return "Vous n'êtes dans aucun gang.";
        if (g.rankOf(actor.getUUID()) != GangRank.CHEF) return "Seul le chef peut changer les grades.";
        UUID target = memberByName(s, g, query);
        if (target == null) return "Aucun membre de votre gang ne porte ce nom.";
        if (target.equals(actor.getUUID())) return "Vous êtes déjà le chef.";
        String name = nameOf(s, target);
        if (rank == GangRank.CHEF) {
            g.members.put(actor.getUUID(), GangRank.LIEUTENANT);
            g.members.put(target, GangRank.CHEF);
            notifyGang(s, g, "§6" + name + " devient le nouveau chef du gang !");
        } else {
            g.members.put(target, rank);
            notifyGang(s, g, "§e" + name + " est maintenant " + rank.label().toLowerCase(Locale.ROOT) + ".");
        }
        data.setDirty();
        membershipChanged(s, g);
        return null;
    }

    // ------------------------------------------------------------------ dissolution

    public static String dissolve(ServerPlayer actor, boolean confirmed) {
        MinecraftServer s = actor.server;
        GangData data = GangData.get(s);
        Gang g = data.gangOf(actor.getUUID());
        if (g == null) return "Vous n'êtes dans aucun gang.";
        if (g.rankOf(actor.getUUID()) != GangRank.CHEF) return "Seul le chef peut dissoudre le gang.";
        if (!g.vaultEmpty()) return "Videz d'abord le coffre commun : un gang dissous ne peut pas garder d'objets.";
        if (!confirmed) return "Cette action est définitive. Confirmez avec /gang dissoudre confirmer.";
        disband(s, g, "Le gang a été dissous par son chef.");
        return null;
    }

    /** Dissolution par un admin ; si force et coffre non vide, le contenu est jeté aux pieds de l'admin. */
    public static String adminDissolve(ServerPlayer admin, String gangQuery, boolean force) {
        MinecraftServer s = admin.server;
        GangData data = GangData.get(s);
        Gang g = data.byName(gangQuery);
        if (g == null) return "Gang introuvable.";
        if (!g.vaultEmpty()) {
            if (!force) return "Le coffre n'est pas vide : ajoutez « forcer » pour jeter son contenu à vos pieds.";
            for (int i = 0; i < g.vault.getContainerSize(); i++) {
                ItemStack st = g.vault.removeItemNoUpdate(i);
                if (!st.isEmpty()) admin.drop(st, false);
            }
        }
        disband(s, g, "Le gang a été dissous par un administrateur.");
        return null;
    }

    private static void disband(MinecraftServer s, Gang g, String reason) {
        GangData data = GangData.get(s);
        for (UUID m : new ArrayList<>(g.members.keySet())) {
            ServerPlayer sp = s.getPlayerList().getPlayer(m);
            if (sp != null) tell(sp, "§c" + g.label() + "§c : " + reason);
        }
        GangDoors.releaseAll(s, g);
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (p.containerMenu instanceof GangVaultMenu vm && vm.gangId().equals(g.id)) p.closeContainer();
        }
        data.remove(g);
        membershipChanged(s, null);
    }

    // ------------------------------------------------------------------ coffres (blocs)

    /** Enregistre un bloc « Coffre de gang » qui vient d'être posé. Faux si le joueur n'en a pas le droit. */
    public static boolean registerChest(ServerPlayer p, String dim, net.minecraft.core.BlockPos pos) {
        GangData data = GangData.get(p.server);
        Gang g = data.gangOf(p.getUUID());
        if (g == null || !g.rankOf(p.getUUID()).atLeast(GangRank.LIEUTENANT)) return false;
        if (g.chests.size() >= GangConfig.chestsMax()) return false;
        if (g.chests.add(new Gang.ChestPos(dim, pos.asLong()))) {
            g.addLog(p.getUUID(), "pose un coffre de gang", GangConfig.logMax());
            data.setDirty();
        }
        return true;
    }

    /** Raison du refus de pose, ou null si le joueur peut poser un Coffre de gang. */
    public static String chestPlacementError(ServerPlayer p) {
        Gang g = GangData.get(p.server).gangOf(p.getUUID());
        if (g == null) return "Il faut être dans un gang pour poser un Coffre de gang.";
        if (!g.rankOf(p.getUUID()).atLeast(GangRank.LIEUTENANT)) return "Seuls le chef et les lieutenants posent le coffre du gang.";
        if (g.chests.size() >= GangConfig.chestsMax()) return "Votre gang a déjà " + GangConfig.chestsMax() + " coffres posés.";
        return null;
    }

    public static void unregisterChest(MinecraftServer s, String dim, net.minecraft.core.BlockPos pos) {
        GangData data = GangData.get(s);
        Gang g = data.gangByChest(dim, pos.asLong());
        if (g == null) return;
        g.chests.remove(new Gang.ChestPos(dim, pos.asLong()));
        data.setDirty();
    }

    // ------------------------------------------------------------------ purge d'un joueur (admin)

    /** Un joueur est supprimé du serveur : il quitte son gang ; s'il en était le chef, un autre membre prend la tête, sinon le gang disparaît. */
    public static boolean wipe(MinecraftServer s, UUID player) {
        GangData data = GangData.get(s);
        Gang g = data.gangOf(player);
        if (g == null) return false;
        INVITES.remove(player);
        if (g.members.size() <= 1) {
            for (int i = 0; i < g.vault.getContainerSize(); i++) g.vault.removeItemNoUpdate(i);
            disband(s, g, "Le gang n'a plus de membres.");
            return true;
        }
        boolean wasChef = g.rankOf(player) == GangRank.CHEF;
        data.leave(g, player, false);
        if (wasChef) {
            UUID next = null;
            for (var e : g.members.entrySet()) if (e.getValue() == GangRank.LIEUTENANT) { next = e.getKey(); break; }
            if (next == null) next = g.members.keySet().iterator().next();
            g.members.put(next, GangRank.CHEF);
            data.setDirty();
            notifyGang(s, g, "§6" + nameOf(s, next) + " prend la tête du gang.");
        }
        membershipChanged(s, g);
        return true;
    }

    // ------------------------------------------------------------------ textes

    public static String money(long cents) {
        return String.format(Locale.FRANCE, "%.2f €", cents / 100.0);
    }

    public static List<String> infoLines(MinecraftServer s, Gang g) {
        List<String> out = new ArrayList<>();
        out.add("§6§l" + g.name() + " §r§7(" + "§" + GangRules.chatColor(g.color) + "[" + g.tag + "]§7)");
        UUID chef = g.chef();
        out.add("§7Chef : §f" + (chef == null ? "-" : nameOf(s, chef)));
        StringBuilder sb = new StringBuilder("§7Membres (" + g.members.size() + ") : ");
        boolean first = true;
        for (var e : g.members.entrySet()) {
            if (!first) sb.append("§7, ");
            first = false;
            boolean online = s.getPlayerList().getPlayer(e.getKey()) != null;
            sb.append(online ? "§a" : "§8").append(nameOf(s, e.getKey()));
            if (e.getValue() != GangRank.MEMBRE) sb.append(" §7(").append(e.getValue().label().toLowerCase(Locale.ROOT)).append(")");
        }
        out.add(sb.toString());
        int doors = GangDoors.countDoors(s, g);
        StringBuilder zones = new StringBuilder();
        for (Territory t : GangData.get(s).territories()) if (g.id.equals(t.holder)) { if (zones.length() > 0) zones.append(", "); zones.append(t.name); }
        out.add("§7Portes : §f" + doors + " §7- Coffres : §f" + g.chests.size() + " §7- Territoires : §f" + (zones.length() == 0 ? "aucun" : zones));
        return out;
    }
}
