package fr.minenorth.portes.gang;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * « Coffre de gang » : bloc sans inventaire propre. La position est rattachée à un gang (GangData) et ouvre le coffre commun du gang,
 * réservé à ses membres. Casser le bloc ne perd rien : le contenu vit dans la sauvegarde du gang.
 */
public class GangChestBlock extends HorizontalDirectionalBlock {
    private static final VoxelShape SHAPE = net.minecraft.world.level.block.Block.box(1, 0, 1, 15, 14, 15);

    public GangChestBlock() {
        super(BlockBehaviour.Properties.of().strength(5.0F, 1200.0F).sound(SoundType.METAL).noOcclusion());
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> b) { b.add(FACING); }

    @Override public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override public VoxelShape getShape(BlockState s, BlockGetter g, BlockPos p, CollisionContext c) { return SHAPE; }

    @Override public PushReaction getPistonPushReaction(BlockState s) { return PushReaction.BLOCK; }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.CONSUME;
        if (!GangService.allow(sp.getUUID(), "coffre", 400)) return InteractionResult.CONSUME;
        GangData data = GangData.get(sp.server);
        Gang g = data.gangByChest(level.dimension().location().toString(), pos.asLong());
        if (g == null) {
            sp.displayClientMessage(Component.literal("§cCe coffre n'est lié à aucun gang."), true);
            return InteractionResult.CONSUME;
        }
        if (!g.isMember(sp.getUUID())) {
            sp.displayClientMessage(Component.literal("§cCe coffre est réservé aux membres de " + g.name() + "."), true);
            return InteractionResult.CONSUME;
        }
        GangVaultMenu.open(sp, g, pos);
        return InteractionResult.CONSUME;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(placer instanceof ServerPlayer sp)) return;
        // La vérification complète a lieu avant la pose (GangEvents) ; ici on enregistre, et on annule tout si l'état a changé entre-temps.
        if (!GangService.registerChest(sp, level.dimension().location().toString(), pos)) {
            level.removeBlock(pos, false);
            ItemStack back = new ItemStack(this);
            if (!sp.getInventory().add(back)) sp.drop(back, false);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!level.isClientSide && !state.is(newState.getBlock()) && level.getServer() != null) {
            GangService.unregisterChest(level.getServer(), level.dimension().location().toString(), pos);
        }
        super.onRemove(state, level, pos, newState, moving);
    }
}
