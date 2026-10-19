package fr.minenorth.portes.agency;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NameOrderTest {
    @Test void numbersAreOrderedNaturally() { assertTrue(NameOrder.compare("Chambre 2", "Chambre 10") < 0); }
    @Test void caseIsIgnored() { assertTrue(NameOrder.compare("abc", "Abd") < 0); assertEquals(0, NameOrder.compare("abc", "ABC")); }
    @Test void accentsAreIgnoredForTheFirstLetter() { assertTrue(NameOrder.compare("Éclair", "Zèbre") < 0); assertTrue(NameOrder.compare("Zèbre", "Étoile") > 0); }
    @Test void sortsAList() {
        List<String> names = new ArrayList<>(List.of("Villa 10", "garage 2", "Villa 2", "Appartement A", "Zen"));
        names.sort(NameOrder::compare);
        assertEquals(List.of("Appartement A", "garage 2", "Villa 2", "Villa 10", "Zen"), names);
    }
}
