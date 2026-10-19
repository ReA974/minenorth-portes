package fr.minenorth.portes.agency;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CataloguesTest {
    @Test void blankMeansTheAgencyItself() { assertEquals("", Catalogues.normalize(null)); assertEquals("", Catalogues.normalize("   ")); }
    @Test void spacesBecomeUnderscoresAndCaseIsIgnored() { assertEquals("casino_royal", Catalogues.normalize("  Casino Royal ")); }
    @Test void accentsAreRemovedAndOtherCharactersDropped() { assertEquals("e_cafe", Catalogues.normalize("É Café!")); assertEquals("ab", Catalogues.normalize("a/b")); }
    @Test void nameIsCappedAt24Characters() { assertEquals(24, Catalogues.normalize("x".repeat(40)).length()); }
    @Test void sameCatalogueIgnoringFormatting() {
        assertTrue(Catalogues.same("Casino", " casino "));
        assertTrue(Catalogues.same("", null));
        assertFalse(Catalogues.same("casino", ""));
    }
    @Test void npcTagGivesTheCatalogue() {
        assertEquals("", Catalogues.fromNpcTag("agence"));
        assertEquals("casino", Catalogues.fromNpcTag("agence:casino"));
        assertEquals("casino_royal", Catalogues.fromNpcTag("agence:Casino Royal"));
        assertNull(Catalogues.fromNpcTag("autre"));
        assertNull(Catalogues.fromNpcTag(null));
    }
}
