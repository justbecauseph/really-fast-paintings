package me.justbecause.fastpaintings.test;

import me.justbecause.fastpaintings.block.PaintingBlock;
import me.justbecause.fastpaintings.block.PaintingPartBlock;
import me.justbecause.fastpaintings.block.entity.PaintingBlockEntity;
import me.justbecause.fastpaintings.init.ModRegistry;
import me.justbecause.fastpaintings.painting.PaintingConversionService;
import me.justbecause.fastpaintings.painting.PaintingFootprint;
import me.justbecause.fastpaintings.painting.PaintingPlacementService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import net.minecraft.world.entity.decoration.painting.PaintingVariants;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

import java.util.List;

public class FastPaintingsGameTests {

    @GameTest
    public void test1x1PlacementAndBreak(GameTestHelper helper) {
        BlockPos wallPos = new BlockPos(2, 2, 2);
        BlockPos paintingPos = new BlockPos(2, 2, 3);
        helper.setBlock(wallPos, Blocks.STONE);
        helper.setBlock(paintingPos, Blocks.AIR);

        ServerLevel level = helper.getLevel();
        Holder<PaintingVariant> kebab = level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
                .getOrThrow(PaintingVariants.KEBAB);

        boolean placed = PaintingPlacementService.tryPlacePainting(
                level, helper.absolutePos(paintingPos), Direction.SOUTH, kebab, null, level.getRandom()
        );
        helper.assertTrue(placed, "Failed to place 1x1 kebab painting");

        BlockState state = helper.getBlockState(paintingPos);
        helper.assertTrue(state.is(ModRegistry.PAINTING_BLOCK), "Anchor block was not placed");
        helper.assertTrue(state.getValue(PaintingBlock.FACING) == Direction.SOUTH, "Facing does not match SOUTH");

        PaintingBlockEntity be = helper.getBlockEntity(paintingPos, PaintingBlockEntity.class);
        helper.assertTrue(be.getVariant() != null && be.getVariant().is(PaintingVariants.KEBAB), "Variant was not set to kebab");

        // Break anchor
        be.removeFootprint(level, false, null);
        helper.assertTrue(helper.getBlockState(paintingPos).isAir(), "Painting was not removed on break");

        helper.succeed();
    }

    @GameTest
    public void test2x2MigrationAndRestore(GameTestHelper helper) {
        // Build 3x3 stone wall
        for (int x = 1; x <= 3; x++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(x, y, 2), Blocks.STONE);
                helper.setBlock(new BlockPos(x, y, 3), Blocks.AIR);
            }
        }

        ServerLevel level = helper.getLevel();
        Holder<PaintingVariant> match = level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
                .getOrThrow(PaintingVariants.MATCH); // 2x2

        BlockPos anchorPos = helper.absolutePos(new BlockPos(2, 2, 3));
        Painting entity = new Painting(level, anchorPos, Direction.SOUTH, match);
        level.addFreshEntity(entity);

        helper.assertTrue(entity.isAlive(), "Spawned painting entity is not alive");
        helper.assertTrue(entity.getPos().equals(anchorPos), "Entity getPos does not match anchorPos");

        // Convert entity to block-backed painting
        boolean converted = PaintingConversionService.tryConvert(entity, level);
        helper.assertTrue(converted, "Conversion failed for 2x2 match painting");
        helper.assertTrue(!entity.isAlive(), "Entity was not killed after successful conversion");

        BlockPos relativeAnchor = new BlockPos(2, 2, 3);
        BlockState anchorState = helper.getBlockState(relativeAnchor);
        helper.assertTrue(anchorState.is(ModRegistry.PAINTING_BLOCK), "Anchor block state missing");

        PaintingBlockEntity be = helper.getBlockEntity(relativeAnchor, PaintingBlockEntity.class);
        helper.assertTrue(be.getVariant() != null && be.getVariant().is(PaintingVariants.MATCH), "Variant is not match");

        // Restore back to entity
        boolean restored = PaintingConversionService.tryRestore(be, level);
        helper.assertTrue(restored, "Failed to restore 2x2 painting back to vanilla entity");

        helper.succeed();
    }

    @GameTest
    public void testWaterloggedPreservation(GameTestHelper helper) {
        BlockPos wallPos = new BlockPos(2, 2, 2);
        BlockPos waterPos = new BlockPos(2, 2, 3);
        helper.setBlock(wallPos, Blocks.STONE);
        helper.setBlock(waterPos, Blocks.WATER);

        ServerLevel level = helper.getLevel();
        Holder<PaintingVariant> kebab = level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
                .getOrThrow(PaintingVariants.KEBAB);

        boolean placed = PaintingPlacementService.tryPlacePainting(
                level, helper.absolutePos(waterPos), Direction.SOUTH, kebab, null, level.getRandom()
        );
        helper.assertTrue(placed, "Failed to place painting in water");

        BlockState state = helper.getBlockState(waterPos);
        helper.assertTrue(state.getValue(PaintingBlock.WATERLOGGED), "Painting is not waterlogged");

        PaintingBlockEntity be = helper.getBlockEntity(waterPos, PaintingBlockEntity.class);
        be.removeFootprint(level, false, null);

        BlockState restoredState = helper.getBlockState(waterPos);
        helper.assertTrue(restoredState.is(Blocks.WATER), "Water was deleted instead of preserved after painting removal");

        helper.succeed();
    }

    @GameTest
    public void testBackingWallDestruction(GameTestHelper helper) {
        BlockPos wallPos = new BlockPos(2, 2, 2);
        BlockPos paintingPos = new BlockPos(2, 2, 3);
        helper.setBlock(wallPos, Blocks.STONE);
        helper.setBlock(paintingPos, Blocks.AIR);

        ServerLevel level = helper.getLevel();
        Holder<PaintingVariant> kebab = level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
                .getOrThrow(PaintingVariants.KEBAB);

        boolean placed = PaintingPlacementService.tryPlacePainting(
                level, helper.absolutePos(paintingPos), Direction.SOUTH, kebab, null, level.getRandom()
        );
        helper.assertTrue(placed, "Painting placement failed before wall removal");
        helper.assertTrue(helper.getBlockState(paintingPos).is(ModRegistry.PAINTING_BLOCK), "Anchor block missing before wall removal");
        PaintingBlockEntity be = helper.getBlockEntity(paintingPos, PaintingBlockEntity.class);
        helper.assertTrue(be != null && be.getVariant() != null, "Anchor BE missing before wall removal");

        // Break backing wall
        helper.setBlock(wallPos, Blocks.AIR);

        // Bounded wait for actual neighbor/update behavior to remove the painting without manual removal
        helper.succeedWhen(() -> helper.assertTrue(
                helper.getBlockState(paintingPos).isAir(),
                "Painting did not drop after backing wall was removed"
        ));
    }

    @GameTest
    public void testDirectFootprintRemoval(GameTestHelper helper) {
        BlockPos wallPos = new BlockPos(2, 2, 2);
        BlockPos paintingPos = new BlockPos(2, 2, 3);
        helper.setBlock(wallPos, Blocks.STONE);
        helper.setBlock(paintingPos, Blocks.AIR);

        ServerLevel level = helper.getLevel();
        Holder<PaintingVariant> kebab = level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
                .getOrThrow(PaintingVariants.KEBAB);

        boolean placed = PaintingPlacementService.tryPlacePainting(
                level, helper.absolutePos(paintingPos), Direction.SOUTH, kebab, null, level.getRandom()
        );
        helper.assertTrue(placed, "Painting placement failed");

        PaintingBlockEntity be = helper.getBlockEntity(paintingPos, PaintingBlockEntity.class);
        be.removeFootprint(level, true, null);

        helper.assertTrue(helper.getBlockState(paintingPos).isAir(), "Painting was not removed by direct removal");
        helper.succeed();
    }

    @GameTest
    public void testPartAnchorResolutionAndBreakOutsideOrigin(GameTestHelper helper) {
        // Build 5x4 stone wall at Z=2 to fit two adjacent 2x2 paintings
        for (int x = 1; x <= 5; x++) {
            for (int y = 1; y <= 4; y++) {
                helper.setBlock(new BlockPos(x, y, 2), Blocks.STONE);
                helper.setBlock(new BlockPos(x, y, 3), Blocks.AIR);
            }
        }

        ServerLevel level = helper.getLevel();
        Holder<PaintingVariant> match = level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
                .getOrThrow(PaintingVariants.MATCH); // 2x2

        // Painting A at relative (1, 2, 3)
        BlockPos relativeAnchorA = new BlockPos(1, 2, 3);
        BlockPos anchorPosA = helper.absolutePos(relativeAnchorA);
        boolean placedA = PaintingPlacementService.tryPlacePainting(
                level, anchorPosA, Direction.SOUTH, match, null, level.getRandom()
        );
        helper.assertTrue(placedA, "Failed to place Painting A (2x2 match)");

        // Painting B at relative (3, 2, 3)
        BlockPos relativeAnchorB = new BlockPos(3, 2, 3);
        BlockPos anchorPosB = helper.absolutePos(relativeAnchorB);
        boolean placedB = PaintingPlacementService.tryPlacePainting(
                level, anchorPosB, Direction.SOUTH, match, null, level.getRandom()
        );
        helper.assertTrue(placedB, "Failed to place Painting B (2x2 match)");

        // Verify part block of Painting A at (2, 2, 3) resolves anchorPosA
        BlockPos relativePartA = new BlockPos(2, 2, 3);
        BlockPos partPosA = helper.absolutePos(relativePartA);
        BlockState partStateA = helper.getBlockState(relativePartA);
        helper.assertTrue(partStateA.is(ModRegistry.PAINTING_PART_BLOCK), "Expected part block at relative (2, 2, 3)");

        PaintingPartBlock.AnchorLookupResult lookupA = PaintingPartBlock.findAnchorLookup(level, partPosA, partStateA);
        helper.assertTrue(lookupA instanceof PaintingPartBlock.AnchorLookupResult.Found found && found.pos().equals(anchorPosA),
                "Anchor lookup did not resolve to anchorPosA outside origin");
        helper.assertTrue(anchorPosA.equals(PaintingPartBlock.findAnchorPos(level, partPosA, partStateA)),
                "findAnchorPos did not return anchorPosA");

        // Break part block via real destruction (helper.destroyBlock) without calling be.removeFootprint() directly
        helper.destroyBlock(relativePartA);

        // Verify Painting A footprint is removed
        helper.assertTrue(helper.getBlockState(new BlockPos(1, 2, 3)).isAir(), "Painting A anchor not cleared");
        helper.assertTrue(helper.getBlockState(new BlockPos(2, 2, 3)).isAir(), "Painting A part (2,2,3) not cleared");
        helper.assertTrue(helper.getBlockState(new BlockPos(1, 3, 3)).isAir(), "Painting A part (1,3,3) not cleared");
        helper.assertTrue(helper.getBlockState(new BlockPos(2, 3, 3)).isAir(), "Painting A part (2,3,3) not cleared");

        // Verify Painting B survives intact
        helper.assertTrue(helper.getBlockState(relativeAnchorB).is(ModRegistry.PAINTING_BLOCK), "Painting B anchor damaged");
        PaintingBlockEntity beB = helper.getBlockEntity(relativeAnchorB, PaintingBlockEntity.class);
        helper.assertTrue(beB != null && beB.getVariant() != null && beB.getVariant().is(PaintingVariants.MATCH),
                "Painting B variant corrupted");
        helper.assertTrue(helper.getBlockState(new BlockPos(4, 2, 3)).is(ModRegistry.PAINTING_PART_BLOCK),
                "Painting B part (4,2,3) damaged");
        helper.assertTrue(helper.getBlockState(new BlockPos(3, 3, 3)).is(ModRegistry.PAINTING_PART_BLOCK),
                "Painting B part (3,3,3) damaged");
        helper.assertTrue(helper.getBlockState(new BlockPos(4, 3, 3)).is(ModRegistry.PAINTING_PART_BLOCK),
                "Painting B part (4,3,3) damaged");

        // Verify exactly 1 painting item dropped
        List<ItemEntity> drops = level.getEntitiesOfClass(
                ItemEntity.class,
                new AABB(anchorPosA).inflate(5.0),
                item -> item.getItem().is(Items.PAINTING)
        );
        helper.assertTrue(drops.size() == 1, "Expected exactly 1 drop, but found " + drops.size());

        helper.succeed();
    }

    @GameTest
    public void testCreativePartBreakZeroDrops(GameTestHelper helper) {
        // Build 3x3 stone wall
        for (int x = 1; x <= 3; x++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(x, y, 2), Blocks.STONE);
                helper.setBlock(new BlockPos(x, y, 3), Blocks.AIR);
            }
        }

        ServerLevel level = helper.getLevel();
        Holder<PaintingVariant> match = level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
                .getOrThrow(PaintingVariants.MATCH); // 2x2

        BlockPos relativeAnchor = new BlockPos(2, 2, 3);
        BlockPos anchorPos = helper.absolutePos(relativeAnchor);
        boolean placed = PaintingPlacementService.tryPlacePainting(
                level, anchorPos, Direction.SOUTH, match, null, level.getRandom()
        );
        helper.assertTrue(placed, "Failed to place 2x2 match painting");

        BlockPos relativePart = new BlockPos(3, 2, 3);
        BlockPos partPos = helper.absolutePos(relativePart);

        ServerPlayer creativePlayer = helper.makeMockServerPlayerInLevel();
        creativePlayer.setGameMode(GameType.CREATIVE);
        creativePlayer.gameMode.destroyBlock(partPos);

        // Entire footprint cleared
        helper.assertTrue(helper.getBlockState(relativeAnchor).isAir(), "Anchor not cleared on creative break");
        helper.assertTrue(helper.getBlockState(relativePart).isAir(), "Part not cleared on creative break");

        List<ItemEntity> drops = level.getEntitiesOfClass(
                ItemEntity.class,
                new AABB(anchorPos).inflate(3.0),
                item -> item.getItem().is(Items.PAINTING)
        );
        helper.assertTrue(drops.isEmpty(), "Item dropped on creative break: expected 0 drops, found " + drops.size());

        helper.succeed();
    }

    @GameTest
    public void testAnchorResolutionAllFacings(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Holder<PaintingVariant> pool = level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
                .getOrThrow(PaintingVariants.POOL); // 2x1: even width, odd height
        Holder<PaintingVariant> wanderer = level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
                .getOrThrow(PaintingVariants.WANDERER); // 1x2: odd width, even height

        // Test all 4 horizontal facings with both even and odd dimension parity
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (Holder<PaintingVariant> variant : List.of(pool, wanderer)) {
                int width = variant.value().width();
                int height = variant.value().height();

                BlockPos basePos = new BlockPos(2, 2, 2);
                BlockPos anchorPos = helper.absolutePos(basePos);

                PaintingFootprint footprint = PaintingFootprint.of(anchorPos, facing, width, height);

                // Set backing blocks to STONE and painting occupied cells to AIR
                for (BlockPos cell : footprint.occupiedCells()) {
                    BlockPos backingCell = cell.relative(facing.getOpposite());
                    level.setBlock(backingCell, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                    level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }

                boolean placed = PaintingPlacementService.tryPlacePainting(
                        level, anchorPos, facing, variant, null, level.getRandom()
                );
                helper.assertTrue(placed, "Failed to place " + variant.getRegisteredName() + " facing " + facing);

                PaintingBlockEntity be = (PaintingBlockEntity) level.getBlockEntity(anchorPos);
                helper.assertTrue(be != null && be.getVariant() != null && be.getVariant().equals(variant),
                        "Anchor BE or variant missing for " + variant.getRegisteredName() + " facing " + facing);

                // Verify every cell in footprint resolves correctly to anchorPos
                for (BlockPos cell : footprint.occupiedCells()) {
                    BlockState cellState = level.getBlockState(cell);
                    BlockPos resolved = PaintingPartBlock.findAnchorPos(level, cell, cellState);
                    helper.assertTrue(anchorPos.equals(resolved),
                            "Cell " + cell + " failed to resolve anchor for facing " + facing + " (" + variant.getRegisteredName() + ")");
                }

                // Clean up painting and backing blocks
                be.removeFootprint(level, false, null);
                for (BlockPos cell : footprint.occupiedCells()) {
                    BlockPos backingCell = cell.relative(facing.getOpposite());
                    level.setBlock(backingCell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }

        helper.succeed();
    }

    @GameTest
    public void testMultiTickRestorationAcrossTicks(GameTestHelper helper) {
        // Build 3x3 stone wall
        for (int x = 1; x <= 3; x++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(x, y, 2), Blocks.STONE);
                helper.setBlock(new BlockPos(x, y, 3), Blocks.AIR);
            }
        }

        ServerLevel level = helper.getLevel();
        Holder<PaintingVariant> match = level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
                .getOrThrow(PaintingVariants.MATCH); // 2x2

        BlockPos relativeAnchor = new BlockPos(2, 2, 3);
        BlockPos anchorPos = helper.absolutePos(relativeAnchor);
        Painting entity = new Painting(level, anchorPos, Direction.SOUTH, match);
        level.addFreshEntity(entity);

        boolean converted = PaintingConversionService.tryConvert(entity, level);
        helper.assertTrue(converted, "Conversion failed for 2x2 match painting");

        PaintingBlockEntity be = helper.getBlockEntity(relativeAnchor, PaintingBlockEntity.class);
        boolean restored = PaintingConversionService.tryRestore(be, level);
        helper.assertTrue(restored, "Failed to restore 2x2 painting back to vanilla entity");

        // Wait 10 ticks to verify automatic reconversion does NOT occur across subsequent ticks
        helper.runAfterDelay(10, () -> {
            List<Painting> paintings = level.getEntitiesOfClass(
                    Painting.class,
                    new AABB(anchorPos).inflate(2.0),
                    Painting::isAlive
            );
            helper.assertTrue(!paintings.isEmpty(), "Restored painting was not found or not alive after 10 ticks");
            Painting restoredPainting = paintings.getFirst();
            helper.assertTrue(restoredPainting.entityTags().contains(PaintingConversionService.RESTORED_TAG),
                    "Restored painting missing suppression tag");
            helper.assertTrue(helper.getBlockState(relativeAnchor).isAir(),
                    "Restored painting was reconverted to block entity on subsequent ticks");

            // Verify explicit tryConvert is also suppressed when allowSuppressedOverride is false
            boolean reConverted = PaintingConversionService.tryConvert(restoredPainting, level, false);
            helper.assertTrue(!reConverted, "tryConvert unexpectedly succeeded on suppressed restored entity");

            helper.succeed();
        });
    }

    @GameTest
    public void testRestorationEntityAddFailureRollback(GameTestHelper helper) {
        // Build 3x3 stone wall
        for (int x = 1; x <= 3; x++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(x, y, 2), Blocks.STONE);
                helper.setBlock(new BlockPos(x, y, 3), Blocks.AIR);
            }
        }

        ServerLevel level = helper.getLevel();
        Holder<PaintingVariant> match = level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
                .getOrThrow(PaintingVariants.MATCH); // 2x2

        BlockPos relativeAnchor = new BlockPos(2, 2, 3);
        BlockPos anchorPos = helper.absolutePos(relativeAnchor);
        boolean placed = PaintingPlacementService.tryPlacePainting(
                level, anchorPos, Direction.SOUTH, match, null, level.getRandom()
        );
        helper.assertTrue(placed, "Failed to place 2x2 match painting");

        PaintingBlockEntity be = helper.getBlockEntity(relativeAnchor, PaintingBlockEntity.class);

        // Simulate addFreshEntity failure via test spawner
        boolean restored = PaintingConversionService.tryRestore(be, level, painting -> false);
        helper.assertTrue(!restored, "tryRestore should report failure when entity add fails");

        // Verify full rollback: anchor and all part blocks restored, variant intact, no drops
        BlockState anchorState = helper.getBlockState(relativeAnchor);
        helper.assertTrue(anchorState.is(ModRegistry.PAINTING_BLOCK), "Anchor block missing after rollback");

        PaintingBlockEntity restoredBe = helper.getBlockEntity(relativeAnchor, PaintingBlockEntity.class);
        helper.assertTrue(restoredBe != null && restoredBe.getVariant() != null && restoredBe.getVariant().is(PaintingVariants.MATCH),
                "Variant missing or corrupted after rollback");

        helper.assertTrue(helper.getBlockState(new BlockPos(3, 2, 3)).is(ModRegistry.PAINTING_PART_BLOCK),
                "Part (3,2,3) missing after rollback");
        helper.assertTrue(helper.getBlockState(new BlockPos(2, 3, 3)).is(ModRegistry.PAINTING_PART_BLOCK),
                "Part (2,3,3) missing after rollback");
        helper.assertTrue(helper.getBlockState(new BlockPos(3, 3, 3)).is(ModRegistry.PAINTING_PART_BLOCK),
                "Part (3,3,3) missing after rollback");

        List<ItemEntity> drops = level.getEntitiesOfClass(
                ItemEntity.class,
                new AABB(anchorPos).inflate(3.0),
                item -> item.getItem().is(Items.PAINTING)
        );
        helper.assertTrue(drops.isEmpty(), "Items were dropped during failed restoration rollback");

        helper.succeed();
    }

    @GameTest
    public void testPlacementFailureRollbackZeroDrops(GameTestHelper helper) {
        // Build 3x3 stone wall
        for (int x = 1; x <= 3; x++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(x, y, 2), Blocks.STONE);
                helper.setBlock(new BlockPos(x, y, 3), Blocks.AIR);
            }
        }

        ServerLevel level = helper.getLevel();
        Holder<PaintingVariant> match = level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
                .getOrThrow(PaintingVariants.MATCH); // 2x2

        BlockPos relativeAnchor = new BlockPos(2, 2, 3);
        BlockPos anchorPos = helper.absolutePos(relativeAnchor);
        PaintingFootprint footprint = PaintingFootprint.of(anchorPos, Direction.SOUTH, 2, 2);

        // Inject write failure on a part position (after anchor and at least one part write)
        BlockPos targetFailCell = helper.absolutePos(new BlockPos(3, 3, 3));
        boolean placed = PaintingPlacementService.placePaintingBlocksTransactional(
                level, footprint, match, null, PaintingPlacementService.PlacementMode.PLAYER_PLACE,
                pos -> pos.equals(targetFailCell)
        );
        helper.assertTrue(!placed, "Placement should have failed when injected predicate triggered");

        // Verify all cells were rolled back to AIR
        for (BlockPos cell : footprint.occupiedCells()) {
            helper.assertTrue(level.getBlockState(cell).isAir(), "Cell " + cell + " was not rolled back to air");
        }

        // Verify zero drops occurred during rollback
        List<ItemEntity> drops = level.getEntitiesOfClass(
                ItemEntity.class,
                new AABB(anchorPos).inflate(3.0),
                item -> item.getItem().is(Items.PAINTING)
        );
        helper.assertTrue(drops.isEmpty(), "Items were dropped during failed placement rollback");

        helper.succeed();
    }

    @GameTest
    public void testOrphanNeighborUpdateSelfHealing(GameTestHelper helper) {
        BlockPos wallPos = new BlockPos(2, 2, 2);
        BlockPos partPos = new BlockPos(2, 2, 3);
        helper.setBlock(wallPos, Blocks.STONE);

        // Place a lone helper part block without an anchor
        BlockState orphanState = ModRegistry.PAINTING_PART_BLOCK.defaultBlockState()
                .setValue(PaintingPartBlock.FACING, Direction.SOUTH);
        helper.setBlock(partPos, orphanState);

        ServerLevel level = helper.getLevel();
        BlockPos absPartPos = helper.absolutePos(partPos);

        // Trigger neighbor update
        level.neighborChanged(absPartPos, Blocks.STONE, null);

        // Helper part should self-heal (turn back to AIR)
        helper.assertTrue(helper.getBlockState(partPos).isAir(), "Orphan helper part did not self-heal on neighbor update");

        List<ItemEntity> drops = level.getEntitiesOfClass(
                ItemEntity.class,
                new AABB(absPartPos).inflate(3.0),
                item -> item.getItem().is(Items.PAINTING)
        );
        helper.assertTrue(drops.isEmpty(), "Orphan self-healing dropped items: expected 0 drops");

        helper.succeed();
    }

    @GameTest
    public void testRestorationThrowingSpawnRollback(GameTestHelper helper) {
        // Build 3x3 stone wall at Z=2
        for (int x = 1; x <= 3; x++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(x, y, 2), Blocks.STONE);
                helper.setBlock(new BlockPos(x, y, 3), Blocks.WATER);
            }
        }

        ServerLevel level = helper.getLevel();
        Holder<PaintingVariant> match = level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
                .getOrThrow(PaintingVariants.MATCH); // 2x2

        BlockPos relativeAnchor = new BlockPos(2, 2, 3);
        BlockPos anchorPos = helper.absolutePos(relativeAnchor);
        boolean placed = PaintingPlacementService.tryPlacePainting(
                level, anchorPos, Direction.SOUTH, match, null, level.getRandom()
        );
        helper.assertTrue(placed, "Failed to place 2x2 waterlogged painting");

        PaintingBlockEntity be = helper.getBlockEntity(relativeAnchor, PaintingBlockEntity.class);
        helper.assertTrue(be != null, "Anchor BE missing");

        // Attempt restore with an entity spawner that throws a RuntimeException
        boolean threw = false;
        try {
            PaintingConversionService.tryRestore(be, level, painting -> {
                throw new IllegalStateException("Simulated entity spawner exception");
            });
        } catch (IllegalStateException expected) {
            threw = true;
        }
        helper.assertTrue(threw, "tryRestore should rethrow spawn exception after successful rollback");

        // Verify postconditions: anchor and parts restored, fluids preserved, variant intact, zero drops
        BlockState anchorState = helper.getBlockState(relativeAnchor);
        helper.assertTrue(anchorState.is(ModRegistry.PAINTING_BLOCK), "Anchor block missing after throwing restore");
        helper.assertTrue(anchorState.getValue(PaintingBlock.WATERLOGGED), "Anchor lost waterlogged state after rollback");

        PaintingBlockEntity restoredBe = helper.getBlockEntity(relativeAnchor, PaintingBlockEntity.class);
        helper.assertTrue(restoredBe != null && restoredBe.getVariant() != null && restoredBe.getVariant().is(PaintingVariants.MATCH),
                "Anchor BE or variant corrupted after rollback");

        for (BlockPos relCell : List.of(new BlockPos(3, 2, 3), new BlockPos(2, 3, 3), new BlockPos(3, 3, 3))) {
            BlockState partState = helper.getBlockState(relCell);
            helper.assertTrue(partState.is(ModRegistry.PAINTING_PART_BLOCK), "Part missing at " + relCell);
            helper.assertTrue(partState.getValue(PaintingPartBlock.WATERLOGGED), "Part lost waterlogged state at " + relCell);
        }

        for (BlockPos relCell : List.of(relativeAnchor, new BlockPos(3, 2, 3), new BlockPos(2, 3, 3), new BlockPos(3, 3, 3))) {
            BlockPos absCell = helper.absolutePos(relCell);
            helper.assertTrue(level.getFluidState(absCell).is(Fluids.WATER),
                    "Fluid state at " + relCell + " is not water");
        }

        List<ItemEntity> drops = level.getEntitiesOfClass(
                ItemEntity.class,
                new AABB(anchorPos).inflate(3.0),
                item -> item.getItem().is(Items.PAINTING)
        );
        helper.assertTrue(drops.isEmpty(), "Items were dropped during throwing restoration rollback");

        List<Painting> paintings = level.getEntitiesOfClass(
                Painting.class,
                new AABB(anchorPos).inflate(3.0),
                Painting::isAlive
        );
        helper.assertTrue(paintings.isEmpty(), "Entity was leaked during throwing restoration rollback");

        helper.succeed();
    }

    @GameTest
    public void testOperatorReconversionOverridesSuppression(GameTestHelper helper) {
        // Build 3x3 stone wall
        for (int x = 1; x <= 3; x++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(x, y, 2), Blocks.STONE);
                helper.setBlock(new BlockPos(x, y, 3), Blocks.AIR);
            }
        }

        ServerLevel level = helper.getLevel();
        Holder<PaintingVariant> match = level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
                .getOrThrow(PaintingVariants.MATCH); // 2x2

        BlockPos relativeAnchor = new BlockPos(2, 2, 3);
        BlockPos anchorPos = helper.absolutePos(relativeAnchor);
        Painting entity = new Painting(level, anchorPos, Direction.SOUTH, match);
        level.addFreshEntity(entity);

        boolean converted = PaintingConversionService.tryConvert(entity, level);
        helper.assertTrue(converted, "Initial conversion failed");

        PaintingBlockEntity be = helper.getBlockEntity(relativeAnchor, PaintingBlockEntity.class);
        boolean restored = PaintingConversionService.tryRestore(be, level);
        helper.assertTrue(restored, "Restoration failed");

        // The restored entity has RESTORED_TAG and UUID suppression
        List<Painting> paintings = level.getEntitiesOfClass(
                Painting.class,
                new AABB(anchorPos).inflate(2.0),
                Painting::isAlive
        );
        helper.assertTrue(!paintings.isEmpty(), "Restored painting entity not found");
        Painting restoredPainting = paintings.getFirst();

        // Normal automatic conversion is suppressed
        boolean autoReconverted = PaintingConversionService.tryConvert(restoredPainting, level, false);
        helper.assertTrue(!autoReconverted, "Automatic conversion should be suppressed on restored entity");

        // Explicit operator reconversion overrides suppression
        boolean operatorReconverted = PaintingConversionService.tryConvert(restoredPainting, level, true);
        helper.assertTrue(operatorReconverted, "Operator reconversion failed to override suppression");
        helper.assertTrue(!restoredPainting.isAlive(), "Entity was not killed after operator reconversion");

        BlockState anchorState = helper.getBlockState(relativeAnchor);
        helper.assertTrue(anchorState.is(ModRegistry.PAINTING_BLOCK), "Anchor block missing after operator reconversion");

        helper.succeed();
    }
}
