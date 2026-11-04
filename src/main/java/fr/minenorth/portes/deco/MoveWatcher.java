package fr.minenorth.portes.deco;

import com.mojang.logging.LogUtils;
import fr.minenorth.portes.agency.AgencyService;
import fr.minenorth.portes.agency.AgencyService.Occupancy;
import fr.minenorth.portes.agency.PropertyCategory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Déménagement automatique. Comme le pont WorldGuard, on compare le titulaire de chaque bien (propriétaire, locataire ou entreprise) à celui
 * vu la dernière fois : s'il a changé ou s'il n'y en a plus, l'ancien occupant déménage (vente, revente, fin de bail, arrêt de location,
 * expulsion, libération ou attribution par le staff, entreprise fermée… quel que soit le chemin). Vérifié juste après chaque action de
 * l'agence ou du staff et toutes les {@code intervalleSecondes} secondes (fin de bail). Le dernier titulaire vu est sauvegardé avec le monde :
 * un changement survenu serveur éteint est traité au démarrage. Un terrain est ensuite remis à nu ({@link TerrainService}).
 */
@Mod.EventBusSubscriber
public final class MoveWatcher extends SavedData {
    private static final Logger LOG = LogUtils.getLogger();
    private static final String NAME = "minenorthportes_deco_holders";

    /** bien -> titulaire (« p:uuid » / « c:id ») */
    private final Map<String, String> holders = new HashMap<>();
    private final Map<String, String[]> names = new HashMap<>();   // bien -> {dimension, nom}

    private static int counter;
    private static boolean running;

    static MoveWatcher get(MinecraftServer s) {
        return s.overworld().getDataStorage().computeIfAbsent(MoveWatcher::load, MoveWatcher::new, NAME);
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (++counter < DecoConfig.get().intervalleSecondes * 20) return;
        counter = 0;
        check(e.getServer());
    }

    /** Compare les titulaires et déménage ceux qui sont partis. Sans effet si la décoration est désactivée. */
    public static void check(MinecraftServer s) {
        if (!DecoConfig.get().actif || running || s == null) return;
        running = true;
        try {
            Zones.invalidate();
            MoveWatcher w = get(s);
            Set<String> seen = new HashSet<>();
            for (Occupancy o : AgencyService.occupancies(s)) {
                String key = o.key();
                seen.add(key);
                String before = w.holders.get(key);
                String now = o.holderKey();
                if (before != null && !before.equals(now)) {
                    String reason = now.isEmpty() ? "départ de l'occupant" : "changement de titulaire";
                    Mover.moveOut(s, key, o.dimension(), o.houseName(), reason);
                    if (o.category() == PropertyCategory.LAND) TerrainService.reset(s, key, reason, before);
                }
                if (!now.equals(before == null ? "" : before)) {
                    if (now.isEmpty()) w.holders.remove(key); else w.holders.put(key, now);
                    w.names.put(key, new String[]{o.dimension(), o.houseName()});
                    w.setDirty();
                }
            }
            // bien disparu (portes retirées, renommé) : ce qui y a été posé revient quand même à ceux qui l'ont posé
            for (String key : new HashSet<>(w.holders.keySet())) {
                if (seen.contains(key)) continue;
                String[] n = w.names.getOrDefault(key, new String[]{PlacedLedger.dimOf(key), key.substring(key.indexOf('|') + 1)});
                Mover.moveOut(s, key, n[0], n[1], "bien supprimé ou renommé");
                w.holders.remove(key);
                w.names.remove(key);
                w.setDirty();
            }
        } catch (RuntimeException ex) {
            LOG.error("[Portes/déco] Vérification des déménagements impossible", ex);
        } finally {
            running = false;
        }
    }

    private static MoveWatcher load(CompoundTag tag) {
        MoveWatcher w = new MoveWatcher();
        for (Tag t : tag.getList("Holders", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            w.holders.put(c.getString("K"), c.getString("H"));
            w.names.put(c.getString("K"), new String[]{c.getString("D"), c.getString("N")});
        }
        return w;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        holders.forEach((k, h) -> {
            CompoundTag c = new CompoundTag();
            c.putString("K", k);
            c.putString("H", h);
            String[] n = names.getOrDefault(k, new String[]{"", ""});
            c.putString("D", n[0]);
            c.putString("N", n[1]);
            list.add(c);
        });
        tag.put("Holders", list);
        return tag;
    }
}
