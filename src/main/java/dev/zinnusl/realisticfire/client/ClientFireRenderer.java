package dev.zinnusl.realisticfire.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.zinnusl.realisticfire.nativebridge.RealisticFireNativeSolver;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientFireRenderer {
    private static final ResourceLocation FLAME_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/particle/flame.png");
    private static final ResourceLocation SMOKE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/particle/big_smoke_0.png");
    private static final ResourceLocation SCORCH_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/block/black_concrete_powder.png");
    private static final RenderType FLAME_RENDER_TYPE = RenderType.entityTranslucent(FLAME_TEXTURE);
    private static final RenderType SMOKE_RENDER_TYPE = RenderType.entityTranslucent(SMOKE_TEXTURE);
    private static final RenderType SCORCH_RENDER_TYPE = RenderType.entityTranslucent(SCORCH_TEXTURE);
    private static final int MAX_RENDERED_RECORDS = 4096;
    private static final long SCORCH_TTL_NANOS = 18_000_000_000L;
    private static final long ACTIVE_FLAME_NANOS = 700_000_000L;
    private static final Map<ScorchKey, ScorchVisual> SCORCHES = new ConcurrentHashMap<>();

    private ClientFireRenderer() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ClientFireRenderer::onRenderLevelStage);
    }

    private static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }

        String dimension = level.dimension().location().toString();
        Map<ClientFireVisuals.Key, ClientFireVisuals.TileVisuals> visuals = ClientFireVisuals.snapshot();
        if (visuals.isEmpty()) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        Quaternionf cameraRotation = new Quaternionf(camera.rotation());
        Vector3f right = new Vector3f(1.0f, 0.0f, 0.0f).rotate(cameraRotation);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f).rotate(cameraRotation);

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4f matrix = poseStack.last().pose();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer scorchConsumer = buffers.getBuffer(SCORCH_RENDER_TYPE);
        VertexConsumer flameConsumer = buffers.getBuffer(FLAME_RENDER_TYPE);
        VertexConsumer smokeConsumer = buffers.getBuffer(SMOKE_RENDER_TYPE);

        int rendered = 0;
        long now = System.nanoTime();
        float time = (System.nanoTime() & 0x7FFF_FFFFL) / 1_000_000_000.0f;
        for (Map.Entry<ClientFireVisuals.Key, ClientFireVisuals.TileVisuals> entry : visuals.entrySet()) {
            if (!entry.getKey().dimension().equals(dimension)) {
                continue;
            }
            rendered += renderRecords(
                    level,
                    matrix,
                    flameConsumer,
                    smokeConsumer,
                    right,
                    up,
                    dimension,
                    entry.getValue().records(),
                    time,
                    now,
                    MAX_RENDERED_RECORDS - rendered);
            if (rendered >= MAX_RENDERED_RECORDS) {
                break;
            }
        }

        renderScorches(level, matrix, scorchConsumer, flameConsumer, dimension, time, now, MAX_RENDERED_RECORDS);
        buffers.endBatch(SCORCH_RENDER_TYPE);
        buffers.endBatch(FLAME_RENDER_TYPE);
        buffers.endBatch(SMOKE_RENDER_TYPE);
        poseStack.popPose();
    }

    private static int renderRecords(
            ClientLevel level,
            Matrix4f matrix,
            VertexConsumer flameConsumer,
            VertexConsumer smokeConsumer,
            Vector3f right,
            Vector3f up,
            String dimension,
            float[] records,
            float time,
            long now,
            int budget) {
        int rendered = 0;
        for (int base = 0; base + RealisticFireNativeSolver.VISUAL_RECORD_FLOATS <= records.length; base += RealisticFireNativeSolver.VISUAL_RECORD_FLOATS) {
            float x = records[base];
            float y = records[base + 1];
            float z = records[base + 2];
            float temperature = records[base + 3];
            float flame = records[base + 4];
            float smoke = records[base + 5];
            float heat = records[base + 7];
            float phase = phase(x, y, z, time);
            if (temperature > 330.0f || smoke > 0.03f || heat > 0.03f) {
                recordScorch(dimension, x, y, z, temperature, flame, smoke, heat, now);
            }
            if (rendered < budget && flame > 0.04f) {
                float intensity = Mth.clamp(flame * (0.82f + phase * 0.18f), 0.0f, 1.35f);
                float surfaceY = surfaceY(level, x, y, z);
                float glowRadius = 0.32f + intensity * 0.28f;
                int glowColor = argb(0.28f + intensity * 0.24f, 1.0f, 0.35f + intensity * 0.18f, 0.02f);
                renderGroundQuad(flameConsumer, matrix, x, surfaceY, z, glowRadius, glowColor);

                float height = 0.32f + intensity * 0.62f;
                float width = 0.16f + intensity * 0.28f;
                int flameColor = argb(0.34f + intensity * 0.36f, 1.0f, 0.42f + intensity * 0.16f, 0.05f);
                renderBillboard(flameConsumer, matrix, right, up, x, surfaceY + height * 0.43f, z, width, height, flameColor);
                rendered++;
            }
            if (smoke > 0.04f && rendered < budget) {
                float intensity = Mth.clamp(smoke * 0.22f, 0.0f, 0.85f);
                float size = 0.35f + intensity * 0.9f;
                int smokeColor = argb(0.09f + intensity * 0.16f, 0.18f, 0.17f, 0.15f);
                renderBillboard(smokeConsumer, matrix, right, up, x, surfaceY(level, x, y, z) + 0.55f + intensity * 0.6f, z, size, size, smokeColor);
                rendered++;
            }
        }
        return rendered;
    }

    private static void recordScorch(String dimension, float x, float y, float z, float temperature, float flame, float smoke, float heat, long now) {
        BlockPos pos = BlockPos.containing(x, y, z);
        ScorchKey key = new ScorchKey(dimension, pos.asLong());
        float target = Mth.clamp(Math.max(Math.max((temperature - 330.0f) / 450.0f, heat * 0.8f), smoke * 0.18f), 0.0f, 1.0f);
        if (target <= 0.02f) {
            return;
        }
        SCORCHES.compute(key, (ignored, existing) -> {
            if (existing == null) {
                existing = new ScorchVisual();
            }
            existing.x = x;
            existing.y = y;
            existing.z = z;
            existing.intensity = Math.max(existing.intensity * 0.985f, target);
            existing.updatedNanos = now;
            if (flame > 0.04f) {
                existing.flame = Math.max(existing.flame * 0.88f, flame);
                existing.lastFlameNanos = now;
            } else {
                existing.flame *= 0.9f;
            }
            return existing;
        });
    }

    private static void renderScorches(
            ClientLevel level,
            Matrix4f matrix,
            VertexConsumer scorchConsumer,
            VertexConsumer flameConsumer,
            String dimension,
            float time,
            long now,
            int budget) {
        int rendered = 0;
        for (Map.Entry<ScorchKey, ScorchVisual> entry : SCORCHES.entrySet()) {
            ScorchKey key = entry.getKey();
            ScorchVisual scorch = entry.getValue();
            if (!key.dimension().equals(dimension)) {
                continue;
            }
            long age = now - scorch.updatedNanos;
            if (age > SCORCH_TTL_NANOS) {
                SCORCHES.remove(key, scorch);
                continue;
            }
            if (rendered >= budget) {
                break;
            }
            float ageFade = 1.0f - (float) age / SCORCH_TTL_NANOS;
            float intensity = Mth.clamp(scorch.intensity * (0.35f + ageFade * 0.65f), 0.0f, 1.0f);
            if (intensity <= 0.03f) {
                continue;
            }
            float x = scorch.x;
            float y = surfaceY(level, scorch.x, scorch.y, scorch.z);
            float z = scorch.z;
            float irregular = phase(x, y, z, time * 0.37f);
            float radius = 0.38f + intensity * 0.24f + irregular * 0.06f;
            int scorchColor = argb(0.22f + intensity * 0.36f, 0.025f, 0.022f, 0.018f);
            renderGroundQuad(scorchConsumer, matrix, x, y + 0.0015f, z, radius, scorchColor);

            if (now - scorch.lastFlameNanos < ACTIVE_FLAME_NANOS && scorch.flame > 0.08f) {
                float front = Mth.clamp(scorch.flame * (0.55f + irregular * 0.45f), 0.0f, 1.0f);
                int emberColor = argb(0.16f + front * 0.42f, 1.0f, 0.38f + front * 0.26f, 0.02f);
                renderEmberFlecks(flameConsumer, matrix, x, y + 0.004f, z, front, emberColor);
            }
            rendered++;
        }
    }

    private static float surfaceY(ClientLevel level, float x, float y, float z) {
        BlockPos pos = BlockPos.containing(x, y, z);
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            BlockPos below = pos.below();
            BlockState belowState = level.getBlockState(below);
            VoxelShape belowShape = belowState.getShape(level, below);
            if (!belowState.isAir() && !belowShape.isEmpty()) {
                return (float) (below.getY() + belowShape.bounds().maxY + 0.0125);
            }
            return pos.getY() + 0.0625f;
        }
        VoxelShape shape = state.getShape(level, pos);
        if (shape.isEmpty()) {
            return pos.getY() + 0.0625f;
        }
        double maxY = shape.bounds().maxY;
        if (maxY < 0.95) {
            return pos.getY() + 0.0625f;
        }
        return (float) (pos.getY() + maxY + 0.0125);
    }

    private static void renderBillboard(
            VertexConsumer consumer,
            Matrix4f matrix,
            Vector3f right,
            Vector3f up,
            float centerX,
            float centerY,
            float centerZ,
            float width,
            float height,
            int color) {
        float rx = right.x() * width;
        float ry = right.y() * width;
        float rz = right.z() * width;
        float ux = up.x() * height;
        float uy = up.y() * height;
        float uz = up.z() * height;
        vertex(consumer, matrix, centerX - rx - ux, centerY - ry - uy, centerZ - rz - uz, 0.0f, 1.0f, color);
        vertex(consumer, matrix, centerX + rx - ux, centerY + ry - uy, centerZ + rz - uz, 1.0f, 1.0f, color);
        vertex(consumer, matrix, centerX + rx + ux, centerY + ry + uy, centerZ + rz + uz, 1.0f, 0.0f, color);
        vertex(consumer, matrix, centerX - rx + ux, centerY - ry + uy, centerZ - rz + uz, 0.0f, 0.0f, color);
    }

    private static void renderGroundQuad(VertexConsumer consumer, Matrix4f matrix, float x, double y, float z, float radius, int color) {
        vertex(consumer, matrix, x - radius, (float) y, z - radius, 0.0f, 1.0f, color);
        vertex(consumer, matrix, x + radius, (float) y, z - radius, 1.0f, 1.0f, color);
        vertex(consumer, matrix, x + radius, (float) y, z + radius, 1.0f, 0.0f, color);
        vertex(consumer, matrix, x - radius, (float) y, z + radius, 0.0f, 0.0f, color);
    }

    private static void renderEmberFlecks(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z, float intensity, int color) {
        int seed = Float.floatToIntBits(x * 11.0f + z * 23.0f);
        for (int i = 0; i < 3; i++) {
            float ox = (((seed >> (i * 5)) & 15) / 15.0f - 0.5f) * 0.72f;
            float oz = (((seed >> (i * 5 + 3)) & 15) / 15.0f - 0.5f) * 0.72f;
            float size = 0.025f + intensity * 0.035f;
            renderGroundQuad(consumer, matrix, x + ox, y + i * 0.0008f, z + oz, size, color);
        }
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z, float u, float v, int color) {
        consumer.addVertex(matrix, x, y, z)
                .setColor(color)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(0.0f, 1.0f, 0.0f);
    }

    private static int argb(float alpha, float red, float green, float blue) {
        int a = Mth.clamp((int) (alpha * 255.0f), 0, 255);
        int r = Mth.clamp((int) (red * 255.0f), 0, 255);
        int g = Mth.clamp((int) (green * 255.0f), 0, 255);
        int b = Mth.clamp((int) (blue * 255.0f), 0, 255);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static float phase(float x, float y, float z, float time) {
        int hash = Float.floatToIntBits(x * 17.0f + y * 31.0f + z * 47.0f);
        return (Mth.sin(time * 9.0f + (hash & 1023) * 0.017f) + 1.0f) * 0.5f;
    }

    private record ScorchKey(String dimension, long blockPos) {
    }

    private static final class ScorchVisual {
        private float x;
        private float y;
        private float z;
        private float intensity;
        private float flame;
        private long updatedNanos;
        private long lastFlameNanos;
    }
}
