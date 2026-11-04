package fr.minenorth.portes.deco;

import fr.minenorth.api.MineNorth;
import fr.minenorth.portes.agency.PropertyLog;
import fr.minenorth.portes.deco.PlacedLedger.Part;
import fr.minenorth.portes.deco.PlacedLedger.Placed;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Retire des blocs du monde vers le garde-meuble, sans duplication possible :
 * <ol>
 *   <li>le bloc doit être exactement celui du registre (sinon : rien) ;</li>
 *   <li>le contenu du meuble (coffre, tiroir…) est vidé dans le garde-meuble AVANT le retrait ;</li>
 *   <li>le bloc et ses parties sont remplacés par de l'air sans drop ni mise à jour des voisins ;</li>
 *   <li>tout objet qui apparaîtrait quand même dans la zone pendant l'opération est capturé (garde-meuble) au lieu de tomber au sol ;</li>
 *   <li>l'entrée sort du registre : un bloc ne peut être rendu qu'une fois.</li>
 * </ol>
 * L'objet rendu est celui qui avait servi à poser le bloc (NBT compris). Tout se fait dans le même tick, sur le thread serveur.
 */
@Mod.EventBusSubscriber
public final class Mover {
    private Mover() {}

    /** Bloc + parties retirés sans drop, sans mise à jour des voisins (pas de torche ou de tableau qui tombe). */
    static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private static ServerLevel captureLevel;
    private static AABB captureBox;
    private static List<ItemStack> captured;

    /** Capture les objets qui apparaissent dans la zone pendant une opération (au lieu de les laisser tomber). */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onJoin(EntityJoinLevelEvent e) {
        if (captured == null || e.getLevel() != captureLevel) return;
        if (e.getEntity() instanceof ItemEntity ie && captureBox.contains(ie.position())) {
            captured.add(ie.getItem().copy());
            e.setCanceled(true);
        } else if (e.getEntity() instanceof ExperienceOrb orb && captureBox.contains(orb.position())) {
            e.setCanceled(true);
        }
    }

    static void beginCapture(ServerLevel level, AABB box) {
        captureLevel = level;
        captureBox = box;
        captured = new ArrayList<>();
    }

    static List<ItemStack> endCapture() {
        List<ItemStack> out = captured == null ? List.of() : captured;
        captured = null;
        captureLevel = null;
        captureBox = null;
        return out;
    }

    public static String id(BlockState s) {
        ResourceLocation k = ForgeRegistries.BLOCKS.getKey(s.getBlock());
        return k == null ? "" : k.toString();
    }

    public static ServerLevel level(MinecraftServer s, String dim) {
        ResourceLocation rl = ResourceLocation.tryParse(dim);
        return rl == null ? null : s.getLevel(ResourceKey.create(Registries.DIMENSION, rl));
    }

    /** Le bloc principal est-il toujours celui enregistré ? */
    public static boolean matches(ServerLevel level, long main, Placed p) {
        BlockPos pos = BlockPos.of(main);
        level.getChunk(pos);   // charge la zone si besoin (déménagement d'un joueur absent)
        return id(level.getBlockState(pos)).equals(p.block());
    }

    /** Vrai si le meuble contient des objets (coffre, tiroir…). */
    public static boolean hasContents(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return false;
        if (be instanceof Container c) return !c.isEmpty();
        IItemHandler h = be.getCapability(ForgeCapabilities.ITEM_HANDLER, (Direction) null).orElse(null);
        if (h == null) return false;
        for (int i = 0; i < h.getSlots(); i++) if (!h.getStackInSlot(i).isEmpty()) return true;
        return false;
    }

    static void drain(ServerLevel level, BlockPos pos, List<ItemStack> out) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return;
        if (be instanceof Container c) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.removeItemNoUpdate(i);
                if (!st.isEmpty()) out.add(st);
            }
            c.setChanged();
            return;
        }
        IItemHandler h = be.getCapability(ForgeCapabilities.ITEM_HANDLER, (Direction) null).orElse(null);
        if (h == null) return;
        for (int i = 0; i < h.getSlots(); i++) {
            for (int guard = 0; guard < 64 && !h.getStackInSlot(i).isEmpty(); guard++) {
                ItemStack st = h.extractItem(i, Integer.MAX_VALUE, false);
                if (st.isEmpty()) break;
                out.add(st);
            }
        }
    }

    /**
     * Retire un bloc enregistré du monde et renvoie ce qu'il rend : l'objet qui l'avait posé, puis son contenu et tout objet capturé.
     * Le bloc doit correspondre ({@link #matches}) : à vérifier avant. L'entrée du registre est retirée par l'appelant.
     */
    static List<ItemStack> takeOut(ServerLevel level, long main, Placed p) {
        BlockPos pos = BlockPos.of(main);
        List<ItemStack> out = new ArrayList<>();
        out.add(p.item().copyWithCount(1));
        AABB box = new AABB(pos).inflate(3);
        for (Part part : p.parts()) box = box.minmax(new AABB(BlockPos.of(part.pos())).inflate(3));
        beginCapture(level, box);
        try {
            drain(level, pos, out);
            for (Part part : p.parts()) {
                BlockPos pp = BlockPos.of(part.pos());
                if (!id(level.getBlockState(pp)).equals(part.block())) continue;   // partie déjà disparue ou remplacée : on n'y touche pas
                drain(level, pp, out);
                level.setBlock(pp, Blocks.AIR.defaultBlockState(), FLAGS);
            }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
        } finally {
            out.addAll(endCapture());
        }
        return out;
    }

    private static String describe(ItemStack st) {
        ResourceLocation k = ForgeRegistries.ITEMS.getKey(st.getItem());
        return (k == null ? "?" : k.toString()) + " x" + st.getCount();
    }

    /**
     * Déménagement d'un bien : chaque bloc enregistré encore en place part au garde-meuble de celui qui l'a posé, avec son contenu.
     * Toujours accepté (même au-delà de la capacité). Journalisé (journal du bien + logs/minenorth-deco.log). Renvoie le nombre de meubles.
     */
    public static int moveOut(MinecraftServer s, String house, String dimension, String houseName, String reason) {
        PlacedLedger ledger = PlacedLedger.get(s);
        Map<Long, Placed> entries = ledger.entries(house);
        if (entries.isEmpty()) return 0;
        ServerLevel level = level(s, dimension);
        GardeMeuble store = GardeMeuble.get(s);
        Map<UUID, int[]> perPlayer = new LinkedHashMap<>();
        int moved = 0, stale = 0;
        for (Map.Entry<Long, Placed> en : entries.entrySet()) {
            Placed p = en.getValue();
            ledger.forget(PlacedLedger.dimOf(house), en.getKey());   // d'abord hors du registre : jamais rendu deux fois
            if (level == null || !matches(level, en.getKey(), p)) { stale++; continue; }
            List<ItemStack> items = takeOut(level, en.getKey(), p);
            StringBuilder what = new StringBuilder();
            for (ItemStack st : items) {
                store.add(p.placer(), st);
                perPlayer.computeIfAbsent(p.placer(), k -> new int[1])[0] += st.getCount();
                what.append(what.length() == 0 ? "" : ", ").append(describe(st));
            }
            moved++;
            DecoJournal.log("Déménagement « " + houseName + " » (" + reason + ") : " + BlockPos.of(en.getKey()).toShortString() + " -> garde-meuble de "
                    + MineNorth.pseudo(s, p.placer()) + " : " + what);
        }
        StringBuilder who = new StringBuilder();
        for (Map.Entry<UUID, int[]> e : perPlayer.entrySet()) {
            who.append(who.length() == 0 ? "" : ", ").append(MineNorth.pseudo(s, e.getKey())).append(" (").append(e.getValue()[0]).append(" objet(s))");
            ServerPlayer online = s.getPlayerList().getPlayer(e.getKey());
            if (online != null) online.sendSystemMessage(Component.literal("§b🛋 Déménagement de « " + houseName + " » : " + e.getValue()[0]
                    + " objet(s) rangés dans votre garde-meuble (application Déco du téléphone)."));
        }
        if (moved > 0 || stale > 0) {
            PropertyLog.add(s, dimension, houseName, "Déménagement automatique (" + reason + ") : " + moved + " meuble(s) vers le garde-meuble"
                    + (who.length() > 0 ? " de " + who : "") + (stale > 0 ? " ; " + stale + " déjà retiré(s)" : ""));
            DecoJournal.log("Déménagement « " + houseName + " » (" + reason + ") terminé : " + moved + " meuble(s), " + stale + " entrée(s) périmée(s).");
        }
        return moved;
    }
}
