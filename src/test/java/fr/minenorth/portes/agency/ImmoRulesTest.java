package fr.minenorth.portes.agency;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImmoRulesTest {
    @Test void resaleIs65Percent() { assertEquals(2_600_000L, ImmoRules.resaleCents(4_000_000L)); }
    @Test void resaleRoundsDown() { assertEquals(0L, ImmoRules.resaleCents(1)); }
    @Test void limitsAreTwoPerCategory() {
        for (PropertyCategory c : PropertyCategory.values()) {
            assertEquals(2, ImmoRules.limit(c));
            assertTrue(ImmoRules.canAcquire(c, 1));
            assertFalse(ImmoRules.canAcquire(c, 2));
        }
    }
    @Test void extensionAddsToRemainingDays() { assertEquals(5_000 + 86_400_000L, ImmoRules.extendedExpiryMs(1_000, 5_000, 1)); }
    @Test void extensionOfExpiredRestartsFromNow() { assertEquals(10_000 + 172_800_000L, ImmoRules.extendedExpiryMs(10_000, 5_000, 2)); }
    @Test void unknownCategoryDefaultsToHouse() { assertEquals(PropertyCategory.HOUSE, PropertyCategory.from("bogus")); assertEquals(PropertyCategory.BUSINESS, PropertyCategory.from("business")); }
}
