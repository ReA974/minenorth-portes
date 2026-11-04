package fr.minenorth.portes.deco;

import fr.minenorth.api.MineNorth;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Avantages VIP de la décoration et de l'immobilier, lus dans le mod VIP via l'API MineNorth (purement cosmétiques ou de confort :
 * rien ne s'achète avec de l'argent réel dans le jeu, conforme à l'EULA) :
 * decoVip (1 = collection VIP de l'application Déco, 2 = VIP et VIP+), gardeMeuble (objets en plus dans le garde-meuble),
 * logementsBonus (logements en plus : maison, appartement, terrain). Sans mod VIP : 0 partout.
 */
public final class VipPerks {
    private VipPerks() {}

    private static int perk(MinecraftServer s, UUID id, String name) {
        try {
            return (int) Math.max(0, Math.floor(MineNorth.grades().perk(s, id, name, 0)));
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    /** Niveau de collection exclusive accessible (les opérateurs voient tout, pour les tests). */
    public static int decoLevel(ServerPlayer p) { return p.hasPermissions(2) ? 2 : perk(p.server, p.getUUID(), "decoVip"); }

    public static int storageBonus(MinecraftServer s, UUID id) { return perk(s, id, "gardeMeuble"); }

    public static int housingBonus(ServerPlayer p) { return perk(p.server, p.getUUID(), "logementsBonus"); }

    /** Capacité du garde-meuble (en objets). */
    public static int capacity(MinecraftServer s, UUID id) { return DecoConfig.get().gardeMeubleBase + storageBonus(s, id); }
}
