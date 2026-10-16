package fr.minenorth.portes.network;

import fr.minenorth.portes.agency.PropertyImages;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Morceau d'une capture d'aperçu envoyée par un admin (client vers serveur). */
public record ImageUploadPacket(int index, int total, byte[] chunk) {
    static void encode(ImageUploadPacket p, FriendlyByteBuf b) { b.writeVarInt(p.index); b.writeVarInt(p.total); b.writeByteArray(p.chunk); }

    static ImageUploadPacket decode(FriendlyByteBuf b) { return new ImageUploadPacket(b.readVarInt(), b.readVarInt(), b.readByteArray(PropertyImages.CHUNK_BYTES)); }

    static void handle(ImageUploadPacket p, Supplier<NetworkEvent.Context> c) {
        c.get().enqueueWork(() -> {
            ServerPlayer sp = c.get().getSender();
            if (sp != null) PropertyImages.receive(sp, p.index, p.total, p.chunk);
        });
        c.get().setPacketHandled(true);
    }
}
