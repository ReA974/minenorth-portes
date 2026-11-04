package fr.minenorth.portes.client.phone;

import com.mikasrevs.newphone.client.zphone.PhoneNavigationState;
import fr.minenorth.portes.network.DecoNet;
import net.minecraft.client.Minecraft;

/**
 * Seul point d'entrée vers {@link PhoneDecoScreen} (sous-classe d'un écran du téléphone) : ne JAMAIS l'appeler sans avoir vérifié
 * {@code GangClient.phone()}. Côté client uniquement.
 */
public final class PhoneDeco {
    private static long pendingUntil;

    private PhoneDeco() {}

    public static void request() { pendingUntil = System.currentTimeMillis() + 5000; }

    public static void handle(DecoNet.View v) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PhoneDecoScreen s) {
            s.update(v);
        } else if (System.currentTimeMillis() < pendingUntil && mc.player != null) {
            pendingUntil = 0;
            PhoneNavigationState.open(mc, mc.screen, new PhoneDecoScreen(v));
        }
    }
}
