package fr.minenorth.portes.gang;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Photo de ce qu'un joueur a le droit de voir de son gang, envoyée au client pour dessiner l'écran « Mon gang ».
 * Le client n'en tire AUCUN droit : chaque action revient au serveur qui revérifie tout.
 */
public final class GangView {
    public record Member(String uuid, String name, int rank, boolean online) {}
    public record Nearby(String uuid, String name) {}
    public record DoorInfo(String dim, long pos, int minRank) {}
    public record Terr(String name, String dim, int x1, int z1, int x2, int z2, String holderTag, String holderName, int holderColor, boolean mine, String status) {}
    public record Invite(String tag, String name, int color, String inviter) {}
    public record GangInfo(String name, String tag, int color, int members) {}

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
    public int doorsMax, chestsCount, chestsMax, vaultItems, vaultStacks;
    public final List<String> log = new ArrayList<>();
    public final List<Terr> territories = new ArrayList<>();
    public long incomePerZone, incomeCap;
    public int incomeMinutes, zonesHeld;
    public final List<Invite> invites = new ArrayList<>();
    public long createCost;
    public int membersMax;
    public final List<GangInfo> allGangs = new ArrayList<>();

    public void encode(FriendlyByteBuf b) {
        b.writeBoolean(open); b.writeVarInt(doorMode); b.writeLong(doorPos); b.writeVarInt(doorMin);
        b.writeUtf(message, 300); b.writeBoolean(messageError);
        b.writeBoolean(inGang); b.writeUtf(name); b.writeUtf(tag); b.writeInt(color); b.writeVarInt(myRank + 1); b.writeBoolean(op);
        b.writeCollection(members, (x, m) -> { x.writeUtf(m.uuid); x.writeUtf(m.name); x.writeVarInt(m.rank); x.writeBoolean(m.online); });
        b.writeCollection(nearby, (x, m) -> { x.writeUtf(m.uuid); x.writeUtf(m.name); });
        b.writeCollection(doors, (x, m) -> { x.writeUtf(m.dim); x.writeLong(m.pos); x.writeVarInt(m.minRank); });
        b.writeVarInt(doorsMax); b.writeVarInt(chestsCount); b.writeVarInt(chestsMax); b.writeVarInt(vaultItems); b.writeVarInt(vaultStacks);
        b.writeCollection(log, (x, s) -> x.writeUtf(s, 200));
        b.writeCollection(territories, (x, t) -> {
            x.writeUtf(t.name); x.writeUtf(t.dim); x.writeInt(t.x1); x.writeInt(t.z1); x.writeInt(t.x2); x.writeInt(t.z2);
            x.writeUtf(t.holderTag); x.writeUtf(t.holderName); x.writeInt(t.holderColor); x.writeBoolean(t.mine); x.writeUtf(t.status);
        });
        b.writeLong(incomePerZone); b.writeLong(incomeCap); b.writeVarInt(incomeMinutes); b.writeVarInt(zonesHeld);
        b.writeCollection(invites, (x, i) -> { x.writeUtf(i.tag); x.writeUtf(i.name); x.writeInt(i.color); x.writeUtf(i.inviter); });
        b.writeLong(createCost); b.writeVarInt(membersMax);
        b.writeCollection(allGangs, (x, g) -> { x.writeUtf(g.name); x.writeUtf(g.tag); x.writeInt(g.color); x.writeVarInt(g.members); });
    }

    public static GangView decode(FriendlyByteBuf b) {
        GangView v = new GangView();
        v.open = b.readBoolean(); v.doorMode = b.readVarInt(); v.doorPos = b.readLong(); v.doorMin = b.readVarInt();
        v.message = b.readUtf(300); v.messageError = b.readBoolean();
        v.inGang = b.readBoolean(); v.name = b.readUtf(); v.tag = b.readUtf(); v.color = b.readInt(); v.myRank = b.readVarInt() - 1; v.op = b.readBoolean();
        v.members.addAll(b.readList(x -> new Member(x.readUtf(), x.readUtf(), x.readVarInt(), x.readBoolean())));
        v.nearby.addAll(b.readList(x -> new Nearby(x.readUtf(), x.readUtf())));
        v.doors.addAll(b.readList(x -> new DoorInfo(x.readUtf(), x.readLong(), x.readVarInt())));
        v.doorsMax = b.readVarInt(); v.chestsCount = b.readVarInt(); v.chestsMax = b.readVarInt(); v.vaultItems = b.readVarInt(); v.vaultStacks = b.readVarInt();
        v.log.addAll(b.readList(x -> x.readUtf(200)));
        v.territories.addAll(b.readList(x -> new Terr(x.readUtf(), x.readUtf(), x.readInt(), x.readInt(), x.readInt(), x.readInt(),
                x.readUtf(), x.readUtf(), x.readInt(), x.readBoolean(), x.readUtf())));
        v.incomePerZone = b.readLong(); v.incomeCap = b.readLong(); v.incomeMinutes = b.readVarInt(); v.zonesHeld = b.readVarInt();
        v.invites.addAll(b.readList(x -> new Invite(x.readUtf(), x.readUtf(), x.readInt(), x.readUtf())));
        v.createCost = b.readLong(); v.membersMax = b.readVarInt();
        v.allGangs.addAll(b.readList(x -> new GangInfo(x.readUtf(), x.readUtf(), x.readInt(), x.readVarInt())));
        return v;
    }
}
