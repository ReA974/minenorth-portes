package fr.minenorth.portes.deco;

import com.mojang.logging.LogUtils;
import fr.minenorth.portes.door.OwnerDoorData;
import fr.minenorth.portes.door.WorldGuardBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * /diagdeco (tout joueur, pour lui-même) : explique pourquoi l'appli Déco le voit chez lui ou non. Résultat dans le chat et dans latest.log :
 * position comparée, biens dont il est occupant (par UUID) avec la méthode de détection et le résultat de chaque test, portes proches et leur
 * titulaire enregistré. Ne donne que ce qu'un joueur voit déjà en cliquant sur une porte, et seulement dans un rayon de {@value #NEAR} blocs.
 */
final class DecoDiag {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int NEAR = 48, MAX = 10;

    private DecoDiag() {}

    static int run(ServerPlayer p) {
        List<String> out = new ArrayList<>();
        BlockPos pos = p.blockPosition();
        String dim = p.level().dimension().location().toString();
        var s = p.server;
        var homes = Zones.homes(s);
        int rayon = DecoConfig.get().rayonSansRegion;
        out.add("Joueur " + p.getGameProfile().getName() + " (" + p.getUUID() + ")");
        out.add("Position " + pos.toShortString() + " dans " + dim);
        out.add("Déco active : " + DecoConfig.get().actif + ", rayon autour des portes : " + rayon + ", biens connus : " + homes.size());
        Path f = WorldGuardBridge.regionsFile(s, dim);
        out.add("Fichier WorldGuard " + (Files.isRegularFile(f) ? "lu" : "ABSENT") + " : " + f);

        int mine = 0;
        for (Zones.Home h : homes) {
            if (!h.livesHere(p.getUUID())) continue;
            if (++mine > MAX) continue;
            var o = h.occ();
            boolean holder = p.getUUID().equals(o.holder());
            String method = h.terrain() ? "zone du terrain" : h.fromRegion() ? "région " + WorldGuardBridge.regionName(o.houseName()) : "portes (région " + WorldGuardBridge.regionName(o.houseName()) + " introuvable)";
            double near = -1;
            for (BlockPos d : o.doors()) { double v = Math.sqrt(d.distSqr(pos)); if (near < 0 || v < near) near = v; }
            out.add("Bien « " + o.houseName() + " » (" + o.category().label() + ", " + (holder ? "titulaire" : "colocataire") + ") dans " + o.dimension());
            out.add("  détection : " + method + " ; dans la zone : " + h.contains(p.level(), pos) + " ; abords des portes : " + h.around(p.level(), pos)
                    + " ; porte la plus proche : " + (near < 0 ? "aucune" : (int) near + " blocs"));
        }
        if (mine == 0) out.add("Occupant d'aucun bien pour cet UUID.");
        else if (mine > MAX) out.add("… et " + (mine - MAX) + " autre(s) bien(s).");

        int doors = 0;
        for (var en : OwnerDoorData.get(s.overworld()).allDoors()) {
            BlockPos d = en.getKey().pos();
            if (!en.getKey().dimension().location().toString().equals(dim) || d.distSqr(pos) > NEAR * NEAR) continue;
            if (++doors > MAX) continue;
            var e = en.getValue();
            out.add("Porte " + d.toShortString() + " : maison « " + e.houseName() + " », type " + e.type() + ", titulaire " + e.ownerName() + " (" + e.owner() + ")"
                    + (e.temporary() ? ", temporaire" : "") + (p.getUUID().equals(e.owner()) ? ", C'EST VOUS" : ""));
        }
        if (doors == 0) out.add("Aucune porte enregistrée à moins de " + NEAR + " blocs.");
        else if (doors > MAX) out.add("… et " + (doors - MAX) + " autre(s) porte(s).");

        Zones.Home home = Zones.homeOf(p);
        out.add("Résultat : " + (home == null ? "PAS CHEZ VOUS" : "CHEZ VOUS, « " + home.occ().houseName() + " »"));
        for (String line : out) {
            p.sendSystemMessage(Component.literal("§b[diagdeco] §f" + line));
            LOG.info("[diagdeco] {}", line);
        }
        return 1;
    }
}
