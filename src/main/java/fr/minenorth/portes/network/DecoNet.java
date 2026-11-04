package fr.minenorth.portes.network;

import fr.minenorth.portes.deco.DecoService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.function.Supplier;

/** Paquets de l'application Déco : Action (client -> serveur, revérifiée par DecoService) et View (serveur -> client). */
public final class DecoNet {
    private DecoNet() {}

    public record Shop(String name, int vip, boolean locked) {}

    public record Article(int id, int shop, ItemStack item, long price, String category) {}

    public record Lot(ItemStack item, int count) {}

    /** withCatalog : shops et articles sont remplis (sinon le client garde ceux qu'il a). */
    public record View(boolean withCatalog, List<Shop> shops, List<Article> articles, List<Lot> lots, int total, int capacity, String home,
                       String homeCategory, int placed, boolean terrain, int vipLevel, String message, boolean error) {
        static void encode(View v, FriendlyByteBuf b) {
            b.writeBoolean(v.withCatalog);
            b.writeCollection(v.shops, (x, s) -> { x.writeUtf(s.name); x.writeVarInt(s.vip); x.writeBoolean(s.locked); });
            b.writeCollection(v.articles, (x, a) -> { x.writeVarInt(a.id); x.writeVarInt(a.shop); x.writeItem(a.item); x.writeVarLong(a.price); x.writeUtf(a.category); });
            b.writeCollection(v.lots, (x, l) -> { x.writeItem(l.item); x.writeVarInt(l.count); });
            b.writeVarInt(v.total);
            b.writeVarInt(v.capacity);
            b.writeUtf(v.home);
            b.writeUtf(v.homeCategory);
            b.writeVarInt(v.placed);
            b.writeBoolean(v.terrain);
            b.writeVarInt(v.vipLevel);
            b.writeUtf(v.message, 512);
            b.writeBoolean(v.error);
        }

        static View decode(FriendlyByteBuf b) {
            return new View(b.readBoolean(),
                    b.readList(x -> new Shop(x.readUtf(), x.readVarInt(), x.readBoolean())),
                    b.readList(x -> new Article(x.readVarInt(), x.readVarInt(), x.readItem(), x.readVarLong(), x.readUtf())),
                    b.readList(x -> new Lot(x.readItem(), x.readVarInt())),
                    b.readVarInt(), b.readVarInt(), b.readUtf(), b.readUtf(), b.readVarInt(), b.readBoolean(), b.readVarInt(), b.readUtf(512), b.readBoolean());
        }

        static void handle(View v, Supplier<NetworkEvent.Context> c) {
            c.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> fr.minenorth.portes.client.DecoClient.onView(v)));
            c.get().setPacketHandled(true);
        }
    }

    /** action : DecoService.REFRESH (a = 1 : avec le catalogue), BUY (a = article), STORE, TAKE (a = lot, b = quantité). */
    public record Action(int action, int a, int b) {
        static void encode(Action p, FriendlyByteBuf b) { b.writeVarInt(p.action); b.writeVarInt(p.a); b.writeVarInt(p.b); }

        static Action decode(FriendlyByteBuf b) { return new Action(b.readVarInt(), b.readVarInt(), b.readVarInt()); }

        static void handle(Action p, Supplier<NetworkEvent.Context> c) {
            c.get().enqueueWork(() -> {
                ServerPlayer sp = c.get().getSender();
                if (sp != null) DecoService.handle(sp, p.action, p.a, p.b);
            });
            c.get().setPacketHandled(true);
        }
    }

    public static void send(ServerPlayer p, View v) { ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), v); }

    static void register(int first) {
        ModNetwork.CHANNEL.registerMessage(first, View.class, View::encode, View::decode, View::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        ModNetwork.CHANNEL.registerMessage(first + 1, Action.class, Action::encode, Action::decode, Action::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
    }
}
