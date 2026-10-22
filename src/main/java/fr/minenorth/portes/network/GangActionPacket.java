package fr.minenorth.portes.network;

import fr.minenorth.portes.gang.GangGui;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client -> serveur : une intention du joueur dans l'écran « Mon gang ». Le serveur revérifie tout (voir GangGui). */
public record GangActionPacket(int action, String a, String b, long pos) {
    static void encode(GangActionPacket p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.action); buf.writeUtf(p.a, 256); buf.writeUtf(p.b, 256); buf.writeLong(p.pos);
    }

    static GangActionPacket decode(FriendlyByteBuf buf) {
        return new GangActionPacket(buf.readVarInt(), buf.readUtf(256), buf.readUtf(256), buf.readLong());
    }

    static void handle(GangActionPacket p, Supplier<NetworkEvent.Context> c) {
        c.get().enqueueWork(() -> {
            ServerPlayer sp = c.get().getSender();
            if (sp != null) GangGui.handle(sp, p.action, p.a, p.b, p.pos);
        });
        c.get().setPacketHandled(true);
    }
}
