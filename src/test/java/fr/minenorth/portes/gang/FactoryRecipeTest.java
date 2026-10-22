package fr.minenorth.portes.gang;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FactoryRecipeTest {
    @Test
    void ligneValide() {
        FactoryRecipe r = FactoryRecipe.parse("Munitions 9 mm | tacz:ammo{AmmoId:'tacz:9mm'} | 30 | 90 | 25");
        assertNotNull(r);
        assertEquals("Munitions 9 mm", r.label());
        assertEquals("tacz:ammo{AmmoId:'tacz:9mm'}", r.spec());
        assertEquals(30, r.qty());
        assertEquals(90, r.seconds());
        assertEquals(2500, r.cents());
        assertEquals("", r.needs());
        assertEquals(r, FactoryRecipe.parse(r.format()));
    }

    @Test
    void prixAvecDecimales() {
        assertEquals(1250, FactoryRecipe.parse("A b | minecraft:stone | 1 | 10 | 12,5").cents());
        assertEquals("12.50", FactoryRecipe.eurosText(1250));
        assertEquals("25", FactoryRecipe.eurosText(2500));
    }

    @Test
    void refuseLesSaisiesDangereuses() {
        assertNull(FactoryRecipe.parse("Trop | court"));
        assertNull(FactoryRecipe.parse("x | minecraft:stone | 1 | 10 | 5"));               // libellé trop court
        assertNull(FactoryRecipe.parse("Ok | minecraft:stone | 0 | 10 | 5"));               // quantité nulle
        assertNull(FactoryRecipe.parse("Ok | minecraft:stone | 1 | 1 | 5"));                // durée trop courte
        assertNull(FactoryRecipe.parse("Ok | minecraft:stone | 1 | 10 | -5"));              // prix négatif
        assertNull(FactoryRecipe.parse("Ok | pas un objet | 1 | 10 | 5"));
        assertNotNull(FactoryRecipe.check("Ok", "tacz:ammo{AmmoId:\"x\"}", 1, 10, 5));    // guillemets interdits (apostrophes)
        assertNotNull(FactoryRecipe.check("Ok", "minecraft:stone", 1, 10, FactoryRecipe.CENTS_MAX + 1));
        assertNotNull(FactoryRecipe.check("A|B", "minecraft:stone", 1, 10, 5));            // barre verticale interdite
        assertNull(FactoryRecipe.check("Ok", "minecraft:stone", 1, 10, 0));
    }

    @Test
    void lignesIllisiblesIgnorees() {
        List<FactoryRecipe> l = FactoryRecipe.parseAll(List.of("Ok | minecraft:stone | 1 | 10 | 5", "cassée", "Aussi | minecraft:dirt | 2 | 20 | 0"));
        assertEquals(2, l.size());
        assertEquals("Aussi", l.get(1).label());
    }

    @Test
    void besoinsEnMatieres() {
        FactoryRecipe r = FactoryRecipe.parse("Glock | tacz:modern_kinetic_gun{GunId:'tacz:glock_17'} | 1 | 600 | 100 | acier:5;poudre:1");
        assertNotNull(r);
        assertEquals(java.util.Map.of("acier", 5, "poudre", 1), FactoryRecipe.parseNeeds(r.needs()));
        assertEquals(r, FactoryRecipe.parse(r.format()));
        assertTrue(FactoryRecipe.parseNeeds("").isEmpty());
        assertNull(FactoryRecipe.parseNeeds("Acier:5"));          // identifiant en minuscules seulement
        assertNull(FactoryRecipe.parseNeeds("acier:0"));
        assertNull(FactoryRecipe.parseNeeds("acier:5;;"));
        assertNull(FactoryRecipe.parseNeeds("a:1;b:1;c:1;d:1;e:1;f:1;g:1")); // 6 matières au plus
        assertNull(FactoryRecipe.parse("Ok | minecraft:stone | 1 | 10 | 5 | n'importe quoi"));
        assertEquals(java.util.Map.of("acier", 7), FactoryRecipe.parseNeeds("acier:3;acier:4")); // cumul
    }

    @Test
    void matieres() {
        FactoryMaterial m = FactoryMaterial.parse("Acier | acier | 10 | 60");
        assertNotNull(m);
        assertEquals("acier", m.id());
        assertEquals(10, m.pack());
        assertEquals(6000, m.cents());
        assertEquals(m, FactoryMaterial.parse(m.format()));
        assertNull(FactoryMaterial.parse("A | acier | 10 | 60"));            // libellé trop court
        assertNull(FactoryMaterial.parse("Acier | Acier | 10 | 60"));        // identifiant en minuscules
        assertNull(FactoryMaterial.parse("Acier | acier | 0 | 60"));
        assertNull(FactoryMaterial.parse("Acier | acier | 10 | -1"));
        assertEquals(2, FactoryMaterial.parseAll(java.util.List.of("Acier | acier | 10 | 60", "Acier bis | acier | 5 | 10", "Laiton | laiton | 20 | 30")).size()); // identifiant unique
    }
}
