package fr.minenorth.portes.client;

import fr.minenorth.portes.deco.DecoService;
import fr.minenorth.portes.network.DecoNet;
import fr.minenorth.portes.network.ModNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Côté client de l'application Déco. Appelée par le téléphone MRPhone patché (JobsHook, par réflexion : {@link #visible()} et
 * {@link #openFromPhone()}) : cette classe ne touche aucune classe du téléphone ; l'écran est dans phone.PhoneDeco / PhoneDecoScreen.
 */
public final class DecoClient {
    private DecoClient() {}

    /** Catalogue reçu à chaque ouverture (le staff peut le recharger : les numéros d'articles suivent le serveur). */
    public static List<DecoNet.Shop> shops = List.of();
    public static List<DecoNet.Article> articles = List.of();

    /** Icône toujours visible : l'application explique elle-même qu'on achète et qu'on sort ses meubles chez soi. */
    public static boolean visible() { return true; }

    public static void openFromPhone() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!GangClient.phone()) {
            mc.player.displayClientMessage(Component.literal("L'application Déco s'ouvre depuis le téléphone."), true);
            return;
        }
        fr.minenorth.portes.client.phone.PhoneDeco.request();
        send(DecoService.REFRESH, 1, 0);
    }

    public static void send(int action, int a, int b) { ModNetwork.CHANNEL.sendToServer(new DecoNet.Action(action, a, b)); }

    public static void onView(DecoNet.View v) {
        if (v.withCatalog()) {
            shops = v.shops();
            articles = v.articles();
        }
        if (GangClient.phone()) fr.minenorth.portes.client.phone.PhoneDeco.handle(v);
    }
}
