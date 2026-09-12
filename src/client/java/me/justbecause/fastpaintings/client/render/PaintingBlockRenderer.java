package me.justbecause.fastpaintings.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import me.justbecause.fastpaintings.block.entity.PaintingBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

@Environment(EnvType.CLIENT)
public class PaintingBlockRenderer implements BlockEntityRenderer<PaintingBlockEntity, PaintingBlockRenderState> {

    private static final Identifier BACK_SPRITE_LOCATION = Identifier.withDefaultNamespace("back");
    private final @Nullable TextureAtlas paintingsAtlas;

    public PaintingBlockRenderer(BlockEntityRendererProvider.Context context) {
        if (context.sprites() instanceof AtlasManager atlasManager) {
            this.paintingsAtlas = atlasManager.getAtlasOrThrow(AtlasIds.PAINTINGS);
        } else {
            this.paintingsAtlas = null;
        }
    }

    @Override
    public PaintingBlockRenderState createRenderState() {
        return new PaintingBlockRenderState();
    }

    @Override
    public void extractRenderState(
            PaintingBlockEntity blockEntity,
            PaintingBlockRenderState state,
            float partialTick,
            Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay
    ) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTick, cameraPosition, crumblingOverlay);

        if (PaintingInstrumentation.isEnabled()) {
            PaintingInstrumentation.totalExtractions++;
        }

        state.direction = blockEntity.getFacing();
        state.variant = blockEntity.getVariant() != null ? blockEntity.getVariant().value() : null;
        state.renderBoundingBox = blockEntity.getRenderBoundingBox();

        PaintingVariant variant = state.variant;
        if (variant == null) {
            state.lod = PaintingBlockRenderState.Lod.SKIP;
            return;
        }

        Level level = blockEntity.getLevel();
        if (level == null) {
            state.lod = PaintingBlockRenderState.Lod.SKIP;
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        PaintingRenderMetrics metrics = PaintingRenderMetrics.getInstance();
        if (mc != null && mc.gameRenderer != null && mc.gameRenderer.mainCamera() != null) {
            metrics.update(mc, mc.gameRenderer.mainCamera());
        }

        // Secondary frustum check inside extractRenderState ahead of any lighting
        if (metrics.currentFrustum != null && state.renderBoundingBox != null && !metrics.currentFrustum.isVisible(state.renderBoundingBox)) {
            state.lod = PaintingBlockRenderState.Lod.SKIP;
            if (PaintingInstrumentation.isEnabled()) {
                PaintingInstrumentation.totalExtractionFrustumRejects++;
            }
            return;
        }

        double distanceSq = state.renderBoundingBox.distanceToSqr(cameraPosition);
        double distance = Math.sqrt(distanceSq);
        int width = variant.width();
        int height = variant.height();
        double worldSize = Math.max(width, height);
        double projectedSize = metrics.calculateProjectedSize(worldSize, distance);
        state.projectedSize = projectedSize;

        // Query transient previous LOD from BE and update it
        PaintingBlockRenderState.Lod prevLod = blockEntity.getLastRenderLod();
        state.lod = PaintingLodManager.classifyLod(projectedSize, prevLod);
        blockEntity.setLastRenderLod(state.lod);

        switch (state.lod) {
            case SKIP -> {
                if (PaintingInstrumentation.isEnabled()) {
                    PaintingInstrumentation.totalSkipCount++;
                }
            }
            case FAR -> {
                if (PaintingInstrumentation.isEnabled()) {
                    PaintingInstrumentation.totalFarCount++;
                    PaintingInstrumentation.totalSingleLightSamples++;
                }
                state.singleLight = LightCoordsUtil.getLightCoords(level, blockEntity.getBlockPos());
            }
            case SIMPLIFIED -> {
                if (PaintingInstrumentation.isEnabled()) {
                    PaintingInstrumentation.totalSimplifiedCount++;
                    PaintingInstrumentation.totalSingleLightSamples++;
                }
                state.singleLight = LightCoordsUtil.getLightCoords(level, blockEntity.getBlockPos());
            }
            case FULL -> {
                if (PaintingInstrumentation.isEnabled()) {
                    PaintingInstrumentation.totalFullCount++;
                }
                this.extractFullLighting(blockEntity, state, level, variant, width, height);
            }
        }
    }

    private void extractFullLighting(
            PaintingBlockEntity blockEntity,
            PaintingBlockRenderState state,
            Level level,
            PaintingVariant variant,
            int width,
            int height
    ) {
        int totalSegments = width * height;
        if (state.lightCoordsPerBlock.length != totalSegments) {
            state.lightCoordsPerBlock = new int[totalSegments];
        }

        long gameTime = level.getGameTime();
        if (blockEntity.isLightDirty(gameTime, totalSegments)) {
            int[] cached = blockEntity.getOrCreateLightCache(totalSegments);

            float offsetX = -width / 2.0F;
            float offsetY = -height / 2.0F;
            Direction direction = state.direction;
            Direction left = direction.getCounterClockWise();
            double horizontalOffset = (width % 2 == 0) ? 0.5 : 0.0;
            double verticalOffset = (height % 2 == 0) ? 0.5 : 0.0;

            BlockPos anchorPos = blockEntity.getBlockPos();
            double centerX = anchorPos.getX() + 0.5 + left.getStepX() * horizontalOffset;
            double centerY = anchorPos.getY() + 0.5 + verticalOffset;
            double centerZ = anchorPos.getZ() + 0.5 + left.getStepZ() * horizontalOffset;

            BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();
            for (int segmentY = 0; segmentY < height; segmentY++) {
                for (int segmentX = 0; segmentX < width; segmentX++) {
                    float segmentOffsetX = segmentX + offsetX + 0.5F;
                    float segmentOffsetY = segmentY + offsetY + 0.5F;
                    int x = (int) Math.floor(centerX);
                    int y = (int) Math.floor(centerY + segmentOffsetY);
                    int z = (int) Math.floor(centerZ);
                    switch (direction) {
                        case NORTH -> x = (int) Math.floor(centerX + segmentOffsetX);
                        case WEST -> z = (int) Math.floor(centerZ - segmentOffsetX);
                        case SOUTH -> x = (int) Math.floor(centerX - segmentOffsetX);
                        case EAST -> z = (int) Math.floor(centerZ + segmentOffsetX);
                    }

                    samplePos.set(x, y, z);
                    cached[segmentX + segmentY * width] = LightCoordsUtil.getLightCoords(level, samplePos);
                }
            }
            blockEntity.setCachedLight(cached, gameTime);
            if (PaintingInstrumentation.isEnabled()) {
                PaintingInstrumentation.totalPerBlockLightSamples += totalSegments;
            }
        }

        int[] cached = blockEntity.getCachedLight();
        if (cached != null && cached.length == totalSegments) {
            System.arraycopy(cached, 0, state.lightCoordsPerBlock, 0, totalSegments);
        }
    }

    @Override
    public void submit(
            PaintingBlockRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            CameraRenderState camera
    ) {
        if (state.lod == PaintingBlockRenderState.Lod.SKIP) {
            return;
        }

        if (camera.cullFrustum != null && state.renderBoundingBox != null && !camera.cullFrustum.isVisible(state.renderBoundingBox)) {
            if (PaintingInstrumentation.isEnabled()) {
                PaintingInstrumentation.totalLateFrustumRejects++;
            }
            return;
        }

        PaintingVariant variant = state.variant;
        if (variant != null && this.paintingsAtlas != null) {
            int width = variant.width();
            int height = variant.height();
            double horizontalOffset = (width % 2 == 0) ? 0.5 : 0.0;
            double verticalOffset = (height % 2 == 0) ? 0.5 : 0.0;

            poseStack.pushPose();
            poseStack.translate(0.5, 0.5, 0.5);
            poseStack.rotateDegrees(Axis.YP, 180 - state.direction.get2DDataValue() * 90);
            poseStack.translate(horizontalOffset, verticalOffset, 0.46875);

            TextureAtlasSprite frontSprite = this.paintingsAtlas.getSprite(variant.assetId());
            TextureAtlasSprite backSprite = this.paintingsAtlas.getSprite(BACK_SPRITE_LOCATION);
            RenderType renderType = RenderTypes.entitySolidZOffsetForward(backSprite.atlasLocation());

            switch (state.lod) {
                case FULL -> {
                    if (PaintingInstrumentation.isEnabled()) {
                        PaintingInstrumentation.totalCustomGeometrySubmissions++;
                        int quads = 2 * width * height + 2 * width + 2 * height;
                        PaintingInstrumentation.totalQuadsSubmitted += quads;
                        PaintingInstrumentation.totalVerticesSubmitted += quads * 4;
                    }
                    this.renderFull(
                            poseStack,
                            submitNodeCollector,
                            renderType,
                            state.lightCoordsPerBlock,
                            width,
                            height,
                            frontSprite,
                            backSprite
                    );
                }
                case SIMPLIFIED -> {
                    if (PaintingInstrumentation.isEnabled()) {
                        PaintingInstrumentation.totalCustomGeometrySubmissions++;
                        PaintingInstrumentation.totalQuadsSubmitted += 6;
                        PaintingInstrumentation.totalVerticesSubmitted += 24;
                    }
                    this.renderSimplified(
                            poseStack,
                            submitNodeCollector,
                            renderType,
                            state.singleLight,
                            width,
                            height,
                            frontSprite,
                            backSprite
                    );
                }
                case FAR -> {
                    if (PaintingInstrumentation.isEnabled()) {
                        PaintingInstrumentation.totalCustomGeometrySubmissions++;
                        PaintingInstrumentation.totalQuadsSubmitted += 1;
                        PaintingInstrumentation.totalVerticesSubmitted += 4;
                    }
                    this.renderFar(
                            poseStack,
                            submitNodeCollector,
                            renderType,
                            state.singleLight,
                            width,
                            height,
                            frontSprite
                    );
                }
                case SKIP -> {
                }
            }
            poseStack.popPose();
        }
    }

    private void renderFull(
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            RenderType renderType,
            int[] lightCoordsMap,
            int width,
            int height,
            TextureAtlasSprite front,
            TextureAtlasSprite back
    ) {
        submitNodeCollector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
            float offsetX = -width / 2.0F;
            float offsetY = -height / 2.0F;
            float topBottomU0 = back.getU0();
            float topBottomU1 = back.getU1();
            float topBottomV0 = back.getV0();
            float topBottomV1 = back.getV(0.0625F);
            float leftRightU0 = back.getU0();
            float leftRightU1 = back.getU(0.0625F);
            float leftRightV0 = back.getV0();
            float leftRightV1 = back.getV1();
            double deltaU = 1.0 / width;
            double deltaV = 1.0 / height;

            for (int segmentX = 0; segmentX < width; segmentX++) {
                for (int segmentY = 0; segmentY < height; segmentY++) {
                    float x0 = offsetX + (segmentX + 1);
                    float x1 = offsetX + segmentX;
                    float y0 = offsetY + (segmentY + 1);
                    float y1 = offsetY + segmentY;
                    int lightCoords = lightCoordsMap.length > (segmentX + segmentY * width)
                            ? lightCoordsMap[segmentX + segmentY * width]
                            : 0;

                    float frontU0 = front.getU((float) (deltaU * (width - segmentX)));
                    float frontU1 = front.getU((float) (deltaU * (width - (segmentX + 1))));
                    float frontV0 = front.getV((float) (deltaV * (height - segmentY)));
                    float frontV1 = front.getV((float) (deltaV * (height - (segmentY + 1))));

                    // Front face
                    vertex(pose, buffer, x0, y1, frontU1, frontV0, -0.03125F, 0, 0, -1, lightCoords);
                    vertex(pose, buffer, x1, y1, frontU0, frontV0, -0.03125F, 0, 0, -1, lightCoords);
                    vertex(pose, buffer, x1, y0, frontU0, frontV1, -0.03125F, 0, 0, -1, lightCoords);
                    vertex(pose, buffer, x0, y0, frontU1, frontV1, -0.03125F, 0, 0, -1, lightCoords);

                    // Back face
                    vertex(pose, buffer, x0, y0, back.getU1(), back.getV0(), 0.03125F, 0, 0, 1, lightCoords);
                    vertex(pose, buffer, x1, y0, back.getU0(), back.getV0(), 0.03125F, 0, 0, 1, lightCoords);
                    vertex(pose, buffer, x1, y1, back.getU0(), back.getV1(), 0.03125F, 0, 0, 1, lightCoords);
                    vertex(pose, buffer, x0, y1, back.getU1(), back.getV1(), 0.03125F, 0, 0, 1, lightCoords);

                    // Top edge
                    if (segmentY == height - 1) {
                        vertex(pose, buffer, x0, y0, topBottomU0, topBottomV0, -0.03125F, 0, 1, 0, lightCoords);
                        vertex(pose, buffer, x1, y0, topBottomU1, topBottomV0, -0.03125F, 0, 1, 0, lightCoords);
                        vertex(pose, buffer, x1, y0, topBottomU1, topBottomV1, 0.03125F, 0, 1, 0, lightCoords);
                        vertex(pose, buffer, x0, y0, topBottomU0, topBottomV1, 0.03125F, 0, 1, 0, lightCoords);
                    }

                    // Bottom edge
                    if (segmentY == 0) {
                        vertex(pose, buffer, x0, y1, topBottomU0, topBottomV0, 0.03125F, 0, -1, 0, lightCoords);
                        vertex(pose, buffer, x1, y1, topBottomU1, topBottomV0, 0.03125F, 0, -1, 0, lightCoords);
                        vertex(pose, buffer, x1, y1, topBottomU1, topBottomV1, -0.03125F, 0, -1, 0, lightCoords);
                        vertex(pose, buffer, x0, y1, topBottomU0, topBottomV1, -0.03125F, 0, -1, 0, lightCoords);
                    }

                    // Right edge
                    if (segmentX == width - 1) {
                        vertex(pose, buffer, x0, y0, leftRightU1, leftRightV0, 0.03125F, -1, 0, 0, lightCoords);
                        vertex(pose, buffer, x0, y1, leftRightU1, leftRightV1, 0.03125F, -1, 0, 0, lightCoords);
                        vertex(pose, buffer, x0, y1, leftRightU0, leftRightV1, -0.03125F, -1, 0, 0, lightCoords);
                        vertex(pose, buffer, x0, y0, leftRightU0, leftRightV0, -0.03125F, -1, 0, 0, lightCoords);
                    }

                    // Left edge
                    if (segmentX == 0) {
                        vertex(pose, buffer, x1, y0, leftRightU1, leftRightV0, -0.03125F, 1, 0, 0, lightCoords);
                        vertex(pose, buffer, x1, y1, leftRightU1, leftRightV1, -0.03125F, 1, 0, 0, lightCoords);
                        vertex(pose, buffer, x1, y1, leftRightU0, leftRightV1, 0.03125F, 1, 0, 0, lightCoords);
                        vertex(pose, buffer, x1, y0, leftRightU0, leftRightV0, 0.03125F, 1, 0, 0, lightCoords);
                    }
                }
            }
        });
    }

    private void renderSimplified(
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            RenderType renderType,
            int lightCoords,
            int width,
            int height,
            TextureAtlasSprite front,
            TextureAtlasSprite back
    ) {
        submitNodeCollector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
            float x0 = width / 2.0F;
            float x1 = -width / 2.0F;
            float y0 = height / 2.0F;
            float y1 = -height / 2.0F;

            float frontU0 = front.getU0();
            float frontU1 = front.getU1();
            float frontV0 = front.getV0();
            float frontV1 = front.getV1();

            float topBottomU0 = back.getU0();
            float topBottomU1 = back.getU1();
            float topBottomV0 = back.getV0();
            float topBottomV1 = back.getV(0.0625F);
            float leftRightU0 = back.getU0();
            float leftRightU1 = back.getU(0.0625F);
            float leftRightV0 = back.getV0();
            float leftRightV1 = back.getV1();

            // 1. Front face
            vertex(pose, buffer, x0, y1, frontU0, frontV1, -0.03125F, 0, 0, -1, lightCoords);
            vertex(pose, buffer, x1, y1, frontU1, frontV1, -0.03125F, 0, 0, -1, lightCoords);
            vertex(pose, buffer, x1, y0, frontU1, frontV0, -0.03125F, 0, 0, -1, lightCoords);
            vertex(pose, buffer, x0, y0, frontU0, frontV0, -0.03125F, 0, 0, -1, lightCoords);

            // 2. Back face
            vertex(pose, buffer, x0, y0, back.getU1(), back.getV0(), 0.03125F, 0, 0, 1, lightCoords);
            vertex(pose, buffer, x1, y0, back.getU0(), back.getV0(), 0.03125F, 0, 0, 1, lightCoords);
            vertex(pose, buffer, x1, y1, back.getU0(), back.getV1(), 0.03125F, 0, 0, 1, lightCoords);
            vertex(pose, buffer, x0, y1, back.getU1(), back.getV1(), 0.03125F, 0, 0, 1, lightCoords);

            // 3. Top edge
            vertex(pose, buffer, x0, y0, topBottomU0, topBottomV0, -0.03125F, 0, 1, 0, lightCoords);
            vertex(pose, buffer, x1, y0, topBottomU1, topBottomV0, -0.03125F, 0, 1, 0, lightCoords);
            vertex(pose, buffer, x1, y0, topBottomU1, topBottomV1, 0.03125F, 0, 1, 0, lightCoords);
            vertex(pose, buffer, x0, y0, topBottomU0, topBottomV1, 0.03125F, 0, 1, 0, lightCoords);

            // 4. Bottom edge
            vertex(pose, buffer, x0, y1, topBottomU0, topBottomV0, 0.03125F, 0, -1, 0, lightCoords);
            vertex(pose, buffer, x1, y1, topBottomU1, topBottomV0, 0.03125F, 0, -1, 0, lightCoords);
            vertex(pose, buffer, x1, y1, topBottomU1, topBottomV1, -0.03125F, 0, -1, 0, lightCoords);
            vertex(pose, buffer, x0, y1, topBottomU0, topBottomV1, -0.03125F, 0, -1, 0, lightCoords);

            // 5. Right edge
            vertex(pose, buffer, x0, y0, leftRightU1, leftRightV0, 0.03125F, -1, 0, 0, lightCoords);
            vertex(pose, buffer, x0, y1, leftRightU1, leftRightV1, 0.03125F, -1, 0, 0, lightCoords);
            vertex(pose, buffer, x0, y1, leftRightU0, leftRightV1, -0.03125F, -1, 0, 0, lightCoords);
            vertex(pose, buffer, x0, y0, leftRightU0, leftRightV0, -0.03125F, -1, 0, 0, lightCoords);

            // 6. Left edge
            vertex(pose, buffer, x1, y0, leftRightU1, leftRightV0, -0.03125F, 1, 0, 0, lightCoords);
            vertex(pose, buffer, x1, y1, leftRightU1, leftRightV1, -0.03125F, 1, 0, 0, lightCoords);
            vertex(pose, buffer, x1, y1, leftRightU0, leftRightV1, 0.03125F, 1, 0, 0, lightCoords);
            vertex(pose, buffer, x1, y0, leftRightU0, leftRightV0, 0.03125F, 1, 0, 0, lightCoords);
        });
    }

    private void renderFar(
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            RenderType renderType,
            int lightCoords,
            int width,
            int height,
            TextureAtlasSprite front
    ) {
        submitNodeCollector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
            float x0 = width / 2.0F;
            float x1 = -width / 2.0F;
            float y0 = height / 2.0F;
            float y1 = -height / 2.0F;

            float frontU0 = front.getU0();
            float frontU1 = front.getU1();
            float frontV0 = front.getV0();
            float frontV1 = front.getV1();

            // 1. Front face only (1 quad)
            vertex(pose, buffer, x0, y1, frontU0, frontV1, -0.03125F, 0, 0, -1, lightCoords);
            vertex(pose, buffer, x1, y1, frontU1, frontV1, -0.03125F, 0, 0, -1, lightCoords);
            vertex(pose, buffer, x1, y0, frontU1, frontV0, -0.03125F, 0, 0, -1, lightCoords);
            vertex(pose, buffer, x0, y0, frontU0, frontV0, -0.03125F, 0, 0, -1, lightCoords);
        });
    }

    private static void vertex(
            PoseStack.Pose pose,
            VertexConsumer buffer,
            float x,
            float y,
            float u,
            float v,
            float z,
            int nx,
            int ny,
            int nz,
            int lightCoords
    ) {
        buffer.addVertex(pose, x, y, z)
                .setColor(-1)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(lightCoords)
                .setNormal(pose, nx, ny, nz);
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Override
    public boolean shouldRender(PaintingBlockEntity blockEntity, Vec3 cameraPos) {
        if (PaintingInstrumentation.isEnabled()) {
            PaintingInstrumentation.totalRenderChecks++;
        }

        if (blockEntity.isRemoved() || blockEntity.getLevel() == null) {
            return false;
        }

        if (blockEntity.getVariant() == null) {
            return false;
        }

        AABB renderBox = blockEntity.getRenderBoundingBox();
        Minecraft mc = Minecraft.getInstance();
        Frustum frustum = null;
        if (mc != null && mc.gameRenderer != null && mc.gameRenderer.mainCamera() != null) {
            frustum = mc.gameRenderer.mainCamera().getCullFrustum();
        }

        if (frustum != null && !frustum.isVisible(renderBox)) {
            if (PaintingInstrumentation.isEnabled()) {
                PaintingInstrumentation.totalEarlyFrustumRejects++;
            }
            return false;
        }

        return true;
    }

    @Override
    public int getViewDistance() {
        return Integer.MAX_VALUE;
    }
}