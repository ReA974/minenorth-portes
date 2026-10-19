package fr.minenorth.portes.gang;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GangRulesTest {
    @Test
    void nomValide() {
        assertEquals("Les Aigles", GangRules.cleanName("  Les   Aigles "));
        assertEquals("Ñandú-9", GangRules.cleanName("Ñandú-9"));
        assertNull(GangRules.cleanName("ab"));
        assertNull(GangRules.cleanName("x".repeat(21)));
        assertNull(GangRules.cleanName("§cRouge"));
        assertEquals("Bad Name", GangRules.cleanName("Bad\nName")); // les caractères de contrôle deviennent des espaces
        assertNull(GangRules.cleanName(null));
    }

    @Test
    void tagValide() {
        assertEquals("AIG", GangRules.cleanTag("aig"));
        assertNull(GangRules.cleanTag("A"));
        assertNull(GangRules.cleanTag("ABCDE"));
        assertNull(GangRules.cleanTag("A B"));
        assertNull(GangRules.cleanTag("é!"));
    }

    @Test
    void couleurs() {
        assertEquals(0xE53935, GangRules.parseColor("Rouge"));
        assertEquals(0x112233, GangRules.parseColor("#112233"));
        assertEquals(0xABCDEF, GangRules.parseColor("abcdef"));
        assertEquals(-1, GangRules.parseColor("turquoisebizarre"));
        assertEquals('c', GangRules.chatColor(0xFF5555));
    }

    @Test
    void grades() {
        assertEquals(GangRank.LIEUTENANT, GangRank.parse(" Lieutenant "));
        assertNull(GangRank.parse("roi"));
        assertTrue(GangRank.CHEF.atLeast(GangRank.MEMBRE));
        assertFalse(GangRank.MEMBRE.atLeast(GangRank.LIEUTENANT));
        assertEquals(GangRank.MEMBRE, GangRank.ofLevel(99));
    }

    @Test
    void presenceEtCapture() {
        Map<UUID, Integer> m = new HashMap<>();
        assertEquals(GangRules.Presence.VIDE, GangRules.presence(m));
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        m.put(a, 3);
        assertEquals(GangRules.Presence.UN_SEUL_GANG, GangRules.presence(m));
        m.put(b, 1);
        assertEquals(GangRules.Presence.CONTESTE, GangRules.presence(m));

        // un gang seul avec assez de monde progresse ; trop peu : recule ; contesté : gelé ; vide : recule
        assertEquals(11, GangRules.nextProgress(10, GangRules.Presence.UN_SEUL_GANG, 2, 2, false, 2));
        assertEquals(8, GangRules.nextProgress(10, GangRules.Presence.UN_SEUL_GANG, 1, 2, false, 2));
        assertEquals(10, GangRules.nextProgress(10, GangRules.Presence.CONTESTE, 3, 2, false, 2));
        assertEquals(0, GangRules.nextProgress(1, GangRules.Presence.VIDE, 0, 2, false, 2));
        assertEquals(8, GangRules.nextProgress(10, GangRules.Presence.UN_SEUL_GANG, 5, 2, true, 2)); // le détenteur ne se « recapture » pas
    }

    @Test
    void revenuPlafonne() {
        assertEquals(2000, GangRules.cappedPayout(2000, 0, 12000));
        assertEquals(500, GangRules.cappedPayout(2000, 11500, 12000));
        assertEquals(0, GangRules.cappedPayout(2000, 12000, 12000));
        assertEquals(0, GangRules.cappedPayout(2000, 99999, 12000));
        assertEquals(2000, GangRules.cappedPayout(2000, 99999, 0)); // 0 = pas de plafond
        assertEquals(0, GangRules.cappedPayout(-5, 0, 100));
    }

    @Test
    void cuboide() {
        assertTrue(GangRules.inBox(5, 64, 5, 0, 0, 0, 10, 100, 10));
        assertTrue(GangRules.inBox(5, 64, 5, 10, 100, 10, 0, 0, 0)); // coins dans l'autre ordre
        assertFalse(GangRules.inBox(11, 64, 5, 0, 0, 0, 10, 100, 10));
        assertTrue(GangRules.sameName(" Jean Dupont ", "jean dupont"));
    }
}
