package fr.minenorth.portes.gang;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FactoryCatalogueJsonTest {
    @Test
    void allerRetourAvecDonneesDeLArme() {
        // objet TACZ avec ses données (guillemets, comme écrit par un NBT d'objet réel)
        FactoryRecipe gun = new FactoryRecipe("Glock 17", "tacz:modern_kinetic_gun{GunId:\"tacz:glock_17\",GunCurrentAmmoCount:0}", 1, 600, 10000, "acier:5", true, 3);
        FactoryRecipe ammo = new FactoryRecipe("9 mm", "tacz:ammo{AmmoId:\"tacz:9mm\"}", 30, 90, 0, "", false, 0);
        List<FactoryRecipe> back = FactoryCatalogueJson.fromJson(FactoryCatalogueJson.toJson(List.of(gun, ammo)));
        assertEquals(List.of(gun, ammo), back);
        assertEquals("tacz:modern_kinetic_gun{GunId:\"tacz:glock_17\",GunCurrentAmmoCount:0}", back.get(0).spec());
        assertFalse(back.get(1).enabled());
        assertEquals(3, back.get(0).daily());
    }

    @Test
    void fichierIllisibleOuEntreesInvalides() {
        assertNull(FactoryCatalogueJson.fromJson("pas du json"));
        assertNull(FactoryCatalogueJson.fromJson("{\"autre\": 1}"));
        // une entrée invalide est ignorée, les autres restent
        String json = "{\"recettes\":[{\"libelle\":\"Bon\",\"objet\":\"minecraft:stone\",\"quantite\":2,\"secondes\":30,\"euros\":1.5},"
                + "{\"libelle\":\"x\",\"objet\":\"minecraft:stone\"},"
                + "{\"libelle\":\"Mauvais\",\"objet\":\"minecraft:stone\",\"quantite\":-4,\"secondes\":30,\"euros\":1},"
                + "{\"libelle\":\"Limite folle\",\"objet\":\"minecraft:stone\",\"quantite\":1,\"secondes\":30,\"euros\":1,\"limite_jour\":99999}]}";
        List<FactoryRecipe> r = FactoryCatalogueJson.fromJson(json);
        assertNotNull(r);
        assertEquals(1, r.size());
        assertEquals("Bon", r.get(0).label());
        assertEquals(150, r.get(0).cents());
        assertTrue(r.get(0).enabled());
        assertEquals(0, r.get(0).daily());
    }

    @Test
    void objetAvecGuillemetsMaisSansBarreVerticale() {
        assertNull(FactoryRecipe.check("Ok", "tacz:ammo{AmmoId:\"tacz:9mm\"}", 1, 10, 0, ""));
        assertNotNull(FactoryRecipe.check("Ok", "tacz:ammo{Nom:\"a|b\"}", 1, 10, 0, ""));
    }
}
