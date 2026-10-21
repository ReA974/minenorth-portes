package fr.minenorth.portes.gang;

import fr.minenorth.api.MineNorth;
import fr.minenorth.portes.door.OwnerDoorData;
import fr.minenorth.portes.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.DoorBlock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Côté serveur de l'écran « Mon gang » : construit ce que le joueur voit et exécute ses actions.
 * Le client envoie seulement une intention (action + texte + position) ; chaque action repasse par GangService / GangDoors
 * qui revérifient grade, limites, distance et état. Cadence limitée par joueur.
 */
public final class GangGui {
    private GangGui() {}

    public static final int REFRESH = 0, CREATE = 1, INVITE = 2, ACCEPT = 3, DECLINE = 4, KICK = 5, SET_RANK = 6, LEAVE = 7, DISSOLVE = 8,
            DOOR_CLAIM = 9, DOOR_REMOVE = 10, DOOR_RANK = 11, DOOR_REMOVE_REMOTE = 12, SETTINGS = 13,
            ADMIN_TERR_CREATE = 14, ADMIN_TERR_DELETE = 15, ADMIN_TERR_FREE = 16, ADMIN_DISSOLVE = 17, DOOR_CONTEXT = 18, REFRESH_QUIET = 19, ADMIN_INCOME_ZONE = 20, ADMIN_INCOME_GLOBAL = 21;

    private static final double NEARBY = 48.0;
    private static final int LOG_LINES = 40;

    // ------------------------------------------------------------------ envoi

    public static void send(ServerPlayer p, boolean open) { send(p, open, 0, 0, 0, null, false); }

    public static void send(ServerPlayer p, boolean open, int doorMode, long doorPos, int doorMin, String message, boolean error) {
        ModNetwork.sendGangView(p, build(p, open, doorMode, doorPos, doorMin, message, error));
    }

    /** Ouvre la fenêtre « porte » (attribuer / gérer) pour cette porte. */
    public static void sendDoor(ServerPlayer p, int mode, BlockPos pos, int min) { send(p, true, mode, pos.asLong(), min, null, false); }

    public static GangView build(ServerPlayer p, boolean open, int doorMode, long doorPos, int doorMin, String message, boolean error) {
        var server = p.server;
        GangData data = GangData.get(server);
        GangView v = new GangView();
        v.open = open; v.doorMode = doorMode; v.doorPos = doorPos; v.doorMin = doorMin;
        v.message = message == null ? "" : message; v.messageError = error;
        v.op = p.hasPermissions(2);
        v.createCost = GangConfig.creationCost();
        v.membersMax = GangConfig.membersMax();
        v.doorsMax = GangConfig.doorsMax();
        v.chestsMax = GangConfig.chestsMax();
        v.incomePerZone = GangConfig.incomePerZone();
        v.incomeCap = GangConfig.incomeCapPerHour();
        v.incomeNeedsOnline = GangConfig.incomeNeedsOnline();
        v.incomeMinutes = (int) (GangConfig.incomeIntervalMs() / 60000L);
        v.invites.addAll(GangService.invitesOf(p));

        Gang g = data.gangOf(p.getUUID());
        if (g != null) {
            GangRank mine = g.rankOf(p.getUUID());
            v.inGang = true; v.name = g.name(); v.tag = g.tag(); v.color = g.color(); v.myRank = mine.level();
            for (var e : g.members().entrySet()) {
                v.members.add(new GangView.Member(e.getKey().toString(), GangService.nameOf(server, e.getKey()), e.getValue().level(),
                        server.getPlayerList().getPlayer(e.getKey()) != null));
            }
            if (mine.atLeast(GangRank.LIEUTENANT)) {
                for (ServerPlayer o : server.getPlayerList().getPlayers()) {
                    if (o == p || o.level() != p.level() || data.gangOf(o.getUUID()) != null || o.distanceToSqr(p) > NEARBY * NEARBY) continue;
                    v.nearby.add(new GangView.Nearby(o.getUUID().toString(), MineNorth.displayName(o)));
                }
            }
            for (var k : GangDoors.doorsOf(server, g)) {
                ServerLevel l = server.getLevel(k.dimension());
                OwnerDoorData.DoorEntry en = l == null ? null : OwnerDoorData.get(l).entry(k);
                v.doors.add(new GangView.DoorInfo(k.dimension().location().toString(), k.pos().asLong(),
                        en == null ? GangRank.MEMBRE.level() : GangDoors.minRank(en.permission()).level()));
            }
            v.chestsCount = g.chests().size();
            for (int i = 0; i < g.vault().getContainerSize(); i++) {
                var st = g.vault().getItem(i);
                if (!st.isEmpty()) { v.vaultStacks++; v.vaultItems += st.getCount(); }
            }
            if (mine == GangRank.CHEF || v.op) {
                List<Gang.LogEntry> log = g.log();
                SimpleDateFormat fmt = new SimpleDateFormat("dd/MM HH:mm");
                for (int i = log.size() - 1; i >= 0 && v.log.size() < LOG_LINES; i--) {
                    Gang.LogEntry le = log.get(i);
                    v.log.add(fmt.format(new Date(le.timeMs())) + "  " + GangService.nameOf(server, le.actor()) + " " + le.text());
                }
            }
        }
        long now = System.currentTimeMillis();
        for (Territory t : data.territories()) {
            Gang h = data.gang(t.holder());
            boolean mineT = g != null && h != null && h.id.equals(g.id);
            if (mineT) v.zonesHeld++;
            v.territories.add(new GangView.Terr(t.name, t.dim, t.x1, t.z1, t.x2, t.z2,
                    h == null ? "" : h.tag(), h == null ? "" : h.name(), h == null ? 0x9E9E9E : h.color(), mineT, TerritoryManager.status(data, t, now), t.effectiveIncome(), t.customIncome()));
        }
        if (v.op) for (Gang o : data.gangs()) v.allGangs.add(new GangView.GangInfo(o.name(), o.tag(), o.color(), o.members().size()));
        return v;
    }

    // ------------------------------------------------------------------ actions

    public static void handle(ServerPlayer p, int action, String a, String b, long pos) {
        if (!GangService.allow(p.getUUID(), "gui", 150)) return;
        a = a == null ? "" : a; b = b == null ? "" : b;
        if (a.length() > 64) a = a.substring(0, 64);
        if (b.length() > 64) b = b.substring(0, 64);
        String err = null, ok = null;
        switch (action) {
            case REFRESH -> { send(p, true); return; }
            case REFRESH_QUIET -> { send(p, false); return; }
            case CREATE -> {
                String[] tc = b.split("\\|", 2);
                err = GangService.create(p, a, tc[0], tc.length > 1 ? tc[1] : null);
                ok = "Votre gang est fondé !";
            }
            case INVITE -> {
                ServerPlayer target = byUuid(p, a);
                if (target == null) err = "Ce joueur n'est plus connecté.";
                else if (target.level() != p.level() || target.distanceToSqr(p) > NEARBY * NEARBY) err = "Ce joueur est trop loin.";
                else err = GangService.invite(p, target);
                ok = "Invitation envoyée.";
            }
            case ACCEPT -> { err = GangService.accept(p, a); ok = "Bienvenue dans le gang !"; }
            case DECLINE -> { err = GangService.decline(p, a); ok = "Invitation refusée."; }
            case KICK -> { err = GangService.kickUuid(p, parse(a)); ok = "Membre exclu."; }
            case SET_RANK -> {
                GangRank r = GangRank.parse(b);
                err = r == null ? "Grade inconnu." : GangService.setRankUuid(p, parse(a), r);
                ok = "Grade modifié.";
            }
            case LEAVE -> { err = GangService.leave(p); ok = "Vous avez quitté le gang."; }
            case DISSOLVE -> { err = GangService.dissolve(p, "1".equals(b)); ok = "Le gang est dissous."; }
            case DOOR_CLAIM -> {
                GangRank r = GangRank.parse(b);
                err = r == null ? "Grade inconnu." : GangDoors.claim(p, BlockPos.of(pos), r);
                ok = "Porte attribuée au gang.";
            }
            case DOOR_REMOVE -> { err = GangDoors.unclaim(p, BlockPos.of(pos)); ok = "La porte n'appartient plus au gang."; }
            case DOOR_RANK -> {
                GangRank r = GangRank.parse(b);
                err = r == null ? "Grade inconnu." : GangDoors.setMinRank(p, BlockPos.of(pos), r);
                ok = "Grade de la porte modifié.";
            }
            case DOOR_REMOVE_REMOTE -> { err = GangDoors.unclaimAt(p, a, pos); ok = "Porte retirée du gang."; }
            case SETTINGS -> {
                String[] tc = b.split("\\|", 2);
                int color = tc.length > 1 ? GangRules.parseColor(tc[1]) : -1;
                err = color < 0 ? "Couleur invalide." : GangService.settings(p, a, tc[0], color);
                ok = "Paramètres enregistrés.";
            }
            case DOOR_CONTEXT -> {
                // Depuis le panneau d'une porte de maison : proposer de la donner au gang.
                err = GangDoors.offerOwnDoor(p, BlockPos.of(pos));
                if (err == null) return;
            }
            case ADMIN_TERR_CREATE -> {
                if (!p.hasPermissions(2)) return;
                int r;
                try { r = Integer.parseInt(b.trim()); } catch (NumberFormatException e) { r = -1; }
                if (r < 2 || r > 200) err = "Rayon : 2 à 200 blocs.";
                else {
                    BlockPos o = p.blockPosition();
                    err = TerritoryManager.create(p.server, a, p.level().dimension().location().toString(), o.getX() - r, p.level().getMinBuildHeight(),
                            o.getZ() - r, o.getX() + r, p.level().getMaxBuildHeight() - 1, o.getZ() + r);
                }
                ok = "Territoire créé autour de vous.";
            }
            case ADMIN_TERR_DELETE -> { if (!p.hasPermissions(2)) return; err = TerritoryManager.delete(p.server, a); ok = "Territoire supprimé."; }
            case ADMIN_TERR_FREE -> { if (!p.hasPermissions(2)) return; err = TerritoryManager.free(p.server, a); ok = "Territoire libéré."; }
            case ADMIN_INCOME_ZONE -> {
                if (!p.hasPermissions(2)) return;
                long cents = parseEuros(b);
                err = cents == -2 ? "Montant invalide : un nombre d'euros positif (ex. 20 ou 12,50), ou « défaut »." : TerritoryManager.setIncome(p.server, a, cents);
                ok = cents == -1 ? "Cette zone utilise maintenant le revenu par défaut (au prochain versement)." : "Revenu de la zone enregistré (au prochain versement).";
            }
            case ADMIN_INCOME_GLOBAL -> {
                if (!p.hasPermissions(2)) return;
                err = setGlobalIncome(a, b);
                ok = "Réglages du revenu enregistrés (au prochain versement).";
            }
            case ADMIN_DISSOLVE -> { if (!p.hasPermissions(2)) return; err = GangService.adminDissolve(p, a, "1".equals(b)); ok = "Gang dissous."; }
            default -> { return; }
        }
        send(p, true, 0, 0, 0, err != null ? err : ok, err != null);
    }

    /** Euros saisis -> centimes (voir GangRules.parseEuros). */
    static long parseEuros(String s) { return GangRules.parseEuros(s, Territory.MAX_INCOME); }

    /** a = « revenuParZone;plafondHeure;intervalleMinutes » (euros entiers, minutes), b = « 1 » si le versement exige un membre en ligne. Tout est vérifié avant d'appliquer quoi que ce soit. */
    private static String setGlobalIncome(String a, String b) {
        String[] parts = a.split(";", -1);
        if (parts.length != 3) return "Réglages incomplets.";
        long zone, cap, minutes;
        try {
            zone = Long.parseLong(parts[0].trim());
            cap = Long.parseLong(parts[1].trim());
            minutes = Long.parseLong(parts[2].trim());
        } catch (NumberFormatException e) {
            return "Entrez des nombres entiers (euros, euros, minutes).";
        }
        if (zone < 0 || zone > GangConfig.INCOME_ZONE_MAX_EUROS) return "Revenu par zone : 0 à " + GangConfig.INCOME_ZONE_MAX_EUROS + " €.";
        if (cap < 0 || cap > GangConfig.INCOME_CAP_MAX_EUROS) return "Plafond par heure : 0 à " + GangConfig.INCOME_CAP_MAX_EUROS + " € (0 = pas de plafond).";
        if (minutes < 1 || minutes > 1440) return "Intervalle : 1 à 1440 minutes.";
        GangConfig.setIncomePerZoneEuros(zone);
        GangConfig.setIncomeCapEuros(cap);
        GangConfig.setIncomeIntervalMinutes(minutes);
        GangConfig.setIncomeNeedsOnline("1".equals(b));
        return null;
    }

    private static UUID parse(String s) {
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    private static ServerPlayer byUuid(ServerPlayer p, String s) {
        UUID id = parse(s);
        return id == null ? null : p.server.getPlayerList().getPlayer(id);
    }

    /** Porte visée valide ? (utilisé pour les fenêtres « porte »). */
    static boolean isDoor(ServerLevel l, BlockPos pos) { return l.getBlockState(pos).getBlock() instanceof DoorBlock; }

    static String rankName(int level) { return GangRank.ofLevel(level).label().toLowerCase(Locale.ROOT); }
}
