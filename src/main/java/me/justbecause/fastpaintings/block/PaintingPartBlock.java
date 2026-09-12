package me.justbecause.fastpaintings.block;

import com.mojang.serialization.MapCodec;
import me.justbecause.fastpaintings.block.entity.PaintingBlockEntity;
import me.justbecause.fastpaintings.init.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Lightweight, non-ticking helper part block for multi-block paintings.
 * Contains only 8 block states (FACING x WATERLOGGED), eliminating chunk palette bloat.
 */
public class PaintingPartBlock extends HorizontalDirectionalBlock implements SimpleWaterloggedBlock {

    public static final MapCodec<PaintingPartBlock> CODEC = simpleCodec(PaintingPartBlock::new);

    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    public PaintingPartBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(WATERLOGGED, Boolean.FALSE));
    }

    public PaintingPartBlock(ResourceKey<Block> key) {
        this(Properties.of()
                .setId(key)
                .mapColor(MapColor.NONE)
                .noCollision()
                .noOcclusion()
                .instabreak()
                .sound(SoundType.WOOL)
                .pushReaction(PushReaction.DESTROY)
                .isRedstoneConductor((state, level, pos) -> false));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, WATERLOGGED);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return PaintingBlock.getShapeForFacing(state.getValue(FACING));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (context instanceof EntityCollisionContext entityContext
                && entityContext.getEntity() instanceof Projectile projectile) {
            if (level instanceof ServerLevel serverLevel
                    && projectile.mayInteract(serverLevel, pos)
                    && projectile.mayBreak(serverLevel)) {
                return this.getShape(state, level, pos, context);
            }
        }
        return Shapes.empty();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        BlockPos backingPos = pos.relative(facing.getOpposite());
        BlockState backingState = level.getBlockState(backingPos);
        return backingState.isSolid() || DiodeBlock.isDiode(backingState);
    }

    public sealed interface AnchorLookupResult {
        record Found(BlockPos pos) implements AnchorLookupResult {}
        enum Unloaded implements AnchorLookupResult { INSTANCE }
        enum Orphan implements AnchorLookupResult { INSTANCE }
    }

    /**
     * Resolves the anchor position of this part block within its bounded 16x16 plane.
     * Distinguishes a found anchor, an uninspected candidate in an unloaded chunk,
     * and a proven orphan part whose candidate plane was completely inspected.
     */
    public static AnchorLookupResult findAnchorLookup(LevelReader level, BlockPos partPos, BlockState partState) {
        Direction facing = partState.getValue(FACING);
        Direction left = facing.getCounterClockWise();

        BlockPos.MutableBlockPos candidate = new BlockPos.MutableBlockPos();
        boolean hasUnloadedCandidates = false;

        // Check closest distances first for fastest lookup
        for (int r = 0; r <= 15; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) {
                        continue; // Only check outer perimeter of radius r
                    }
                    candidate.set(
                            partPos.getX() + left.getStepX() * dx,
                            partPos.getY() + dy,
                            partPos.getZ() + left.getStepZ() * dx
                    );

                    if (level.isOutsideBuildHeight(candidate.getY())) {
                        continue;
                    }

                    boolean loaded = (level instanceof Level lvl) ? lvl.isLoaded(candidate) : level.hasChunkAt(candidate);
                    if (loaded) {
                        BlockState state = level.getBlockState(candidate);
                        if (state.is(ModRegistry.PAINTING_BLOCK) && state.getValue(PaintingBlock.FACING) == facing) {
                            if (level.getBlockEntity(candidate) instanceof PaintingBlockEntity be) {
                                if (be.getFootprint().containsOccupied(partPos)) {
                                    return new AnchorLookupResult.Found(candidate.immutable());
                                }
                            }
                        }
                    } else {
                        hasUnloadedCandidates = true;
                    }
                }
            }
        }

        if (hasUnloadedCandidates) {
            return AnchorLookupResult.Unloaded.INSTANCE;
        }
        return AnchorLookupResult.Orphan.INSTANCE;
    }

    /**
     * Resolves the anchor position of this part block within its bounded 16x16 plane.
     * Returns null if no owning anchor is currently found or inspectable.
     */
    public static @Nullable BlockPos findAnchorPos(LevelReader level, BlockPos partPos, BlockState partState) {
        if (findAnchorLookup(level, partPos, partState) instanceof AnchorLookupResult.Found found) {
            return found.pos();
        }
        return null;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, @Nullable Orientation orientation, boolean movedByPiston) {
        if (!level.isClientSide()) {
            AnchorLookupResult lookup = findAnchorLookup(level, pos, state);
            if (lookup instanceof AnchorLookupResult.Found found) {
                if (level.getBlockEntity(found.pos()) instanceof PaintingBlockEntity be) {
                    if (!be.getFootprint().isSupported(level)) {
                        be.removeFootprint(level, true, null);
                    }
                }
            } else if (lookup instanceof AnchorLookupResult.Orphan) {
                // Orphan helper part self-healing: restore fluid state only when proven orphan
                level.setBlock(pos, state.getFluidState().createLegacyBlock(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
            }
        }
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide()) {
            AnchorLookupResult lookup = findAnchorLookup(level, pos, state);
            if (lookup instanceof AnchorLookupResult.Found found) {
                if (level.getBlockEntity(found.pos()) instanceof PaintingBlockEntity be) {
                    be.removeFootprint(level, !player.isCreative(), player);
                }
            } else if (lookup instanceof AnchorLookupResult.Orphan) {
                level.setBlock(pos, state.getFluidState().createLegacyBlock(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void onProjectileHit(Level level, BlockState state, BlockHitResult hitResult, Projectile projectile) {
        BlockPos pos = hitResult.getBlockPos();
        if (level instanceof ServerLevel serverLevel
                && projectile.mayInteract(serverLevel, pos)
                && projectile.mayBreak(serverLevel)) {
            AnchorLookupResult lookup = findAnchorLookup(level, pos, state);
            if (lookup instanceof AnchorLookupResult.Found found) {
                if (level.getBlockEntity(found.pos()) instanceof PaintingBlockEntity be) {
                    be.removeFootprint(level, true, projectile);
                }
            } else if (lookup instanceof AnchorLookupResult.Orphan) {
                level.setBlock(pos, state.getFluidState().createLegacyBlock(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
            }
        }
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        AnchorLookupResult lookup = findAnchorLookup(level, pos, state);
        if (lookup instanceof AnchorLookupResult.Found found) {
            if (level.getBlockEntity(found.pos()) instanceof PaintingBlockEntity be) {
                be.removeFootprint(level, true, null);
            }
        }
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess scheduledTickAccess, BlockPos pos, Direction direction, BlockPos neighborPos, BlockState neighborState, net.minecraft.util.RandomSource random) {
        if (direction.getOpposite() == state.getValue(FACING) && !this.canSurvive(state, level, pos)) {
            return state.getFluidState().createLegacyBlock();
        }
        if (state.getValue(WATERLOGGED)) {
            scheduledTickAccess.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        return super.updateShape(state, level, scheduledTickAccess, pos, direction, neighborPos, neighborState, random);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
        ItemStack stack = new ItemStack(Items.PAINTING);
        BlockPos anchorPos = findAnchorPos(level, pos, state);
        if (anchorPos != null && level.getBlockEntity(anchorPos) instanceof PaintingBlockEntity be && be.getVariant() != null) {
            stack.set(DataComponents.PAINTING_VARIANT, be.getVariant());
        }
        return stack;
    }
}
