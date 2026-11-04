package fr.minenorth.portes.deco;

import fr.minenorth.api.MineNorth;
import fr.minenorth.api.PayResult;
import fr.minenorth.portes.network.DecoNet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Application Déco (côté serveur, tout est revérifié ici) : catalogue, achat livré seulement chez soi, garde-meuble (ranger le meuble visé,
 * ressortir un objet chez soi). Thread serveur uniquement.
 */
public final class DecoService {
    private DecoService() {}

    public static final int REFRESH = 0, BUY = 1, STORE = 2, TAKE = 3;
    private static final Map<UUID, Long> LAST = new HashMap<>();

    public static void handle(ServerPlayer p, int action, int a, int b) {
        if (!DecoConfig.get().actif) { send(p, false, "La décoration est désactivée sur le serveur.", true); return; }
        long now = System.currentTimeMillis();
        Long last = LAST.get(p.getUUID());
        if (action != REFRESH && last != null && now - last < 250) return;   // anti-martelage
        LAST.put(p.getUUID(), now);
        switch (action) {
            case REFRESH -> send(p, a == 1, "", false);
            case BUY -> { String err = buy(p, a); send(p, false, err == null ? lastOk : err, err != null); }
            case STORE -> { String err = store(p); send(p, false, err == null ? lastOk : err, err != null); }
            case TAKE -> { String err = take(p, a, b); send(p, false, err == null ? lastOk : err, err != null); }
            default -> { }
        }
    }

    private static String lastOk = "";

    private static String name(ItemStack st) { return st.getHoverName().getString(); }

    private static String itemId(ItemStack st) {
        ResourceLocation k = ForgeRegistries.ITEMS.getKey(st.getItem());
        return k == null ? "?" : k.toString();
    }

    // ------------------------------------------------------------------ achat

    private static String buy(ServerPlayer p, int articleId) {
        DecoCatalog.Article art = DecoCatalog.article(articleId);
        if (art == null) return "Article introuvable (catalogue modifié ?).";
        DecoCatalog.Shop shop = DecoCatalog.shops().get(art.shop());
        if (shop.vip() > VipPerks.decoLevel(p)) return "Réservé au grade " + (shop.vip() >= 2 ? "VIP+" : "VIP") + ".";
        Zones.Home home = Zones.homeOf(p);
        if (home == null) return "Livraison seulement chez vous : entrez dans un bien dont vous êtes propriétaire, locataire ou colocataire.";
        long price = MineNorth.discounted(p.server, p.getUUID(), art.price());
        PayResult r = MineNorth.bank().charge(p, price, "portes:deco");
        if (r != PayResult.OK) return "Paiement refusé : " + r.message();
        ItemStack give = art.item().copy();
        String what = name(give) + (give.getCount() > 1 ? " x" + give.getCount() : "");
        int count = give.getCount();
        boolean all = p.getInventory().add(give);
        String extra = "";
        if (!all && !give.isEmpty()) {
            GardeMeuble.get(p.server).add(p.getUUID(), give);   // inventaire plein : rien ne tombe au sol
            extra = " Inventaire plein : " + give.getCount() + " objet(s) rangés au garde-meuble.";
        }
        DecoJournal.log(p.getGameProfile().getName() + " achète " + itemId(art.item()) + " x" + count + " (" + TerrainService.euro(price) + ") livré à « "
                + home.occ().houseName() + " »" + (extra.isEmpty() ? "" : " (en partie au garde-meuble)"));
        lastOk = "Livré : " + what + " pour " + TerrainService.euro(price) + "." + extra;
        return null;
    }

    // ------------------------------------------------------------------ ranger le meuble visé

    private static String store(ServerPlayer p) {
        Zones.Home home = Zones.homeOf(p);
        if (home == null) return "Vous n'êtes pas chez vous.";
        HitResult hit = p.pick(5.0, 1.0f, false);
        if (!(hit instanceof BlockHitResult bh) || hit.getType() != HitResult.Type.BLOCK) return "Visez le meuble à ranger (à moins de 5 blocs), puis appuyez sur Ranger.";
        ServerLevel level = p.serverLevel();
        BlockPos pos = bh.getBlockPos();
        Zones.Home there = Zones.at(p.server, level, pos);
        if (there == null || !there.occ().key().equals(home.occ().key())) return "Ce bloc n'est pas dans votre logement.";
        PlacedLedger ledger = PlacedLedger.get(p.server);
        String dim = level.dimension().location().toString();
        PlacedLedger.Found f = ledger.find(dim, pos.asLong());
        if (f == null || !f.house().equals(home.occ().key())) return "Ce bloc fait partie du logement (ou a été posé avant le garde-meuble) : il ne se range pas.";
        UUID owner = f.placed().placer();
        if (!owner.equals(p.getUUID()) && !p.getUUID().equals(home.occ().holder())) return "Ce meuble a été posé par un colocataire : lui seul (ou le titulaire) peut le ranger.";
        if (!Mover.matches(level, f.main(), f.placed())) { ledger.forget(dim, f.main()); return "Ce meuble a changé : il ne peut plus être rangé."; }
        if (Mover.hasContents(level, BlockPos.of(f.main()))) return "Videz d'abord ce meuble.";
        if (GardeMeuble.get(p.server).total(owner) + 1 > VipPerks.capacity(p.server, owner)) return "Garde-meuble plein (" + VipPerks.capacity(p.server, owner) + " objets).";
        ledger.forget(dim, f.main());   // hors du registre AVANT le retrait : jamais rendu deux fois
        List<ItemStack> items = Mover.takeOut(level, f.main(), f.placed());
        GardeMeuble store = GardeMeuble.get(p.server);
        for (ItemStack st : items) store.add(owner, st);
        String who = owner.equals(p.getUUID()) ? "votre garde-meuble" : "le garde-meuble de " + MineNorth.pseudo(p.server, owner);
        DecoJournal.log(p.getGameProfile().getName() + " range " + itemId(f.placed().item()) + " (" + BlockPos.of(f.main()).toShortString() + ", « "
                + home.occ().houseName() + " ») -> " + who + (items.size() > 1 ? " (+" + (items.size() - 1) + " objet(s) capturés)" : ""));
        lastOk = name(f.placed().item()) + " rangé dans " + who + ".";
        return null;
    }

    // ------------------------------------------------------------------ ressortir

    private static String take(ServerPlayer p, int index, int count) {
        if (Zones.homeOf(p) == null) return "Le garde-meuble livre seulement chez vous.";
        GardeMeuble store = GardeMeuble.get(p.server);
        List<GardeMeuble.Lot> lots = store.lots(p.getUUID());
        if (index < 0 || index >= lots.size()) return "Objet introuvable (liste modifiée ?).";
        int n = Math.max(1, Math.min(count, lots.get(index).item.getMaxStackSize()));
        ItemStack out = store.take(p.getUUID(), index, n);
        if (out.isEmpty()) return "Objet introuvable.";
        int wanted = out.getCount();
        String what = name(out), id = itemId(out);
        p.getInventory().add(out);
        if (!out.isEmpty()) store.add(p.getUUID(), out);   // ce qui n'a pas tenu dans l'inventaire retourne au garde-meuble
        int got = wanted - out.getCount();
        if (got <= 0) return "Inventaire plein.";
        DecoJournal.log(p.getGameProfile().getName() + " sort du garde-meuble : " + id + " (" + what + ")" + " x" + got);
        lastOk = "Sorti du garde-meuble : " + what + " x" + got + ".";
        return null;
    }

    // ------------------------------------------------------------------ vue

    private static void send(ServerPlayer p, boolean withCatalog, String message, boolean error) {
        Zones.Home home = Zones.homeOf(p);
        List<DecoNet.Shop> shops = new ArrayList<>();
        List<DecoNet.Article> arts = new ArrayList<>();
        int level = VipPerks.decoLevel(p);
        if (withCatalog) {
            for (DecoCatalog.Shop s : DecoCatalog.shops()) shops.add(new DecoNet.Shop(s.name(), s.vip(), s.vip() > level));
            for (DecoCatalog.Article a : DecoCatalog.articles())
                arts.add(new DecoNet.Article(a.id(), a.shop(), a.item(), MineNorth.discounted(p.server, p.getUUID(), a.price()), a.category()));
        }
        List<DecoNet.Lot> lots = new ArrayList<>();
        GardeMeuble store = GardeMeuble.get(p.server);
        for (GardeMeuble.Lot l : store.lots(p.getUUID())) lots.add(new DecoNet.Lot(l.item, l.count));
        String homeName = home == null ? "" : home.occ().houseName();
        String homeCat = home == null ? "" : home.occ().category().label();
        int placed = home == null ? 0 : PlacedLedger.get(p.server).count(home.occ().key());
        DecoNet.send(p, new DecoNet.View(withCatalog, shops, arts, lots, store.total(p.getUUID()), VipPerks.capacity(p.server, p.getUUID()),
                homeName, homeCat, placed, home != null && home.terrain(), level, message, error));
    }
}
