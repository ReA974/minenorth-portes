package fr.minenorth.portes.agency;

import fr.minenorth.api.MineNorth;
import fr.minenorth.api.PayResult;
import fr.minenorth.portes.config.SystemeConfigFiles;
import fr.minenorth.portes.door.EntrepriseCompat;
import fr.minenorth.portes.door.OwnerDoorData;
import fr.minenorth.portes.door.OwnerDoorData.DoorEntry;
import fr.minenorth.portes.door.OwnerDoorData.DoorKey;
import fr.minenorth.portes.door.OwnerDoorData.DoorListing;
import fr.minenorth.portes.door.OwnerDoorData.ListingMode;
import fr.minenorth.portes.door.WorldGuardBridge;
import fr.minenorth.portes.door.MapBridge;
import fr.minenorth.portes.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Logique de l'agence immobilière : catalogue, achat, location, prolongation, revente.
 * Un bien = toutes les portes de même nom de maison dans la même dimension. Thread serveur uniquement.
 * La première porte (triée par position) de la maison fait foi pour la fiche de vente ou de location.
 */
public final class AgencyService {
    private AgencyService() {}

    private static final long DAY_MS = 86_400_000L;

    public record AgencyResult(boolean ok, String message) {
        static AgencyResult ok(String m) { return new AgencyResult(true, m); }
        static AgencyResult fail(String m) { return new AgencyResult(false, m); }
    }

    /** Fiche d'un bien pour l'agence. x, y, z : première porte de la maison. */
    public record PropertyView(String dimension, String houseName, PropertyCategory category, ListingMode mode, long priceCents,
                               int days, boolean furnished, String description, int imageCount, int x, int y, int z, String catalogue) {}

    /** Bien d'un joueur (ou de son entreprise s'il est patron). Pour un bien acheté, view.priceCents = prix d'achat. */
    public record OwnedView(PropertyView view, boolean rented, long expiryMs, long purchaseCents, int companyId, String companyName) {}

    private record House(String dimension, String name, List<DoorKey> doors) {
        DoorKey first() { return doors.get(0); }
    }

    // ------------------------------------------------------------------ lecture

    private static String dimOf(DoorKey k) { return k.dimension().location().toString(); }

    private static List<House> houses(OwnerDoorData d) {
        Map<String, List<DoorKey>> groups = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (var e : d.allDoors()) {
            String name = e.getValue().houseName();
            if (name == null || name.isBlank()) continue;
            String k = PropertyInfoData.key(dimOf(e.getKey()), name);
            groups.computeIfAbsent(k, x -> new ArrayList<>()).add(e.getKey());
            names.putIfAbsent(k, name.trim());
        }
        List<House> out = new ArrayList<>();
        for (var g : groups.entrySet()) {
            List<DoorKey> doors = g.getValue();
            // Porte de référence : d'abord une porte de maison (personnelle ou d'entreprise), puis par position.
            doors.sort(Comparator.comparingInt((DoorKey k) -> referenceDoor(d.entry(k)) ? 0 : 1).thenComparingLong(k -> k.pos().asLong()));
            out.add(new House(dimOf(doors.get(0)), names.get(g.getKey()), doors));
        }
        return out;
    }

    private static boolean referenceDoor(DoorEntry e) {
        return e != null && (e.type() == OwnerDoorData.DoorType.PERSONAL || (e.type() == OwnerDoorData.DoorType.ENTREPRISE && EntrepriseCompat.linked(e.permission())));
    }

    private static House find(OwnerDoorData d, String dimension, String houseName) {
        String wanted = PropertyInfoData.key(dimension, houseName);
        for (House h : houses(d)) if (PropertyInfoData.key(h.dimension(), h.name()).equals(wanted)) return h;
        return null;
    }

    /** Fiche active de la maison (première porte qui en a une). */
    private static DoorListing listing(OwnerDoorData d, House h) {
        for (DoorKey k : h.doors()) {
            DoorListing l = d.listing(k);
            if (l != null && l.active()) return l;
        }
        return null;
    }

    private static boolean available(DoorListing l) {
        return l != null && (l.mode() == ListingMode.SALE || (l.mode() == ListingMode.RENTAL && !l.rentalActive()));
    }

    private static PropertyView view(OwnerDoorData d, PropertyInfoData infos, House h, DoorListing l, long fallbackPrice) {
        var info = infos.info(d, h.dimension(), h.name());
        BlockPos p = h.first().pos();
        return new PropertyView(h.dimension(), h.name(), info.category(), l == null ? ListingMode.NONE : l.mode(),
                l == null ? fallbackPrice : l.price(), l == null ? 0 : l.days(), info.furnished(), info.description(),
                info.imageIds().size(), p.getX(), p.getY(), p.getZ(), info.catalogue());
    }

    /** Biens disponibles d'un catalogue (« » = l'agence elle-même : les biens qui ont un catalogue n'y figurent pas). */
    public static List<PropertyView> catalog(MinecraftServer s, String catalogue) {
        OwnerDoorData d = OwnerDoorData.get(s.overworld());
        PropertyInfoData infos = PropertyInfoData.get(s.overworld());
        List<PropertyView> out = new ArrayList<>();
        for (House h : houses(d)) {
            DoorListing l = listing(d, h);
            if (!available(l)) continue;
            PropertyView v = view(d, infos, h, l, 0);
            if (Catalogues.same(v.catalogue(), catalogue)) out.add(v);
        }
        out.sort((a, b) -> NameOrder.compare(a.houseName(), b.houseName()));
        return out;
    }

    /** Tous les biens (vendus, loués ou non), pour les admins ; mode NONE = pas de fiche de vente ni de location. */
    public static List<PropertyView> catalogAll(MinecraftServer s) {
        OwnerDoorData d = OwnerDoorData.get(s.overworld());
        PropertyInfoData infos = PropertyInfoData.get(s.overworld());
        List<PropertyView> out = new ArrayList<>();
        for (House h : houses(d)) out.add(view(d, infos, h, listing(d, h), d.housePurchasePrice(h.first())));
        out.sort((a, b) -> NameOrder.compare(a.houseName(), b.houseName()));
        return out;
    }

    /** Porte de référence d'un bien (celle dont le panneau admin règle toute la maison), ou null. */
    public static DoorKey referenceDoorKey(MinecraftServer s, String dimension, String houseName) {
        House h = find(OwnerDoorData.get(s.overworld()), dimension, houseName);
        return h == null ? null : h.first();
    }

    private static boolean companyDoor(DoorEntry e) {
        return e.type() == OwnerDoorData.DoorType.ENTREPRISE && EntrepriseCompat.linked(e.permission());
    }

    public static List<OwnedView> myProperties(ServerPlayer p) {
        MinecraftServer s = p.server;
        OwnerDoorData d = OwnerDoorData.get(s.overworld());
        PropertyInfoData infos = PropertyInfoData.get(s.overworld());
        List<OwnedView> out = new ArrayList<>();
        for (House h : houses(d)) {
            DoorEntry e = d.entry(h.first());
            if (e == null) continue;
            DoorListing l = listing(d, h);
            boolean rentedNow = l != null && l.mode() == ListingMode.RENTAL && l.rentalActive();
            if (companyDoor(e)) {
                int cid = EntrepriseCompat.id(e.permission());
                if (!EntrepriseCompat.isOwner(p, cid)) continue;
                long purchase = d.housePurchasePrice(h.first());
                out.add(new OwnedView(view(d, infos, h, l, purchase), rentedNow, rentedNow ? l.expiryMs() : 0, purchase, cid, EntrepriseCompat.name(s, cid)));
            } else if (rentedNow && l.renterCompany() == 0 && p.getUUID().equals(l.renter())) {
                out.add(new OwnedView(view(d, infos, h, l, 0), true, l.expiryMs(), 0, 0, ""));
            } else if (e.type() == OwnerDoorData.DoorType.PERSONAL && p.getUUID().equals(e.owner())) {
                long purchase = d.housePurchasePrice(h.first());
                out.add(new OwnedView(view(d, infos, h, l, purchase), false, 0, purchase, 0, ""));
            }
        }
        out.sort((a, b) -> NameOrder.compare(a.view().houseName(), b.view().houseName()));
        return out;
    }

    /** Biens (achats + locations en cours) d'un joueur dans une catégorie. */
    public static int heldCount(MinecraftServer s, UUID player, PropertyCategory c) {
        OwnerDoorData d = OwnerDoorData.get(s.overworld());
        PropertyInfoData infos = PropertyInfoData.get(s.overworld());
        int n = 0;
        for (House h : houses(d)) {
            DoorEntry e = d.entry(h.first());
            if (e == null || companyDoor(e)) continue;
            if (infos.info(d, h.dimension(), h.name()).category() != c) continue;
            DoorListing l = listing(d, h);
            boolean rented = l != null && l.mode() == ListingMode.RENTAL && l.rentalActive() && l.renterCompany() == 0 && player.equals(l.renter());
            boolean owned = e.type() == OwnerDoorData.DoorType.PERSONAL && player.equals(e.owner());
            if (rented || owned) n++;
        }
        return n;
    }

    /** Biens (achats + locations en cours) d'une entreprise dans une catégorie. */
    public static int heldCountCompany(MinecraftServer s, int companyId, PropertyCategory category) {
        PropertyInfoData infos = PropertyInfoData.get(s.overworld());
        OwnerDoorData d = OwnerDoorData.get(s.overworld());
        int n = 0;
        for (House h : houses(d)) {
            DoorEntry e = d.entry(h.first());
            if (e != null && companyDoor(e) && EntrepriseCompat.id(e.permission()) == companyId && infos.info(d, h.dimension(), h.name()).category() == category) n++;
        }
        return n;
    }

    /** Local d'entreprise (acheté ou loué en cours) : entreprise, dimension, nom du bien et position de sa porte de référence. */
    public record CompanyBuilding(int companyId, String dimension, String houseName, int x, int y, int z) {}

    /** Tous les locaux détenus par une entreprise (achat ou location en cours), pour la carte. */
    public static List<CompanyBuilding> companyBuildings(MinecraftServer s) {
        OwnerDoorData d = OwnerDoorData.get(s.overworld());
        List<CompanyBuilding> out = new ArrayList<>();
        for (House h : houses(d)) {
            DoorEntry e = d.entry(h.first());
            if (e == null) continue;
            int cid = 0;
            DoorListing l = listing(d, h);
            if (companyDoor(e)) cid = EntrepriseCompat.id(e.permission());
            else if (l != null && l.mode() == ListingMode.RENTAL && l.rentalActive() && l.renterCompany() > 0) cid = l.renterCompany();
            if (cid <= 0) continue;
            BlockPos p = h.first().pos();
            out.add(new CompanyBuilding(cid, h.dimension(), h.name(), p.getX(), p.getY(), p.getZ()));
        }
        return out;
    }

    // ------------------------------------------------------------------ actions

    private static void persist(MinecraftServer s, OwnerDoorData d) {
        SystemeConfigFiles.writeDoors(s, d);
        ModNetwork.syncDoorLocks(s);
        MapBridge.refresh(s);   // la carte pose / retire le point du local tout de suite
    }

    private static String euros(long cents) { return String.format(Locale.FRANCE, "%.2f €", cents / 100.0); }

    /** Paiement personnel : au propriétaire s'il a un compte (virement), sinon au trésor public. */
    private static PayResult payPersonal(ServerPlayer payer, UUID owner, long cents, String source) {
        var bank = MineNorth.bank();
        if (owner == null || owner.equals(payer.getUUID()) || !bank.hasAccount(payer.server, owner)) return bank.charge(payer, cents, source);
        PayResult check = bank.check(payer, cents);
        if (check != PayResult.OK) return check;
        PayResult r = bank.transfer(payer.server, payer.getUUID(), owner, cents);
        if (r == PayResult.OK) {
            ServerPlayer o = payer.server.getPlayerList().getPlayer(owner);
            if (o != null) o.sendSystemMessage(Component.literal("§a" + MineNorth.displayName(payer) + " vous a versé " + euros(cents)
                    + " (" + (source.endsWith("location") ? "location" : "achat") + ")."));
        }
        return r;
    }

    /** Titulaire qui paie : joueur seul (companyId 0) ou entreprise dont il est patron. */
    private record Payer(int companyId, String companyName, UUID account) {}

    /** Vérifie que le joueur peut agir pour cette entreprise ; renvoie le message d'erreur, ou null si tout va bien. */
    private static String companyError(ServerPlayer p, int companyId) {
        if (companyId <= 0) return "Un local d'entreprise s'achète ou se loue avec le compte d'une entreprise : choisissez-en une.";
        if (!EntrepriseCompat.isOwner(p, companyId)) return "Seul le patron de l'entreprise peut le faire.";
        if (EntrepriseCompat.accountId(p.server, companyId) == null) return "Cette entreprise n'a pas de compte bancaire.";
        return null;
    }

    private static PayResult payCompany(ServerPlayer p, int companyId, long cents, String source) {
        return MineNorth.bank().payFromAccount(p.server, EntrepriseCompat.accountId(p.server, companyId), cents, source, p.getUUID());
    }

    /** Compte de l'entreprise qui encaisse les paiements de ce bien (ex. le casino), ou null : règle habituelle (propriétaire, sinon trésor). */
    private static UUID payeeAccount(MinecraftServer s, PropertyInfoData.PropertyInfo info) {
        if (info.payeeCompany() <= 0 || !Boolean.TRUE.equals(EntrepriseCompat.isActive(s, info.payeeCompany()))) return null;
        return EntrepriseCompat.accountId(s, info.payeeCompany());
    }

    /**
     * Paiement d'un achat, d'une location ou d'une prolongation. Entreprise bénéficiaire : virement vers son compte (depuis la carte du joueur
     * ou depuis le compte de l'entreprise payeuse). Sinon : trésor pour une entreprise payeuse, propriétaire ou trésor pour un joueur.
     */
    private static PayResult collect(ServerPlayer p, int payerCompany, UUID payeeAcc, UUID landlord, long cents, String source) {
        var bank = MineNorth.bank();
        if (payeeAcc == null) return payerCompany > 0 ? payCompany(p, payerCompany, cents, source) : payPersonal(p, landlord, cents, source);
        if (payerCompany > 0) {
            UUID from = EntrepriseCompat.accountId(p.server, payerCompany);
            if (from == null) return PayResult.UNAVAILABLE;
            return from.equals(payeeAcc) ? PayResult.OK : bank.transfer(p.server, from, payeeAcc, cents);
        }
        PayResult check = bank.check(p, cents);
        return check != PayResult.OK ? check : bank.transfer(p.server, p.getUUID(), payeeAcc, cents);
    }

    /** Annule un paiement fait par {@link #collect} : l'argent repart de l'entreprise bénéficiaire, ou du trésor. */
    private static void undoCollect(ServerPlayer p, int payerCompany, UUID payeeAcc, long cents) {
        var bank = MineNorth.bank();
        UUID payer = payerCompany > 0 ? EntrepriseCompat.accountId(p.server, payerCompany) : p.getUUID();
        if (payer == null) return;
        if (payeeAcc != null) { if (!payeeAcc.equals(payer)) bank.transfer(p.server, payeeAcc, payer, cents); }
        else bank.refund(p.server, payer, cents, "portes:annulation");
    }

    public static AgencyResult buy(ServerPlayer p, String dimension, String houseName, int companyId) { return acquire(p, dimension, houseName, companyId, false); }

    public static AgencyResult rent(ServerPlayer p, String dimension, String houseName, int companyId) { return acquire(p, dimension, houseName, companyId, true); }

    private static AgencyResult acquire(ServerPlayer p, String dimension, String houseName, int companyId, boolean rental) {
        MinecraftServer s = p.server;
        OwnerDoorData d = OwnerDoorData.get(s.overworld());
        House h = find(d, dimension, houseName);
        if (h == null) return AgencyResult.fail("Bien introuvable.");
        DoorListing l = listing(d, h);
        ListingMode wanted = rental ? ListingMode.RENTAL : ListingMode.SALE;
        if (!available(l) || l.mode() != wanted) return AgencyResult.fail("Ce bien n'est plus disponible.");
        DoorEntry e = d.entry(h.first());
        if (e != null && e.owner() != null && e.owner().equals(p.getUUID())) return AgencyResult.fail("Vous possédez déjà ce bien.");
        var info = PropertyInfoData.get(s.overworld()).info(d, h.dimension(), h.name());
        PropertyCategory cat = info.category();
        UUID payeeAcc = payeeAccount(s, info);
        // Entreprise : obligatoire pour un local d'entreprise, au choix (companyId > 0) pour local, stockage et garage ; ignorée sinon.
        boolean asCompany = cat.companyOnly() || (cat.companyOptional() && companyId > 0);
        long price = l.price();
        String source = rental ? "portes:location" : "portes:achat";
        DoorKey key = h.first();
        PayResult r;
        if (asCompany && e != null && e.owner() != null) return AgencyResult.fail("Ce bien appartient à un joueur : une entreprise ne peut pas l'acquérir.");
        if (asCompany) {
            String err = companyError(p, companyId);
            if (err != null) return AgencyResult.fail(err);
            if (!ImmoRules.canAcquire(cat, heldCountCompany(s, companyId, cat))) return AgencyResult.fail("Limite atteinte pour cette entreprise : " + ImmoRules.limit(cat) + " biens de type « " + cat.label() + " ».");
            r = collect(p, companyId, payeeAcc, null, price, source);
        } else {
            if (!ImmoRules.canAcquire(cat, heldCount(s, p.getUUID(), cat))) {
                return AgencyResult.fail("Limite atteinte : " + ImmoRules.limit(cat) + " biens de type « " + cat.label() + " ».");
            }
            r = collect(p, 0, payeeAcc, e == null ? null : e.owner(), price, source);
        }
        if (r != PayResult.OK) return AgencyResult.fail("Paiement refusé : " + r.message());
        try {
            if (rental) {
                String name = asCompany ? EntrepriseCompat.name(s, companyId) : "";
                d.rentHouse(key, p.getUUID(), asCompany ? companyId : 0, name, System.currentTimeMillis() + l.days() * DAY_MS);
            } else if (asCompany) {
                d.transferHouseToCompany(key, companyId, EntrepriseCompat.name(s, companyId), price);
            } else {
                d.transferHouse(key, p.getUUID(), p.getGameProfile().getName(), price);
            }
            persist(s, d);
        } catch (RuntimeException ex) {
            // Paiement fait mais bien non attribué : on rembourse.
            undoCollect(p, asCompany ? companyId : 0, payeeAcc, price);
            return AgencyResult.fail("Une erreur est survenue, vous avez été remboursé.");
        }
        return AgencyResult.ok(rental ? "Location activée pour " + l.days() + " jour(s) : " + h.name() + "." : "Achat effectué : " + h.name() + " pour " + euros(price) + ".");
    }

    public static AgencyResult extend(ServerPlayer p, String dimension, String houseName) {
        MinecraftServer s = p.server;
        OwnerDoorData d = OwnerDoorData.get(s.overworld());
        House h = find(d, dimension, houseName);
        if (h == null) return AgencyResult.fail("Bien introuvable.");
        DoorListing l = listing(d, h);
        if (l == null || l.mode() != ListingMode.RENTAL || !l.rentalActive()) return AgencyResult.fail("Cette location est terminée : louez à nouveau le bien depuis le catalogue.");
        UUID payeeAcc = payeeAccount(s, PropertyInfoData.get(s.overworld()).info(d, h.dimension(), h.name()));
        boolean company = l.renterCompany() > 0;
        PayResult r;
        if (company) {
            String err = companyError(p, l.renterCompany());
            if (err != null) return AgencyResult.fail(err);
            r = collect(p, l.renterCompany(), payeeAcc, null, l.price(), "portes:location");
        } else {
            if (!p.getUUID().equals(l.renter())) return AgencyResult.fail("Ce bien n'est pas loué à votre nom.");
            DoorEntry first = d.entry(h.first());
            r = collect(p, 0, payeeAcc, first == null ? null : first.owner(), l.price(), "portes:location");
        }
        if (r != PayResult.OK) return AgencyResult.fail("Paiement refusé : " + r.message());
        long expiry = ImmoRules.extendedExpiryMs(System.currentTimeMillis(), l.expiryMs(), l.days());
        d.extendRental(h.first(), expiry);
        persist(s, d);
        return AgencyResult.ok("Location prolongée de " + l.days() + " jour(s) pour " + euros(l.price()) + ".");
    }

    /** Arrêt d'une location à la demande du locataire (ou du patron pour une entreprise) : aucun remboursement. */
    public static AgencyResult stopRental(ServerPlayer p, String dimension, String houseName) {
        MinecraftServer s = p.server;
        OwnerDoorData d = OwnerDoorData.get(s.overworld());
        House h = find(d, dimension, houseName);
        if (h == null) return AgencyResult.fail("Bien introuvable.");
        DoorListing l = listing(d, h);
        if (l == null || l.mode() != ListingMode.RENTAL || !l.rentalActive()) return AgencyResult.fail("Aucune location en cours sur ce bien.");
        UUID wgMember;
        if (l.renterCompany() > 0) {
            if (!EntrepriseCompat.isOwner(p, l.renterCompany())) return AgencyResult.fail("Seul le patron de l'entreprise peut arrêter cette location.");
            wgMember = EntrepriseCompat.ownerUuid(s, l.renterCompany());
        } else {
            if (!p.getUUID().equals(l.renter())) return AgencyResult.fail("Ce bien n'est pas loué à votre nom.");
            wgMember = p.getUUID();
        }
        d.endRental(h.first());
        if (wgMember != null) WorldGuardBridge.release(s, h.dimension(), h.name(), wgMember, MineNorth.pseudo(s, wgMember));
        persist(s, d);
        return AgencyResult.ok("Location arrêtée (aucun remboursement) : « " + h.name() + " » est de nouveau disponible.");
    }

    public static AgencyResult resell(ServerPlayer p, String dimension, String houseName) {
        MinecraftServer s = p.server;
        OwnerDoorData d = OwnerDoorData.get(s.overworld());
        House h = find(d, dimension, houseName);
        if (h == null) return AgencyResult.fail("Bien introuvable.");
        DoorEntry e = d.entry(h.first());
        DoorListing l = listing(d, h);
        if (e == null) return AgencyResult.fail("Bien introuvable.");
        if (l != null && l.mode() == ListingMode.RENTAL && l.rentalActive()) return AgencyResult.fail("Un bien loué ne se revend pas.");
        boolean company = companyDoor(e);
        int cid = company ? EntrepriseCompat.id(e.permission()) : 0;
        if (company) {
            if (!EntrepriseCompat.isOwner(p, cid)) return AgencyResult.fail("Seul le patron de l'entreprise peut revendre ce local.");
        } else if (e.type() != OwnerDoorData.DoorType.PERSONAL || !p.getUUID().equals(e.owner())) {
            return AgencyResult.fail("Ce bien ne vous appartient pas.");
        }
        DoorKey key = h.first();
        long purchase = d.housePurchasePrice(key);
        if (purchase <= 0) return AgencyResult.fail("Ce bien n'a pas de prix d'achat enregistré.");
        long refund = ImmoRules.resaleCents(purchase);
        if (refund <= 0) return AgencyResult.fail("Remboursement nul.");
        UUID account = company ? EntrepriseCompat.accountId(s, cid) : p.getUUID();
        UUID payeeAcc = payeeAccount(s, PropertyInfoData.get(s.overworld()).info(d, h.dimension(), h.name()));
        if (account == null) return AgencyResult.fail("Remboursement impossible : compte bancaire introuvable.");
        if (payeeAcc != null) {
            // Le bien vient d'une entreprise (ex. casino) : c'est elle qui rachète, pas le trésor.
            if (!payeeAcc.equals(account) && MineNorth.bank().transfer(s, payeeAcc, account, refund) != PayResult.OK) return AgencyResult.fail("L'entreprise vendeuse n'a pas les fonds pour le rachat.");
        } else if (!MineNorth.bank().refund(s, account, refund, "portes:revente")) {
            return AgencyResult.fail("Remboursement impossible : compte bancaire introuvable.");
        }
        UUID wgMember = company ? EntrepriseCompat.ownerUuid(s, cid) : p.getUUID();
        d.returnHouseToMarket(key, purchase);
        if (wgMember != null) WorldGuardBridge.release(s, h.dimension(), h.name(), wgMember, MineNorth.pseudo(s, wgMember));
        persist(s, d);
        return AgencyResult.ok("Bien revendu : vous récupérez " + euros(refund) + " (" + ImmoRules.RESALE_PERCENT + " % du prix d'achat).");
    }
}
