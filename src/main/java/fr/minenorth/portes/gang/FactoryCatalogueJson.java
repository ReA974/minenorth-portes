package fr.minenorth.portes.gang;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Format JSON du catalogue de la Fabrique d'armes (fichier config/Minenorth-gangs/fabrique_catalogue.json, écrit par le mod, relu automatiquement).
 * Logique pure (sans Minecraft) : testée à part. Une entrée illisible ou invalide est ignorée, jamais fatale.
 */
public final class FactoryCatalogueJson {
    private FactoryCatalogueJson() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static String toJson(List<FactoryRecipe> list) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("note", "Catalogue de la Fabrique d'armes. Modifiable en jeu (panneau admin > Gangs > Catalogue) ; rechargé automatiquement.");
        JsonArray arr = new JsonArray();
        for (FactoryRecipe r : list) {
            JsonObject o = new JsonObject();
            o.addProperty("libelle", r.label());
            o.addProperty("objet", r.spec());
            o.addProperty("quantite", r.qty());
            o.addProperty("secondes", r.seconds());
            o.addProperty("euros", r.cents() / 100.0);
            o.addProperty("besoins", r.needs());
            o.addProperty("active", r.enabled());
            o.addProperty("limite_jour", r.daily());
            arr.add(o);
        }
        root.add("recettes", arr);
        return GSON.toJson(root);
    }

    /** Recettes lues ; null si le texte n'est pas un JSON de catalogue lisible (le fichier existant est alors conservé en mémoire). */
    public static List<FactoryRecipe> fromJson(String json) {
        try {
            JsonElement el = GSON.fromJson(json, JsonElement.class);
            if (el == null || !el.isJsonObject() || !el.getAsJsonObject().has("recettes")) return null;
            List<FactoryRecipe> out = new ArrayList<>();
            for (JsonElement e : el.getAsJsonObject().getAsJsonArray("recettes")) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                try {
                    String label = str(o, "libelle"), spec = str(o, "objet"), needs = str(o, "besoins");
                    long qty = num(o, "quantite", 1), sec = num(o, "secondes", 60);
                    long cents = Math.round(dbl(o, "euros") * 100.0);
                    boolean enabled = !o.has("active") || o.get("active").getAsBoolean();
                    long daily = num(o, "limite_jour", 0);
                    if (daily < 0 || daily > 1000) continue;
                    if (FactoryRecipe.check(label, spec, qty, sec, cents, needs) != null) continue;
                    out.add(new FactoryRecipe(label.trim(), spec.trim(), (int) qty, (int) sec, cents, needs.trim(), enabled, (int) daily));
                } catch (RuntimeException ex) {
                    // entrée abîmée : ignorée
                }
            }
            return out;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
    private static long num(JsonObject o, String k, long def) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : def; }
    private static double dbl(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : 0; }
}
