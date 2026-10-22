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
}
