package fr.minenorth.portes.gang;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Photo de ce qu'un joueur a le droit de voir de son gang, envoyée au client pour dessiner l'écran « Mon gang » (et l'écran de la fabrique).
 * Le client n'en tire AUCUN droit : chaque action revient au serveur qui revérifie tout.
 */
public final class GangView {
    public record Member(String uuid, String name, int rank, boolean online) {}
    public record Nearby(String uuid, String name) {}
    public record DoorInfo(String dim, long pos, int minRank) {}
    public record Terr(String name, String dim, int x1, int z1, int x2, int z2, String holderTag, String holderName, int holderColor, boolean mine, String status, long incomeCents, boolean customIncome) {}
    public record Invite(String tag, String name, int color, String inviter) {}
    public record GangInfo(String name, String tag, int color, int members) {}
    /** Recette de la fabrique ; spec n'est rempli que pour les OP (édition en jeu). needs = « acier:5;poudre:1 ». */
    public record Recipe(String label, String spec, int qty, int seconds, long cents, String needs) {}
    public record JobInfo(String label, int qty, int total, int remaining) {}
    public record LotInfo(String label, int qty) {}
    /** Matière première d'une fabrique : réserve actuelle et plafond, quantité et prix d'un achat. */
    public record MatInfo(String id, String label, int stock, int cap, int pack, long cents) {}
    /** Définition d'une matière (édition en jeu, OP). */
    public record MaterialDef(String label, String id, int pack, long cents) {}
    /** Mouvement du compte du gang. */
    public record TxInfo(String when, String who, long delta, String label) {}

    /** Écran de la fabrique (clic droit sur le bloc). */
    public static final class FactoryInfo {
        public String dim = "";
        public long pos;
        public String gangName = "", gangTag = "", zone = "", status = "", collectRank = "", spendRank = "";
        public int gangColor;
        public boolean running, mine, canQueue, canCollect, canSeize, canSpend;
        public long balance;
        public int stockMax, queueMax;
        public final List<JobInfo> queue = new ArrayList<>();
        public final List<LotInfo> stock = new ArrayList<>();
        public final List<Recipe> recipes = new ArrayList<>();
        public final List<MatInfo> materials = new ArrayList<>();

        void encode(FriendlyByteBuf b) {
            b.writeUtf(dim); b.writeLong(pos); b.writeUtf(gangName); b.writeUtf(gangTag); b.writeUtf(zone); b.writeUtf(status, 200); b.writeUtf(collectRank); b.writeUtf(spendRank);
            b.writeInt(gangColor); b.writeBoolean(running); b.writeBoolean(mine); b.writeBoolean(canQueue); b.writeBoolean(canCollect); b.writeBoolean(canSeize); b.writeBoolean(canSpend);
            b.writeLong(balance); b.writeVarInt(stockMax); b.writeVarInt(queueMax);
            b.writeCollection(queue, (x, j) -> { x.writeUtf(j.label); x.writeVarInt(j.qty); x.writeVarInt(j.total); x.writeVarInt(j.remaining); });
            b.writeCollection(stock, (x, l) -> { x.writeUtf(l.label); x.writeVarInt(l.qty); });
            b.writeCollection(recipes, GangView::writeRecipe);
            b.writeCollection(materials, (x, m) -> { x.writeUtf(m.id); x.writeUtf(m.label); x.writeVarInt(m.stock); x.writeVarInt(m.cap); x.writeVarInt(m.pack); x.writeLong(m.cents); });
        }

        static FactoryInfo decode(FriendlyByteBuf b) {
            FactoryInfo f = new FactoryInfo();
            f.dim = b.readUtf(); f.pos = b.readLong(); f.gangName = b.readUtf(); f.gangTag = b.readUtf(); f.zone = b.readUtf(); f.status = b.readUtf(200); f.collectRank = b.readUtf(); f.spendRank = b.readUtf();
            f.gangColor = b.readInt(); f.running = b.readBoolean(); f.mine = b.readBoolean(); f.canQueue = b.readBoolean(); f.canCollect = b.readBoolean(); f.canSeize = b.readBoolean(); f.canSpend = b.readBoolean();
            f.balance = b.readLong(); f.stockMax = b.readVarInt(); f.queueMax = b.readVarInt();
            f.queue.addAll(b.readList(x -> new JobInfo(x.readUtf(), x.readVarInt(), x.readVarInt(), x.readVarInt())));
            f.stock.addAll(b.readList(x -> new LotInfo(x.readUtf(), x.readVarInt())));
            f.recipes.addAll(b.readList(GangView::readRecipe));
            f.materials.addAll(b.readList(x -> new MatInfo(x.readUtf(), x.readUtf(), x.readVarInt(), x.readVarInt(), x.readVarInt(), x.readLong())));
            return f;
        }
    }

    private static void writeRecipe(FriendlyByteBuf x, Recipe r) {
        x.writeUtf(r.label); x.writeUtf(r.spec, 256); x.writeVarInt(r.qty); x.writeVarInt(r.seconds); x.writeLong(r.cents); x.writeUtf(r.needs, 128);
    }

    private static Recipe readRecipe(FriendlyByteBuf x) {
        return new Recipe(x.readUtf(), x.readUtf(256), x.readVarInt(), x.readVarInt(), x.readLong(), x.readUtf(128));
    }

    /** Le client ouvre l'écran (s'il n'a pas déjà un écran ouvert). */
    public boolean open;
    /** Fenêtre « porte » : 0 aucune, 1 porte libre à attribuer, 2 porte du gang, 3 porte personnelle à attribuer. */
    public int doorMode;
    public long doorPos;
    public int doorMin;
    public String message = "";
    public boolean messageError;

    public boolean inGang;
    public String name = "", tag = "";
    public int color = 0xE53935;
    public int myRank = -1;
    public boolean op;
    public final List<Member> members = new ArrayList<>();
    public final List<Nearby> nearby = new ArrayList<>();
    public final List<DoorInfo> doors = new ArrayList<>();
    public int doorsMax, factoriesCount, factoriesMax;
    /** Point de base du gang (défini par le chef). */
    public boolean hasBase;
    public String baseDim = "";
    public long basePos;
    public int baseRadius;
    public final List<String> log = new ArrayList<>();
    public final List<Terr> territories = new ArrayList<>();
    public long incomePerZone, incomeCap;
    public boolean incomeNeedsOnline, incomeToGang;
    public int incomeMinutes, zonesHeld;
    public final List<Invite> invites = new ArrayList<>();
    public long createCost;
    public int membersMax;
    public final List<GangInfo> allGangs = new ArrayList<>();
    /** Compte du gang : solde, grade minimum pour dépenser (0 chef, 1 lieutenant), droit du joueur, historique (récent d'abord). */
    public long balance;
    public int spendRank = 1;
    public boolean canSpend;
    public final List<TxInfo> txs = new ArrayList<>();
    /** Réglages de la fabrique (OP : édition en jeu). */
    public boolean factoryEnabled;
    public int factoryMaxPerGang, factoryStockMax, factoryQueueMax, materialCap;
    public String factoryCollectRank = "";
    public final List<Recipe> adminRecipes = new ArrayList<>();
    public final List<MaterialDef> adminMaterials = new ArrayList<>();
    /** Non nul : ouvrir l'écran de la fabrique. */
    public FactoryInfo factory;

    public void encode(FriendlyByteBuf b) {
        b.writeBoolean(open); b.writeVarInt(doorMode); b.writeLong(doorPos); b.writeVarInt(doorMin);
        b.writeUtf(message, 300); b.writeBoolean(messageError);
        b.writeBoolean(inGang); b.writeUtf(name); b.writeUtf(tag); b.writeInt(color); b.writeVarInt(myRank + 1); b.writeBoolean(op);
        b.writeCollection(members, (x, m) -> { x.writeUtf(m.uuid); x.writeUtf(m.name); x.writeVarInt(m.rank); x.writeBoolean(m.online); });
        b.writeCollection(nearby, (x, m) -> { x.writeUtf(m.uuid); x.writeUtf(m.name); });
        b.writeCollection(doors, (x, m) -> { x.writeUtf(m.dim); x.writeLong(m.pos); x.writeVarInt(m.minRank); });
        b.writeVarInt(doorsMax); b.writeVarInt(factoriesCount); b.writeVarInt(factoriesMax);
        b.writeBoolean(hasBase); b.writeUtf(baseDim); b.writeLong(basePos); b.writeVarInt(baseRadius);
        b.writeCollection(log, (x, s) -> x.writeUtf(s, 200));
        b.writeCollection(territories, (x, t) -> {
            x.writeUtf(t.name); x.writeUtf(t.dim); x.writeInt(t.x1); x.writeInt(t.z1); x.writeInt(t.x2); x.writeInt(t.z2);
            x.writeUtf(t.holderTag); x.writeUtf(t.holderName); x.writeInt(t.holderColor); x.writeBoolean(t.mine); x.writeUtf(t.status); x.writeLong(t.incomeCents); x.writeBoolean(t.customIncome);
        });
        b.writeLong(incomePerZone); b.writeLong(incomeCap); b.writeVarInt(incomeMinutes); b.writeVarInt(zonesHeld); b.writeBoolean(incomeNeedsOnline); b.writeBoolean(incomeToGang);
        b.writeCollection(invites, (x, i) -> { x.writeUtf(i.tag); x.writeUtf(i.name); x.writeInt(i.color); x.writeUtf(i.inviter); });
        b.writeLong(createCost); b.writeVarInt(membersMax);
        b.writeCollection(allGangs, (x, g) -> { x.writeUtf(g.name); x.writeUtf(g.tag); x.writeInt(g.color); x.writeVarInt(g.members); });
        b.writeLong(balance); b.writeVarInt(spendRank); b.writeBoolean(canSpend);
        b.writeCollection(txs, (x, t) -> { x.writeUtf(t.when); x.writeUtf(t.who); x.writeLong(t.delta); x.writeUtf(t.label, 120); });
        b.writeBoolean(factoryEnabled); b.writeVarInt(factoryMaxPerGang); b.writeVarInt(factoryStockMax); b.writeVarInt(factoryQueueMax); b.writeVarInt(materialCap); b.writeUtf(factoryCollectRank);
        b.writeCollection(adminRecipes, GangView::writeRecipe);
        b.writeCollection(adminMaterials, (x, m) -> { x.writeUtf(m.label); x.writeUtf(m.id); x.writeVarInt(m.pack); x.writeLong(m.cents); });
        b.writeBoolean(factory != null);
        if (factory != null) factory.encode(b);
    }

    public static GangView decode(FriendlyByteBuf b) {
        GangView v = new GangView();
        v.open = b.readBoolean(); v.doorMode = b.readVarInt(); v.doorPos = b.readLong(); v.doorMin = b.readVarInt();
        v.message = b.readUtf(300); v.messageError = b.readBoolean();
        v.inGang = b.readBoolean(); v.name = b.readUtf(); v.tag = b.readUtf(); v.color = b.readInt(); v.myRank = b.readVarInt() - 1; v.op = b.readBoolean();
        v.members.addAll(b.readList(x -> new Member(x.readUtf(), x.readUtf(), x.readVarInt(), x.readBoolean())));
        v.nearby.addAll(b.readList(x -> new Nearby(x.readUtf(), x.readUtf())));
        v.doors.addAll(b.readList(x -> new DoorInfo(x.readUtf(), x.readLong(), x.readVarInt())));
        v.doorsMax = b.readVarInt(); v.factoriesCount = b.readVarInt(); v.factoriesMax = b.readVarInt();
        v.hasBase = b.readBoolean(); v.baseDim = b.readUtf(); v.basePos = b.readLong(); v.baseRadius = b.readVarInt();
        v.log.addAll(b.readList(x -> x.readUtf(200)));
        v.territories.addAll(b.readList(x -> new Terr(x.readUtf(), x.readUtf(), x.readInt(), x.readInt(), x.readInt(), x.readInt(),
                x.readUtf(), x.readUtf(), x.readInt(), x.readBoolean(), x.readUtf(), x.readLong(), x.readBoolean())));
        v.incomePerZone = b.readLong(); v.incomeCap = b.readLong(); v.incomeMinutes = b.readVarInt(); v.zonesHeld = b.readVarInt(); v.incomeNeedsOnline = b.readBoolean(); v.incomeToGang = b.readBoolean();
        v.invites.addAll(b.readList(x -> new Invite(x.readUtf(), x.readUtf(), x.readInt(), x.readUtf())));
        v.createCost = b.readLong(); v.membersMax = b.readVarInt();
        v.allGangs.addAll(b.readList(x -> new GangInfo(x.readUtf(), x.readUtf(), x.readInt(), x.readVarInt())));
        v.balance = b.readLong(); v.spendRank = b.readVarInt(); v.canSpend = b.readBoolean();
        v.txs.addAll(b.readList(x -> new TxInfo(x.readUtf(), x.readUtf(), x.readLong(), x.readUtf(120))));
        v.factoryEnabled = b.readBoolean(); v.factoryMaxPerGang = b.readVarInt(); v.factoryStockMax = b.readVarInt(); v.factoryQueueMax = b.readVarInt(); v.materialCap = b.readVarInt(); v.factoryCollectRank = b.readUtf();
        v.adminRecipes.addAll(b.readList(GangView::readRecipe));
        v.adminMaterials.addAll(b.readList(x -> new MaterialDef(x.readUtf(), x.readUtf(), x.readVarInt(), x.readLong())));
        if (b.readBoolean()) v.factory = FactoryInfo.decode(b);
        return v;
    }
}
