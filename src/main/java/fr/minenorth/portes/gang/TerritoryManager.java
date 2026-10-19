package fr.minenorth.portes.gang;

import fr.minenorth.api.MineNorth;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Capture des territoires : un gang qui tient seul une zone avec assez de membres pendant le temps réglé en prend le contrôle
 * (barre de progression visible des joueurs présents, annonce à la prise). Revenu passif plafonné versé au compte du chef.
 * Tout se calcule chaque seconde côté serveur ; le client ne fait que recevoir la barre de boss.
 */
@Mod.EventBusSubscriber
public final class TerritoryManager {
    private TerritoryManager() {}

    private static final class State {
        UUID attacker;
        int progress;
        ServerBossEvent bar;
    }

    private static final Map<String, State> STATES = new HashMap<>();
    private static final Map<UUID, Long> ALERTS = new HashMap<>();
    private static long nextIncomeAt;
    private static volatile boolean mapDirty = true;
    private static long lastMapRefresh;

    public static void requestMapRefresh() { mapDirty = true; }

    // ------------------------------------------------------------------ boucle principale (1 fois par seconde)

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.getServer().getTickCount() % 20 != 0) return;
        MinecraftServer s = e.getServer();
        GangData data = GangData.get(s);
        long now = System.currentTimeMillis();
        if (!data.territories().isEmpty()) {
            Map<UUID, Gang> gangOfPlayer = new HashMap<>();
            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                Gang g = data.gangOf(p.getUUID());
                if (g != null) gangOfPlayer.put(p.getUUID(), g);
            }
            for (Territory t : data.territories()) tick(s, data, t, gangOfPlayer, now);
            if (nextIncomeAt == 0) nextIncomeAt = now + GangConfig.incomeIntervalMs();
            if (now >= nextIncomeAt) {
                nextIncomeAt = now + GangConfig.incomeIntervalMs();
                payIncome(s, data, now);
            }
        }
        STATES.keySet().removeIf(k -> data.territory(k) == null && cleanup(STATES.get(k)));
        if (mapDirty && now - lastMapRefresh >= 3000 || now - lastMapRefresh >= 300_000) {
            mapDirty = false;
            lastMapRefresh = now;
            refreshMap(s, data);
        }
    }

    private static boolean cleanup(State st) {
        if (st != null && st.bar != null) st.bar.removeAllPlayers();
        return true;
    }

    private static void tick(MinecraftServer s, GangData data, Territory t, Map<UUID, Gang> gangOfPlayer, long now) {
        State st = STATES.computeIfAbsent(t.name.toLowerCase(Locale.ROOT), k -> new State());
        // Joueurs présents dans la zone (vivants, pas spectateurs).
        List<ServerPlayer> inside = new ArrayList<>();
        Map<UUID, Integer> counts = new HashMap<>();
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (!p.isAlive() || p.isSpectator()) continue;
            if (!t.contains(p.level().dimension().location().toString(), p.getX(), p.getY(), p.getZ())) continue;
            inside.add(p);
            Gang g = gangOfPlayer.get(p.getUUID());
            if (g != null) counts.merge(g.id, 1, Integer::sum);
        }
        GangRules.Presence presence = GangRules.presence(counts);
        boolean protectedNow = now < t.protectedUntil;
        UUID only = null;
        if (presence == GangRules.Presence.UN_SEUL_GANG) only = counts.keySet().iterator().next();
        int min = GangConfig.captureMinMembers();

        if (protectedNow) {
            st.attacker = null; st.progress = 0;
            if (only != null && !only.equals(t.holder)) alert(inside, gangOfPlayer, only, "§7" + t.name + " vient d'être prise : elle est protégée encore " + ((t.protectedUntil - now) / 60000 + 1) + " min.", now);
        } else if (presence == GangRules.Presence.CONTESTE) {
            alert(inside, gangOfPlayer, null, "§c⚔ " + t.name + " est contestée : la capture est gelée tant que plusieurs gangs sont là.", now);
        } else {
            if (only != null && !only.equals(st.attacker)) { st.attacker = only; st.progress = 0; }
            int count = only == null ? 0 : counts.get(only);
            boolean holderIsAttacker = only != null && only.equals(t.holder);
            if (only != null && !holderIsAttacker && count < min) {
                alert(inside, gangOfPlayer, only, "§e" + t.name + " : il faut " + min + " membres de votre gang dans la zone pour la capturer.", now);
            }
            st.progress = GangRules.nextProgress(st.progress, presence, count, min, holderIsAttacker, GangConfig.captureDecay());
            if (st.progress == 0 && presence == GangRules.Presence.VIDE) st.attacker = null;
            if (st.attacker != null && st.progress >= GangConfig.captureSeconds()) capture(s, data, t, st, now);
        }
        updateBar(data, t, st, inside, presence == GangRules.Presence.CONTESTE);
    }

    private static void alert(List<ServerPlayer> inside, Map<UUID, Gang> gangOfPlayer, UUID onlyGang, String text, long now) {
        for (ServerPlayer p : inside) {
            Gang g = gangOfPlayer.get(p.getUUID());
            if (onlyGang != null && (g == null || !g.id.equals(onlyGang))) continue;
            Long last = ALERTS.get(p.getUUID());
            if (last != null && now - last < GangConfig.alertMs()) continue;
            ALERTS.put(p.getUUID(), now);
            p.displayClientMessage(Component.literal(text), true);
        }
        if (ALERTS.size() > 2000) ALERTS.values().removeIf(t -> now - t > 600_000L);
    }

    private static void capture(MinecraftServer s, GangData data, Territory t, State st, long now) {
        Gang g = data.gang(st.attacker);
        st.attacker = null; st.progress = 0;
        if (g == null) return;
        t.holder = g.id;
        t.protectedUntil = now + GangConfig.captureProtectionMs();
        data.setDirty();
        Component msg = Component.literal("§6§l⚑ " + g.label() + " §6§lprend le contrôle de " + t.name + " !");
        for (ServerPlayer p : s.getPlayerList().getPlayers()) p.sendSystemMessage(msg);
        requestMapRefresh();
    }

    private static void updateBar(GangData data, Territory t, State st, List<ServerPlayer> inside, boolean contested) {
        boolean active = st.attacker != null && st.progress > 0 && !inside.isEmpty();
        if (!active) {
            if (st.bar != null) st.bar.removeAllPlayers();
            return;
        }
        Gang a = data.gang(st.attacker);
        if (a == null) return;
        int pct = Math.min(100, st.progress * 100 / Math.max(1, GangConfig.captureSeconds()));
        Component title = Component.literal((contested ? "§c⚔ Contestée : " : "§e⚔ Capture : ") + t.name + " §7- " + a.label() + " §f" + pct + "%");
        if (st.bar == null) st.bar = new ServerBossEvent(title, BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);
        st.bar.setName(title);
        st.bar.setColor(contested ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.YELLOW);
        st.bar.setProgress(Math.min(1.0F, st.progress / (float) Math.max(1, GangConfig.captureSeconds())));
        Set<ServerPlayer> shown = new HashSet<>(st.bar.getPlayers());
        for (ServerPlayer p : inside) if (!shown.remove(p)) st.bar.addPlayer(p);
        for (ServerPlayer gone : shown) st.bar.removePlayer(gone);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        ALERTS.remove(p.getUUID());
        for (State st : STATES.values()) if (st.bar != null) st.bar.removePlayer(p);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) { requestMapRefresh(); }

    // ------------------------------------------------------------------ revenu passif

    private static void payIncome(MinecraftServer s, GangData data, long now) {
        long perZone = GangConfig.incomePerZone();
        if (perZone <= 0) return;
        Map<UUID, Integer> zones = new HashMap<>();
        for (Territory t : data.territories()) if (t.holder != null) zones.merge(t.holder, 1, Integer::sum);
        for (var en : zones.entrySet()) {
            Gang g = data.gang(en.getKey());
            if (g == null) continue;
            if (GangConfig.incomeNeedsOnline() && g.members.keySet().stream().noneMatch(m -> s.getPlayerList().getPlayer(m) != null)) continue;
            if (now - g.incomeWindowStart >= 3_600_000L) { g.incomeWindowStart = now; g.incomePaid = 0; }
            long pay = GangRules.cappedPayout(perZone * en.getValue(), g.incomePaid, GangConfig.incomeCapPerHour());
            UUID chef = g.chef();
            if (pay <= 0 || chef == null || !MineNorth.bank().hasAccount(s, chef)) continue;
            if (!MineNorth.bank().refund(s, chef, pay, "gang:territoire")) continue;
            g.incomePaid += pay;
            data.setDirty();
            GangService.notifyGang(s, g, "§aVos territoires ont rapporté §f" + GangService.money(pay) + " §aau compte du chef.");
        }
    }

    // ------------------------------------------------------------------ carte (mod minenorth_map, par réflexion)

    private static Method setMarker, clearMarkers;
    private static boolean mapMissing;

    private static void refreshMap(MinecraftServer s, GangData data) {
        if (mapMissing) return;
        try {
            if (setMarker == null) {
                Class<?> api = Class.forName("fr.minenorth.map.api.MapApi");
                setMarker = api.getMethod("setMarker", ServerPlayer.class, String.class, String.class, int.class, int.class, int.class, int.class, boolean.class);
                clearMarkers = api.getMethod("clearMarkers", ServerPlayer.class, String.class);
            }
            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                clearMarkers.invoke(null, p, "Gang: ");
                for (Territory t : data.territories()) {
                    Gang h = data.gang(t.holder);
                    String name = "Gang: " + t.name + (h == null ? " (libre)" : " [" + h.tag() + "]");
                    int y = Math.max(t.y1, Math.min(t.y2, p.getBlockY()));
                    setMarker.invoke(null, p, name, t.dim, t.centerX(), y, t.centerZ(), h == null ? 0x9E9E9E : h.color(), false);
                }
            }
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError ex) {
            mapMissing = true; // mod Carte absent ou ancien : on n'essaie plus
        } catch (ReflectiveOperationException ignored) {
            // un joueur raté, on réessaiera au prochain rafraîchissement
        }
    }

    // ------------------------------------------------------------------ administration et textes

    public static String create(MinecraftServer s, String name, String dim, int ax, int ay, int az, int bx, int by, int bz) {
        GangData data = GangData.get(s);
        String clean = name == null ? "" : name.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").trim();
        if (clean.length() < 2 || clean.length() > 32) return "Nom de territoire : 2 à 32 caractères.";
        if (data.territory(clean) != null) return "Un territoire porte déjà ce nom.";
        Territory t = new Territory(clean, dim, ax, ay, az, bx, by, bz);
        if (t.volume() <= 0 || t.volume() > 4_000_000) return "Zone trop grande (4 000 000 blocs au plus).";
        data.addTerritory(t);
        requestMapRefresh();
        return null;
    }

    public static String delete(MinecraftServer s, String name) {
        GangData data = GangData.get(s);
        Territory t = data.territory(name);
        if (t == null) return "Territoire introuvable.";
        State st = STATES.remove(t.name.toLowerCase(Locale.ROOT));
        if (st != null) cleanup(st);
        data.removeTerritory(t.name);
        requestMapRefresh();
        return null;
    }

    public static String free(MinecraftServer s, String name) {
        GangData data = GangData.get(s);
        Territory t = data.territory(name);
        if (t == null) return "Territoire introuvable.";
        t.holder = null; t.protectedUntil = 0;
        data.setDirty();
        requestMapRefresh();
        return null;
    }

    public static List<String> listLines(MinecraftServer s) {
        GangData data = GangData.get(s);
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Territory t : data.territories()) {
            Gang h = data.gang(t.holder);
            State st = STATES.get(t.name.toLowerCase(Locale.ROOT));
            String line = "§e" + t.name + " §7- " + (h == null ? "§alibre" : h.label());
            if (now < t.protectedUntil) line += " §8(protégée " + ((t.protectedUntil - now) / 60000 + 1) + " min)";
            if (st != null && st.attacker != null && st.progress > 0) {
                Gang a = data.gang(st.attacker);
                if (a != null) line += " §c⚔ " + a.tag() + " " + (st.progress * 100 / Math.max(1, GangConfig.captureSeconds())) + "%";
            }
            out.add(line);
        }
        return out;
    }

    /** Clic droit sur une pancarte de territoire. */
    public static void describeAt(ServerPlayer p, String dim, net.minecraft.core.BlockPos pos) {
        GangData data = GangData.get(p.server);
        Territory best = null;
        for (Territory t : data.territories()) {
            if (t.contains(dim, pos.getX(), pos.getY(), pos.getZ())) { best = t; break; }
        }
        if (best == null) {
            p.sendSystemMessage(Component.literal("§7Cette pancarte n'est dans aucun territoire de gang."));
            return;
        }
        Gang h = data.gang(best.holder);
        long now = System.currentTimeMillis();
        p.sendSystemMessage(Component.literal("§6§l⚑ " + best.name));
        p.sendSystemMessage(Component.literal("§7Tenu par : " + (h == null ? "§aPersonne - à prendre !" : h.label())));
        p.sendSystemMessage(Component.literal("§7Pour le prendre : §f" + GangConfig.captureMinMembers() + " membres de votre gang, seuls dans la zone, pendant §f" + GangConfig.captureSeconds() + " s§7."));
        if (now < best.protectedUntil) p.sendSystemMessage(Component.literal("§8Protégée encore " + ((best.protectedUntil - now) / 60000 + 1) + " min après la dernière prise."));
    }
}
