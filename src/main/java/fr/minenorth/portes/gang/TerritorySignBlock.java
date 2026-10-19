package fr.minenorth.portes.gang;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** « Pancarte de territoire » : posée par un admin à l'entrée d'une zone ; un clic droit affiche qui la tient et l'état de la capture. */
public class TerritorySignBlock extends HorizontalDirectionalBlock {
    private static final VoxelShape NS = Shapes.or(Block.box(1, 6, 6.5, 15, 16, 9.5), Block.box(7.25, 0, 7.25, 8.75, 6, 8.75));
    private static final VoxelShape EW = Shapes.or(Block.box(6.5, 6, 1, 9.5, 16, 15), Block.box(7.25, 0, 7.25, 8.75, 6, 8.75));

    public TerritorySignBlock() {
        super(BlockBehaviour.Properties.of().strength(3.0F, 1200.0F).sound(SoundType.WOOD).noOcclusion());
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }

    @Override public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override public VoxelShape getShape(BlockState s, BlockGetter g, BlockPos p, CollisionContext c) {
        return s.getValue(FACING).getAxis() == Direction.Axis.Z ? NS : EW;
    }

    @Override public PushReaction getPistonPushReaction(BlockState s) { return PushReaction.BLOCK; }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp) || hand != InteractionHand.MAIN_HAND) return InteractionResult.CONSUME;
        if (!GangService.allow(sp.getUUID(), "pancarte", 800)) return InteractionResult.CONSUME;
        TerritoryManager.describeAt(sp, level.dimension().location().toString(), pos);
        return InteractionResult.CONSUME;
    }
}
