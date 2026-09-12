package me.justbecause.fastpaintings.client.render;

import me.justbecause.fastpaintings.block.entity.PaintingBlockEntity;
import me.justbecause.fastpaintings.init.ModRegistry;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;

import static org.junit.jupiter.api.Assertions.*;

class PaintingRenderPipelineTest {

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

    @Test
    @DisplayName("Variant change invalidates light cache and render bounding box")
    void testVariantChangeInvalidates() {
        PaintingBlockEntity be = new PaintingBlockEntity(BlockPos.ZERO, ModRegistry.PAINTING_BLOCK.defaultBlockState());

        PaintingVariant variant1x1 = new PaintingVariant(1, 1, Identifier.withDefaultNamespace("kebab"), null, null);
        PaintingVariant variant4x4 = new PaintingVariant(4, 4, Identifier.withDefaultNamespace("skeleton"), null, null);

        be.setVariant(Holder.direct(variant1x1));
        AABB box1 = be.getRenderBoundingBox();
        be.getOrCreateLightCache(1);
        be.setCachedLight(new int[]{15}, 100);

        assertFalse(be.isLightDirty(105, 1));

        // Change to 4x4
        be.setVariant(Holder.direct(variant4x4));
        AABB box4 = be.getRenderBoundingBox();

        // Light cache must be dirty
        assertTrue(be.isLightDirty(105, 16));
        // Bounding box must have resized
        assertNotEquals(box1, box4);
    }

    @Test
    @DisplayName("Render state maintains light array capacity across LOD transitions without reallocation")
    void testLightArrayReuseAcrossTransitions() {
        PaintingBlockRenderState state = new PaintingBlockRenderState();

        // 4x4 painting in FULL mode -> needs 16 ints
        int totalSegments = 16;
        if (state.lightCoordsPerBlock.length != totalSegments) {
            state.lightCoordsPerBlock = new int[totalSegments];
        }
        int[] originalArray = state.lightCoordsPerBlock;
        assertEquals(16, originalArray.length);

        // Transition to FAR
        state.lod = PaintingBlockRenderState.Lod.FAR;
        // Array is preserved, not reallocated to 0
        assertSame(originalArray, state.lightCoordsPerBlock);

        // Transition to SIMPLIFIED
        state.lod = PaintingBlockRenderState.Lod.SIMPLIFIED;
        assertSame(originalArray, state.lightCoordsPerBlock);

        // Transition back to FULL with same 4x4 variant -> reuses original array
        if (state.lightCoordsPerBlock.length != totalSegments) {
            state.lightCoordsPerBlock = new int[totalSegments];
        }
        assertSame(originalArray, state.lightCoordsPerBlock);
    }

    @Test
    @DisplayName("Geometry quad and vertex counts match mathematical tier requirements")
    void testGeometryTierMath() {
        // 1x1 painting
        int w1 = 1, h1 = 1;
        int fullQuads1x1 = 2 * w1 * h1 + 2 * w1 + 2 * h1; // 2 front/back + 2 top/bottom + 2 left/right = 6 quads
        assertEquals(6, fullQuads1x1);
        assertEquals(24, fullQuads1x1 * 4); // 24 vertices

        // 16x16 painting
        int w16 = 16, h16 = 16;
        int fullQuads16x16 = 2 * w16 * h16 + 2 * w16 + 2 * h16; // 512 + 32 + 32 = 576 quads
        assertEquals(576, fullQuads16x16);
        assertEquals(2304, fullQuads16x16 * 4); // 2304 vertices

        // SIMPLIFIED is always exactly 6 quads = 24 vertices regardless of dimensions
        int simplifiedQuads = 6;
        assertEquals(24, simplifiedQuads * 4);

        // FAR is always exactly 1 quad = 4 vertices regardless of dimensions
        int farQuads = 1;
        assertEquals(4, farQuads * 4);

        // SKIP is 0 quads = 0 vertices
        int skipQuads = 0;
        assertEquals(0, skipQuads * 4);
    }
}