package fr.minenorth.portes.gang;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Catalogue de la Fabrique d'armes, stocké dans config/Minenorth-gangs/fabrique_catalogue.json : écrit par le mod (panneau admin), relu tout seul
 * quand le fichier change (vérification au plus toutes les 2 s). Un fichier illisible n'efface rien : on garde le dernier catalogue valide.
 * Au premier lancement, le catalogue est créé à partir des anciennes recettes de minenorth_gangs.toml (migration).
 */
public final class FactoryCatalogue {
    private FactoryCatalogue() {}

    private static final Logger LOG = LogUtils.getLogger();
    private static List<FactoryRecipe> cache;
    private static long stamp = -1;
    private static long lastCheck;

    public static Path file() { return GangPaths.file("fabrique_catalogue.json"); }

    public static synchronized List<FactoryRecipe> all() {
        poll(false);
        return new ArrayList<>(cache);
    }

    public static synchronized void save(List<FactoryRecipe> list) {
        cache = new ArrayList<>(list);
        write();
    }

    /** Relit le fichier s'il a changé (force = sans attendre le délai). */
    public static synchronized void poll(boolean force) {
        long now = System.currentTimeMillis();
        if (cache != null && !force && now - lastCheck < 2000) return;
        lastCheck = now;
        Path f = file();
        try {
            if (!Files.exists(f)) {
                if (cache == null) cache = FactoryRecipe.parseAll(GangConfig.legacyRecipeLines());
                write();
                return;
            }
            long mod = Files.getLastModifiedTime(f).toMillis();
            if (cache != null && mod == stamp) return;
            List<FactoryRecipe> read = FactoryCatalogueJson.fromJson(Files.readString(f, StandardCharsets.UTF_8));
            if (read == null) {
                LOG.warn("[Portes/gangs] {} est illisible : le dernier catalogue valide est conservé.", f.getFileName());
                if (cache == null) cache = FactoryRecipe.parseAll(GangConfig.legacyRecipeLines());
                stamp = mod;
                return;
            }
            cache = read;
            stamp = mod;
            LOG.info("[Portes/gangs] Catalogue de la fabrique rechargé : {} recette(s).", read.size());
        } catch (IOException e) {
            if (cache == null) cache = FactoryRecipe.parseAll(GangConfig.legacyRecipeLines());
        }
    }

    private static void write() {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, FactoryCatalogueJson.toJson(cache), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFail) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
            stamp = Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            LOG.warn("[Portes/gangs] Impossible d'écrire {} : {}", f.getFileName(), e.toString());
        }
    }
}
