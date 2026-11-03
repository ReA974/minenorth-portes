package fr.minenorth.portes.client.phone;

import com.mikasrevs.newphone.client.zphone.PhoneNavigationState;
import fr.minenorth.portes.client.GangClient;
import fr.minenorth.portes.gang.GangView;
import net.minecraft.client.Minecraft;

/**
 * Seul point d'entrée vers {@link PhoneGangScreen} (qui hérite d'une classe du téléphone MikasRevs Phone 2). Ne JAMAIS toucher cette classe sans
 * avoir vérifié {@code GangClient.phone()} : sa vérification par la JVM charge PhoneGangScreen, donc la classe du téléphone. Côté client uniquement.
 */
public final class PhoneGang {
    /** Ouverture demandée depuis le téléphone : la prochaine vue du serveur ouvre l'application dans le téléphone (pendant 5 s). */
    private static long pendingUntil;

    private PhoneGang() {}

    /** Clic sur l'icône : on garde le téléphone ouvert et on demande l'état au serveur. */
    public static void request() {
        pendingUntil = System.currentTimeMillis() + 5000;
        GangClient.requestOpen();
    }

    /** true si la vue a été prise par l'application du téléphone (ouverte ou mise à jour). */
    public static boolean handle(GangView view) {
        Minecraft mc = Minecraft.getInstance();
        if (view.factory != null || view.doorMode != 0) return false;   // fabrique et fenêtre « porte » : écrans habituels
        if (mc.screen instanceof PhoneGangScreen ps) {
            ps.update(view);
            return true;
        }
        if (System.currentTimeMillis() < pendingUntil && mc.player != null) {
            pendingUntil = 0;
            PhoneNavigationState.open(mc, mc.screen, new PhoneGangScreen(view));
            return true;
        }
        return false;
    }
}
