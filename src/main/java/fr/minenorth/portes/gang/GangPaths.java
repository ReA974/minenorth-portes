package fr.minenorth.portes.gang;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Dossier des fichiers de configuration des gangs : config/Minenorth-gangs/ (minenorth_gangs.toml, fabrique_catalogue.json).
 * Les fichiers qui se trouvaient dans l'ancien dossier config/Minenorth-portes/ sont déplacés ici une seule fois, au premier accès.
 */
public final class GangPaths {
    private GangPaths() {}

    private static Path dir;

    public static synchronized Path dir() {
        if (dir == null) {
            Path d = FMLPaths.CONFIGDIR.get().resolve("Minenorth-gangs");
            try {
                Files.createDirectories(d);
                Path old = FMLPaths.CONFIGDIR.get().resolve("Minenorth-portes");
                for (String name : new String[]{"minenorth_gangs.toml", "fabrique_catalogue.json"}) {
                    Path from = old.resolve(name), to = d.resolve(name);
                    if (Files.exists(from) && !Files.exists(to)) Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ignored) {
                // on continue : les valeurs par défaut s'appliquent
            }
            dir = d;
        }
        return dir;
    }

    public static Path file(String name) { return dir().resolve(name); }
}
