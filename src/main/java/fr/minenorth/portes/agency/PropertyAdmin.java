package fr.minenorth.portes.agency;

import fr.minenorth.api.MineNorth;
import fr.minenorth.portes.agency.AgencyService.House;
import fr.minenorth.portes.door.EntrepriseCompat;
import fr.minenorth.portes.door.OwnerDoorData;
import fr.minenorth.portes.door.OwnerDoorData.DoorEntry;
import fr.minenorth.portes.door.OwnerDoorData.DoorKey;
import fr.minenorth.portes.door.OwnerDoorData.DoorListing;
import fr.minenorth.portes.door.OwnerDoorData.ListingMode;
import fr.minenorth.portes.door.WorldGuardBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Gestion des propriétés pour le staff (panneau MineNorth Admin, via {@link fr.minenorth.portes.api.PropertyAdminApi}).
 * Aucun contrôle de droit ici : c'est l'appelant (panneau admin) qui vérifie le staff. Toutes les actions passent par les mêmes
 * données que l'agence (OwnerDoorData) : sauvegardes inchangées. Thread serveur uniquement.
 */
public final class PropertyAdmin {
    private PropertyAdmin() {}

    private static final long DAY_MS = 86_400_000L;
    public static final long MAX_PRICE_CENTS = 10_000_000_000L;   // 100 millions d'euros
    public static final int MAX_DAYS = 365;

    private static String ok(String m) { return "OK\t" + m; }
    private static String ko(String m) { return "KO\t" + m; }

    private static String uuid(UUID u) { return u == null ? "" : u.toString(); }

    // ------------------------------------------------------------------ lecture

    /**
     * Une ligne par propriété, champs séparés par une tabulation (un nom de maison n'en contient jamais) :
     * 0 dimension, 1 nom, 2 catégorie (nom de l'enum), 3 fiche (NONE / SALE / RENTAL), 4 prix de la fiche en centimes, 5 jours de location,
     * 6 uuid du propriétaire, 7 uuid du locataire, 8 id entreprise locataire, 9 fin de location (ms), 10 nombre de portes, 11 x, 12 y, 13 z,
     * 14 catalogue, 15 id entreprise propriétaire, 16 nom de cette entreprise, 17 prix d'achat en centimes, 18 location en cours (0/1), 19 nombre de colocataires.
     */
    public static List<String> list(MinecraftServer s) {
        OwnerDoorData d = OwnerDoorData.get(s.overworld());
        PropertyInfoData infos = PropertyInfoData.get(s.overworld());
        List<String> out = new ArrayList<>();
        for (House h : AgencyService.houses(d)) out.add(line(s, d, infos, h));
        return out;
    }

    public static String one(MinecraftServer s, String dimension, String houseName) {
        OwnerDoorData d = OwnerDoorData.get(s.overworld());
        House h = AgencyService.find(d, dimension, houseName);
        return h == null ? "" : line(s, d, PropertyInfoData.get(s.overworld()), h);
    }

    private static String line(MinecraftServer s, OwnerDoorData d, PropertyInfoData infos, House h) {
        DoorEntry e = d.entry(h.first());
        DoorListing l = AgencyService.listing(d, h);
        var info = infos.info(d, h.dimension(), h.name());
        boolean company = e != null && AgencyService.companyDoor(e);
        UUID owner = e != null && e.type() == OwnerDoorData.DoorType.PERSONAL ? e.owner() : null;
        boolean rented = l != null && l.mode() == ListingMode.RENTAL && l.rentalActive();
        UUID holder = owner != null ? owner : rented ? l.renter() : null;
        int cid = company ? EntrepriseCompat.id(e.permission()) : 0;
        BlockPos p = h.first().pos();
        return String.join("\t", h.dimension(), h.name(), info.category().name(), l == null ? "NONE" : l.mode().name(),
                String.valueOf(l == null ? 0 : l.price()), String.valueOf(l == null ? 0 : l.days()), uuid(owner), rented ? uuid(l.renter()) : "",
                String.valueOf(rented ? l.renterCompany() : 0), String.valueOf(rented ? l.expiryMs() : 0), String.valueOf(h.doors().size()),
                String.valueOf(p.getX()), String.valueOf(p.getY()), String.valueOf(p.getZ()), info.catalogue(),
                String.valueOf(cid), company ? EntrepriseCompat.name(s, cid) : "", String.valueOf(d.housePurchasePrice(h.first())),
                rented ? "1" : "0", String.valueOf(holder == null ? 0 : d.trusted(holder).size()));
    }

    /** Colocataires (joueurs de confiance) du titulaire du bien : « uuid » par ligne. */
    public static List<String> trusted(MinecraftServer s, String dimension, String houseName) {
        OwnerDoorData d = OwnerDoorData.get(s.overworld());
        House h = AgencyService.find(d, dimension, houseName);
        List<String> out = new ArrayList<>();
        UUID holder = h == null ? null : holder(d, h);
        if (holder != null) for (UUID u : d.trusted(holder)) out.add(u.toString());
        return out;
    }

    /** Journal du bien, du plus récent au plus ancien : « date en ms » TAB « texte ». */
    public static List<String> log(MinecraftServer s, String dimension, String houseName) {
        List<String> out = new ArrayList<>();
        for (PropertyLog.Entry e : PropertyLog.entries(s, dimension, houseName)) out.add(e.time() + "\t" + e.text());
        return out;
    }

    private static UUID holder(OwnerDoorData d, House h) {
        DoorEntry e = d.entry(h.first());
        if (e != null && e.type() == OwnerDoorData.DoorType.PERSONAL && e.owner() != null) return e.owner();
        DoorListing l = AgencyService.listing(d, h);
        return l != null && l.mode() == ListingMode.RENTAL && l.rentalActive() ? l.renter() : null;
    }

    // ------------------------------------------------------------------ actions

    /**
     * Action sur un bien. action : setOwner (player), free, evict, sale (n = prix en centimes), rent (n = prix, days), unlist,
     * price (n), category (text = nom de l'enum), setRenter (player, days), addTrusted / removeTrusted (player).
     * Renvoie « OK » ou « KO » + TAB + message. actor : nom du membre du staff, écrit au journal du bien.
     */
    public static String act(MinecraftServer s, String action, String dimension, String houseName, String player, String playerName, long n, int days, String text, String actor) {
        OwnerDoorData d = OwnerDoorData.get(s.overworld());
        House h = AgencyService.find(d, dimension, houseName);
        if (h == null) return ko("Propriété introuvable (liste modifiée ?). Actualise.");
        DoorKey key = h.first();
        DoorEntry e = d.entry(key);
        DoorListing l = AgencyService.listing(d, h);
        boolean rented = l != null && l.mode() == ListingMode.RENTAL && l.rentalActive();
        UUID target = null;
        if (player != null && !player.isBlank()) {
            try { target = UUID.fromString(player); } catch (IllegalArgumentException ex) { return ko("Joueur invalide."); }
        }
        String who = playerName == null || playerName.isBlank() ? (target == null ? "" : target.toString()) : playerName;
        String name = h.name();
        String by = actor == null || actor.isBlank() ? "Staff" : actor;
        try {
            switch (action) {
                case "setOwner" -> {
                    if (target == null) return ko("Choisis un joueur.");
                    if (!personalHouse(d, h)) return ko("Cette propriété n'a pas de porte de maison à attribuer.");
                    Set<UUID> before = occupants(s, d, h);
                    if (rented) d.endRental(key);
                    if (e != null && AgencyService.companyDoor(e)) d.returnHouseToMarket(key, 0);
                    d.transferHouse(key, target, who, 0);   // prix d'achat 0 : pas de revente remboursée sur une attribution du staff
                    d.clearHouseListings(key);
                    before.remove(target);
                    releaseAll(s, h, before);
                    AgencyService.persist(s, d);
                    PropertyLog.add(s, h.dimension(), name, by + " (staff) attribue le bien à " + who);
                    return ok(name + " attribué à " + who + ".");
                }
                case "free" -> {
                    Set<UUID> before = occupants(s, d, h);
                    if (rented) d.endRental(key);
                    d.returnHouseToMarket(key, 0);
                    d.clearHouseListings(key);
                    d.setHouseSecurity(key, 0);
                    releaseAll(s, h, before);
                    AgencyService.persist(s, d);
                    PropertyLog.add(s, h.dimension(), name, by + " (staff) libère le bien : propriétaire, locataire, fiche et serrures remis à zéro");
                    return ok(name + " libérée : portes sans propriétaire, sans fiche, serrures retirées, droits WorldGuard retirés.");
                }
                case "evict" -> {
                    Set<UUID> before = occupants(s, d, h);
                    if (rented) {
                        d.endRental(key);
                        releaseAll(s, h, before);
                        AgencyService.persist(s, d);
                        PropertyLog.add(s, h.dimension(), name, by + " (staff) expulse le locataire (sans remboursement) : bien de nouveau en location");
                        return ok("Locataire expulsé de " + name + " : le bien est de nouveau disponible à la location.");
                    }
                    if (before.isEmpty() && (e == null || (e.owner() == null && !AgencyService.companyDoor(e)))) return ko("Aucun occupant à expulser.");
                    long purchase = d.housePurchasePrice(key);
                    d.returnHouseToMarket(key, purchase);
                    if (purchase <= 0) d.clearHouseListings(key);
                    releaseAll(s, h, before);
                    AgencyService.persist(s, d);
                    PropertyLog.add(s, h.dimension(), name, by + " (staff) expulse le propriétaire (sans remboursement)");
                    return ok("Propriétaire expulsé de " + name + (purchase > 0 ? " : le bien est remis en vente à son prix d'achat." : "."));
                }
                case "sale" -> {
                    if (rented) return ko("Un bien loué ne se vend pas : expulse le locataire d'abord.");
                    if (n < 1 || n > MAX_PRICE_CENTS) return ko("Prix invalide (0,01 € à 100 000 000 €).");
                    d.relistHouseForSale(key, n);
                    AgencyService.persist(s, d);
                    PropertyLog.add(s, h.dimension(), name, by + " (staff) met le bien en vente à " + AgencyService.euros(n));
                    return ok(name + " mise en vente à " + AgencyService.euros(n) + ".");
                }
                case "rent" -> {
                    if (rented) return ko("Ce bien est déjà loué.");
                    if (n < 1 || n > MAX_PRICE_CENTS) return ko("Prix invalide (0,01 € à 100 000 000 €).");
                    if (days < 1 || days > MAX_DAYS) return ko("Durée invalide (1 à " + MAX_DAYS + " jours).");
                    d.relistHouseForRental(key, n, days);
                    AgencyService.persist(s, d);
                    PropertyLog.add(s, h.dimension(), name, by + " (staff) met le bien en location : " + AgencyService.euros(n) + " pour " + days + " j");
                    return ok(name + " mise en location : " + AgencyService.euros(n) + " pour " + days + " jour(s).");
                }
                case "unlist" -> {
                    if (l == null) return ko("Ce bien n'a pas de fiche de vente ou de location.");
                    if (rented) return ko("Location en cours : expulse le locataire d'abord.");
                    d.clearHouseListings(key);
                    AgencyService.persist(s, d);
                    PropertyLog.add(s, h.dimension(), name, by + " (staff) retire la fiche de " + (l.mode() == ListingMode.SALE ? "vente" : "location"));
                    return ok("Fiche retirée : " + name + " n'est plus proposée.");
                }
                case "price" -> {
                    if (l == null) return ko("Pas de fiche : mets d'abord le bien en vente ou en location.");
                    if (n < 1 || n > MAX_PRICE_CENTS) return ko("Prix invalide (0,01 € à 100 000 000 €).");
                    long old = l.price();
                    d.setListingPrice(key, n);
                    AgencyService.persist(s, d);
                    PropertyLog.add(s, h.dimension(), name, by + " (staff) change le prix : " + AgencyService.euros(old) + " -> " + AgencyService.euros(n));
                    return ok("Prix de " + name + " : " + AgencyService.euros(n) + ".");
                }
                case "category" -> {
                    PropertyCategory c;
                    try { c = PropertyCategory.valueOf(text == null ? "" : text.trim()); } catch (IllegalArgumentException ex) { return ko("Catégorie inconnue."); }
                    PropertyInfoData infos = PropertyInfoData.get(s.overworld());
                    var info = infos.info(d, h.dimension(), name);
                    if (info.category() == c) return ko("Cette catégorie est déjà celle du bien.");
                    infos.set(h.dimension(), name, c, info.furnished(), info.description(), info.catalogue(), info.payeeCompany());
                    PropertyLog.add(s, h.dimension(), name, by + " (staff) change la catégorie : " + info.category().label() + " -> " + c.label());
                    return ok("Catégorie de " + name + " : " + c.label() + ".");
                }
                case "setRenter" -> {
                    if (target == null) return ko("Choisis un joueur.");
                    if (l == null || l.mode() != ListingMode.RENTAL) return ko("Mets d'abord le bien en location.");
                    if (rented) return ko("Ce bien est déjà loué : expulse le locataire d'abord.");
                    if (e == null || e.owner() != null || AgencyService.companyDoor(e)) return ko("Ce bien a un propriétaire : libère-le d'abord.");
                    if (days < 1 || days > MAX_DAYS) return ko("Durée invalide (1 à " + MAX_DAYS + " jours).");
                    d.rentHouse(key, target, 0, "", System.currentTimeMillis() + days * DAY_MS);
                    AgencyService.persist(s, d);
                    PropertyLog.add(s, h.dimension(), name, by + " (staff) installe " + who + " en locataire pour " + days + " j (sans paiement)");
                    return ok(who + " est maintenant locataire de " + name + " pour " + days + " jour(s).");
                }
                case "addTrusted", "removeTrusted" -> {
                    if (target == null) return ko("Choisis un joueur.");
                    UUID holder = holder(d, h);
                    if (holder == null) return ko("Ce bien n'a ni propriétaire ni locataire.");
                    if (holder.equals(target)) return ko("C'est déjà le titulaire du bien.");
                    boolean add = action.equals("addTrusted");
                    boolean changed = add ? d.addTrusted(holder, target) : d.removeTrusted(holder, target);
                    if (!changed) return ko(add ? who + " est déjà colocataire." : who + " n'est pas colocataire.");
                    if (!add) WorldGuardBridge.release(s, h.dimension(), name, target, MineNorth.pseudo(s, target));
                    AgencyService.persist(s, d);
                    PropertyLog.add(s, h.dimension(), name, by + " (staff) " + (add ? "ajoute " : "retire ") + who + " des colocataires");
                    return ok(who + (add ? " ajouté aux colocataires de " : " retiré des colocataires de ") + name + ".");
                }
                default -> { return ko("Action inconnue."); }
            }
        } catch (RuntimeException ex) {
            return ko("Erreur : " + ex.getClass().getSimpleName());
        }
    }

    /** Vrai si au moins une porte de la maison est une porte de maison personnelle ou d'entreprise (attribuable). */
    private static boolean personalHouse(OwnerDoorData d, House h) {
        for (DoorKey k : h.doors()) {
            DoorEntry e = d.entry(k);
            if (e != null && (e.type() == OwnerDoorData.DoorType.PERSONAL || AgencyService.companyDoor(e))) return true;
        }
        return false;
    }

    /** Tous ceux qui ont des droits sur la région WorldGuard du bien : propriétaire, locataire, colocataires, patron de l'entreprise. */
    private static Set<UUID> occupants(MinecraftServer s, OwnerDoorData d, House h) {
        Set<UUID> out = new LinkedHashSet<>();
        DoorEntry e = d.entry(h.first());
        if (e != null) {
            if (e.owner() != null) { out.add(e.owner()); out.addAll(d.trusted(e.owner())); }
            if (AgencyService.companyDoor(e)) {
                UUID boss = EntrepriseCompat.ownerUuid(s, EntrepriseCompat.id(e.permission()));
                if (boss != null) out.add(boss);
            }
        }
        DoorListing l = AgencyService.listing(d, h);
        if (l != null && l.mode() == ListingMode.RENTAL && l.rentalActive()) {
            if (l.renterCompany() > 0) {
                UUID boss = EntrepriseCompat.ownerUuid(s, l.renterCompany());
                if (boss != null) out.add(boss);
            } else if (l.renter() != null) {
                out.add(l.renter());
                out.addAll(d.trusted(l.renter()));
            }
        }
        return out;
    }

    private static void releaseAll(MinecraftServer s, House h, Set<UUID> players) {
        for (UUID u : players) WorldGuardBridge.release(s, h.dimension(), h.name(), u, MineNorth.pseudo(s, u));
    }
}
