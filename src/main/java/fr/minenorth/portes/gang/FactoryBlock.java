package fr.minenorth.portes.gang;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;

/**
 * « Fabrique d'armes » : atelier clandestin posé dans une zone que le gang contrôle. Pas de bloc-entité : l'état (file, stock) vit dans la
 * sauvegarde du monde (GangData). Le voyant s'allume par intermittence tant que la fabrique produit.
 */
public class FactoryBlock extends HorizontalDirectionalBlock {
    public static final BooleanProperty LIT = BooleanProperty.create("lit");

    public FactoryBlock() {
        super(BlockBehaviour.Properties.of().strength(6.0F, 30.0F).sound(SoundType.METAL).noOcclusion()
                .lightLevel(s -> s.getValue(LIT) ? 9 : 0));
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LIT, false));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING, LIT); }

    @Override public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite()).setValue(LIT, false);
    }

    @Override public PushReaction getPistonPushReaction(BlockState s) { return PushReaction.BLOCK; }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp) || hand != InteractionHand.MAIN_HAND) return InteractionResult.CONSUME;
        if (!GangService.allow(sp.getUUID(), "fabrique", 400)) return InteractionResult.CONSUME;
        FactoryManager.open(sp, level.dimension().location().toString(), pos);
        return InteractionResult.CONSUME;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(placer instanceof ServerPlayer sp)) return;
        // Les conditions ont été contrôlées avant la pose (GangEvents) ; on enregistre, et on annule tout si l'état a changé entre-temps.
        String why = FactoryManager.registerError(sp, level.dimension().location().toString(), pos);
        if (why != null) {
            // Ne doit pas arriver (la pose est déjà contrôlée avant) : on explique clairement plutôt que de retirer le bloc en silence.
            GangEvents.refuse(sp, why, "Pose de la Fabrique d'armes");
            level.removeBlock(pos, false);
            ItemStack back = new ItemStack(this);
            if (!sp.getInventory().add(back)) sp.drop(back, false);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!level.isClientSide && !state.is(newState.getBlock()) && level.getServer() != null) {
            FactoryManager.onRemoved(level.getServer(), level.dimension().location().toString(), pos, level);
        }
        super.onRemove(state, level, pos, newState, moving);
    }
}
