package fr.minenorth.portes.deco;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Catalogue de l'application Déco : config/Minenorth-portes/deco-catalogue.json (généré par minenorth_shops/tools/gen_meubles.py : les mêmes
 * articles que les magasins de meubles, plus les collections VIP). Sans ce fichier : le pack de boutiques packs/meubles.json (sans VIP).
 * Un objet d'un mod absent est ignoré. Relu au démarrage et par /deco recharger.
 */
public final class DecoCatalog {
    private DecoCatalog() {}

    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    public record Article(int id, int shop, ItemStack item, long price, String category) {}

    public record Shop(String name, int vip) {}

    private static List<Shop> shops = List.of();
    private static List<Article> articles = List.of();

    public static List<Shop> shops() { return shops; }

    public static List<Article> articles() { return articles; }

    public static Article article(int id) { return id >= 0 && id < articles.size() ? articles.get(id) : null; }

    /** Renvoie le nombre d'articles chargés. */
    public static int load() {
        Path deco = DecoConfig.DIR.resolve("deco-catalogue.json");
        Path pack = FMLPaths.CONFIGDIR.get().resolve("minenorth_shops").resolve("packs").resolve("meubles.json");
        List<Shop> sh = new ArrayList<>();
        List<Article> ar = new ArrayList<>();
        try {
            boolean own = Files.isRegularFile(deco);
            Path f = own ? deco : pack;
            if (!Files.isRegularFile(f)) {
                LOG.warn("[Portes/déco] Ni deco-catalogue.json ni packs/meubles.json : catalogue Déco vide.");
            } else {
                JsonObject root = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), JsonObject.class);
                for (JsonElement el : root.getAsJsonArray(own ? "enseignes" : "shops")) {
                    JsonObject o = el.getAsJsonObject();
                    int shopId = sh.size();
                    sh.add(new Shop(o.get("name").getAsString(), o.has("vip") ? o.get("vip").getAsInt() : 0));
                    for (JsonElement ee : o.getAsJsonArray("entries")) {
                        JsonObject e = ee.getAsJsonObject();
                        ResourceLocation rl = ResourceLocation.tryParse(e.get("item").getAsString());
                        Item it = rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
                        if (it == null || it == Items.AIR) continue;
                        ItemStack st = new ItemStack(it, Math.max(1, e.has("quantity") ? e.get("quantity").getAsInt() : 1));
                        try {
                            if (e.has("nbt")) st.setTag(TagParser.parseTag(e.get("nbt").getAsString()));
                        } catch (Exception ex) {
                            continue;
                        }
                        long price = e.has("price") ? Math.max(0, e.get("price").getAsLong()) : 0;
                        if (price <= 0) continue;
                        ar.add(new Article(ar.size(), shopId, st, price, e.has("category") ? e.get("category").getAsString() : ""));
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("[Portes/déco] Catalogue illisible, l'ancien est gardé : {}", e.toString());
            return articles.size();
        }
        shops = List.copyOf(sh);
        articles = List.copyOf(ar);
        return ar.size();
    }
}
