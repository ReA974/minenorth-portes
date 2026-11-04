package fr.minenorth.portes.deco;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * config/Minenorth-portes/deco.json : réglages de la décoration (application Déco, garde-meuble, déménagement) et des terrains à bâtir.
 * Créé au premier démarrage, complété avec les nouveaux réglages à chaque lecture ; relu par /deco recharger (OP).
 */
public final class DecoConfig {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static final Path DIR = FMLPaths.CONFIGDIR.get().resolve("Minenorth-portes");
    private static final Path FILE = DIR.resolve("deco.json");

    public String _aide = "gardeMeubleBase = objets rangés au plus (les VIP ajoutent l'avantage gardeMeuble du mod VIP). Un déménagement automatique "
            + "n'est jamais refusé, même au-delà. rayonSansRegion : « chez soi » = dans la région WorldGuard de la maison, ou à moins de ce "
            + "nombre de blocs d'une de ses portes. Terrains : surface et côtés maximum à la création, hauteur / profondeur maximum autour du sol, "
            + "blocsParTick = vitesse de remise à nu.";
    public boolean actif = true;
    public int gardeMeubleBase = 300;
    public int rayonSansRegion = 12;
    public int intervalleSecondes = 5;
    public int terrainCoteMax = 128;
    public int terrainSurfaceMax = 8192;
    public int terrainHauteurMax = 96;
    public int terrainProfondeurMax = 48;
    public int terrainVolumeMax = 1_500_000;
    public int terrainBlocsParTick = 4000;

    private static DecoConfig current = new DecoConfig();

    public static DecoConfig get() { return current; }

    public static void load() {
        try {
            Files.createDirectories(DIR);
            DecoConfig c = Files.exists(FILE) ? GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), DecoConfig.class) : null;
            if (c == null) c = new DecoConfig();
            c.gardeMeubleBase = Math.max(0, c.gardeMeubleBase);
            c.rayonSansRegion = Math.max(1, Math.min(64, c.rayonSansRegion));
            c.intervalleSecondes = Math.max(1, c.intervalleSecondes);
            c.terrainBlocsParTick = Math.max(100, c.terrainBlocsParTick);
            c._aide = new DecoConfig()._aide;
            current = c;
            Files.writeString(FILE, GSON.toJson(c), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("[Portes/déco] deco.json illisible, réglages précédents conservés : {}", e.toString());
        }
    }
}
