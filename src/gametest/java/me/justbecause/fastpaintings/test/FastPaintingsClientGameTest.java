package me.justbecause.fastpaintings.test;

import me.justbecause.fastpaintings.block.entity.PaintingBlockEntity;
import me.justbecause.fastpaintings.client.render.PaintingBlockRenderer;
import me.justbecause.fastpaintings.client.render.PaintingInstrumentation;
import me.justbecause.fastpaintings.init.ModRegistry;
import me.justbecause.fastpaintings.painting.PaintingPlacementService;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.painting.PaintingVariants;
import net.minecraft.world.level.block.Blocks;

/** Exercises the actual client renderer after a block-backed painting reaches the client. */
public final class FastPaintingsClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        context.runOnClient(client -> {
            PaintingInstrumentation.forceEnabled = true;
            PaintingInstrumentation.reset();
        });

        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            BlockPos anchor = world.getServer().computeOnServer(server -> {
                var connection = world.getConnection();
                var level = connection.getServerLevel();
                BlockPos playerPos = connection.getServerPlayer().blockPosition();
                BlockPos paintingPos = playerPos.offset(0, 1, 3);

                for (int dx = -2; dx <= 2; dx++) {
                    for (int dy = -1; dy <= 3; dy++) {
                        level.setBlockAndUpdate(paintingPos.offset(dx, dy, 1), Blocks.STONE.defaultBlockState());
                    }
                }

                var variant = level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
                        .getOrThrow(PaintingVariants.MATCH);
                if (!PaintingPlacementService.tryPlacePainting(
                        level, paintingPos, Direction.NORTH, variant, null, level.getRandom())) {
                    throw new AssertionError("Could not place the 2x2 block-backed painting");
                }
                if (FabricLoader.getInstance().isModLoaded("distantdecorations")) {
                    DistantDecorationsAssertions.assertIndexed(level, paintingPos);
                }
                return paintingPos;
            });

            context.runOnClient(client -> {
                client.player.setYRot(0.0F);
                client.player.setXRot(0.0F);
            });

            // Server block updates are sent at the end of a tick.
            context.waitTick();
            world.getConnection().waitForClientboundPackets();
            context.waitFor(client -> client.level != null
                    && client.level.getBlockState(anchor).is(ModRegistry.PAINTING_BLOCK)
                    && client.level.getBlockEntity(anchor) instanceof PaintingBlockEntity painting
                    && painting.getVariant() != null);

            context.runOnClient(client -> {
                var painting = (PaintingBlockEntity) client.level.getBlockEntity(anchor);
                var renderer = client.getBlockEntityRenderDispatcher().getRenderer(painting);
                if (renderer == null || renderer.getClass() != PaintingBlockRenderer.class) {
                    throw new AssertionError("PaintingBlockRenderer is not registered for the synced painting");
                }
            });

            world.getConnection().waitForChunksRender();
            context.waitFor(client -> PaintingInstrumentation.totalExtractions > 0
                    && PaintingInstrumentation.totalCustomGeometrySubmissions > 0);

            context.runOnClient(client -> {
                var snapshot = PaintingInstrumentation.takeSnapshot();
                System.out.printf("Fast Paintings client render: extractions=%d, geometry submissions=%d, "
                                + "FULL=%d, SIMPLIFIED=%d, FAR=%d%n",
                        snapshot.extractions(), snapshot.customGeometrySubmissions(),
                        snapshot.fullCount(), snapshot.simplifiedCount(), snapshot.farCount());
            });
            System.out.println("Fast Paintings client screenshot: "
                    + context.takeScreenshot("fastpaintings-2x2-client-render"));

            if (FabricLoader.getInstance().isModLoaded("distantdecorations")) {
                context.waitFor(client -> DistantDecorationsAssertions.isClientRecordSynced(client, anchor));

                world.getServer().runOnServer(server -> {
                    var player = world.getConnection().getServerPlayer();
                    player.teleportTo(anchor.getX() + 5.5, anchor.getY(), anchor.getZ() - 120.0);
                    player.setYRot(0.0F);
                    player.setXRot(0.0F);
                });
                context.waitFor(client -> client.player != null
                        && client.player.distanceToSqr(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5)
                        > 100.0 * 100.0);
                context.runOnClient(client -> {
                    client.player.setYRot(0.0F);
                    client.player.setXRot(0.0F);
                });
                context.waitFor(client -> client.level != null
                        && !client.level.getChunkSource().hasChunk(anchor.getX() >> 4, anchor.getZ() >> 4), 400);
                world.getConnection().waitForChunksRender();

                long renderedBefore = context.computeOnClient(client ->
                        DistantDecorationsAssertions.totalRendered());
                context.waitFor(client -> DistantDecorationsAssertions.isClientRecordSynced(client, anchor)
                        && DistantDecorationsAssertions.totalRendered() > renderedBefore, 200);
                context.runOnClient(client -> System.out.printf(
                        "Fast Paintings DD far render: anchor chunk loaded=%s, synced records=1, "
                                + "total rendered before=%d after=%d%n",
                        client.level.getChunkSource().hasChunk(anchor.getX() >> 4, anchor.getZ() >> 4),
                        renderedBefore,
                        DistantDecorationsAssertions.totalRendered()));
                System.out.println("Fast Paintings DD far screenshot: "
                        + context.takeScreenshot("fastpaintings-dd-far-render"));
            }
        } finally {
            context.runOnClient(client -> {
                PaintingInstrumentation.forceEnabled = false;
                PaintingInstrumentation.reset();
            });
        }
    }

    /** Loaded only when the optional Distant Decorations mod is present. */
    private static final class DistantDecorationsAssertions {
        private static boolean isClientRecordSynced(net.minecraft.client.Minecraft client, BlockPos anchor) {
            if (client.level == null) {
                return false;
            }
            var world = me.justbecause.distantdecorations.client.render.DecorationRenderManager
                    .getInstance().getWorld(client.level.dimension());
            return world != null && world.getTotalDecorationsCount() == 1
                    && world.getLoadedRegions().stream()
                    .flatMap(region -> region.getAllRecords().stream())
                    .anyMatch(record -> record.id().type().equals(
                            me.justbecause.fastpaintings.compat.distantdecorations
                                    .FastPaintingDistantDecorationProvider.TYPE_ID)
                            && record.id().anchor().equals(anchor));
        }

        private static long totalRendered() {
            return me.justbecause.distantdecorations.telemetry.TelemetryMetrics.CLIENT_TOTAL_RENDERED.get();
        }

        private static void assertIndexed(ServerLevel level, BlockPos anchor) {
            var type = me.justbecause.fastpaintings.compat.distantdecorations
                    .FastPaintingDistantDecorationProvider.TYPE;
            var provider = me.justbecause.distantdecorations.api.DecorationRegistry.getProvider(type.id());
            if (provider == null) {
                throw new AssertionError("Fast Paintings DD provider was not registered");
            }

            var index = me.justbecause.distantdecorations.server.ServerDecorationManager
                    .getInstance().getIndex(level);
            if (index == null) {
                throw new AssertionError("Distant Decorations world index was not created");
            }

            var record = index.getLoadedRegions().stream()
                    .flatMap(region -> region.getAllRecords().stream())
                    .filter(candidate -> candidate.id().type().equals(type.id())
                            && candidate.id().anchor().equals(anchor)
                            && candidate.id().dimension().equals(level.dimension()))
                    .findFirst().orElseThrow(() -> new AssertionError(
                            "DD did not capture the placed Fast Paintings anchor"));

            var data = type.fromBytes(record.payload());
            if (data.width() != 2 || data.height() != 2 || data.direction() != Direction.NORTH) {
                throw new AssertionError("DD captured incorrect painting dimensions or facing");
            }
            var expectedBounds = me.justbecause.fastpaintings.painting.PaintingFootprint
                    .calculateBoundingBox(anchor, Direction.NORTH, 2, 2);
            if (!record.bounds().equals(expectedBounds)) {
                throw new AssertionError("DD captured incorrect painting bounds");
            }
            System.out.printf("Fast Paintings DD index: type=%s, payload=%d bytes, bounds=%s%n",
                    record.id().type(), record.payload().length, record.bounds());
        }
    }
}
