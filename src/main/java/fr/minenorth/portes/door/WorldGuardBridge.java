package fr.minenorth.portes.door;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Lien avec le plugin WorldGuard : chaque maison (les portes qui portent le même nom de maison) correspond à une région WorldGuard du même nom ;
 * le propriétaire et le locataire en cours de la maison en sont « membres » (ils peuvent poser et casser des blocs), personne d'autre.
 * Le pont compare régulièrement l'état voulu (propriétaires et locataires des portes) à ce qu'il a déjà appliqué et lance les commandes
 * WorldGuard de la différence à la console : achat, vente, location, fin de bail, expulsion sont donc tous pris en compte sans toucher au reste du mod.
 * Les régions elles-mêmes sont créées à la main par le staff (/rg define maison_12 …), avec le nom de la maison, sans espaces ni accents.
 */
@Mod.EventBusSubscriber
public final class WorldGuardBridge {
    private WorldGuardBridge() {}

    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("Minenorth-portes").resolve("worldguard.json");

    /** config/Minenorth-portes/worldguard.json */
    public static final class Cfg {
        /** false = le pont ne fait rien. */
        public boolean actif = true;
        public int intervalleSecondes = 5;
        /** Variables : {region}, {joueur}, {monde}. Lancées à la console du serveur. */
        public String commandeAjout = "region addmember -w {monde} {region} {joueur}";
        public String commandeRetrait = "region removemember -w {monde} {region} {joueur}";
        /** Lancée aussi au retrait : au cas où le joueur serait propriétaire (owner) et non simple membre de la région. Vide = désactivée. */
        public String commandeRetraitProprietaire = "region removeowner -w {monde} {region} {joueur}";
        /** Ajouté devant le nom de la maison pour obtenir le nom de la région (ex. « maison_ »). */
        public String prefixeRegion = "";
        /** Nom du monde Bukkit de l'overworld ; vide = nom du dossier du monde (le nether et l'end y ajoutent _nether / _the_end). */
        public String mondeOverworld = "";
        public int maxCommandesParCycle = 10;
    }

    private static Cfg cfg = new Cfg();
    private static int counter;

    /** Ce que le pont a déjà appliqué : région -> (uuid -> pseudo). Sauvegardé avec le monde. */
    public static final class Applied extends SavedData {
        private static final String NAME = "minenorth_portes_wg";
        final Map<String, Map<UUID, String>> regions = new HashMap<>();

        static Applied get(MinecraftServer s) {
            return s.overworld().getDataStorage().computeIfAbsent(Applied::load, Applied::new, NAME);
        }

        static Applied load(CompoundTag tag) {
            Applied a = new Applied();
            for (Tag t : tag.getList("regions", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) t;
                Map<UUID, String> m = new LinkedHashMap<>();
                for (Tag u : c.getList("members", Tag.TAG_COMPOUND)) {
                    CompoundTag mc = (CompoundTag) u;
                    m.put(mc.getUUID("id"), mc.getString("name"));
                }
                a.regions.put(c.getString("key"), m);
            }
            return a;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            regions.forEach((k, m) -> {
                CompoundTag c = new CompoundTag();
                c.putString("key", k);
                ListTag ml = new ListTag();
                m.forEach((id, n) -> {
                    CompoundTag mc = new CompoundTag();
                    mc.putUUID("id", id);
                    mc.putString("name", n);
                    ml.add(mc);
                });
                c.put("members", ml);
                list.add(c);
            });
            tag.put("regions", list);
            return tag;
        }
    }

    // ------------------------------------------------------------------ config

    @SubscribeEvent
    public static void onStarting(ServerStartingEvent e) {
        try {
            Files.createDirectories(FILE.getParent());
            if (Files.exists(FILE)) {
                Cfg c = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), Cfg.class);
                if (c != null) cfg = c;
            }
            cfg.intervalleSecondes = Math.max(1, cfg.intervalleSecondes);
            cfg.maxCommandesParCycle = Math.max(1, cfg.maxCommandesParCycle);
            Files.writeString(FILE, GSON.toJson(cfg), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            LOG.warn("[Portes] worldguard.json illisible, valeurs par défaut : {}", ex.toString());
        }
    }

    /** Oublie ce qui a été appliqué : tout est renvoyé à WorldGuard au prochain cycle (région créée après coup, région supprimée…). */
    public static int resync(MinecraftServer s) {
        Applied a = Applied.get(s);
        int n = a.regions.size();
        a.regions.clear();
        a.setDirty();
        counter = Integer.MAX_VALUE / 2;
        return n;
    }

    // ------------------------------------------------------------------ cycle

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !cfg.actif) return;
        if (++counter < cfg.intervalleSecondes * 20) return;
        counter = 0;
        try {
            sync(e.getServer());
        } catch (Exception ex) {
            LOG.warn("[Portes] Synchronisation WorldGuard impossible : {}", ex.toString());
        }
    }

    private record House(String dim, String name) {}

    private static void sync(MinecraftServer s) {
        OwnerDoorData data = OwnerDoorData.get(s.overworld());
        Map<House, Map<UUID, String>> desired = new LinkedHashMap<>();
        for (var en : data.allDoors()) {
            OwnerDoorData.DoorEntry d = en.getValue();
            // Local d'entreprise (acheté ou loué via l'agence) : le patron est membre de la région.
            boolean company = d.type() == OwnerDoorData.DoorType.ENTREPRISE && EntrepriseCompat.linked(d.permission());
            if ((d.type() != OwnerDoorData.DoorType.PERSONAL && !company) || d.temporary() || d.houseName() == null || d.houseName().isBlank()) continue;
            House h = new House(en.getKey().dimension().location().toString(), d.houseName().trim().toLowerCase(Locale.ROOT));
            Map<UUID, String> m = desired.computeIfAbsent(h, k -> new LinkedHashMap<>());
            if (company) {
                UUID boss = EntrepriseCompat.ownerUuid(s, EntrepriseCompat.id(d.permission()));
                if (boss != null) put(s, m, boss);
                continue;
            }
            if (d.owner() != null) {
                put(s, m, d.owner());
                for (UUID friend : data.trusted(d.owner())) put(s, m, friend);   // joueurs à qui le propriétaire a donné les droits (menu porte)
            }
            OwnerDoorData.DoorListing l = data.listing(en.getKey());
            if (l != null && l.rentalActive()) put(s, m, l.renter());
        }
        Applied applied = Applied.get(s);
        int budget = cfg.maxCommandesParCycle;
        // maisons connues : ajouts
        for (var he : desired.entrySet()) {
            String key = key(he.getKey());
            if (!regionExists(s, he.getKey())) { warnMissing(he.getKey()); continue; }
            MISSING_WARNED.remove(key);
            Map<UUID, String> have = applied.regions.computeIfAbsent(key, k -> new LinkedHashMap<>());
            for (var m : he.getValue().entrySet()) {
                if (have.containsKey(m.getKey())) continue;
                if (budget-- <= 0) return;
                if (run(cfg.commandeAjout, he.getKey(), m.getValue(), s)) { have.put(m.getKey(), m.getValue()); applied.setDirty(); }
            }
        }
        // retraits : membres qui ne sont plus propriétaires ni locataires, et maisons disparues
        Set<String> wanted = new LinkedHashSet<>();
        desired.keySet().forEach(h -> wanted.add(key(h)));
        for (var re : new HashMap<>(applied.regions).entrySet()) {
            House h = parse(re.getKey());
            if (!regionExists(s, h)) {   // région supprimée : rien à retirer, on oublie
                applied.regions.remove(re.getKey());
                applied.setDirty();
                continue;
            }
            Map<UUID, String> want = wanted.contains(re.getKey()) ? desired.get(h) : Map.of();
            for (var m : new HashMap<>(re.getValue()).entrySet()) {
                if (want != null && want.containsKey(m.getKey())) continue;
                if (budget-- <= 0) return;
                if (revoke(h, m.getValue(), s)) { re.getValue().remove(m.getKey()); applied.setDirty(); }
            }
            if (re.getValue().isEmpty() && !wanted.contains(re.getKey())) { applied.regions.remove(re.getKey()); applied.setDirty(); }
        }
    }

    /** Retire le joueur de la région (membre, et propriétaire si la config le prévoit). */
    private static boolean revoke(House h, String player, MinecraftServer s) {
        boolean ok = run(cfg.commandeRetrait, h, player, s);
        if (cfg.commandeRetraitProprietaire != null && !cfg.commandeRetraitProprietaire.isBlank()) run(cfg.commandeRetraitProprietaire, h, player, s);
        return ok;
    }

    /**
     * Retrait immédiat (revente, expulsion…) : sans attendre le prochain cycle de synchronisation ni dépendre de ce que le pont a déjà appliqué
     * (un joueur ajouté à la région à la main, ou avant la mise en place du pont, serait sinon oublié).
     */
    public static void release(MinecraftServer s, String dim, String houseName, UUID id, String playerName) {
        if (!cfg.actif || houseName == null || houseName.isBlank() || playerName == null || playerName.isBlank()) return;
        House h = new House(dim, houseName.trim().toLowerCase(Locale.ROOT));
        if (!regionExists(s, h)) return;
        revoke(h, playerName, s);
        Applied applied = Applied.get(s);
        Map<UUID, String> have = applied.regions.get(key(h));
        if (have != null && have.remove(id) != null) applied.setDirty();
    }

    private static final Set<String> MISSING_WARNED = new LinkedHashSet<>();

    private static void warnMissing(House h) {
        if (MISSING_WARNED.add(key(h)))
            LOG.warn("[Portes] Région WorldGuard « {} » introuvable pour la maison « {} » : créez-la (/rg define {}), puis /portewg resync.", regionName(h.name()), h.name(), regionName(h.name()));
    }

    /**
     * La région existe-t-elle ? Lecture du fichier regions.yml de WorldGuard (plugins/WorldGuard/worlds/<monde>/regions.yml) ; si le fichier est
     * absent (autre stockage, autre chemin), on suppose qu'elle existe. WorldGuard enregistre ses régions avec un petit délai après un /rg define.
     */
    private static boolean regionExists(MinecraftServer s, House h) {
        Path f = FMLPaths.GAMEDIR.get().resolve("plugins").resolve("WorldGuard").resolve("worlds").resolve(worldName(s, h.dim())).resolve("regions.yml");
        try {
            if (!Files.isRegularFile(f)) return true;
            String text = Files.readString(f, StandardCharsets.UTF_8);
            String id = regionName(h.name());
            return java.util.regex.Pattern.compile("(?m)^ {2,8}" + java.util.regex.Pattern.quote(id) + ":[ \\t\\r]*$").matcher(text).find();
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    private static void put(MinecraftServer s, Map<UUID, String> m, UUID id) {
        if (m.containsKey(id)) return;
        Optional<GameProfile> p = s.getProfileCache() == null ? Optional.empty() : s.getProfileCache().get(id);
        p.ifPresent(gp -> m.put(id, gp.getName()));      // pseudo introuvable : on réessaie au cycle suivant
    }

    private static String key(House h) { return h.dim() + "|" + h.name().toLowerCase(Locale.ROOT); }

    private static House parse(String key) {
        int i = key.indexOf('|');
        return new House(key.substring(0, i), key.substring(i + 1));
    }

    /** Nom de région WorldGuard : minuscules, sans accents, lettres / chiffres / _ - uniquement. */
    public static String regionName(String house) {
        String n = Normalizer.normalize(house, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        n = n.replaceAll("[^a-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        return cfg.prefixeRegion + n;
    }

    private static String worldName(MinecraftServer s, String dim) {
        String base = cfg.mondeOverworld.isBlank() ? s.getWorldData().getLevelName() : cfg.mondeOverworld;
        return switch (dim) {
            case "minecraft:overworld" -> base;
            case "minecraft:the_nether" -> base + "_nether";
            case "minecraft:the_end" -> base + "_the_end";
            default -> dim.substring(dim.indexOf(':') + 1);
        };
    }

    private static boolean run(String template, House h, String player, MinecraftServer s) {
        String cmd = template.replace("{region}", regionName(h.name())).replace("{joueur}", player).replace("{monde}", worldName(s, h.dim()));
        try {
            boolean ok = false;
            try {   // Arclight : console Bukkit
                Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
                Class<?> sender = Class.forName("org.bukkit.command.CommandSender");
                Object console = bukkit.getMethod("getConsoleSender").invoke(null);
                ok = Boolean.TRUE.equals(bukkit.getMethod("dispatchCommand", sender, String.class).invoke(null, console, cmd));
            } catch (ReflectiveOperationException | LinkageError ignored) {}
            if (!ok) {
                s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), cmd);
                ok = true;
            }
            LOG.info("[Portes] WorldGuard : {}", cmd);
            return ok;
        } catch (Exception ex) {
            LOG.warn("[Portes] Commande WorldGuard refusée ({}) : {}", cmd, ex.toString());
            return false;
        }
    }
}
