package fr.minenorth.portes.api;

import fr.minenorth.portes.agency.AgencyService;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * API pour les autres mods MineNorth (le mod Carte l'appelle par réflexion : ne pas changer les noms ni les paramètres).
 * Thread serveur uniquement.
 */
public final class PortesApi {
    private PortesApi() {}

    /** Locaux d'entreprise (achetés ou loués en cours), une ligne par bien : « idEntreprise|dimension|x|y|z|nomDuBien ». */
    public static List<String> companyBuildings(MinecraftServer s) {
        List<String> out = new ArrayList<>();
        for (AgencyService.CompanyBuilding b : AgencyService.companyBuildings(s)) {
            out.add(b.companyId() + "|" + b.dimension() + "|" + b.x() + "|" + b.y() + "|" + b.z() + "|" + b.houseName());
        }
        return out;
    }
}
