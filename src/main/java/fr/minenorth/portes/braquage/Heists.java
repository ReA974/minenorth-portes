package fr.minenorth.portes.braquage;

import fr.minenorth.api.MineNorth;
import fr.minenorth.portes.MineNorthPortes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Braquage d'un vendeur PNJ. Tout se passe côté serveur : le client n'envoie rien (aucun paquet), l'état vit ici et dans
 * {@link BraqData}. Déclenchement : accroupi + clic droit sur le vendeur, arme tenue en main. Un seul braquage par vendeur
 * et par joueur ; l'argent n'est versé qu'une fois (le braquage est retiré de la table AVANT le versement).
 */
@Mod.EventBusSubscriber(modid = MineNorthPortes.MOD_ID)
public final class Heists {
    private Heists() {}

    private static final class Heist {
        UUID player, entity;
        ResourceKey<net.minecraft.world.level.Level> dim;
        String storeName;
        boolean station, alerted;
        long startTick;
        float baseYaw;
    }

    private static final Map<UUID, Heist> BY_ENTITY = new HashMap<>();
    private static final Map<UUID, Heist> BY_PLAYER = new HashMap<>();
    private static final Map<UUID, Long> LAST_CLICK = new HashMap<>();

    /** Tiroirs ouverts à refermer : {dimension, pos, tick de fermeture}. */
    private record Drawer(ResourceKey<net.minecraft.world.level.Level> dim, BlockPos pos, long closeAt) {}
    private static final List<Drawer> DRAWERS = new ArrayList<>();

    // ------------------------------------------------------------------ armes

    public static boolean isArmed(Player p) {
        ItemStack st = p.getMainHandItem();
        if (st.isEmpty()) return false;
        var id = ForgeRegistries.ITEMS.getKey(st.getItem());
        if (id == null) return false;
        for (String s : BraqConfig.ARMES.get()) {
            String w = s.trim();
            if (w.equals(id.toString()) || (w.endsWith(":*") && id.getNamespace().equals(w.substring(0, w.length() - 2)))) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ déclenchement

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteract(PlayerInteractEvent.EntityInteract e) {
        if (handle(e.getEntity(), e.getTarget(), e.getHand())) {
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteractSpecific(PlayerInteractEvent.EntityInteractSpecific e) {
        if (handle(e.getEntity(), e.getTarget(), e.getHand())) {
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /** @return vrai si le clic est consommé par le braquage (la boutique ne s'ouvre pas). */
    private static boolean handle(Player pl, Entity target, InteractionHand hand) {
        if (!(pl instanceof ServerPlayer p) || hand != InteractionHand.MAIN_HAND) return false;
        if (!p.isShiftKeyDown() || !isArmed(p)) return false;                  // sinon : la boutique s'ouvre normalement
        BraqData data = BraqData.get(p.server);
        BraqData.Store store = data.byEntity(target.getUUID());
        if (store == null) return false;
        long now = p.level().getGameTime();
        Long last = LAST_CLICK.put(p.getUUID(), now);
        if (last != null && last == now) return true;                           // 2e événement du même clic
        start(p, target, data, store);
        return true;
    }

    private static void start(ServerPlayer p, Entity vendor, BraqData data, BraqData.Store store) {
        MinecraftServer s = p.server;
        if (BY_PLAYER.containsKey(p.getUUID())) { msg(p, "§cTu es déjà en train de braquer."); return; }
        if (BY_ENTITY.containsKey(vendor.getUUID())) { msg(p, "§cCe magasin est déjà en train d'être braqué."); return; }
        if (MineNorth.police().isPolice(p)) { msg(p, "§cUn policier ne braque pas."); return; }
        long left = data.lockLeftMinutes(store);
        if (left > 0) { msg(p, "§cLa caisse est verrouillée (encore environ " + left + " min)."); return; }
        if (p.getHealth() < BraqConfig.VIE_MINIMUM.get() || !p.isAlive()) { msg(p, "§cTu es trop mal en point pour braquer."); return; }

        int min = BraqConfig.POLICIERS_MIN.get();
        if (min > 0 && !(BraqConfig.OPS_IGNORENT.get() && p.hasPermissions(2)) && copsAvailable(s) < min) {
            msg(p, "§cPas assez de policiers" + (BraqConfig.SERVICE_SEULEMENT.get() ? " en service" : "") + " (" + min + " minimum) : la caisse est vide ce soir.");
            return;
        }
        if (data.countLastHour(p.getUUID()) >= BraqConfig.PAR_JOUEUR_HEURE.get()) { msg(p, "§cTu as déjà braqué assez de magasins cette heure-ci."); return; }
        if (remainingHourCents(data) <= 0) { msg(p, "§cLes caisses du coin sont à sec : reviens plus tard."); return; }

        Heist h = new Heist();
        h.player = p.getUUID();
        h.entity = vendor.getUUID();
        h.dim = vendor.level().dimension();
        h.storeName = store.name;
        h.station = store.station;
        h.startTick = s.getTickCount();
        h.baseYaw = vendor.getYRot();
        BY_ENTITY.put(h.entity, h);
        BY_PLAYER.put(h.player, h);
        faceTowards(vendor, p);
        vendor.level().playSound(null, vendor.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 1.0F, 1.0F);
        msg(p, "§e§lBraquage ! §r§eRestez à moins de " + BraqConfig.DISTANCE_MAX.get() + " blocs, arme en main, pendant "
                + BraqConfig.DUREE_SECONDES.get() + " s. La police arrive peut-être...");
    }

    private static int copsAvailable(MinecraftServer s) {
        int n = 0;
        for (ServerPlayer c : MineNorth.police().online(s))
            if (!BraqConfig.SERVICE_SEULEMENT.get() || MineNorth.police().onDuty(s, c.getUUID())) n++;
        return n;
    }

    private static long remainingHourCents(BraqData d) {
        return BraqConfig.PLAFOND_HEURE.get() * 100L - d.paidLastHour();
    }

    // ------------------------------------------------------------------ déroulement

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer s = e.getServer();
        long tick = s.getTickCount();
        if (!DRAWERS.isEmpty()) closeDrawers(s, tick);
        if (BY_PLAYER.isEmpty() || tick % 5 != 0) return;
        for (Heist h : new ArrayList<>(BY_PLAYER.values())) step(s, h, tick);
    }

    private static void step(MinecraftServer s, Heist h, long tick) {
        ServerPlayer p = s.getPlayerList().getPlayer(h.player);
        ServerLevel level = s.getLevel(h.dim);
        Entity vendor = level == null ? null : level.getEntity(h.entity);
        if (p == null) { fail(s, h, null, vendor, "Le braqueur s'est déconnecté."); return; }
        if (vendor == null || !vendor.isAlive()) { fail(s, h, p, null, "Le vendeur a disparu : braquage annulé."); return; }
        if (!p.isAlive() || p.getHealth() < BraqConfig.VIE_MINIMUM.get()) { fail(s, h, p, vendor, "Tu es à terre : le braquage échoue."); return; }
        if (p.level().dimension() != h.dim || p.distanceTo(vendor) > BraqConfig.DISTANCE_MAX.get()) {
            fail(s, h, p, vendor, "§cTu t'es éloigné : le vendeur reprend courage et verrouille la caisse.");
            return;
        }
        if (!isArmed(p)) { fail(s, h, p, vendor, "§cTu as rangé ton arme : le vendeur ne te prend plus au sérieux."); return; }
        for (ServerPlayer cop : MineNorth.police().online(s)) {
            if (cop.level().dimension() == h.dim && cop.distanceTo(vendor) < BraqConfig.POLICE_DISTANCE.get()) {
                level.playSound(null, vendor.blockPosition(), SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 1.0F, 1.0F);
                fail(s, h, p, vendor, "§cLa police arrive ! Le vendeur s'enfuit et la caisse se verrouille.");
                return;
            }
        }

        long elapsed = tick - h.startTick;
        if (!h.alerted && elapsed >= BraqConfig.ALERTE_DELAI.get() * 20L) {
            h.alerted = true;
            alert(s, h, vendor, p);
        }
        double total = BraqConfig.DUREE_SECONDES.get() * 20.0;
        double progress = Math.min(1.0, elapsed / total);
        faceTowards(vendor, p);
        level.sendParticles(ParticleTypes.SPLASH, vendor.getX(), vendor.getY() + vendor.getBbHeight() + 0.2, vendor.getZ(), 2, 0.2, 0.1, 0.2, 0.0);
        if (tick % 20 == 0) level.playSound(null, vendor.blockPosition(), SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.NEUTRAL, 0.4F, 0.6F);
        p.displayClientMessage(Component.literal(bar(progress)), true);
        if (progress >= 1.0) success(s, h, p, vendor, level);
    }

    private static String bar(double progress) {
        int n = 20, f = (int) Math.round(progress * n);
        return "§eBraquage §8[§a" + "|".repeat(f) + "§7" + "|".repeat(n - f) + "§8] §f" + (int) Math.round(progress * 100) + " %";
    }

    private static void alert(MinecraftServer s, Heist h, Entity vendor, ServerPlayer p) {
        String kind = h.station ? "station-service" : "supérette";
        BlockPos at = vendor.blockPosition();
        BraqCompat.alertPolice(s, "Braquage de " + kind, "« " + h.storeName + " » : le vendeur signale un braqueur armé. Se rendre sur place.",
                h.dim.location().toString(), at.getX(), at.getY(), at.getZ(), BraqConfig.ALERTE_DUREE.get());
        if (BraqConfig.ANNONCE_GLOBALE.get()) {
            s.getPlayerList().broadcastSystemMessage(Component.literal("§c§l[BRAQUAGE] §r§cUn(e) " + kind + " (« " + h.storeName + " ») est en train d'être braqué(e) !"), false);
        }
        p.displayClientMessage(Component.literal("§c§lLe vendeur a appelé la police !"), false);
    }

    private static void success(MinecraftServer s, Heist h, ServerPlayer p, Entity vendor, ServerLevel level) {
        release(h, vendor);                                  // retiré AVANT le versement : impossible de payer deux fois
        BraqData data = BraqData.get(s);
        BraqData.Store store = data.byName(h.storeName);
        long min = BraqConfig.MONTANT_MIN.get() * 100L, max = Math.max(min, BraqConfig.MONTANT_MAX.get() * 100L);
        long cents = min + (long) (level.random.nextDouble() * (max - min + 1));
        cents = Math.min(cents, Math.min(BraqConfig.PLAFOND_BRAQUAGE.get() * 100L, max));
        cents = Math.min(cents, remainingHourCents(data));
        if (store != null) data.lock(store, BraqConfig.COOLDOWN_MIN.get());
        if (cents <= 0) { msg(p, "§7La caisse était vide."); return; }
        data.record(p.getUUID(), cents);
        if (!BraqCompat.payCash(p, cents)) {
            data.forget(p.getUUID());                         // rien n'a été versé : on ne compte pas le butin
            msg(p, "§cLa caisse est vide (erreur de versement, préviens un admin).");
            return;
        }
        level.playSound(null, vendor.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1.0F, 1.2F);
        p.sendSystemMessage(Component.literal("§a§lBraquage réussi ! §r§aTu repars avec §f" + euros(cents) + " §aen espèces. Fuis avant l'arrivée de la police !"));
        openRegister(level, vendor.blockPosition());
    }

    private static void fail(MinecraftServer s, Heist h, ServerPlayer p, Entity vendor, String reason) {
        release(h, vendor);
        BraqData data = BraqData.get(s);
        BraqData.Store store = data.byName(h.storeName);
        if (store != null) data.lock(store, BraqConfig.COOLDOWN_ECHEC_MIN.get());
        if (p != null) {
            p.displayClientMessage(Component.literal(reason.startsWith("§") ? reason : "§c" + reason), false);
            p.displayClientMessage(Component.literal("§cBraquage raté."), true);
        }
    }

    /** Termine le braquage : retire l'état et rend sa position d'origine au vendeur. */
    private static void release(Heist h, Entity vendor) {
        BY_ENTITY.remove(h.entity, h);
        BY_PLAYER.remove(h.player, h);
        if (vendor != null) { vendor.setYRot(h.baseYaw); if (vendor instanceof LivingEntity l) { l.setYHeadRot(h.baseYaw); l.setYBodyRot(h.baseYaw); } }
    }

    private static void faceTowards(Entity vendor, Entity target) {
        float yaw = (float) (Mth.atan2(target.getZ() - vendor.getZ(), target.getX() - vendor.getX()) * 57.29577951308232D) - 90.0F;
        vendor.setYRot(yaw);
        if (vendor instanceof LivingEntity l) { l.setYHeadRot(yaw); l.setYBodyRot(yaw); }
    }

    // ------------------------------------------------------------------ caisse enregistreuse

    private static void openRegister(ServerLevel level, BlockPos center) {
        for (BlockPos q : BlockPos.betweenClosed(center.offset(-4, -2, -4), center.offset(4, 2, 4))) {
            var st = level.getBlockState(q);
            if (st.is(BraqRegistry.CAISSE.get()) && !st.getValue(BraqRegistry.CashRegisterBlock.OPEN)) {
                level.setBlock(q, st.setValue(BraqRegistry.CashRegisterBlock.OPEN, true), 3);
                level.playSound(null, q, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.8F, 1.4F);
                DRAWERS.add(new Drawer(level.dimension(), q.immutable(), level.getServer().getTickCount() + 20 * 20L));
            }
        }
    }

    private static void closeDrawers(MinecraftServer s, long tick) {
        for (Iterator<Drawer> it = DRAWERS.iterator(); it.hasNext(); ) {
            Drawer d = it.next();
            if (d.closeAt > tick) continue;
            it.remove();
            ServerLevel l = s.getLevel(d.dim);
            if (l == null || !l.isLoaded(d.pos)) continue;
            var st = l.getBlockState(d.pos);
            if (st.is(BraqRegistry.CAISSE.get())) l.setBlock(d.pos, st.setValue(BraqRegistry.CashRegisterBlock.OPEN, false), 3);
        }
    }

    // ------------------------------------------------------------------ nettoyage

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        LAST_CLICK.remove(e.getEntity().getUUID());      // le braquage en cours échouera au prochain tick (joueur absent)
    }

    @SubscribeEvent
    public static void onStop(net.minecraftforge.event.server.ServerStoppedEvent e) {
        BY_ENTITY.clear();
        BY_PLAYER.clear();
        LAST_CLICK.clear();
        DRAWERS.clear();
    }

    // ------------------------------------------------------------------ outils

    static void msg(ServerPlayer p, String text) { p.displayClientMessage(Component.literal(text), true); }

    static String euros(long cents) { return (cents / 100) + "," + String.format("%02d", cents % 100) + " €"; }

    /** Annule le braquage en cours sur ce vendeur (suppression du magasin par un admin). */
    static void cancelFor(MinecraftServer s, UUID entity) {
        Heist h = BY_ENTITY.get(entity);
        if (h == null) return;
        ServerLevel l = s.getLevel(h.dim);
        release(h, l == null ? null : l.getEntity(entity));
    }
}
