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
    @Test void localIsLimitedToTwoAndNotBusiness() {
        assertEquals(2, ImmoRules.MAX_LOCALS);
        assertEquals(PropertyCategory.LOCAL, PropertyCategory.from("local"));
        assertEquals(2, ImmoRules.limit(PropertyCategory.LOCAL));
        assertNotEquals(PropertyCategory.BUSINESS, PropertyCategory.LOCAL);
    }
    @Test void allEightCategoriesExistWithFrenchLabels() {
        assertEquals(8, PropertyCategory.values().length);
        assertEquals(PropertyCategory.SMALL_SHOP, PropertyCategory.from("small_shop"));
        assertEquals(PropertyCategory.STORAGE, PropertyCategory.from("STORAGE"));
        assertEquals(PropertyCategory.GARAGE, PropertyCategory.from("garage"));
        assertEquals(PropertyCategory.OTHER, PropertyCategory.from("other"));
        assertEquals("Petit commerce", PropertyCategory.SMALL_SHOP.label());
        assertEquals("AUTRES", PropertyCategory.OTHER.shortLabel());
        for (PropertyCategory c : PropertyCategory.values()) { assertFalse(c.label().isBlank()); assertFalse(c.shortLabel().isBlank()); }
    }
    @Test void onlyBusinessIsReservedToCompanies() {
        for (PropertyCategory c : PropertyCategory.values()) assertEquals(c == PropertyCategory.BUSINESS, c.companyOnly());
    }
    @Test void localStorageAndGarageCanBeBoughtByCompanyOrPlayer() {
        for (PropertyCategory c : PropertyCategory.values()) {
            boolean optional = c == PropertyCategory.LOCAL || c == PropertyCategory.STORAGE || c == PropertyCategory.GARAGE;
            assertEquals(optional, c.companyOptional(), c.name());
            assertEquals(optional || c == PropertyCategory.BUSINESS, c.companyAllowed(), c.name());
        }
    }
    @Test void unknownCategoryDefaultsToHouse() { assertEquals(PropertyCategory.HOUSE, PropertyCategory.from("bogus")); assertEquals(PropertyCategory.BUSINESS, PropertyCategory.from("business")); }
}
