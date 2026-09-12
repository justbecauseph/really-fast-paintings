package me.justbecause.fastpaintings.painting;

import me.justbecause.fastpaintings.FastPaintings;
import me.justbecause.fastpaintings.block.entity.PaintingBlockEntity;
import me.justbecause.fastpaintings.init.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PaintingConversionService {

    public static final String RESTORED_TAG = "fastpaintings:restored";

    private static final Set<UUID> RESTORED_UUIDS = Collections.synchronizedSet(
            Collections.newSetFromMap(new java.util.LinkedHashMap<UUID, Boolean>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                    return size() > 2048;
                }
            })
    );

    public static void suppressRestoredEntity(UUID uuid) {
        RESTORED_UUIDS.add(uuid);
    }

    public static void unsuppressRestoredEntity(UUID uuid) {
        RESTORED_UUIDS.remove(uuid);
    }

    public static boolean isRestorationSuppressed(Painting painting) {
        return painting.entityTags().contains(RESTORED_TAG) || RESTORED_UUIDS.contains(painting.getUUID());
    }

    public static boolean isRestorationSuppressed(UUID uuid) {
        return RESTORED_UUIDS.contains(uuid);
    }

    public static boolean tryConvert(Painting painting, ServerLevel serverLevel) {
        return tryConvert(painting, serverLevel, false);
    }

    public static boolean tryConvert(Painting painting, ServerLevel serverLevel, boolean allowSuppressedOverride) {
        if (!painting.isAlive()) {
            return false;
        }

        if (isRestorationSuppressed(painting)) {
            if (!allowSuppressedOverride) {
                return false;
            }
        }

        // Parity check: skip if entity has special custom data and config requests skipping
        if (FastPaintings.CONFIG.skipSpecialEntityData) {
            if (painting.hasCustomName() || painting.isInvulnerable() || painting.hasGlowingTag()) {
                FastPaintings.LOGGER.debug("Skipping conversion of painting at {}: special entity properties present",
                        painting.getPos());
                return false;
            }
        }

        // Critical: BlockAttachedEntity#getPos() is the authoritative attachment anchor BlockPos.
        // Entity center blockPosition() is offset by 0.5 for even dimensions and floors to the wrong cell.
        BlockPos anchorPos = painting.getPos();
        Direction facing = painting.getDirection();
        Holder<PaintingVariant> variant = painting.getVariant();

        PaintingFootprint footprint = PaintingFootprint.of(
                anchorPos, facing, variant.value().width(), variant.value().height()
        );

        if (!footprint.isSupported(serverLevel)) {
            FastPaintings.LOGGER.debug("Skipping conversion of painting at {}: backing blocks are not solid/supported",
                    anchorPos);
            return false;
        }

        // Validate all occupied cells are convertible (air, water)
        for (BlockPos pos : footprint.occupiedCells()) {
            if (!serverLevel.isLoaded(pos)) {
                return false;
            }
            BlockState state = serverLevel.getBlockState(pos);
            if (!state.isAir() && !state.is(Blocks.WATER)) {
                FastPaintings.LOGGER.debug("Skipping conversion of painting at {}: cell {} is obstructed by {}",
                        anchorPos, pos, state.getBlock());
                return false;
            }
        }

        // Transactional placement in MIGRATION mode (silent, no physics storms), ignoring the current entity during overlap check
        boolean success = PaintingPlacementService.placePaintingBlocksTransactional(
                serverLevel, footprint, variant, null, PaintingPlacementService.PlacementMode.MIGRATION
        );

        if (success) {
            painting.removeTag(RESTORED_TAG);
            unsuppressRestoredEntity(painting.getUUID());
            painting.kill(serverLevel);
            FastPaintings.LOGGER.debug("Successfully converted painting at {} to block-backed decoration", anchorPos);
            return true;
        }

        return false;
    }

    public static boolean tryRestore(PaintingBlockEntity blockEntity, ServerLevel serverLevel) {
        return tryRestore(blockEntity, serverLevel, serverLevel::addFreshEntity);
    }

    public static boolean tryRestore(
            PaintingBlockEntity blockEntity,
            ServerLevel serverLevel,
            java.util.function.Predicate<Painting> entitySpawner
    ) {
        return tryRestore(blockEntity, serverLevel, entitySpawner, (be, lvl) -> be.removeFootprint(lvl, false, null));
    }

    @FunctionalInterface
    public interface RollbackHandler {
        boolean rollback(
                ServerLevel serverLevel,
                PaintingFootprint footprint,
                BlockPos anchorPos,
                Map<BlockPos, BlockState> originalStates,
                Holder<PaintingVariant> variant
        ) throws Throwable;
    }

    public static boolean tryRestore(
            PaintingBlockEntity blockEntity,
            ServerLevel serverLevel,
            java.util.function.Predicate<Painting> entitySpawner,
            java.util.function.BiConsumer<PaintingBlockEntity, ServerLevel> footprintRemover
    ) {
        return tryRestore(blockEntity, serverLevel, entitySpawner, footprintRemover, PaintingConversionService::rollbackRestoration);
    }

    public static boolean tryRestore(
            PaintingBlockEntity blockEntity,
            ServerLevel serverLevel,
            java.util.function.Predicate<Painting> entitySpawner,
            java.util.function.BiConsumer<PaintingBlockEntity, ServerLevel> footprintRemover,
            RollbackHandler rollbackHandler
    ) {
        Holder<PaintingVariant> variant = blockEntity.getVariant();
        if (variant == null) {
            return false;
        }

        BlockPos anchorPos = blockEntity.getBlockPos();
        Direction facing = blockEntity.getFacing();
        PaintingFootprint footprint = blockEntity.getFootprint();

        Painting painting = new Painting(serverLevel, anchorPos, facing, variant);
        if (!painting.survives()) {
            return false;
        }

        // Snapshot original states across all footprint cells for rollback
        Map<BlockPos, BlockState> originalStates = new HashMap<>();
        for (BlockPos pos : footprint.occupiedCells()) {
            if (!serverLevel.isLoaded(pos)) {
                return false;
            }
            originalStates.put(pos.immutable(), serverLevel.getBlockState(pos));
        }

        // Tag and record restoration suppression before any destructive mutation
        painting.addTag(RESTORED_TAG);
        suppressRestoredEntity(painting.getUUID());

        boolean committed = false;
        Throwable failure = null;

        try {
            // Destructive removal inside the recovery boundary
            footprintRemover.accept(blockEntity, serverLevel);

            // Verify all footprint cells were removed (no leftover block-backed cells)
            for (BlockPos pos : footprint.occupiedCells()) {
                BlockState state = serverLevel.getBlockState(pos);
                if (state.is(ModRegistry.PAINTING_BLOCK) || state.is(ModRegistry.PAINTING_PART_BLOCK)) {
                    throw new IllegalStateException("Footprint removal incomplete: cell " + pos + " still occupied by " + state.getBlock());
                }
            }

            // Attempt to spawn the vanilla entity
            boolean added = entitySpawner.test(painting);
            if (!added) {
                return false;
            }

            // Verify entity is alive
            if (!painting.isAlive()) {
                throw new IllegalStateException("Entity spawner reported success but entity is not alive");
            }

            committed = true;
            return true;
        } catch (Throwable t) {
            failure = t;
            return false;
        } finally {
            if (!committed) {
                // Reconcile and discard attempted entity to prevent duplication
                unsuppressRestoredEntity(painting.getUUID());
                painting.removeTag(RESTORED_TAG);
                if (serverLevel.getEntity(painting.getUUID()) != null || painting.isAlive()) {
                    painting.discard();
                }

                // Rollback captured footprint and restore operational removal-guard state
                Throwable rollbackError = null;
                boolean rollbackSuccess = false;
                try {
                    rollbackSuccess = rollbackHandler.rollback(serverLevel, footprint, anchorPos, originalStates, variant);
                } catch (Throwable rb) {
                    rollbackError = rb;
                }

                // Reset removal guard on anchor BE if it exists in the level
                if (serverLevel.getBlockEntity(anchorPos) instanceof PaintingBlockEntity restoredBe) {
                    restoredBe.setRemoving(false);
                }

                if (!rollbackSuccess || rollbackError != null) {
                    RestorationException rex = new RestorationException(
                            "Restoration rollback verification failed: footprint could not be restored to original states at " + anchorPos,
                            rollbackError != null ? rollbackError : failure
                    );
                    if (failure != null && rollbackError != null) {
                        rex.addSuppressed(failure);
                    }
                    throw rex;
                }

                if (failure != null) {
                    if (failure instanceof RuntimeException re) {
                        throw re;
                    } else if (failure instanceof Error err) {
                        throw err;
                    } else {
                        throw new RuntimeException("Restoration failed", failure);
                    }
                }
            }
        }
    }

    private static boolean rollbackRestoration(
            ServerLevel serverLevel,
            PaintingFootprint footprint,
            BlockPos anchorPos,
            Map<BlockPos, BlockState> originalStates,
            Holder<PaintingVariant> variant
    ) {
        int rollbackFlags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

        // 1. Restore anchor block first
        serverLevel.setBlock(anchorPos, originalStates.get(anchorPos), rollbackFlags);

        // 2. Restore variant on anchor BE immediately and reset removal guard
        if (serverLevel.getBlockEntity(anchorPos) instanceof PaintingBlockEntity restoredBe) {
            restoredBe.setVariant(variant);
            restoredBe.setRemoving(false);
        } else {
            return false;
        }

        // 3. Restore part blocks
        for (Map.Entry<BlockPos, BlockState> entry : originalStates.entrySet()) {
            if (!entry.getKey().equals(anchorPos)) {
                serverLevel.setBlock(entry.getKey(), entry.getValue(), rollbackFlags);
            }
        }

        // 4. Notify neighbors after entire footprint is restored
        for (BlockPos pos : footprint.occupiedCells()) {
            serverLevel.updateNeighborsAt(pos, serverLevel.getBlockState(pos).getBlock());
        }

        // 5. Verify postconditions (exact blocks, fluids, facing, BE variant, operational guard, zero drops)
        for (Map.Entry<BlockPos, BlockState> entry : originalStates.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState expected = entry.getValue();
            BlockState actual = serverLevel.getBlockState(pos);
            if (!actual.equals(expected)) {
                return false;
            }
        }

        if (!(serverLevel.getBlockEntity(anchorPos) instanceof PaintingBlockEntity verifiedBe)) {
            return false;
        }
        if (!variant.equals(verifiedBe.getVariant())) {
            return false;
        }
        if (verifiedBe.getFacing() != footprint.facing()) {
            return false;
        }
        if (verifiedBe.isRemoving()) {
            return false;
        }

        return true;
    }

    private PaintingConversionService() {}
}
