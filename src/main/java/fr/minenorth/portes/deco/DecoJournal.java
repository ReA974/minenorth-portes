package fr.minenorth.portes.deco;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Journal détaillé de la décoration : logs/minenorth-deco.log (achats, rangements, sorties, déménagements objet par objet, remises à nu).
 * Le journal du bien (panneau admin, 100 dernières lignes) reçoit en plus un résumé de chaque déménagement.
 */
public final class DecoJournal {
    private DecoJournal() {}

    private static final Logger LOG = LogUtils.getLogger();
    private static final Path FILE = FMLPaths.GAMEDIR.get().resolve("logs").resolve("minenorth-deco.log");

    public static synchronized void log(String line) {
        String l = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " " + line;
        LOG.info("[Portes/déco] {}", line);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, l + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            LOG.warn("[Portes/déco] journal illisible : {}", e.toString());
        }
    }
}
