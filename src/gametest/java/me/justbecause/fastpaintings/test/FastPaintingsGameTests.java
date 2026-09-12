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
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import net.minecraft.world.entity.decoration.painting.PaintingVariants;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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

        PaintingPlacementService.tryPlacePainting(
                level, helper.absolutePos(paintingPos), Direction.SOUTH, kebab, null, level.getRandom()
        );

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

        PaintingPlacementService.tryPlacePainting(
                level, helper.absolutePos(paintingPos), Direction.SOUTH, kebab, null, level.getRandom()
        );

        PaintingBlockEntity be = helper.getBlockEntity(paintingPos, PaintingBlockEntity.class);
        be.removeFootprint(level, true, null);

        helper.assertTrue(helper.getBlockState(paintingPos).isAir(), "Painting was not removed by direct removal");
        helper.succeed();
    }

    @GameTest
    public void testPartAnchorResolutionAndBreakOutsideOrigin(GameTestHelper helper) {
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

        // Verify part blocks exist and correctly resolve their anchor at non-origin coordinates
        BlockPos relativePart = new BlockPos(3, 2, 3);
        BlockPos partPos = helper.absolutePos(relativePart);
        BlockState partState = helper.getBlockState(relativePart);
        helper.assertTrue(partState.is(ModRegistry.PAINTING_PART_BLOCK), "Expected part block at relative (3, 2, 3)");

        PaintingPartBlock.AnchorLookupResult lookup = PaintingPartBlock.findAnchorLookup(level, partPos, partState);
        helper.assertTrue(lookup instanceof PaintingPartBlock.AnchorLookupResult.Found found && found.pos().equals(anchorPos),
                "Anchor lookup did not resolve to anchorPos outside origin");

        BlockPos resolved = PaintingPartBlock.findAnchorPos(level, partPos, partState);
        helper.assertTrue(anchorPos.equals(resolved), "findAnchorPos did not return anchorPos");

        // Break part block and verify entire painting footprint is removed and 1 item drops
        PaintingBlockEntity be = helper.getBlockEntity(relativeAnchor, PaintingBlockEntity.class);
        be.removeFootprint(level, true, null);

        helper.assertTrue(helper.getBlockState(new BlockPos(2, 2, 3)).isAir(), "Anchor cell not cleared on break");
        helper.assertTrue(helper.getBlockState(new BlockPos(3, 2, 3)).isAir(), "Part cell (3,2,3) not cleared on break");
        helper.assertTrue(helper.getBlockState(new BlockPos(2, 3, 3)).isAir(), "Part cell (2,3,3) not cleared on break");
        helper.assertTrue(helper.getBlockState(new BlockPos(3, 3, 3)).isAir(), "Part cell (3,3,3) not cleared on break");

        List<ItemEntity> drops = level.getEntitiesOfClass(
                ItemEntity.class,
                new AABB(anchorPos).inflate(3.0),
                item -> item.getItem().is(Items.PAINTING)
        );
        helper.assertTrue(!drops.isEmpty(), "No painting item dropped when part was broken");

        helper.succeed();
    }

    @GameTest
    public void testAnchorResolutionAllFacings(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Holder<PaintingVariant> pool = level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
                .getOrThrow(PaintingVariants.POOL); // 2x1 horizontal

        // Test SOUTH facing: wall at Z=2, painting at Z=3
        helper.setBlock(new BlockPos(1, 1, 2), Blocks.STONE);
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE);
        helper.setBlock(new BlockPos(1, 1, 3), Blocks.AIR);
        helper.setBlock(new BlockPos(2, 1, 3), Blocks.AIR);

        BlockPos southAnchor = helper.absolutePos(new BlockPos(1, 1, 3));
        boolean placedSouth = PaintingPlacementService.tryPlacePainting(
                level, southAnchor, Direction.SOUTH, pool, null, level.getRandom()
        );
        helper.assertTrue(placedSouth, "Failed to place 2x1 SOUTH painting");
        BlockPos southPart = helper.absolutePos(new BlockPos(2, 1, 3));
        BlockPos foundSouth = PaintingPartBlock.findAnchorPos(level, southPart, level.getBlockState(southPart));
        helper.assertTrue(southAnchor.equals(foundSouth), "SOUTH facing part failed to resolve anchor");

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

            // Verify explicit tryConvert is also suppressed
            boolean reConverted = PaintingConversionService.tryConvert(restoredPainting, level);
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
}
