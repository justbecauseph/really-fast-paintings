package me.justbecause.fastpaintings.block;

import me.justbecause.fastpaintings.block.entity.PaintingBlockEntity;
import me.justbecause.fastpaintings.init.ModRegistry;
import me.justbecause.fastpaintings.painting.PaintingFootprint;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class PaintingPartBlockUnitTest {

    @BeforeAll
    static void setup() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        unfreezeRegistry(BuiltInRegistries.BLOCK);
        unfreezeRegistry(BuiltInRegistries.BLOCK_ENTITY_TYPE);
        if (!BuiltInRegistries.BLOCK.containsKey(ModRegistry.PAINTING_BLOCK_ID)) {
            ModRegistry.init();
        }
    }

    private static void unfreezeRegistry(Object registry) throws Exception {
        if (registry instanceof MappedRegistry<?> mapped) {
            Field intrusiveField = MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders");
            intrusiveField.setAccessible(true);
            if (intrusiveField.get(mapped) == null) {
                intrusiveField.set(mapped, new IdentityHashMap<>());
            }
            Field frozenField = MappedRegistry.class.getDeclaredField("frozen");
            frozenField.setAccessible(true);
            frozenField.set(mapped, false);
        }
    }

    private static LevelReader createStubLevel(
            Map<BlockPos, BlockState> blockStates,
            Map<BlockPos, BlockEntity> blockEntities,
            Set<ChunkPos> unloadedChunks
    ) {
        return (LevelReader) Proxy.newProxyInstance(
                LevelReader.class.getClassLoader(),
                new Class<?>[]{LevelReader.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("isOutsideBuildHeight")) {
                        if (args[0] instanceof Integer y) {
                            return y < -64 || y >= 320;
                        }
                        if (args[0] instanceof BlockPos pos) {
                            return pos.getY() < -64 || pos.getY() >= 320;
                        }
                    }
                    if (name.equals("hasChunkAt") || name.equals("isLoaded")) {
                        BlockPos pos = (BlockPos) args[0];
                        return !unloadedChunks.contains(ChunkPos.containing(pos));
                    }
                    if (name.equals("hasChunk") && args.length == 2 && args[0] instanceof Integer cx && args[1] instanceof Integer cz) {
                        return !unloadedChunks.contains(new ChunkPos(cx, cz));
                    }
                    if (name.equals("getBlockState")) {
                        BlockPos pos = (BlockPos) args[0];
                        ChunkPos cp = ChunkPos.containing(pos);
                        if (unloadedChunks.contains(cp)) {
                            throw new AssertionError("Violated unloaded chunk contract: attempted to read blockState at " + pos + " in unloaded chunk " + cp);
                        }
                        return blockStates.getOrDefault(pos, Blocks.AIR.defaultBlockState());
                    }
                    if (name.equals("getBlockEntity")) {
                        BlockPos pos = (BlockPos) args[0];
                        ChunkPos cp = ChunkPos.containing(pos);
                        if (unloadedChunks.contains(cp)) {
                            throw new AssertionError("Violated unloaded chunk contract: attempted to read blockEntity at " + pos + " in unloaded chunk " + cp);
                        }
                        return blockEntities.get(pos);
                    }
                    if (method.isDefault()) {
                        return InvocationHandlerDefaultMethodCaller(proxy, method, args);
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) return false;
                    if (returnType == int.class) return 0;
                    return null;
                }
        );
    }

    private static Object InvocationHandlerDefaultMethodCaller(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
        return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
    }

    @Test
    @DisplayName("Anchor lookup resolves maximum size 16x16 painting across all 4 facings")
    void test16x16MaximumFootprintResolution() {
        PaintingVariant variant16x16 = new PaintingVariant(16, 16, Identifier.withDefaultNamespace("colossal"), null, null);

        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockPos anchor = new BlockPos(1000, 64, 1000);
            PaintingFootprint footprint = PaintingFootprint.of(anchor, facing, 16, 16);

            Map<BlockPos, BlockState> blockStates = new HashMap<>();
            Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();

            BlockState anchorState = ModRegistry.PAINTING_BLOCK.defaultBlockState()
                    .setValue(PaintingBlock.FACING, facing);
            blockStates.put(anchor, anchorState);

            PaintingBlockEntity be = new PaintingBlockEntity(anchor, anchorState);
            be.setVariant(Holder.direct(variant16x16));
            blockEntities.put(anchor, be);

            BlockState partState = ModRegistry.PAINTING_PART_BLOCK.defaultBlockState()
                    .setValue(PaintingPartBlock.FACING, facing);

            for (BlockPos cell : footprint.occupiedCells()) {
                if (!cell.equals(anchor)) {
                    blockStates.put(cell, partState);
                }
            }

            LevelReader level = createStubLevel(blockStates, blockEntities, Set.of());

            // Test every occupied cell in the 16x16 footprint resolves back to anchor
            for (BlockPos cell : footprint.occupiedCells()) {
                if (cell.equals(anchor)) continue;

                PaintingPartBlock.AnchorLookupResult result = PaintingPartBlock.findAnchorLookup(level, cell, partState);
                assertInstanceOf(PaintingPartBlock.AnchorLookupResult.Found.class, result,
                        "Failed to find anchor for facing " + facing + " at cell " + cell);
                assertEquals(anchor, ((PaintingPartBlock.AnchorLookupResult.Found) result).pos());

                BlockPos foundPos = PaintingPartBlock.findAnchorPos(level, cell, partState);
                assertEquals(anchor, foundPos);
            }
        }
    }

    @Test
    @DisplayName("Neighboring paintings on the same plane resolve to their respective anchors without cross-talk")
    void testNeighboringPaintingsOnSamePlane() {
        Direction facing = Direction.SOUTH;
        PaintingVariant variant2x2 = new PaintingVariant(2, 2, Identifier.withDefaultNamespace("match"), null, null);

        BlockPos anchorA = new BlockPos(100, 64, 100);
        BlockPos anchorB = new BlockPos(102, 64, 100);

        PaintingFootprint fpA = PaintingFootprint.of(anchorA, facing, 2, 2);
        PaintingFootprint fpB = PaintingFootprint.of(anchorB, facing, 2, 2);

        Map<BlockPos, BlockState> blockStates = new HashMap<>();
        Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();

        BlockState anchorState = ModRegistry.PAINTING_BLOCK.defaultBlockState().setValue(PaintingBlock.FACING, facing);
        BlockState partState = ModRegistry.PAINTING_PART_BLOCK.defaultBlockState().setValue(PaintingPartBlock.FACING, facing);

        blockStates.put(anchorA, anchorState);
        PaintingBlockEntity beA = new PaintingBlockEntity(anchorA, anchorState);
        beA.setVariant(Holder.direct(variant2x2));
        blockEntities.put(anchorA, beA);

        blockStates.put(anchorB, anchorState);
        PaintingBlockEntity beB = new PaintingBlockEntity(anchorB, anchorState);
        beB.setVariant(Holder.direct(variant2x2));
        blockEntities.put(anchorB, beB);

        for (BlockPos c : fpA.occupiedCells()) {
            if (!c.equals(anchorA)) blockStates.put(c, partState);
        }
        for (BlockPos c : fpB.occupiedCells()) {
            if (!c.equals(anchorB)) blockStates.put(c, partState);
        }

        LevelReader level = createStubLevel(blockStates, blockEntities, Set.of());

        // Footprint A parts must resolve to anchor A
        for (BlockPos c : fpA.occupiedCells()) {
            if (c.equals(anchorA)) continue;
            assertEquals(anchorA, PaintingPartBlock.findAnchorPos(level, c, partState));
        }

        // Footprint B parts must resolve to anchor B
        for (BlockPos c : fpB.occupiedCells()) {
            if (c.equals(anchorB)) continue;
            assertEquals(anchorB, PaintingPartBlock.findAnchorPos(level, c, partState));
        }
    }

    @Test
    @DisplayName("Unloaded candidate chunk returns Unloaded status and prevents orphan classification")
    void testUnloadedCandidateChunkPreventsOrphan() {
        Direction facing = Direction.SOUTH;
        BlockPos partPos = new BlockPos(100, 64, 100);
        BlockState partState = ModRegistry.PAINTING_PART_BLOCK.defaultBlockState().setValue(PaintingPartBlock.FACING, facing);

        // Candidate at X=112 is in chunk (7, 6) while partPos (100, 64, 100) is in chunk (6, 6)
        BlockPos adjacentCandidatePos = new BlockPos(112, 64, 100);
        ChunkPos unloadedChunk = ChunkPos.containing(adjacentCandidatePos);

        LevelReader level = createStubLevel(Map.of(), Map.of(), Set.of(unloadedChunk));

        PaintingPartBlock.AnchorLookupResult result = PaintingPartBlock.findAnchorLookup(level, partPos, partState);
        assertSame(PaintingPartBlock.AnchorLookupResult.Unloaded.INSTANCE, result,
                "Expected Unloaded status when candidate chunks cannot be inspected");
        assertNull(PaintingPartBlock.findAnchorPos(level, partPos, partState));
    }

    @Test
    @DisplayName("Proven orphan is identified only when all candidates in bounded plane are loaded")
    void testProvenOrphanWhenAllLoaded() {
        Direction facing = Direction.SOUTH;
        BlockPos partPos = new BlockPos(100, 64, 100);
        BlockState partState = ModRegistry.PAINTING_PART_BLOCK.defaultBlockState().setValue(PaintingPartBlock.FACING, facing);

        // All chunks loaded, empty world (no anchor anywhere)
        LevelReader level = createStubLevel(Map.of(), Map.of(), Set.of());

        PaintingPartBlock.AnchorLookupResult result = PaintingPartBlock.findAnchorLookup(level, partPos, partState);
        assertSame(PaintingPartBlock.AnchorLookupResult.Orphan.INSTANCE, result,
                "Expected Orphan status when all candidates in plane are loaded and none owns the part");
        assertNull(PaintingPartBlock.findAnchorPos(level, partPos, partState));
    }

    @Test
    @DisplayName("Anchor lookup candidate search makes zero reads into unloaded chunks")
    void testAnchorLookupCandidateMakesNoReadsIntoUnloadedChunks() {
        Direction facing = Direction.SOUTH;
        BlockPos partPos = new BlockPos(100, 64, 100);
        BlockState partState = ModRegistry.PAINTING_PART_BLOCK.defaultBlockState().setValue(PaintingPartBlock.FACING, facing);

        // Mark all neighboring candidate chunks outside the part's home chunk as unloaded
        ChunkPos homeChunk = ChunkPos.containing(partPos);
        Set<ChunkPos> unloadedChunks = new HashSet<>();
        for (int cx = homeChunk.x() - 2; cx <= homeChunk.x() + 2; cx++) {
            for (int cz = homeChunk.z() - 2; cz <= homeChunk.z() + 2; cz++) {
                ChunkPos cp = new ChunkPos(cx, cz);
                if (!cp.equals(homeChunk)) {
                    unloadedChunks.add(cp);
                }
            }
        }

        LevelReader level = createStubLevel(Map.of(), Map.of(), unloadedChunks);

        // Verification: must not throw AssertionError from createStubLevel's unloaded chunk boundary checks
        PaintingPartBlock.AnchorLookupResult result = PaintingPartBlock.findAnchorLookup(level, partPos, partState);
        assertSame(PaintingPartBlock.AnchorLookupResult.Unloaded.INSTANCE, result,
                "Expected Unloaded status without attempting reads into unloaded chunks");
        assertNull(PaintingPartBlock.findAnchorPos(level, partPos, partState));
    }

    @Test
    @DisplayName("PaintingBlock and PaintingPartBlock configure PushReaction.POPPED for 26.3 compatibility")
    void testPushReactionPopped() {
        assertEquals(PushReaction.POPPED, ModRegistry.PAINTING_BLOCK.defaultBlockState().getPistonPushReaction(),
                "PaintingBlock must declare PushReaction.POPPED");
        assertEquals(PushReaction.POPPED, ModRegistry.PAINTING_PART_BLOCK.defaultBlockState().getPistonPushReaction(),
                "PaintingPartBlock must declare PushReaction.POPPED");
    }
}
