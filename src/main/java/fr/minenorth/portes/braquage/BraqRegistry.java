package fr.minenorth.portes.braquage;

import fr.minenorth.portes.MineNorthPortes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import javax.annotation.Nullable;
import java.util.List;

/** Caisse enregistreuse (bloc 3D posé par l'admin à côté du vendeur) et sac de butin (objet 3D). */
public final class BraqRegistry {
    private BraqRegistry() {}

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MineNorthPortes.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MineNorthPortes.MOD_ID);

    public static final RegistryObject<Block> CAISSE = BLOCKS.register("caisse_enregistreuse", CashRegisterBlock::new);
    public static final RegistryObject<Item> CAISSE_ITEM = ITEMS.register("caisse_enregistreuse",
            () -> new BlockItem(CAISSE.get(), new Item.Properties()));
    public static final RegistryObject<Item> SAC_BUTIN = ITEMS.register("sac_butin", () -> new Item(new Item.Properties().stacksTo(16)) {
        @Override
        public void appendHoverText(ItemStack s, @Nullable Level l, List<Component> tip, TooltipFlag f) {
            tip.add(Component.literal("§7Un sac de toile pour le butin (objet décoratif RP)."));
        }
    });

    public static final net.minecraft.world.item.CreativeModeTab.DisplayItemsGenerator TAB_ITEMS = (params, out) -> {
        out.accept(CAISSE_ITEM.get());
        out.accept(SAC_BUTIN.get());
    };

    /** Caisse enregistreuse : indestructible en survie (seul un admin créatif la retire), le tiroir s'ouvre quand elle est vidée. */
    public static final class CashRegisterBlock extends HorizontalDirectionalBlock {
        public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
        private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 12, 15);

        public CashRegisterBlock() {
            super(BlockBehaviour.Properties.of().strength(-1.0F, 3_600_000.0F).sound(SoundType.METAL).noOcclusion().noLootTable());
            registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH).setValue(OPEN, false));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            b.add(FACING, OPEN);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext c) {
            return defaultBlockState().setValue(FACING, c.getHorizontalDirection().getOpposite());
        }

        @Override
        public VoxelShape getShape(BlockState s, BlockGetter g, BlockPos p, CollisionContext c) { return SHAPE; }

        @Override
        public InteractionResult use(BlockState s, Level level, BlockPos pos, Player p, InteractionHand h, BlockHitResult hit) {
            if (!level.isClientSide && h == InteractionHand.MAIN_HAND) {
                p.displayClientMessage(Component.literal(s.getValue(OPEN) ? "§7Caisse enregistreuse : tiroir ouvert, vide."
                        : "§7Caisse enregistreuse : verrouillée. Seul un braquage peut l'ouvrir."), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
    }
}
