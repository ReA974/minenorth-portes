package fr.minenorth.portes.gang;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/**
 * Menu du coffre commun d'un gang (54 cases, vanilla côté client). Un SEUL conteneur par gang, partagé par tous les ouvreurs :
 * aucune copie d'objets possible. Chaque clic est comparé avant/après : ce qui sort ou entre est écrit au journal avec l'UUID du joueur.
 * Le menu se referme tout seul si le joueur n'est plus membre, s'il s'éloigne ou si le bloc disparaît.
 */
public final class GangVaultMenu extends ChestMenu {
    private final MinecraftServer server;
    private final UUID gangId;
    private final String dim;
    private final BlockPos origin;

    private GangVaultMenu(int id, Inventory inv, Gang gang, MinecraftServer server, String dim, BlockPos origin) {
        super(MenuType.GENERIC_9x6, id, inv, gang.vault(), 6);
        this.server = server;
        this.gangId = gang.id;
        this.dim = dim;
        this.origin = origin;
    }

    public UUID gangId() { return gangId; }

    public static void open(ServerPlayer p, Gang gang, BlockPos pos) {
        String dim = p.level().dimension().location().toString();
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new GangVaultMenu(id, inv, gang, p.server, dim, pos),
                Component.literal("Coffre de gang – " + gang.name())));
    }

    @Override
    public boolean stillValid(Player player) {
        Gang g = GangData.get(server).gang(gangId);
        if (g == null || !g.isMember(player.getUUID())) return false;
        if (!player.level().dimension().location().toString().equals(dim)) return false;
        if (!(player.level().getBlockState(origin).getBlock() instanceof GangChestBlock)) return false;
        return player.distanceToSqr(origin.getX() + 0.5, origin.getY() + 0.5, origin.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void clicked(int slot, int button, ClickType type, Player player) {
        Gang g = GangData.get(server).gang(gangId);
        if (!(player instanceof ServerPlayer sp) || g == null || !g.isMember(player.getUUID())) {
            // Pas membre (ou plus membre) : aucune action, on referme.
            if (player instanceof ServerPlayer sp2) sp2.closeContainer();
            return;
        }
        Tally before = Tally.of(g.vault());
        super.clicked(slot, button, type, player);
        Tally after = Tally.of(g.vault());
        logDiff(g, sp, before, after);
    }

    private void logDiff(Gang g, ServerPlayer p, Tally before, Tally after) {
        Set<String> keys = new LinkedHashSet<>(before.counts.keySet());
        keys.addAll(after.counts.keySet());
        int lines = 0;
        for (String k : keys) {
            int delta = after.counts.getOrDefault(k, 0) - before.counts.getOrDefault(k, 0);
            if (delta == 0) continue;
            if (lines++ >= 6) break; // un clic ne change jamais autant de types : garde-fou
            String name = after.names.getOrDefault(k, before.names.getOrDefault(k, k));
            g.addLog(p.getUUID(), (delta < 0 ? "retire " : "dépose ") + Math.abs(delta) + " x " + name, GangConfig.logMax());
        }
        if (lines > 0) GangData.get(server).setDirty();
    }

    /** Photo du contenu : quantité totale par type d'objet (identifiant + NBT). */
    private static final class Tally {
        final Map<String, Integer> counts = new HashMap<>();
        final Map<String, String> names = new HashMap<>();

        static Tally of(Container c) {
            Tally t = new Tally();
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty()) continue;
                var id = ForgeRegistries.ITEMS.getKey(s.getItem());
                String key = (id == null ? "?" : id.toString()) + (s.hasTag() ? s.getTag().toString() : "");
                t.counts.merge(key, s.getCount(), Integer::sum);
                t.names.putIfAbsent(key, s.getHoverName().getString());
            }
            return t;
        }
    }
}
