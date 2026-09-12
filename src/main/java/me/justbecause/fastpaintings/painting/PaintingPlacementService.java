package me.justbecause.fastpaintings.painting;

import me.justbecause.fastpaintings.block.PaintingBlock;
import me.justbecause.fastpaintings.block.PaintingPartBlock;
import me.justbecause.fastpaintings.block.entity.PaintingBlockEntity;
import me.justbecause.fastpaintings.init.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.PaintingVariantTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class PaintingPlacementService {

    public enum PlacementMode {
        PLAYER_PLACE,
        MIGRATION
    }

    public static boolean tryPlacePainting(
            Level level,
            BlockPos anchorPos,
            Direction facing,
            @Nullable Holder<PaintingVariant> forcedVariant,
            @Nullable Player player,
            RandomSource random
    ) {
        return tryPlacePainting(level, anchorPos, facing, forcedVariant, player, random, PlacementMode.PLAYER_PLACE, null);
    }

    public static boolean tryPlacePainting(
            Level level,
            BlockPos anchorPos,
            Direction facing,
            @Nullable Holder<PaintingVariant> forcedVariant,
            @Nullable Player player,
            RandomSource random,
            PlacementMode mode,
            @Nullable Entity entityToIgnore
    ) {
        if (facing.getAxis().isVertical()) {
            return false;
        }

        Holder<PaintingVariant> selectedVariant;
        if (forcedVariant != null) {
            PaintingFootprint footprint = PaintingFootprint.of(
                    anchorPos, facing, forcedVariant.value().width(), forcedVariant.value().height()
            );
            if (!canPlaceFootprint(level, footprint, entityToIgnore)) {
                return false;
            }
            selectedVariant = forcedVariant;
        } else {
            Optional<Holder<PaintingVariant>> variantOpt = selectBestVariant(level, anchorPos, facing, random, entityToIgnore);
            if (variantOpt.isEmpty()) {
                return false;
            }
            selectedVariant = variantOpt.get();
        }

        PaintingFootprint footprint = PaintingFootprint.of(
                anchorPos, facing, selectedVariant.value().width(), selectedVariant.value().height()
        );

        return placePaintingBlocksTransactional(level, footprint, selectedVariant, player, mode);
    }

    public static Optional<Holder<PaintingVariant>> selectBestVariant(
            Level level,
            BlockPos anchorPos,
            Direction facing,
            RandomSource random,
            @Nullable Entity entityToIgnore
    ) {
        List<Holder<PaintingVariant>> candidates = new ArrayList<>();
        level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
                .getTagOrEmpty(PaintingVariantTags.PLACEABLE)
                .forEach(candidates::add);

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        // Filter out variants that cannot fit or lack support
        candidates.removeIf(variant -> {
            PaintingFootprint footprint = PaintingFootprint.of(
                    anchorPos, facing, variant.value().width(), variant.value().height()
            );
            return !canPlaceFootprint(level, footprint, entityToIgnore);
        });

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        // Keep only the largest area variants (exact vanilla parity)
        int maxArea = candidates.stream().mapToInt(v -> v.value().area()).max().orElse(0);
        candidates.removeIf(v -> v.value().area() < maxArea);

        return Util.getRandomSafe(candidates, random);
    }

    public static boolean canPlaceFootprint(Level level, PaintingFootprint footprint, @Nullable Entity entityToIgnore) {
        if (!footprint.isSupported(level)) {
            return false;
        }

        for (BlockPos pos : footprint.occupiedCells()) {
            if (!level.isLoaded(pos)) {
                return false;
            }
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !state.is(Blocks.WATER)) {
                return false;
            }
        }

        if (!level.noBlockCollision(null, footprint.boundingBox())
                || !level.noBorderCollision(null, footprint.boundingBox())) {
            return false;
        }

        // Overlap parity check: reject placement if another hanging entity occupies the same space
        List<HangingEntity> conflicting = level.getEntitiesOfClass(
                HangingEntity.class,
                footprint.boundingBox(),
                e -> e != entityToIgnore && (e.getDirection() == footprint.facing() || e.getBoundingBox().intersects(footprint.boundingBox()))
        );

        return conflicting.isEmpty();
    }

    /**
     * Transactional multi-block placement.
     * Takes a snapshot of all original cell block/fluid states, writes the anchor and parts,
     * verifies every placement, and rolls back all cells if any write or verification fails.
     */
    public static boolean placePaintingBlocksTransactional(
            Level level,
            PaintingFootprint footprint,
            Holder<PaintingVariant> variant,
            @Nullable Player player,
            PlacementMode mode
    ) {
        BlockPos anchorPos = footprint.anchor();
        Direction facing = footprint.facing();
        int placementFlags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

        // 1. Snapshot original states
        Map<BlockPos, BlockState> originalStates = new HashMap<>();
        for (BlockPos cellPos : footprint.occupiedCells()) {
            if (!level.isLoaded(cellPos)) {
                return false;
            }
            originalStates.put(cellPos.immutable(), level.getBlockState(cellPos));
        }

        boolean success = false;
        try {
            // 2. Place Anchor Block first
            boolean isAnchorWaterlogged = level.getFluidState(anchorPos).getType() == Fluids.WATER;
            BlockState anchorState = ModRegistry.PAINTING_BLOCK.defaultBlockState()
                    .setValue(PaintingBlock.FACING, facing)
                    .setValue(PaintingBlock.WATERLOGGED, isAnchorWaterlogged);

            if (!level.setBlock(anchorPos, anchorState, placementFlags)) {
                return false;
            }

            // 3. Set variant on Anchor BE immediately so parts can resolve anchor footprint
            if (level.getBlockEntity(anchorPos) instanceof PaintingBlockEntity be) {
                be.setVariant(variant);
            } else {
                return false;
            }

            // 4. Place Part Blocks
            for (BlockPos cellPos : footprint.occupiedCells()) {
                if (cellPos.equals(anchorPos)) {
                    continue;
                }
                boolean isWaterlogged = level.getFluidState(cellPos).getType() == Fluids.WATER;
                BlockState partState = ModRegistry.PAINTING_PART_BLOCK.defaultBlockState()
                        .setValue(PaintingPartBlock.FACING, facing)
                        .setValue(PaintingPartBlock.WATERLOGGED, isWaterlogged);

                if (!level.setBlock(cellPos, partState, placementFlags)) {
                    return false;
                }
            }

            success = true;
        } finally {
            // 5. Rollback on failure
            if (!success) {
                for (Map.Entry<BlockPos, BlockState> entry : originalStates.entrySet()) {
                    level.setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                }
            }
        }

        if (mode == PlacementMode.PLAYER_PLACE) {
            for (BlockPos cellPos : footprint.occupiedCells()) {
                level.updateNeighborsAt(cellPos, level.getBlockState(cellPos).getBlock());
            }
            level.playSound(null, anchorPos, SoundEvents.PAINTING_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
            if (player != null) {
                level.gameEvent(player, GameEvent.BLOCK_CHANGE, anchorPos);
            }
        }

        return true;
    }

    private PaintingPlacementService() {}
}
