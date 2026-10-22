package fr.minenorth.portes.gang;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/** Fabrication des objets d'une recette : « modid:objet{données} » -> piles d'objets. Aucune confiance au texte : objet connu et données lisibles, sinon refus. */
public final class FactoryItems {
    private FactoryItems() {}

    /** Objet principal d'un texte « modid:objet{...} », ou null s'il n'existe pas. */
    private static Item item(String spec) {
        int brace = spec.indexOf('{');
        String id = brace < 0 ? spec : spec.substring(0, brace);
        ResourceLocation rl = ResourceLocation.tryParse(id.trim());
        if (rl == null || !ForgeRegistries.ITEMS.containsKey(rl)) return null;
        Item it = ForgeRegistries.ITEMS.getValue(rl);
        return it == null || it == net.minecraft.world.item.Items.AIR ? null : it;
    }

    private static CompoundTag nbt(String spec) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        int brace = spec.indexOf('{');
        return brace < 0 ? null : TagParser.parseTag(spec.substring(brace));
    }

    /** Message d'erreur si l'objet n'existe pas ou si ses données sont illisibles ; null si tout va bien. */
    public static String validate(String spec) {
        if (item(spec) == null) return "Objet inconnu (mod absent ?) : " + spec.split("\\{")[0];
        try {
            CompoundTag t = nbt(spec);
            if (t != null && t.toString().length() > 2000) return "Données de l'objet trop longues.";
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            return "Données de l'objet illisibles (utilisez des apostrophes pour les textes).";
        }
        return null;
    }

    /** Piles prêtes à donner pour {@code qty} exemplaires. Liste vide si l'objet est invalide. */
    public static List<ItemStack> stacks(String spec, int qty) {
        List<ItemStack> out = new ArrayList<>();
        Item it = item(spec);
        if (it == null || qty <= 0) return out;
        CompoundTag tag;
        try { tag = nbt(spec); } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) { return out; }
        int left = Math.min(qty, 4096);
        int max = Math.max(1, new ItemStack(it).getMaxStackSize());
        while (left > 0) {
            int n = Math.min(max, left);
            ItemStack st = new ItemStack(it, n);
            if (tag != null) st.setTag(tag.copy());
            out.add(st);
            left -= n;
        }
        return out;
    }
}
