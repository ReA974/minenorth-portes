package fr.minenorth.portes.gang;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** « Carnet de gang » : clic droit = ouvre l'écran « Mon gang » (comme la touche G). Ne contient rien, ne se perd pas. */
public class GangBookItem extends Item {
    public GangBookItem() { super(new Item.Properties().stacksTo(1)); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack st = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer sp && GangService.allow(sp.getUUID(), "carnet", 500)) GangGui.send(sp, true);
        return InteractionResultHolder.sidedSuccess(st, level.isClientSide);
    }
}
