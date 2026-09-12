package me.justbecause.fastpaintings.client.compat.distantdecorations;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import me.justbecause.distantdecorations.api.DecorationRecord;
import me.justbecause.distantdecorations.api.DecorationType;
import me.justbecause.distantdecorations.api.client.ClientDecorationRegistry;
import me.justbecause.distantdecorations.api.client.DecorationClientRenderer;
import me.justbecause.distantdecorations.client.spatial.ProjectionMetrics;
import me.justbecause.fastpaintings.compat.distantdecorations.FastPaintingDistantData;
import me.justbecause.fastpaintings.compat.distantdecorations.FastPaintingDistantDecorationProvider;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.data.AtlasIds;
import net.minecraft.world.phys.Vec3;

public class FastPaintingDistantDecorationRenderer implements DecorationClientRenderer<FastPaintingDistantData> {

    public static void init() {
        ClientDecorationRegistry.registerRenderer(new FastPaintingDistantDecorationRenderer());
    }

    @Override
    public double cullBelowProjectedPixelSize() {
        return 0.01;
    }

    @Override
    public DecorationType<FastPaintingDistantData> type() {
        return FastPaintingDistantDecorationProvider.TYPE;
    }

    @Override
    public void render(
        DecorationRecord record,
        FastPaintingDistantData data,
        Camera camera,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector,
        ProjectionMetrics metrics,
        double projectedPixelSize
    ) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        TextureAtlas paintingsAtlas = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.PAINTINGS);
        if (paintingsAtlas == null) {
            return;
        }

        TextureAtlasSprite frontSprite = paintingsAtlas.getSprite(data.assetId());
        if (frontSprite == null) {
            return;
        }

        BlockPos anchorPos = record.pos();
        Vec3 cameraPos = camera.position();

        poseStack.pushPose();
        poseStack.translate(anchorPos.getX() - cameraPos.x, anchorPos.getY() - cameraPos.y, anchorPos.getZ() - cameraPos.z);
        poseStack.translate(0.5, 0.5, 0.5);

        Direction facing = data.direction();
        poseStack.rotateDegrees(Axis.YP, 180.0F - facing.get2DDataValue() * 90.0F);

        int width = data.width();
        int height = data.height();
        double horizontalOffset = (width % 2 == 0) ? 0.5 : 0.0;
        double verticalOffset = (height % 2 == 0) ? 0.5 : 0.0;
        poseStack.translate(horizontalOffset, verticalOffset, 0.46875);

        // Far-LOD visual footprint scaling: ensure subpixel quad maintains ~1px rasterizable footprint
        double targetMinPixelSize = 1.0;
        double visualScale = 1.0;
        if (projectedPixelSize > 0 && projectedPixelSize < targetMinPixelSize) {
            visualScale = Math.min(16.0, targetMinPixelSize / projectedPixelSize);
            me.justbecause.distantdecorations.telemetry.TelemetryMetrics.clientFarLodScaledRenders++;
        }
        if (visualScale > 1.0) {
            poseStack.scale((float) visualScale, (float) visualScale, 1.0F);
        }

        float x0 = width / 2.0F;
        float x1 = -width / 2.0F;
        float y0 = height / 2.0F;
        float y1 = -height / 2.0F;

        float frontU0 = frontSprite.getU0();
        float frontU1 = frontSprite.getU1();
        float frontV0 = frontSprite.getV0();
        float frontV1 = frontSprite.getV1();

        int lightCoords = 0x00F000F0; // Full ambient light for distant LOD

        RenderType renderType = RenderTypes.entitySolidZOffsetForward(frontSprite.atlasLocation());
        submitNodeCollector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
            vertex(pose, buffer, x0, y1, frontU0, frontV1, -0.03125F, 0, 0, -1, lightCoords);
            vertex(pose, buffer, x1, y1, frontU1, frontV1, -0.03125F, 0, 0, -1, lightCoords);
            vertex(pose, buffer, x1, y0, frontU1, frontV0, -0.03125F, 0, 0, -1, lightCoords);
            vertex(pose, buffer, x0, y0, frontU0, frontV0, -0.03125F, 0, 0, -1, lightCoords);
        });

        poseStack.popPose();
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
}
