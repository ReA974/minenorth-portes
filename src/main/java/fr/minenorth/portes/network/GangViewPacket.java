package fr.minenorth.portes.network;

import fr.minenorth.portes.client.GangClient;
import fr.minenorth.portes.gang.GangView;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Serveur -> client : ce que le joueur voit de son gang (écran « Mon gang »). */
public record GangViewPacket(GangView view) {
    static void encode(GangViewPacket p, FriendlyByteBuf b) { p.view.encode(b); }

    static GangViewPacket decode(FriendlyByteBuf b) { return new GangViewPacket(GangView.decode(b)); }

    static void handle(GangViewPacket p, Supplier<NetworkEvent.Context> c) {
        c.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> GangClient.onView(p.view)));
        c.get().setPacketHandled(true);
    }
}
