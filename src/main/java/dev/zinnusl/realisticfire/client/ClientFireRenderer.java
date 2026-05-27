package dev.zinnusl.realisticfire.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.zinnusl.realisticfire.config.RealisticFireConfig;
import dev.zinnusl.realisticfire.nativebridge.RealisticFireNativeSolver;
import dev.zinnusl.realisticfire.simulation.material.FireMaterialRegistry;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ClientFireRenderer {
    private static final ResourceLocation FLAME_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/particle/flame.png");
    private static final ResourceLocation SMOKE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/particle/big_smoke_0.png");
    private static final ResourceLocation SCORCH_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("realisticfire", "textures/block/scorch_ash.png");
    private static final ResourceLocation EMBER_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("realisticfire", "textures/block/ember_overlay.png");

    private static final int MAX_RENDERED_RECORDS = 32768;
    private static final int MAX_FOOTPRINTS = 128;
    private static final int FOOTPRINT_SECTORS = 48;
    private static final int EMBER_FLECK_LIMIT = 32;
    private static final int RISING_SPARK_LIMIT = 22;
    private static final int PLUME_COLUMN_LAYERS = 4;
    private static final int DIRECT_SMOKE_LAYERS = 7;
    private static final int DIRECT_TONGUE_LIMIT = 4;
    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final float MIN_FOOTPRINT_RADIUS = 0.28f;
    // Decoupled from MIN_FOOTPRINT_RADIUS (which stays the MERGE/acceptance floor): the smallest
    // VISIBLE contour, so a brand-new fire starts as almost a point at the click and the contour
    // ramps up over FOOTPRINT_GROW_IN_NANOS (smoothstep) to its extent — a smooth point->oval.
    private static final float MIN_VISIBLE_RADIUS = 0.06f;
    private static final long FOOTPRINT_GROW_IN_NANOS = 750_000_000L;
    // Target world width (blocks) of a single perimeter flame sprite. The flame ribbon is
    // subdivided so each sub-quad is ~this wide regardless of footprint radius — keeps flames a
    // constant size instead of smearing one texture across a long arc on big ovals.
    private static final float FLAME_SPRITE_WIDTH = 0.10f;
    private static final int FLAME_MAX_SUBDIV = 32;
    // How far (blocks) beyond a footprint's current contour a newly-burning cell can be and still
    // MERGE into it instead of spawning its own footprint. Must exceed the cell spacing of a
    // spreading fire (~1.0–1.4 blocks) so a contiguous burn forms ONE growing oval, not a little
    // oval per block. (The visual contour still tracks the actual burnt cells via the small
    // absorb padding, so a generous merge margin does not push flames ahead of the burn.)
    private static final float FOOTPRINT_MATCH_MARGIN = 1.7f;
    private static final float MIN_RECORD_INTENSITY = 0.018f;
    private static final long FOOTPRINT_TTL_NANOS = 28_000_000_000L;
    // How long embers keep glowing after the fire front leaves a spot (~5x the old 4.8s).
    private static final long EMBER_LINGER_NANOS = 24_000_000_000L;
    private static final long ACTIVE_FRONT_NANOS = 1_300_000_000L;
    private static final long SMOKE_FRONT_NANOS = 2_400_000_000L;
    private static final long VISUAL_SAMPLE_HOLD_NANOS = 3_500_000_000L;
    private static final float ACTIVE_FLAME_THRESHOLD = 0.18f;
    private static final float SURFACE_Y_BAND = 0.55f;
    private static final float RIBBON_SWAY = 1.0f;
    private static final float BILLBOARD_SWAY = 0.85f;
    private static final float SMOKE_SWAY = 0.45f;
    private static final float DIRECT_SMOKE_DRIFT = 0.16f;

    private static final Map<FootprintKey, FireFootprint> FOOTPRINTS = new ConcurrentHashMap<>();
    private static final Map<FrameCellKey, FrameCell> FRAME_CELLS = new HashMap<>();
    private static final Map<FrameSmokeKey, FrameSmokeCell> FRAME_SMOKE_CELLS = new HashMap<>();
    private static final AtomicLong NEXT_FOOTPRINT_ID = new AtomicLong(1L);

    // Reused on the (single-threaded) render thread to avoid per-frame/per-footprint allocation:
    // the OVAL_P* arrays hold one footprint's perimeter points while its ash + ember ovals emit.
    private static final float[] OVAL_PX = new float[FOOTPRINT_SECTORS];
    private static final float[] OVAL_PZ = new float[FOOTPRINT_SECTORS];
    private static final float[] OVAL_PY = new float[FOOTPRINT_SECTORS];

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

        long now = System.nanoTime();
        int processed = 0;
        for (Map.Entry<ClientFireVisuals.Key, ClientFireVisuals.TileVisuals> entry : visuals.entrySet()) {
            if (!entry.getKey().dimension().equals(dimension)) {
                continue;
            }
            processed += processRecords(level, dimension, entry.getValue().records(), now, Integer.MAX_VALUE);
        }
        pruneFrameCells(dimension, now);

        // Snapshot the framebuffer once per render pass so the distortion shader can
        // sample the world *behind* the fire instead of the half-drawn surface.
        ClientFireDistortion.captureFrameForDistortion();

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

        RenderType scorchRenderType = ClientFireShaders.scorch(SCORCH_TEXTURE);
        RenderType emberRenderType = ClientFireShaders.embers(EMBER_TEXTURE);
        RenderType flameRenderType = ClientFireShaders.flame(FLAME_TEXTURE);
        RenderType smokeRenderType = ClientFireShaders.smoke(SMOKE_TEXTURE);
        RenderType distortionRenderType = ClientFireDistortion.distortionType();

        VertexConsumer scorchConsumer = buffers.getBuffer(scorchRenderType);
        VertexConsumer emberConsumer = buffers.getBuffer(emberRenderType);
        VertexConsumer flameConsumer = buffers.getBuffer(flameRenderType);
        VertexConsumer smokeConsumer = buffers.getBuffer(smokeRenderType);
        VertexConsumer distortionConsumer = distortionRenderType == null ? null : buffers.getBuffer(distortionRenderType);

        // Mask to ~9 minutes of nanoseconds before float wraparound — long enough that the
        // visible animation jump never lands in a typical session, short enough that the float
        // keeps ~65us precision (well below frame time) for smooth sin/cos animation.
        float time = (now & 0x7F_FFFF_FFFFL) / 1_000_000_000.0f;
        renderFootprints(
                level,
                matrix,
                scorchConsumer,
                emberConsumer,
                flameConsumer,
                smokeConsumer,
                distortionConsumer,
                right,
                up,
                dimension,
                time,
                now);

        buffers.endBatch(scorchRenderType);
        buffers.endBatch(emberRenderType);
        buffers.endBatch(flameRenderType);
        buffers.endBatch(smokeRenderType);
        if (distortionRenderType != null) {
            buffers.endBatch(distortionRenderType);
        }
        ClientFireDistortion.clearFrameFlag();
        poseStack.popPose();
    }

    public static void clear() {
        FOOTPRINTS.clear();
        FRAME_CELLS.clear();
        FRAME_SMOKE_CELLS.clear();
        ClientFireVisuals.clear();
    }

    private static int processRecords(ClientLevel level, String dimension, float[] records, long recordNanos, int budget) {
        int processed = 0;
        BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
        for (int base = 0; base + RealisticFireNativeSolver.VISUAL_RECORD_FLOATS <= records.length; base += RealisticFireNativeSolver.VISUAL_RECORD_FLOATS) {
            if (processed >= budget) {
                break;
            }

            float x = records[base];
            float y = records[base + 1];
            float z = records[base + 2];
            float temperature = records[base + 3];
            float flame = records[base + 4];
            float smoke = records[base + 5];
            float heat = records[base + 7];

            float intensity = visualIntensity(temperature, flame, smoke, heat);
            if (intensity <= MIN_RECORD_INTENSITY && smoke <= 0.035f) {
                continue;
            }
            // Skip samples whose cell (or the cell directly below — fires sit on the surface above
            // the burning block) is water: water should never visually carry flame.
            scratch.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
            if (level.getFluidState(scratch).is(FluidTags.WATER)) {
                continue;
            }
            scratch.setY(scratch.getY() - 1);
            if (level.getFluidState(scratch).is(FluidTags.WATER)) {
                continue;
            }
            boolean recorded = false;
            if (smoke > 0.035f) {
                recordFrameSmokeCell(
                        dimension,
                        x,
                        y,
                        z,
                        temperature,
                        flame,
                        smoke,
                        heat,
                        intensity,
                        recordNanos);
                recorded = true;
            }
            scratch.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
            BlockPos fuelSurface = fuelSurfacePos(level, scratch);
            if (fuelSurface != null) {
                recordFrameCell(
                        dimension,
                        fuelSurface,
                        x,
                        z,
                        temperature,
                        flame,
                        smoke,
                        heat,
                        intensity,
                        recordNanos);
                recorded = true;
            }
            if (recorded) {
                processed++;
            }
        }
        return processed;
    }

    private static BlockPos fuelSurfacePos(ClientLevel level, BlockPos.MutableBlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (isVisualFuel(state)) {
            VoxelShape shape = state.getShape(level, pos);
            if (shape.isEmpty() || shape.bounds().maxY < 0.95) {
                BlockPos support = pos.below();
                return isVisualFuel(level.getBlockState(support)) ? support.immutable() : null;
            }
            return pos.immutable();
        }
        pos.setY(pos.getY() - 1);
        state = level.getBlockState(pos);
        if (!isVisualFuel(state)) {
            return null;
        }
        VoxelShape shape = state.getShape(level, pos);
        if (shape.isEmpty() || shape.bounds().maxY < 0.95) {
            BlockPos support = pos.below();
            return isVisualFuel(level.getBlockState(support)) ? support.immutable() : null;
        }
        return pos.immutable();
    }

    private static boolean isVisualFuel(BlockState state) {
        if (state.isAir() || state.getFluidState().is(FluidTags.WATER)) {
            return false;
        }
        // The Rust visual record is the authority that heat/fire exists at this position. The
        // client-side block check is only a surface/water guard: after fire mutates grass into
        // dirt/ash/char, that surface may no longer be flammable, but it must still be allowed to
        // render the remaining flame/ember field reported by the model.
        return true;
    }

    private static void recordFrameCell(
            String dimension,
            BlockPos fuelSurface,
            float x,
            float z,
            float temperature,
            float flame,
            float smoke,
            float heat,
            float intensity,
            long now) {
        int scale = visualCellsPerBlock();
        FrameCellKey key = new FrameCellKey(dimension, fuelSurface.getY(), Mth.floor(x * scale), Mth.floor(z * scale));
        FRAME_CELLS.compute(key, (ignored, existing) -> {
            FrameCell cell = existing == null
                    ? new FrameCell(fuelSurface.getX(), fuelSurface.getY(), fuelSurface.getZ(), x, z, scale)
                    : existing;
            if (existing != null && existing.lastSeenNanos != now) {
                cell.resetVisuals();
            }
            cell.absorb(fuelSurface.getX(), fuelSurface.getY(), fuelSurface.getZ(), x, z, scale, temperature, flame, smoke, heat, intensity, now);
            return cell;
        });
    }

    private static void recordFrameSmokeCell(
            String dimension,
            float x,
            float y,
            float z,
            float temperature,
            float flame,
            float smoke,
            float heat,
            float intensity,
            long now) {
        int scale = visualCellsPerBlock();
        FrameSmokeKey key = new FrameSmokeKey(
                dimension,
                Mth.floor(y),
                Mth.floor(x * scale),
                Mth.floor(z * scale));
        FRAME_SMOKE_CELLS.compute(key, (ignored, existing) -> {
            FrameSmokeCell cell = existing == null
                    ? new FrameSmokeCell(Mth.floor(x), Mth.floor(z), x, y, z)
                    : existing;
            if (existing != null && existing.lastSeenNanos != now) {
                cell.resetVisuals();
            }
            cell.absorb(
                    Mth.floor(x),
                    Mth.floor(z),
                    x,
                    y,
                    z,
                    temperature,
                    flame,
                    smoke,
                    heat,
                    intensity,
                    now);
            return cell;
        });
    }

    private static void pruneFrameCells(String dimension, long now) {
        FRAME_CELLS.entrySet().removeIf(entry ->
                !entry.getKey().dimension().equals(dimension)
                        || now - entry.getValue().lastSeenNanos > VISUAL_SAMPLE_HOLD_NANOS);
        FRAME_SMOKE_CELLS.entrySet().removeIf(entry ->
                !entry.getKey().dimension().equals(dimension)
                        || now - entry.getValue().lastSeenNanos > VISUAL_SAMPLE_HOLD_NANOS);
    }

    private static int visualCellsPerBlock() {
        return Mth.clamp(RealisticFireConfig.SERVER.cellsPerBlockAxis.get(), 1, 6);
    }

    private static float visualIntensity(float temperature, float flame, float smoke, float heat) {
        return Mth.clamp(
                Math.max(
                        Math.max((temperature - 315.0f) / 440.0f, heat * 0.92f),
                        Math.max(smoke * 0.18f, flame * 0.74f)),
                0.0f,
                1.0f);
    }

    private static void recordFootprintSample(
            String dimension,
            float x,
            float y,
            float z,
            float temperature,
            float flame,
            float smoke,
            float heat,
            float intensity,
            long now) {
        FireFootprint footprint = nearestFootprint(dimension, x, z, now);
        if (footprint == null) {
            footprint = createFootprint(dimension, x, y, z, now);
        }
        footprint.absorb(x, y, z, temperature, flame, smoke, heat, intensity, now);
    }

    private static FireFootprint nearestFootprint(String dimension, float x, float z, long now) {
        FireFootprint best = null;
        float bestScore = Float.MAX_VALUE;
        for (Map.Entry<FootprintKey, FireFootprint> entry : FOOTPRINTS.entrySet()) {
            if (!entry.getKey().dimension().equals(dimension)) {
                continue;
            }
            FireFootprint candidate = entry.getValue();
            if (now - candidate.updatedNanos > FOOTPRINT_TTL_NANOS) {
                continue;
            }
            float score = candidate.acceptanceScore(x, z);
            if (score < bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best == null || bestScore > FOOTPRINT_MATCH_MARGIN) {
            return null;
        }
        return best;
    }

    private static FireFootprint createFootprint(String dimension, float x, float y, float z, long now) {
        trimOldFootprints(now);
        FootprintKey key = new FootprintKey(dimension, NEXT_FOOTPRINT_ID.getAndIncrement());
        FireFootprint footprint = new FireFootprint(x, y, z, key.id(), now);
        FOOTPRINTS.put(key, footprint);
        return footprint;
    }

    private static void trimOldFootprints(long now) {
        if (FOOTPRINTS.size() < MAX_FOOTPRINTS) {
            return;
        }
        FootprintKey oldestKey = null;
        long oldestUpdate = Long.MAX_VALUE;
        for (Map.Entry<FootprintKey, FireFootprint> entry : FOOTPRINTS.entrySet()) {
            long updated = entry.getValue().updatedNanos;
            if (now - updated > FOOTPRINT_TTL_NANOS) {
                FOOTPRINTS.remove(entry.getKey(), entry.getValue());
                continue;
            }
            if (updated < oldestUpdate) {
                oldestUpdate = updated;
                oldestKey = entry.getKey();
            }
        }
        if (FOOTPRINTS.size() >= MAX_FOOTPRINTS && oldestKey != null) {
            FOOTPRINTS.remove(oldestKey);
        }
    }

    private static void renderFootprints(
            ClientLevel level,
            Matrix4f matrix,
            VertexConsumer scorchConsumer,
            VertexConsumer emberConsumer,
            VertexConsumer flameConsumer,
            VertexConsumer smokeConsumer,
            VertexConsumer distortionConsumer,
            Vector3f right,
            Vector3f up,
            String dimension,
            float time,
            long now) {
        int rendered = 0;
        DistortionAccumulator distortion = new DistortionAccumulator();
        Map<SmokeColumnKey, SmokeColumn> smokeColumns = new HashMap<>();

        rendered = renderFrameCellPass(
                level,
                matrix,
                scorchConsumer,
                emberConsumer,
                flameConsumer,
                right,
                up,
                dimension,
                time,
                true,
                rendered,
                smokeColumns,
                distortion);
        rendered = renderFrameCellPass(
                level,
                matrix,
                scorchConsumer,
                emberConsumer,
                flameConsumer,
                right,
                up,
                dimension,
                time,
                false,
                rendered,
                smokeColumns,
                distortion);

        for (Map.Entry<FrameSmokeKey, FrameSmokeCell> entry : FRAME_SMOKE_CELLS.entrySet()) {
            FrameSmokeKey key = entry.getKey();
            if (!key.dimension().equals(dimension)) {
                continue;
            }
            if (rendered >= MAX_RENDERED_RECORDS) {
                break;
            }
            FrameSmokeCell cell = entry.getValue();
            if (isFrameSmokeCellSubmerged(level, cell)) {
                continue;
            }
            recordSmokeColumn(smokeColumns, key, cell);
            rendered++;
        }

        for (SmokeColumn column : smokeColumns.values()) {
            renderDirectSmokeColumn(level, matrix, smokeConsumer, right, up, column, time);
        }

        if (distortionConsumer != null && distortion.weight > 0.0f) {
            renderDirectHeatDistortion(
                    matrix,
                    distortionConsumer,
                    right,
                    up,
                    distortion.x / distortion.weight,
                    distortion.y / distortion.weight,
                    distortion.z / distortion.weight,
                    Mth.clamp(distortion.weight / 12.0f, 0.0f, 1.0f));
        }
    }

    private static int renderFrameCellPass(
            ClientLevel level,
            Matrix4f matrix,
            VertexConsumer scorchConsumer,
            VertexConsumer emberConsumer,
            VertexConsumer flameConsumer,
            Vector3f right,
            Vector3f up,
            String dimension,
            float time,
            boolean activeFlamesOnly,
            int rendered,
            Map<SmokeColumnKey, SmokeColumn> smokeColumns,
            DistortionAccumulator distortion) {
        for (Map.Entry<FrameCellKey, FrameCell> entry : FRAME_CELLS.entrySet()) {
            FrameCellKey key = entry.getKey();
            if (!key.dimension().equals(dimension)) {
                continue;
            }
            if (rendered >= MAX_RENDERED_RECORDS) {
                break;
            }

            FrameCell cell = entry.getValue();
            float flamePower = cell.flamePower();
            if (cell.hasActiveFlame() != activeFlamesOnly) {
                continue;
            }
            if (isFrameCellSubmerged(level, key)) {
                continue;
            }

            renderDirectCellGround(level, matrix, scorchConsumer, emberConsumer, key, cell, time);
            if (flamePower > 0.05f) {
                renderDirectCellFront(level, matrix, flameConsumer, right, up, key, cell, flamePower, time);
                distortion.add(cell, flamePower);
            }
            recordSmokeColumn(smokeColumns, key, cell, flamePower);
            rendered++;
        }
        return rendered;
    }

    private static void renderDirectCellGround(
            ClientLevel level,
            Matrix4f matrix,
            VertexConsumer scorchConsumer,
            VertexConsumer emberConsumer,
            FrameCellKey key,
            FrameCell cell,
            float time) {
        float intensity = Mth.clamp(cell.intensity, 0.0f, 1.0f);
        renderGroundCellWorldUv(level, matrix, scorchConsumer, cell, 0.012f, argb(0.72f + intensity * 0.28f, 1.0f, 1.0f, 1.0f));

        float pulse = 0.72f + 0.28f * (float) Math.sin(time * 2.2f + cell.seed * 0.00037f);
        float glow = cell.emberGlow() * pulse;
        if (glow > 0.025f) {
            renderGroundCellWorldUv(level, matrix, emberConsumer, cell, 0.018f, argb(Mth.clamp(glow, 0.0f, 1.0f), 1.0f, 1.0f, 1.0f));
        }
    }

    private static void renderDirectCellFront(
            ClientLevel level,
            Matrix4f matrix,
            VertexConsumer flameConsumer,
            Vector3f right,
            Vector3f up,
            FrameCellKey key,
            FrameCell cell,
            float flamePower,
            float time) {
        float half = cell.patchHalf();
        float x0 = Mth.clamp(cell.x - half, cell.blockX + 0.001f, cell.blockX + 0.999f);
        float x1 = Mth.clamp(cell.x + half, cell.blockX + 0.001f, cell.blockX + 0.999f);
        float z0 = Mth.clamp(cell.z - half, cell.blockZ + 0.001f, cell.blockZ + 0.999f);
        float z1 = Mth.clamp(cell.z + half, cell.blockZ + 0.001f, cell.blockZ + 0.999f);
        renderDirectCellSide(level, matrix, flameConsumer, right, up, key, cell, flamePower, time, 0, 0, -1,
                x0, z0,
                x1, z0);
        renderDirectCellSide(level, matrix, flameConsumer, right, up, key, cell, flamePower, time, 1, 1, 0,
                x1, z0,
                x1, z1);
        renderDirectCellSide(level, matrix, flameConsumer, right, up, key, cell, flamePower, time, 2, 0, 1,
                x1, z1,
                x0, z1);
        renderDirectCellSide(level, matrix, flameConsumer, right, up, key, cell, flamePower, time, 3, -1, 0,
                x0, z1,
                x0, z0);
        renderDirectCellSparks(level, matrix, flameConsumer, right, up, key, cell, flamePower, time);
    }

    private static void renderDirectCellSide(
            ClientLevel level,
            Matrix4f matrix,
            VertexConsumer flameConsumer,
            Vector3f right,
            Vector3f up,
            FrameCellKey key,
            FrameCell cell,
            float flamePower,
            float time,
            int side,
            int dx,
            int dz,
            float x0,
            float z0,
            float x1,
            float z1) {
        if (hasFrameCellAt(key.dimension(), key.y(), key.qx() + dx, key.qz() + dz)) {
            return;
        }
        if (isFrameEdgeSubmerged(level, key, x0, z0, x1, z1)) {
            return;
        }

        float edgeLength = (float) Math.sqrt((x1 - x0) * (x1 - x0) + (z1 - z0) * (z1 - z0));
        int segments = Mth.clamp((int) Math.ceil(edgeLength / 0.18f), 1, 4);
        for (int segment = 0; segment < segments; segment++) {
            float a = (float) segment / segments;
            float b = (float) (segment + 1) / segments;
            float ax = Mth.lerp(a, x0, x1);
            float az = Mth.lerp(a, z0, z1);
            float bx = Mth.lerp(b, x0, x1);
            float bz = Mth.lerp(b, z0, z1);
            float tangentX = bx - ax;
            float tangentZ = bz - az;
            float tangentLength = (float) Math.sqrt(tangentX * tangentX + tangentZ * tangentZ);
            if (tangentLength > 0.0001f) {
                float overlap = cell.patchHalf() * 0.28f;
                tangentX /= tangentLength;
                tangentZ /= tangentLength;
                ax -= tangentX * overlap;
                az -= tangentZ * overlap;
                bx += tangentX * overlap;
                bz += tangentZ * overlap;
            }

            float y0 = directSurfaceY(level, key, ax, az) + 0.010f;
            float y1 = directSurfaceY(level, key, bx, bz) + 0.010f;
            float wave0 = 0.82f + 0.18f * (float) Math.sin(time * 8.0f + cell.seed * 0.00019f + side * 1.7f + segment * 0.43f);
            float wave1 = 0.82f + 0.18f * (float) Math.sin(time * 8.4f + cell.seed * 0.00023f + side * 1.3f + segment * 0.51f);

            renderDirectFlameLayer(flameConsumer, matrix, ax, y0, az, bx, y1, bz,
                    (0.070f + flamePower * 0.130f) * wave0,
                    (0.070f + flamePower * 0.130f) * wave1,
                    argb(0.30f + flamePower * 0.32f, 1.0f, 0.28f, 0.030f),
                    side,
                    segment);
            renderDirectFlameLayer(flameConsumer, matrix, ax, y0 + 0.006f, az, bx, y1 + 0.006f, bz,
                    (0.115f + flamePower * 0.210f) * wave0,
                    (0.115f + flamePower * 0.210f) * wave1,
                    argb(0.28f + flamePower * 0.38f, 1.0f, 0.46f, 0.055f),
                    side + 1,
                    segment);
            renderDirectFlameLayer(flameConsumer, matrix, ax, y0 + 0.014f, az, bx, y1 + 0.014f, bz,
                    (0.050f + flamePower * 0.105f) * wave1,
                    (0.050f + flamePower * 0.105f) * wave0,
                    argb(0.18f + flamePower * 0.30f, 1.0f, 0.76f, 0.16f),
                    side + 3,
                    segment);
        }
        renderDirectCellTongues(level, matrix, flameConsumer, right, up, key, cell, flamePower, time, side, dx, dz, x0, z0, x1, z1, edgeLength);
    }

    private static void renderDirectFlameLayer(
            VertexConsumer consumer,
            Matrix4f matrix,
            float ax,
            float ay,
            float az,
            float bx,
            float by,
            float bz,
            float heightA,
            float heightB,
            int color,
            int side,
            int segment) {
        float inset = ((side + segment) & 1) == 0 ? 0.16f : 0.24f;
        float u0 = inset;
        float u1 = 1.0f - inset;
        vertex(consumer, matrix, ax, ay, az, u0, 1.0f, color, 0.0f);
        vertex(consumer, matrix, bx, by, bz, u1, 1.0f, color, 0.0f);
        vertex(consumer, matrix, bx, by + heightB, bz, u1, 0.0f, color, RIBBON_SWAY);
        vertex(consumer, matrix, ax, ay + heightA, az, u0, 0.0f, color, RIBBON_SWAY);
    }

    private static void renderDirectCellTongues(
            ClientLevel level,
            Matrix4f matrix,
            VertexConsumer flameConsumer,
            Vector3f right,
            Vector3f up,
            FrameCellKey key,
            FrameCell cell,
            float flamePower,
            float time,
            int side,
            int dx,
            int dz,
            float x0,
            float z0,
            float x1,
            float z1,
            float edgeLength) {
        if (flamePower <= 0.10f || edgeLength <= 0.001f) {
            return;
        }

        float alongX = (x1 - x0) / edgeLength;
        float alongZ = (z1 - z0) / edgeLength;
        int tongues = Mth.clamp((int) Math.ceil(edgeLength / 0.22f), 1, DIRECT_TONGUE_LIMIT);
        for (int i = 0; i < tongues; i++) {
            int salt = cell.seed ^ side * 0x45d9f3b ^ i * 0x119de1f3;
            float t = (i + 0.5f) / tongues;
            float jitter = (random01(salt + 17) - 0.5f) * Math.min(edgeLength * 0.34f, 0.10f);
            float baseX = Mth.lerp(t, x0, x1) + alongX * jitter + dx * 0.026f;
            float baseZ = Mth.lerp(t, z0, z1) + alongZ * jitter + dz * 0.026f;
            float baseY = directSurfaceY(level, key, baseX, baseZ) + 0.020f;

            float lick = 0.76f + 0.24f * (float) Math.sin(time * 5.1f + side * 1.7f + i * 2.3f + cell.seed * 0.00023f);
            float heightBias = 0.72f + random01(salt + 31) * 0.58f;
            float halfHeight = (0.115f + flamePower * 0.190f) * heightBias * lick;
            float halfWidth = (0.030f + flamePower * 0.040f) * (0.78f + random01(salt + 53) * 0.48f);
            float lean = ((float) Math.sin(time * 2.2f + salt * 0.00041f) * 0.048f + flamePower * 0.032f) * (0.6f + random01(salt + 71) * 0.7f);
            float centerX = baseX + dx * lean;
            float centerY = baseY + halfHeight;
            float centerZ = baseZ + dz * lean;

            int outerColor = argb(0.15f + flamePower * 0.24f, 1.0f, 0.34f + lick * 0.12f, 0.030f);
            renderSwayingBillboard(flameConsumer, matrix, right, up, centerX, centerY, centerZ,
                    halfWidth * 1.20f, halfHeight, outerColor, BILLBOARD_SWAY);

            int coreColor = argb(0.16f + flamePower * 0.24f, 1.0f, 0.62f + lick * 0.18f, 0.095f);
            renderSwayingBillboard(flameConsumer, matrix, right, up,
                    centerX + dx * 0.010f, centerY - halfHeight * 0.10f, centerZ + dz * 0.010f,
                    halfWidth * 0.58f, halfHeight * 0.76f, coreColor, BILLBOARD_SWAY * 1.15f);
        }
    }

    private static void renderDirectCellSparks(
            ClientLevel level,
            Matrix4f matrix,
            VertexConsumer flameConsumer,
            Vector3f right,
            Vector3f up,
            FrameCellKey key,
            FrameCell cell,
            float flamePower,
            float time) {
        int count = Math.min(3, 1 + (int) (flamePower * 3.0f));
        for (int i = 0; i < count; i++) {
            float lifeSeconds = 1.1f;
            float life = ((time + random01(cell.seed + i * 97) * lifeSeconds) % lifeSeconds) / lifeSeconds;
            float fade = (1.0f - Mth.clamp((life - 0.55f) / 0.45f, 0.0f, 1.0f)) * Mth.clamp(life / 0.18f, 0.0f, 1.0f);
            if (fade <= 0.02f) {
                continue;
            }
            float half = cell.patchHalf();
            float x = Mth.clamp(cell.x + (random01(cell.seed + i * 31) - 0.5f) * half * 1.4f, cell.blockX + 0.001f, cell.blockX + 0.999f);
            float z = Mth.clamp(cell.z + (random01(cell.seed + i * 43) - 0.5f) * half * 1.4f, cell.blockZ + 0.001f, cell.blockZ + 0.999f);
            float y = directSurfaceY(level, cell, x, z) + 0.05f + life * (0.35f + flamePower * 0.26f);
            float drift = (float) Math.sin(life * TWO_PI + i) * 0.05f;
            float size = 0.018f + random01(cell.seed + i * 61) * 0.010f;
            int color = argb(fade * (0.24f + flamePower * 0.24f), 1.0f, 0.54f, 0.06f);
            renderSwayingBillboard(flameConsumer, matrix, right, up, x + drift, y, z - drift, size, size, color, BILLBOARD_SWAY * 0.5f);
        }
    }

    private static void recordSmokeColumn(
            Map<SmokeColumnKey, SmokeColumn> smokeColumns,
            FrameCellKey key,
            FrameCell cell,
            float flamePower) {
        float smokePower = Mth.clamp(cell.smoke * 0.72f + flamePower * 0.22f + cell.heat * 0.16f, 0.0f, 1.65f);
        if (smokePower <= 0.018f) {
            return;
        }
        SmokeColumnKey columnKey = new SmokeColumnKey(key.y(), cell.blockX, cell.blockZ);
        smokeColumns.computeIfAbsent(columnKey, ignored -> new SmokeColumn(cell.blockX, key.y(), cell.blockZ))
                .absorb(cell.x, cell.z, smokePower, cell.smoke, flamePower, cell.heat);
    }

    private static void recordSmokeColumn(
            Map<SmokeColumnKey, SmokeColumn> smokeColumns,
            FrameSmokeKey key,
            FrameSmokeCell cell) {
        float smokePower = Mth.clamp(
                cell.smoke * 0.96f + cell.flame * 0.20f + cell.heat * 0.12f + cell.intensity * 0.18f,
                0.0f,
                1.9f);
        if (smokePower <= 0.018f) {
            return;
        }
        SmokeColumnKey columnKey = new SmokeColumnKey(key.y(), cell.blockX, cell.blockZ);
        smokeColumns.computeIfAbsent(columnKey, ignored -> new SmokeColumn(cell.blockX, key.y(), cell.blockZ))
                .absorb(cell.x, cell.z, smokePower, cell.smoke, cell.flame, cell.heat);
    }

    private static void renderDirectSmokeColumn(
            ClientLevel level,
            Matrix4f matrix,
            VertexConsumer smokeConsumer,
            Vector3f right,
            Vector3f up,
            SmokeColumn column,
            float time) {
        float density = column.density();
        if (density <= 0.025f) {
            return;
        }

        float xBase = column.x();
        float zBase = column.z();
        float baseY = surfaceY(level, xBase, column.blockY + 0.5f, zBase);
        float windAngle = column.seed * 0.00031f + time * 0.055f;
        float windX = (float) Math.cos(windAngle);
        float windZ = (float) Math.sin(windAngle * 1.17f);
        for (int layer = 0; layer < DIRECT_SMOKE_LAYERS; layer++) {
            float t = (layer + 0.35f) / DIRECT_SMOKE_LAYERS;
            int salt = column.seed ^ layer * 0x632be5ab;
            float swirl = time * (0.38f + layer * 0.07f) + salt * 0.00029f;
            float lift = 0.42f + t * (3.20f + density * 2.75f);
            float drift = t * t * (DIRECT_SMOKE_DRIFT + density * 0.46f) * (0.70f + random01(salt + 41) * 0.60f);
            float curl = (float) Math.sin(swirl) * (0.035f + t * 0.105f);
            float x = xBase + windX * drift + (float) Math.cos(swirl * 1.7f) * curl;
            float z = zBase + windZ * drift + (float) Math.sin(swirl * 1.31f) * curl;
            float y = baseY + lift + (float) Math.sin(time * 0.9f + salt * 0.00019f) * (0.035f + t * 0.050f);
            float alpha = Mth.clamp((0.19f + density * 0.31f) * (1.0f - t * 0.38f), 0.075f, 0.62f);
            if (alpha <= 0.020f) {
                continue;
            }

            float size = (0.30f + density * 0.32f) * (0.78f + t * 1.70f);
            float gray = 0.145f + t * 0.115f + (1.0f - density) * 0.025f;
            int color = argb(alpha, gray, gray, gray * 1.02f);
            renderSwayingBillboard(smokeConsumer, matrix, right, up, x, y, z,
                    size, size * (1.18f + t * 0.42f), color, SMOKE_SWAY * (0.80f + t * 0.95f));
        }
    }

    private static void renderDirectHeatDistortion(
            Matrix4f matrix,
            VertexConsumer distortionConsumer,
            Vector3f right,
            Vector3f up,
            float x,
            float y,
            float z,
            float strength) {
        int color = argb(Mth.clamp(strength * 0.45f, 0.0f, 0.45f), 1.0f, 0.58f, 0.18f);
        renderSwayingBillboard(distortionConsumer, matrix, right, up, x, y + 0.45f, z,
                0.55f + strength * 0.85f, 0.48f + strength * 0.72f, color, BILLBOARD_SWAY);
    }

    private static boolean hasFrameCellAt(String dimension, int y, int qx, int qz) {
        return FRAME_CELLS.containsKey(new FrameCellKey(dimension, y, qx, qz))
                || FRAME_CELLS.containsKey(new FrameCellKey(dimension, y - 1, qx, qz))
                || FRAME_CELLS.containsKey(new FrameCellKey(dimension, y + 1, qx, qz));
    }

    private static boolean isFrameCellSubmerged(ClientLevel level, FrameCellKey key) {
        FrameCell cell = FRAME_CELLS.get(key);
        if (cell == null) {
            return true;
        }
        BlockPos pos = new BlockPos(cell.blockX, cell.blockY, cell.blockZ);
        return level.getFluidState(pos).is(FluidTags.WATER)
                || level.getFluidState(pos.above()).is(FluidTags.WATER);
    }

    private static boolean isFrameSmokeCellSubmerged(ClientLevel level, FrameSmokeCell cell) {
        return level.getFluidState(BlockPos.containing(cell.x, cell.y, cell.z)).is(FluidTags.WATER);
    }

    private static boolean isFrameEdgeSubmerged(ClientLevel level, FrameCellKey key, float x0, float z0, float x1, float z1) {
        float y = key.y() + 1.0f;
        return level.getFluidState(BlockPos.containing(x0, y, z0)).is(FluidTags.WATER)
                || level.getFluidState(BlockPos.containing((x0 + x1) * 0.5f, y, (z0 + z1) * 0.5f)).is(FluidTags.WATER)
                || level.getFluidState(BlockPos.containing(x1, y, z1)).is(FluidTags.WATER);
    }

    private static float directSurfaceY(ClientLevel level, FrameCellKey key, float x, float z) {
        return surfaceY(level, x, key.y() + 0.5f, z);
    }

    private static float directSurfaceY(ClientLevel level, FrameCell cell, float x, float z) {
        return surfaceY(level, x, cell.blockY + 0.5f, z);
    }

    private static void renderCharredOval(
            ClientLevel level,
            Matrix4f matrix,
            VertexConsumer scorchConsumer,
            VertexConsumer emberConsumer,
            FireFootprint footprint,
            float intensity,
            float time,
            long now,
            boolean submerged) {
        // Compute the oval perimeter ONCE (same per-sector contour radii as the flame ring), then
        // draw both the ash and the embers as that same filled oval. Because both share the
        // contour, the embers follow the fire front exactly like the ash — bounded by it, growing
        // with it — instead of leaking past it. OVAL_PY holds the raw surface Y per perimeter point.
        float ox = footprint.originX;
        float oz = footprint.originZ;
        float oy = surfaceY(level, ox, footprint.originY, oz);
        for (int s = 0; s < FOOTPRINT_SECTORS; s++) {
            float angle = sectorAngle(s);
            float r = footprint.contourRadius(s, 0.0f);
            float x = ox + (float) Math.cos(angle) * r;
            float z = oz + (float) Math.sin(angle) * r;
            OVAL_PX[s] = x;
            OVAL_PZ[s] = z;
            OVAL_PY[s] = surfaceY(level, x, footprint.originY, z);
        }

        // Ash: opaque, world-tiled via the scorch shader.
        emitOvalFan(scorchConsumer, matrix, ox, oy, oz, 0.012f, argb(1.0f, 1.0f, 1.0f, 1.0f));

        // Embers: same oval, drawn just above the ash on the additive ember shader (world-tiled
        // ember-spark texture). One global glow per footprint, pulsing and fading as it cools.
        float emberWarmth = 1.0f - Mth.clamp((float) (now - footprint.lastActiveNanos) / EMBER_LINGER_NANOS, 0.0f, 1.0f);
        if (submerged || intensity <= 0.05f || (footprint.heat <= 0.04f && emberWarmth <= 0.0f)) {
            return;
        }
        float emberGlow = Mth.clamp(footprint.heat * 0.8f + intensity * 0.5f, 0.0f, 1.0f) * (0.35f + emberWarmth * 0.75f);
        float pulse = 0.65f + 0.35f * (float) Math.sin(time * 2.0f + footprint.seed * 0.013f);
        float glow = emberGlow * pulse;
        if (glow <= 0.03f) {
            return;
        }
        emitOvalFan(emberConsumer, matrix, ox, oy, oz, 0.018f, argb(Mth.clamp(glow, 0.0f, 1.0f), 1.0f, 1.0f, 1.0f));
    }

    // Filled oval triangle fan from the centre out to the precomputed per-sector contour points
    // (OVAL_P*). UV0 = world (x, z) at every vertex so the bound shader tiles its texture by
    // fract(world). Emitted as QUADS with the two centre vertices coincident (each collapses to a
    // triangle); NO_CULL on the render type covers winding. yOff lifts the fan above the ground.
    private static void emitOvalFan(VertexConsumer consumer, Matrix4f matrix, float ox, float oy, float oz, float yOff, int color) {
        float cy = oy + yOff;
        for (int s = 0; s < FOOTPRINT_SECTORS; s++) {
            int n = wrapSector(s + 1);
            float ys = OVAL_PY[s] + yOff;
            float yn = OVAL_PY[n] + yOff;
            vertex(consumer, matrix, ox, cy, oz, ox, oz, color, 0.0f);
            vertex(consumer, matrix, OVAL_PX[s], ys, OVAL_PZ[s], OVAL_PX[s], OVAL_PZ[s], color, 0.0f);
            vertex(consumer, matrix, OVAL_PX[n], yn, OVAL_PZ[n], OVAL_PX[n], OVAL_PZ[n], color, 0.0f);
            vertex(consumer, matrix, ox, cy, oz, ox, oz, color, 0.0f);
        }
    }

    private static void renderOvalFireFront(
            ClientLevel level,
            Matrix4f matrix,
            VertexConsumer flameConsumer,
            VertexConsumer smokeConsumer,
            Vector3f right,
            Vector3f up,
            FireFootprint footprint,
            float intensity,
            float time,
            long now) {
        float recent = 1.0f - Mth.clamp((float) Math.max(0L, now - footprint.lastActiveNanos - ACTIVE_FRONT_NANOS) / SMOKE_FRONT_NANOS, 0.0f, 1.0f);
        float peakFront = 0.0f;
        for (int sector = 0; sector < FOOTPRINT_SECTORS; sector++) {
            int next = wrapSector(sector + 1);
            float front = footprint.frontPower(sector, recent, intensity, time, now);
            if (front <= 0.045f) {
                continue;
            }

            float wave = footprint.wave(sector, time * 2.4f);
            float frontAlpha = Mth.clamp(front * (0.78f + wave * 0.42f), 0.0f, 1.0f);
            peakFront = Math.max(peakFront, frontAlpha);
            renderFireGlowBand(level, matrix, flameConsumer, footprint, sector, next, frontAlpha, time);
            renderMultiLayerRibbon(level, matrix, flameConsumer, footprint, sector, next, frontAlpha, wave, time);

            if (frontAlpha > 0.22f) {
                renderPlumeColumn(level, matrix, flameConsumer, right, up, footprint, sector, frontAlpha, wave, time);
            }
            if (frontAlpha > 0.18f && ((sector + footprint.seed) % 3) == 0) {
                renderFrontSmoke(level, matrix, smokeConsumer, right, up, footprint, sector, frontAlpha, wave, time);
            }
        }

        if (peakFront > 0.12f) {
            renderRisingSparks(level, matrix, flameConsumer, right, up, footprint, peakFront, time);
        }
    }

    private static void renderFireGlowBand(
            ClientLevel level,
            Matrix4f matrix,
            VertexConsumer flameConsumer,
            FireFootprint footprint,
            int sector,
            int next,
            float front,
            float time) {
        // Thin warm rings that HUG the contour. Kept narrow + low-alpha on purpose: a wide
        // ground band reads as a flat translucent sheet that blends to lime over green grass
        // (these quads go through the full flame shader). Green channels kept low for orange.
        int haloColor = argb(0.08f + front * 0.16f, 1.0f, 0.28f + front * 0.12f, 0.020f);
        renderContourBand(level, matrix, flameConsumer, footprint, sector, next, -0.10f, 0.07f, 0.0030f, haloColor, time);

        int coreColor = argb(0.22f + front * 0.36f, 1.0f, 0.46f + front * 0.16f, 0.040f);
        renderContourBand(level, matrix, flameConsumer, footprint, sector, next, -0.040f, 0.045f, 0.0038f, coreColor, time);
    }

    private static void renderContourBand(
            ClientLevel level,
            Matrix4f matrix,
            VertexConsumer consumer,
            FireFootprint footprint,
            int sector,
            int next,
            float innerOffset,
            float outerOffset,
            float yOffset,
            int color,
            float time) {
        float maxRadius = Math.max(footprint.maxCurrentRadius, MIN_FOOTPRINT_RADIUS);
        float angle0 = sectorAngle(sector);
        float angle1 = sectorAngle(next);
        float baseRadius0 = footprint.contourRadius(sector, time);
        float baseRadius1 = footprint.contourRadius(next, time);
        float inner0 = Math.max(0.04f, baseRadius0 + innerOffset);
        float inner1 = Math.max(0.04f, baseRadius1 + innerOffset);
        float outer0 = Math.max(inner0 + 0.012f, baseRadius0 + outerOffset);
        float outer1 = Math.max(inner1 + 0.012f, baseRadius1 + outerOffset);

        float x00 = footprint.originX + (float) Math.cos(angle0) * inner0;
        float z00 = footprint.originZ + (float) Math.sin(angle0) * inner0;
        float x01 = footprint.originX + (float) Math.cos(angle1) * inner1;
        float z01 = footprint.originZ + (float) Math.sin(angle1) * inner1;
        float x11 = footprint.originX + (float) Math.cos(angle1) * outer1;
        float z11 = footprint.originZ + (float) Math.sin(angle1) * outer1;
        float x10 = footprint.originX + (float) Math.cos(angle0) * outer0;
        float z10 = footprint.originZ + (float) Math.sin(angle0) * outer0;

        groundVertex(consumer, matrix, level, footprint, x00, z00, maxRadius, yOffset, color);
        groundVertex(consumer, matrix, level, footprint, x01, z01, maxRadius, yOffset, color);
        groundVertex(consumer, matrix, level, footprint, x11, z11, maxRadius, yOffset, color);
        groundVertex(consumer, matrix, level, footprint, x10, z10, maxRadius, yOffset, color);
    }

    // Two-layer warm-orange ring — short deep-orange back, slightly taller orange-yellow front.
    // Heights kept low so flames don't tower over single-block grass.
    private static void renderMultiLayerRibbon(
            ClientLevel level,
            Matrix4f matrix,
            VertexConsumer flameConsumer,
            FireFootprint footprint,
            int sector,
            int next,
            float front,
            float wave,
            float time) {
        // Additive blending: per-layer alpha controls how much colour is ADDED, so moderate
        // alphas keep the two stacked ribbons summing to orange rather than blowing the green
        // channel to 1.0 (yellow-white). green params low for the same orange-bias reason.
        // heightScale ~1/3 of the old values — small grass flames, not towering tongues.
        // heightScale 2/3 of prior (0.21->0.14, 0.19->0.127) for smaller flames. alphaScale/green
        // left as-is: under GL_ONE,GL_ONE additive the vertex alpha doesn't scale the deposited
        // RGB and vertex green is masked out by the fsh (r is pinned at 1.0), so cutting them is a
        // no-op — and the 3x density is tangential (more side-by-side sub-quads, not stacked), so
        // per-pixel additive coverage is ~unchanged and the flames stay orange, no yellow blowout.
        renderFireRibbon(level, matrix, flameConsumer, footprint, sector, next, front * 0.92f, wave,
                -0.020f, 0.72f, 0.14f, 0.24f, 0.030f, time);
        renderFireRibbon(level, matrix, flameConsumer, footprint, sector, next, front, wave,
                0.050f, 0.88f, 0.127f, 0.40f, 0.060f, time);
    }

    private static void renderFireRibbon(
            ClientLevel level,
            Matrix4f matrix,
            VertexConsumer flameConsumer,
            FireFootprint footprint,
            int sector,
            int next,
            float front,
            float wave,
            float radialOffset,
            float alphaScale,
            float heightScale,
            float green,
            float blue,
            float time) {
        float angle0 = sectorAngle(sector);
        float angle1 = sectorAngle(next);
        float radius0 = footprint.contourRadius(sector, time) + radialOffset + wave * 0.030f;
        float radius1 = footprint.contourRadius(next, time) + radialOffset + footprint.wave(next, time * 2.0f) * 0.030f;
        float x0 = footprint.originX + (float) Math.cos(angle0) * radius0;
        float z0 = footprint.originZ + (float) Math.sin(angle0) * radius0;
        float x1 = footprint.originX + (float) Math.cos(angle1) * radius1;
        float z1 = footprint.originZ + (float) Math.sin(angle1) * radius1;
        float y0 = surfaceY(level, x0, footprint.originY, z0) + 0.007f;
        float y1 = surfaceY(level, x1, footprint.originY, z1) + 0.007f;
        // Per-sector tongue multiplier breaks the band into individual licking flames.
        float height0 = (0.14f + front * 0.30f + wave * 0.08f) * heightScale * footprint.tongueHeight(sector, time);
        float height1 = (0.13f + front * 0.28f + footprint.wave(next, time * 2.1f) * 0.09f) * heightScale * footprint.tongueHeight(next, time);
        int flameColor = argb((0.44f + front * 0.42f) * alphaScale, 1.0f, green + wave * 0.20f, blue);

        // Subdivide the sector arc into sub-quads of roughly constant world width so each flame
        // sprite keeps a fixed size regardless of footprint radius (one full flame texture per
        // sub-quad) — a large oval no longer smears one flame texture across a long arc. Y and
        // height are lerped from the endpoints to avoid extra per-sub-point surfaceY lookups.
        float dxSeg = x1 - x0;
        float dzSeg = z1 - z0;
        float chord = (float) Math.sqrt(dxSeg * dxSeg + dzSeg * dzSeg);
        int segs = Mth.clamp(Math.round(chord / FLAME_SPRITE_WIDTH), 1, FLAME_MAX_SUBDIV);
        for (int s = 0; s < segs; s++) {
            float ta = (float) s / segs;
            float tb = (float) (s + 1) / segs;
            float ax = x0 + dxSeg * ta, az = z0 + dzSeg * ta, ay = y0 + (y1 - y0) * ta, ah = height0 + (height1 - height0) * ta;
            float bx2 = x0 + dxSeg * tb, bz2 = z0 + dzSeg * tb, by2 = y0 + (y1 - y0) * tb, bh = height0 + (height1 - height0) * tb;
            // Flip U on alternating sub-quads so adjacent small flames aren't identical copies.
            float ua = (s & 1) == 0 ? 0.0f : 1.0f;
            float ub = (s & 1) == 0 ? 1.0f : 0.0f;
            vertex(flameConsumer, matrix, ax, ay, az, ua, 1.0f, flameColor, 0.0f);
            vertex(flameConsumer, matrix, bx2, by2, bz2, ub, 1.0f, flameColor, 0.0f);
            vertex(flameConsumer, matrix, bx2, by2 + bh, bz2, ub, 0.0f, flameColor, RIBBON_SWAY);
            vertex(flameConsumer, matrix, ax, ay + ah, az, ua, 0.0f, flameColor, RIBBON_SWAY);
        }
    }

    // Stacked billboards rising over the perimeter — fakes a volumetric tongue of fire.
    private static void renderPlumeColumn(
            ClientLevel level,
            Matrix4f matrix,
            VertexConsumer flameConsumer,
            Vector3f right,
            Vector3f up,
            FireFootprint footprint,
            int sector,
            float front,
            float wave,
            float time) {
        float angle = sectorAngle(sector + 0.5f);
        float radius = footprint.contourRadius(sector, time) + 0.06f;
        float baseX = footprint.originX + (float) Math.cos(angle) * radius;
        float baseZ = footprint.originZ + (float) Math.sin(angle) * radius;
        float baseY = surfaceY(level, baseX, footprint.originY, baseZ);

        float bendDirX = (float) Math.cos(angle);
        float bendDirZ = (float) Math.sin(angle);

        float maxHeight = 0.0227f + front * 0.0487f + wave * 0.0087f;
        for (int layer = 0; layer < PLUME_COLUMN_LAYERS; layer++) {
            float t = (layer + 0.5f) / PLUME_COLUMN_LAYERS;
            float layerHeight = maxHeight * t;
            float layerBend = layerHeight * 0.22f * (0.5f + footprint.noise(sector * 17 + layer * 41) * 0.5f);

            float cx = baseX + bendDirX * layerBend;
            float cz = baseZ + bendDirZ * layerBend;
            float cy = baseY + layerHeight;

            float widthFalloff = 1.0f - t * 0.62f;
            float width = (0.028f + front * 0.040f) * widthFalloff;
            float height = (0.018f + front * 0.0247f) * (1.0f - t * 0.35f);

            float redBase = 1.0f;
            // Orange at the base, warming only modestly toward the tip (was up to 0.92 green
            // = near-yellow). Keeps plume tongues orange to match the reference flame.
            float greenBase = 0.34f + t * 0.28f;
            float blueBase = 0.05f + t * 0.12f;
            float alphaBase = (0.22f + front * 0.30f) * (1.0f - t * 0.28f);
            int color = argb(alphaBase, redBase, greenBase, blueBase);

            renderSwayingBillboard(flameConsumer, matrix, right, up, cx, cy, cz, width, height, color, BILLBOARD_SWAY);

            if (t < 0.55f) {
                // Soft halo billboard around the plume base. Kept low-alpha because it is a
                // large additive quad — too much here is what washed the flames to yellow.
                int haloColor = argb(0.05f + front * 0.10f, 1.0f, 0.38f, 0.06f);
                renderSwayingBillboard(flameConsumer, matrix, right, up, cx, cy + height * 0.20f, cz,
                        width * 1.6f, height * 1.4f, haloColor, BILLBOARD_SWAY * 0.7f);
            }
        }
    }

    // Deterministic airborne sparks rising from random points around the footprint.
    private static void renderRisingSparks(
            ClientLevel level,
            Matrix4f matrix,
            VertexConsumer flameConsumer,
            Vector3f right,
            Vector3f up,
            FireFootprint footprint,
            float peakFront,
            float time) {
        int count = Math.min(RISING_SPARK_LIMIT, 4 + (int) (footprint.maxCurrentRadius * 5.0f));
        float lifeSeconds = 1.40f;
        for (int i = 0; i < count; i++) {
            float perSparkOffset = footprint.noise(i * 71 + 13) * lifeSeconds;
            float lifePhase = ((time + perSparkOffset) % lifeSeconds) / lifeSeconds;

            float angle = footprint.noise(i * 31 + 7) * TWO_PI;
            float radialFrac = (float) Math.sqrt(footprint.noise(i * 53 + 11));
            int sector = sectorForAngle(angle);
            float baseRadius = footprint.contourRadius(sector, time) * radialFrac * 0.95f;
            float launchX = footprint.originX + (float) Math.cos(angle) * baseRadius;
            float launchZ = footprint.originZ + (float) Math.sin(angle) * baseRadius;
            float launchY = surfaceY(level, launchX, footprint.originY, launchZ) + 0.02f;

            // Low rise (~0.85 block max): small embers drifting up off the flame ring, not the
            // tall thin streaks a higher rise produced.
            float rise = lifePhase * (0.40f + peakFront * 0.30f + footprint.flame * 0.18f);
            float drift = (float) Math.sin(lifePhase * Math.PI * 2.0 + i * 0.7f) * 0.085f;
            float drift2 = (float) Math.cos(lifePhase * Math.PI * 2.1 + i * 0.41f) * 0.070f;

            float x = launchX + drift;
            float z = launchZ + drift2;
            float y = launchY + rise;

            float fadeIn = Mth.clamp(lifePhase / 0.18f, 0.0f, 1.0f);
            float fadeOut = 1.0f - Mth.clamp((lifePhase - 0.55f) / 0.45f, 0.0f, 1.0f);
            float alpha = fadeIn * fadeOut * (0.45f + peakFront * 0.35f);
            if (alpha <= 0.015f) {
                continue;
            }
            float size = 0.022f + footprint.flame * 0.018f + footprint.noise(i * 11 + 3) * 0.012f;

            float coolToward = lifePhase;
            float r = 1.0f;
            float g = Mth.clamp(0.78f - coolToward * 0.50f, 0.0f, 1.0f);
            float b = Mth.clamp(0.20f - coolToward * 0.16f, 0.0f, 1.0f);
            int color = argb(alpha, r, g, b);
            renderSwayingBillboard(flameConsumer, matrix, right, up, x, y, z, size, size, color, BILLBOARD_SWAY * 0.5f);
        }
    }

    // Camera-facing distortion billboards above the fire. Vertex alpha drives shader
    // distortion strength (max at the center, fades to 0 at the corners). Vertex RGB
    // adds a faint warm wash so the haze reads as hot air rather than a pure refraction.
    private static void renderHeatDistortion(
            ClientLevel level,
            Matrix4f matrix,
            VertexConsumer distortionConsumer,
            Vector3f right,
            Vector3f up,
            FireFootprint footprint,
            float intensity) {
        float baseY = surfaceY(level, footprint.originX, footprint.originY, footprint.originZ);
        float radius = Math.max(footprint.maxCurrentRadius, MIN_FOOTPRINT_RADIUS);
        float strength = Mth.clamp(intensity * 0.85f + footprint.flame * 0.45f, 0.0f, 0.95f);
        if (strength <= 0.05f) {
            return;
        }
        for (int layer = 0; layer < 3; layer++) {
            float t = (layer + 0.5f) / 3.0f;
            float cx = footprint.originX;
            float cy = baseY + 0.30f + t * (0.55f + footprint.flame * 0.50f);
            float cz = footprint.originZ;
            float halfW = (radius + 0.40f) * (1.0f + t * 0.35f);
            float halfH = (radius * 0.55f + 0.30f) * (1.0f + t * 0.45f);
            float alpha = strength * (1.0f - t * 0.30f);
            int color = argb(alpha, 1.0f, 0.62f, 0.20f);

            float rx = right.x() * halfW;
            float ry = right.y() * halfW;
            float rz = right.z() * halfW;
            float ux = up.x() * halfH;
            float uy = up.y() * halfH;
            float uz = up.z() * halfH;

            // Manually emit verts because corner alphas differ (0 at outer corners, full at inner)
            // — drives the shader's per-vertex displacement falloff.
            distortionConsumer.addVertex(matrix, cx - rx - ux, cy - ry - uy, cz - rz - uz)
                    .setColor(0).setUv(0.0f, 1.0f).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightTexture.FULL_BRIGHT).setNormal(0.0f, 1.0f, 0.0f);
            distortionConsumer.addVertex(matrix, cx + rx - ux, cy + ry - uy, cz + rz - uz)
                    .setColor(0).setUv(1.0f, 1.0f).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightTexture.FULL_BRIGHT).setNormal(0.0f, 1.0f, 0.0f);
            distortionConsumer.addVertex(matrix, cx + rx + ux, cy + ry + uy, cz + rz + uz)
                    .setColor(color).setUv(1.0f, 0.0f).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightTexture.FULL_BRIGHT).setNormal(0.0f, 1.0f, 0.0f);
            distortionConsumer.addVertex(matrix, cx - rx + ux, cy - ry + uy, cz - rz + uz)
                    .setColor(color).setUv(0.0f, 0.0f).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightTexture.FULL_BRIGHT).setNormal(0.0f, 1.0f, 0.0f);
        }
    }

    private static void renderFrontSmoke(
            ClientLevel level,
            Matrix4f matrix,
            VertexConsumer smokeConsumer,
            Vector3f right,
            Vector3f up,
            FireFootprint footprint,
            int sector,
            float front,
            float wave,
            float time) {
        float angle = sectorAngle(sector + 0.5f);
        float radius = footprint.contourRadius(sector, time) + 0.16f;
        float x = footprint.originX + (float) Math.cos(angle) * radius;
        float z = footprint.originZ + (float) Math.sin(angle) * radius;
        float baseY = surfaceY(level, x, footprint.originY, z);
        // Multi-layer wisp climbing upward — the Rust solver's smoke values feed a stack
        // of progressively higher, paler billboards so smoke reads as a rising column.
        for (int layer = 0; layer < 4; layer++) {
            float t = (layer + 0.5f) / 4.0f;
            // Climbs higher and grows as it rises; pales and thins toward the top so the
            // column dissipates into the air instead of ending in a hard disc.
            float layerY = baseY + 0.40f + t * (1.35f + front * 0.60f);
            float size = (0.26f + front * 0.26f + wave * 0.10f) * (1.0f + t * 0.85f);
            // gray held in [0.30, 0.43]: above the shader's char threshold (0.30, so smoke
            // stays wispy rather than opaque) yet below the flame threshold (~0.30+ into
            // flameWeight) so it renders grey, not orange. Lightens slightly as the wisp rises.
            float gray = 0.30f + t * 0.13f;
            int color = argb(
                    Mth.clamp((0.085f + footprint.smoke * 0.15f + front * 0.040f) * (1.0f - t * 0.45f), 0.0f, 0.40f),
                    gray, gray, gray * 1.02f);
            renderSwayingBillboard(smokeConsumer, matrix, right, up, x, layerY, z, size, size * 1.10f, color, SMOKE_SWAY * (0.7f + t * 0.7f));
        }
    }

    private static void renderDyingSmoke(
            VertexConsumer smokeConsumer,
            Matrix4f matrix,
            Vector3f right,
            Vector3f up,
            FireFootprint footprint,
            float intensity,
            long now) {
        float fade = 1.0f - Mth.clamp((float) (now - footprint.lastActiveNanos) / SMOKE_FRONT_NANOS, 0.0f, 1.0f);
        int smokeColor = argb((0.14f + footprint.smoke * 0.18f) * fade, 0.36f, 0.34f, 0.31f);
        renderSwayingBillboard(
                smokeConsumer,
                matrix,
                right,
                up,
                footprint.originX,
                footprint.originY + 0.55f + intensity * 0.32f,
                footprint.originZ,
                0.48f + footprint.maxCurrentRadius * 0.20f,
                0.50f + footprint.maxCurrentRadius * 0.22f,
                smokeColor,
                SMOKE_SWAY);
    }

    private static void renderInteriorEmbers(VertexConsumer flameConsumer, Matrix4f matrix, ClientLevel level, FireFootprint footprint, float time, long now) {
        float fade = 1.0f - Mth.clamp((float) (now - footprint.lastActiveNanos) / ACTIVE_FRONT_NANOS, 0.0f, 1.0f);
        int flecks = Math.min(EMBER_FLECK_LIMIT, 8 + (int) (footprint.maxCurrentRadius * 7.0f));
        for (int i = 0; i < flecks; i++) {
            float randomA = footprint.noise(i * 41 + 3);
            float randomR = (float) Math.sqrt(footprint.noise(i * 67 + 17));
            float angle = randomA * TWO_PI;
            int sector = sectorForAngle(angle);
            float radius = footprint.contourRadius(sector, time) * randomR * 0.78f;
            float x = footprint.originX + (float) Math.cos(angle) * radius;
            float z = footprint.originZ + (float) Math.sin(angle) * radius;
            float y = surfaceY(level, x, footprint.originY, z) + 0.0040f + i * 0.00005f;
            float size = 0.016f + footprint.flame * 0.022f + footprint.noise(i * 13 + 9) * 0.014f;
            float flicker = 0.74f + phase(x, y, z, time * 2.1f) * 0.26f;
            int color = argb((0.12f + footprint.flame * 0.30f) * fade, 1.0f, 0.30f + flicker * 0.28f, 0.035f);
            renderGroundRect(flameConsumer, matrix, x, y, z, size, size, color);
        }
    }

    private static float surfaceY(ClientLevel level, float x, float y, float z) {
        BlockPos pos = BlockPos.containing(x, y, z);
        BlockState state = level.getBlockState(pos);
        float computed;
        if (state.isAir()) {
            BlockPos below = pos.below();
            BlockState belowState = level.getBlockState(below);
            VoxelShape belowShape = belowState.getShape(level, below);
            if (!belowState.isAir() && !belowShape.isEmpty()) {
                computed = (float) (below.getY() + belowShape.bounds().maxY + 0.0125);
            } else {
                computed = pos.getY() + 0.0625f;
            }
        } else {
            VoxelShape shape = state.getShape(level, pos);
            if (shape.isEmpty()) {
                computed = pos.getY() + 0.0625f;
            } else {
                double maxY = shape.bounds().maxY;
                if (maxY < 0.95) {
                    computed = pos.getY() + 0.0625f;
                } else {
                    computed = (float) (pos.getY() + maxY + 0.0125);
                }
            }
        }
        // Cliff guard: clamp to a band around the ignition Y so polys can't drape vertically over block faces.
        return Mth.clamp(computed, y - SURFACE_Y_BAND, y + SURFACE_Y_BAND);
    }

    // True when the footprint's origin cell or the cell just above it holds water — used to
    // stop a footprint from visibly smouldering after the player floods it.
    private static boolean isSubmerged(ClientLevel level, FireFootprint footprint) {
        BlockPos origin = BlockPos.containing(footprint.originX, footprint.originY, footprint.originZ);
        return level.getFluidState(origin).is(FluidTags.WATER)
                || level.getFluidState(origin.above()).is(FluidTags.WATER);
    }

    private static void groundVertex(
            VertexConsumer consumer,
            Matrix4f matrix,
            ClientLevel level,
            FireFootprint footprint,
            float x,
            float z,
            float maxRadius,
            float yOffset,
            int color) {
        float y = surfaceY(level, x, footprint.originY, z) + yOffset;
        float uvScale = Math.max(maxRadius * 2.18f, 1.0f);
        float u = 0.5f + (x - footprint.originX) / uvScale;
        float v = 0.5f + (z - footprint.originZ) / uvScale;
        vertex(consumer, matrix, x, y, z, u, v, color, 0.0f);
    }

    private static void renderSwayingBillboard(
            VertexConsumer consumer,
            Matrix4f matrix,
            Vector3f right,
            Vector3f up,
            float centerX,
            float centerY,
            float centerZ,
            float width,
            float height,
            int color,
            float swayWeight) {
        float rx = right.x() * width;
        float ry = right.y() * width;
        float rz = right.z() * width;
        float ux = up.x() * height;
        float uy = up.y() * height;
        float uz = up.z() * height;
        vertex(consumer, matrix, centerX - rx - ux, centerY - ry - uy, centerZ - rz - uz, 0.0f, 1.0f, color, 0.0f);
        vertex(consumer, matrix, centerX + rx - ux, centerY + ry - uy, centerZ + rz - uz, 1.0f, 1.0f, color, 0.0f);
        vertex(consumer, matrix, centerX + rx + ux, centerY + ry + uy, centerZ + rz + uz, 1.0f, 0.0f, color, swayWeight);
        vertex(consumer, matrix, centerX - rx + ux, centerY - ry + uy, centerZ - rz + uz, 0.0f, 0.0f, color, swayWeight);
    }

    private static void renderGroundRect(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z, float halfX, float halfZ, int color) {
        renderGroundRectUv(consumer, matrix, x, y, z, halfX, halfZ, color, 0.0f, 0.0f, 1.0f, 1.0f);
    }

    private static void renderGroundRectUv(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z, float halfX, float halfZ, int color, float u0, float v0, float u1, float v1) {
        vertex(consumer, matrix, x - halfX, y, z - halfZ, u0, v1, color, 0.0f);
        vertex(consumer, matrix, x + halfX, y, z - halfZ, u1, v1, color, 0.0f);
        vertex(consumer, matrix, x + halfX, y, z + halfZ, u1, v0, color, 0.0f);
        vertex(consumer, matrix, x - halfX, y, z + halfZ, u0, v0, color, 0.0f);
    }

    private static void renderGroundCellWorldUv(
            ClientLevel level,
            Matrix4f matrix,
            VertexConsumer consumer,
            FrameCell cell,
            float yOffset,
            int color) {
        float half = cell.patchHalf();
        float x0 = Mth.clamp(cell.x - half, cell.blockX + 0.001f, cell.blockX + 0.999f);
        float x1 = Mth.clamp(cell.x + half, cell.blockX + 0.001f, cell.blockX + 0.999f);
        float z0 = Mth.clamp(cell.z - half, cell.blockZ + 0.001f, cell.blockZ + 0.999f);
        float z1 = Mth.clamp(cell.z + half, cell.blockZ + 0.001f, cell.blockZ + 0.999f);
        vertex(consumer, matrix, x0, directSurfaceY(level, cell, x0, z0) + yOffset, z0, x0, z0, color, 0.0f);
        vertex(consumer, matrix, x1, directSurfaceY(level, cell, x1, z0) + yOffset, z0, x1, z0, color, 0.0f);
        vertex(consumer, matrix, x1, directSurfaceY(level, cell, x1, z1) + yOffset, z1, x1, z1, color, 0.0f);
        vertex(consumer, matrix, x0, directSurfaceY(level, cell, x0, z1) + yOffset, z1, x0, z1, color, 0.0f);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z, float u, float v, int color, float swayWeight) {
        consumer.addVertex(matrix, x, y, z)
                .setColor(color)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(Mth.clamp(swayWeight, 0.0f, 1.0f), 1.0f, 0.0f);
    }

    private static int argb(float alpha, float red, float green, float blue) {
        int a = Mth.clamp((int) (alpha * 255.0f), 0, 255);
        int r = Mth.clamp((int) (red * 255.0f), 0, 255);
        int g = Mth.clamp((int) (green * 255.0f), 0, 255);
        int b = Mth.clamp((int) (blue * 255.0f), 0, 255);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static int wrapSector(int sector) {
        int wrapped = sector % FOOTPRINT_SECTORS;
        return wrapped < 0 ? wrapped + FOOTPRINT_SECTORS : wrapped;
    }

    private static int sectorFor(float dx, float dz) {
        // FOOTPRINT_SECTORS=48 => 7.5°/sector, so atan2 precision far beyond that is wasted.
        // fastAtan2 (octant + degree-3 polynomial) is accurate to ~0.0038 rad (~0.22°) — well
        // under a sector — and avoids software FdLibm Math.atan2 (Fix 2). Returns the same
        // sector index as the exact version for the same inputs (within rounding).
        return sectorForAngle(fastAtan2(dz, dx));
    }

    // Octant-based polynomial atan2 approximation in [-PI, PI]. Max abs error ~0.0038 rad.
    private static float fastAtan2(float y, float x) {
        if (x == 0.0f && y == 0.0f) {
            return 0.0f;
        }
        float ax = Math.abs(x);
        float ay = Math.abs(y);
        float a = Math.min(ax, ay) / Math.max(ax, ay);
        float s = a * a;
        // atan(a) for a in [0,1].
        float r = ((-0.0464964749f * s + 0.15931422f) * s - 0.327622764f) * s * a + a;
        if (ay > ax) {
            r = 1.57079637f - r; // PI/2 - r
        }
        if (x < 0.0f) {
            r = 3.14159274f - r; // PI - r
        }
        if (y < 0.0f) {
            r = -r;
        }
        return r;
    }

    private static int sectorForAngle(float angle) {
        float normalized = angle / TWO_PI;
        normalized -= (float) Math.floor(normalized);
        return wrapSector(Math.round(normalized * FOOTPRINT_SECTORS));
    }

    private static float sectorAngle(int sector) {
        return (float) sector / FOOTPRINT_SECTORS * TWO_PI;
    }

    private static float sectorAngle(float sector) {
        return sector / FOOTPRINT_SECTORS * TWO_PI;
    }

    private static float phase(float x, float y, float z, float time) {
        int hash = Float.floatToIntBits(x * 17.0f + y * 31.0f + z * 47.0f);
        return ((float) Math.sin(time * 9.0f + (hash & 1023) * 0.017f) + 1.0f) * 0.5f;
    }

    private static float random01(int seed) {
        int mixed = seed;
        mixed ^= mixed >>> 16;
        mixed *= 0x7feb352d;
        mixed ^= mixed >>> 15;
        mixed *= 0x846ca68b;
        mixed ^= mixed >>> 16;
        return (mixed & 0x00FF_FFFF) / (float) 0x0100_0000;
    }

    private record FootprintKey(String dimension, long id) {
    }

    private record FrameCellKey(String dimension, int y, int qx, int qz) {
    }

    private record FrameSmokeKey(String dimension, int y, int qx, int qz) {
    }

    private record SmokeColumnKey(int y, int x, int z) {
    }

    private static final class SmokeColumn {
        private final int blockX;
        private final int blockY;
        private final int blockZ;
        private final int seed;
        private float weightedX;
        private float weightedZ;
        private float weight;
        private float maxSmokePower;
        private float maxSmoke;
        private float maxFlame;
        private float maxHeat;

        private SmokeColumn(int blockX, int blockY, int blockZ) {
            this.blockX = blockX;
            this.blockY = blockY;
            this.blockZ = blockZ;
            this.seed = blockX * 73428767 ^ blockY * 912931 ^ blockZ * 19349663;
        }

        private void absorb(float x, float z, float smokePower, float smoke, float flame, float heat) {
            float w = Math.max(0.05f, smokePower);
            weightedX += x * w;
            weightedZ += z * w;
            weight += w;
            maxSmokePower = Math.max(maxSmokePower, smokePower);
            maxSmoke = Math.max(maxSmoke, smoke);
            maxFlame = Math.max(maxFlame, flame);
            maxHeat = Math.max(maxHeat, heat);
        }

        private float x() {
            return weight > 0.0f ? weightedX / weight : blockX + 0.5f;
        }

        private float z() {
            return weight > 0.0f ? weightedZ / weight : blockZ + 0.5f;
        }

        private float density() {
            return Mth.clamp(maxSmokePower * 0.94f + Math.min(weight / 5.0f, 1.0f) * 0.26f
                    + maxSmoke * 0.08f + maxFlame * 0.08f + maxHeat * 0.08f, 0.0f, 1.75f);
        }
    }

    private static final class FrameCell {
        private final int seed;
        private int blockX;
        private int blockY;
        private int blockZ;
        private int scale;
        private float x;
        private float z;
        private float intensity;
        private float flame;
        private float heat;
        private float smoke;
        private float temperature;
        private long lastSeenNanos;

        private FrameCell(int blockX, int blockY, int blockZ, float x, float z, int scale) {
            this.seed = Mth.floor(x * 64.0f) * 73428767 ^ blockY * 912931 ^ Mth.floor(z * 64.0f) * 19349663;
            this.blockX = blockX;
            this.blockY = blockY;
            this.blockZ = blockZ;
            this.x = x;
            this.z = z;
            this.scale = scale;
        }

        private void absorb(
                int blockX,
                int blockY,
                int blockZ,
                float x,
                float z,
                int scale,
                float temperature,
                float flame,
                float smoke,
                float heat,
                float intensity,
                long now) {
            this.blockX = blockX;
            this.blockY = blockY;
            this.blockZ = blockZ;
            this.x = x;
            this.z = z;
            this.scale = scale;
            this.lastSeenNanos = now;
            this.temperature = Math.max(this.temperature, temperature);
            this.flame = Math.max(this.flame, flame);
            this.heat = Math.max(this.heat, heat);
            this.smoke = Math.max(this.smoke, smoke);
            this.intensity = Math.max(this.intensity, intensity);
        }

        private float flamePower() {
            float thermal = Mth.clamp(Math.max((temperature - 360.0f) / 520.0f, heat * 1.10f), 0.0f, 1.25f);
            float visual = Mth.clamp(flame * 1.15f, 0.0f, 1.25f);
            return Mth.clamp(Math.max(visual, thermal) + smoke * 0.08f, 0.0f, 1.35f);
        }

        private boolean hasActiveFlame() {
            return flame >= ACTIVE_FLAME_THRESHOLD || temperature >= 520.0f;
        }

        private float emberGlow() {
            return Mth.clamp(heat * 0.85f + intensity * 0.42f + flame * 0.22f, 0.0f, 1.0f);
        }

        private void resetVisuals() {
            intensity = 0.0f;
            flame = 0.0f;
            heat = 0.0f;
            smoke = 0.0f;
            temperature = 0.0f;
        }

        private float patchHalf() {
            return Mth.clamp(0.52f / Math.max(1, scale), 0.075f, 0.50f);
        }
    }

    private static final class DistortionAccumulator {
        private float x;
        private float y;
        private float z;
        private float weight;

        private void add(FrameCell cell, float flamePower) {
            x += cell.x * flamePower;
            y += (cell.blockY + 1.0f) * flamePower;
            z += cell.z * flamePower;
            weight += flamePower;
        }
    }

    private static final class FrameSmokeCell {
        private int blockX;
        private int blockZ;
        private float x;
        private float y;
        private float z;
        private float intensity;
        private float flame;
        private float heat;
        private float smoke;
        private long lastSeenNanos;

        private FrameSmokeCell(int blockX, int blockZ, float x, float y, float z) {
            this.blockX = blockX;
            this.blockZ = blockZ;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private void absorb(
                int blockX,
                int blockZ,
                float x,
                float y,
                float z,
                float temperature,
                float flame,
                float smoke,
                float heat,
                float intensity,
                long now) {
            this.blockX = blockX;
            this.blockZ = blockZ;
            this.x = x;
            this.y = y;
            this.z = z;
            this.lastSeenNanos = now;
            this.flame = Math.max(this.flame, flame);
            this.heat = Math.max(this.heat, heat);
            this.smoke = Math.max(this.smoke, smoke);
            this.intensity = Math.max(
                    this.intensity,
                    Math.max(intensity, smoke * 0.30f + temperature * 0.00004f));
        }

        private void resetVisuals() {
            intensity = 0.0f;
            flame = 0.0f;
            heat = 0.0f;
            smoke = 0.0f;
        }
    }

    private static final class FireFootprint {
        private final long id;
        private final int seed;
        private final float rotation;
        private final float stretchMajor;
        private final float stretchMinor;
        private final float[] targetRadii = new float[FOOTPRINT_SECTORS];
        private final float[] currentRadii = new float[FOOTPRINT_SECTORS];
        private final float[] sectorPowers = new float[FOOTPRINT_SECTORS];
        private float originX;
        private float originY;
        private float originZ;
        private float intensity;
        private float flame;
        private float heat;
        private float smoke;
        private float temperature;
        private float maxCurrentRadius = MIN_FOOTPRINT_RADIUS;
        private float maxTargetRadius = MIN_FOOTPRINT_RADIUS;
        private long updatedNanos;
        private long lastActiveNanos;
        private long lastAdvanceNanos;
        private final long birthNanos;

        private FireFootprint(float originX, float originY, float originZ, long id, long now) {
            this.id = id;
            this.birthNanos = now;
            this.seed = Float.floatToIntBits(originX * 31.0f + originY * 7.0f + originZ * 67.0f) ^ (int) id * 0x45d9f3b;
            this.rotation = noise(103) * TWO_PI;
            this.stretchMajor = 1.02f + noise(211) * 0.18f;
            this.stretchMinor = 0.92f + noise(307) * 0.12f;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.updatedNanos = now;
            this.lastActiveNanos = now;
            this.lastAdvanceNanos = now;
            Arrays.fill(targetRadii, MIN_FOOTPRINT_RADIUS);
            Arrays.fill(currentRadii, MIN_VISIBLE_RADIUS);
        }

        private void absorb(float x, float y, float z, float temperature, float flame, float smoke, float heat, float intensity, long now) {
            this.originY = Mth.lerp(0.08f, this.originY, y);
            this.updatedNanos = now;
            this.temperature = Math.max(this.temperature * 0.96f, temperature);
            this.heat = Math.max(this.heat * 0.95f, heat);
            this.smoke = Math.max(this.smoke * 0.94f, smoke);
            this.intensity = Math.max(this.intensity * 0.992f, intensity);

            float thermalFlame = Mth.clamp(Math.max(heat * 0.68f, (temperature - 420.0f) / 520.0f), 0.0f, 1.0f);
            float active = Math.max(flame, thermalFlame);
            if (active > ACTIVE_FLAME_THRESHOLD) {
                this.lastActiveNanos = now;
                this.flame = Math.max(this.flame * 0.88f, active);
            } else {
                this.flame *= 0.82f;
            }

            float dx = x - originX;
            float dz = z - originZ;
            float distance = (float) Math.sqrt(dx * dx + dz * dz);
            if (distance < 0.04f) {
                dx = (noise(17) - 0.5f) * 0.10f;
                dz = (noise(23) - 0.5f) * 0.10f;
                distance = 0.04f;
            }

            int sector = sectorFor(dx, dz);
            // Padding kept small so the visible flame ring sits ON the hot cells, not ~1 block beyond.
            float sampleRadius = Math.max(MIN_FOOTPRINT_RADIUS, distance + 0.10f + intensity * 0.08f + active * 0.05f);
            for (int offset = -3; offset <= 3; offset++) {
                int neighbor = wrapSector(sector + offset);
                float falloff = 1.0f - Math.abs(offset) / 4.2f;
                float varied = sampleRadius * (0.98f + noise(neighbor * 97 + 19) * 0.04f);
                float target = Math.max(MIN_FOOTPRINT_RADIUS, varied * falloff + targetRadii[neighbor] * (1.0f - falloff));
                // Asymmetric EMA instead of one-way Math.max: grow promptly toward a larger target
                // but also RELAX back down slowly. The old max-latch froze the axis-aligned spikes
                // of the Rust front so angular smoothing could never win — the main blobby source.
                float blend = target > targetRadii[neighbor] ? 0.5f : 0.12f;
                targetRadii[neighbor] = Mth.lerp(blend, targetRadii[neighbor], target);
                if (active > ACTIVE_FLAME_THRESHOLD) {
                    sectorPowers[neighbor] = Math.max(sectorPowers[neighbor] * 0.88f, active * falloff + intensity * 0.20f);
                }
            }
            maxTargetRadius = Math.max(maxTargetRadius, sampleRadius * stretchMajor + 0.20f);
        }

        private void advance(long now) {
            float dt = Math.max(0.0f, (now - lastAdvanceNanos) / 1_000_000_000.0f);
            lastAdvanceNanos = now;

            // 7-tap weighted angular blur — wider than before so the (now non-latching) target
            // radii smooth into an even oval instead of keeping cross-lobed bumps.
            float[] smoothed = new float[FOOTPRINT_SECTORS];
            for (int i = 0; i < FOOTPRINT_SECTORS; i++) {
                smoothed[i] = targetRadii[i] * 0.28f
                        + (targetRadii[wrapSector(i - 1)] + targetRadii[wrapSector(i + 1)]) * 0.20f
                        + (targetRadii[wrapSector(i - 2)] + targetRadii[wrapSector(i + 2)]) * 0.11f
                        + (targetRadii[wrapSector(i - 3)] + targetRadii[wrapSector(i + 3)]) * 0.05f;
            }
            // Pull the raw target radii toward the smoothed average so spikes diffuse away over time.
            float diffuse = Mth.clamp(dt * 1.2f, 0.0f, 1.0f);
            for (int i = 0; i < FOOTPRINT_SECTORS; i++) {
                targetRadii[i] = Mth.lerp(diffuse, targetRadii[i], smoothed[i]);
            }

            // Smooth point->oval inflate: ramp the visible growth target over the birth window
            // (smoothstep) so a new fire starts near a point and eases out to its extent. After the
            // window ease==1 and growth is normal (~1.0 blocks/sec, tracking the spreading burn).
            float birth = Mth.clamp((float) (now - birthNanos) / FOOTPRINT_GROW_IN_NANOS, 0.0f, 1.0f);
            float ease = birth * birth * (3.0f - 2.0f * birth);
            float growth = 0.020f + dt * 1.0f;
            float decay = Mth.clamp(1.0f - dt * 0.55f, 0.0f, 1.0f);
            maxCurrentRadius = MIN_VISIBLE_RADIUS;
            for (int i = 0; i < FOOTPRINT_SECTORS; i++) {
                float target = Math.max(MIN_VISIBLE_RADIUS, smoothed[i]) * ease;
                currentRadii[i] = approach(currentRadii[i], target, growth);
                sectorPowers[i] *= decay;
                maxCurrentRadius = Math.max(maxCurrentRadius, contourRadius(i, 0.0f));
            }

            flame *= Mth.clamp(1.0f - dt * 0.20f, 0.0f, 1.0f);
            heat *= Mth.clamp(1.0f - dt * 0.15f, 0.0f, 1.0f);
            smoke *= Mth.clamp(1.0f - dt * 0.10f, 0.0f, 1.0f);
        }

        private boolean isBurning(long now) {
            return now - lastActiveNanos < ACTIVE_FRONT_NANOS + SMOKE_FRONT_NANOS && firePower(now) > 0.18f;
        }

        private float firePower(long now) {
            float fresh = 1.0f - Mth.clamp((float) Math.max(0L, now - lastActiveNanos - ACTIVE_FRONT_NANOS) / SMOKE_FRONT_NANOS, 0.0f, 1.0f);
            float thermal = Mth.clamp(Math.max((temperature - 360.0f) / 560.0f, heat * 1.10f), 0.0f, 1.25f);
            float visual = Mth.clamp(flame * 1.18f, 0.0f, 1.25f);
            float smokeLift = Mth.clamp(smoke * 0.16f, 0.0f, 0.24f);
            return Mth.clamp(Math.max(visual, thermal) + smokeLift, 0.0f, 1.45f) * fresh;
        }

        private float frontPower(int sector, float recent, float intensity, float time, long now) {
            float local = sectorPowers[sector] * 0.62f
                    + sectorPowers[wrapSector(sector - 1)] * 0.19f
                    + sectorPowers[wrapSector(sector + 1)] * 0.19f;
            float perimeter = recent * Mth.clamp(firePower(now) * 0.28f + intensity * 0.34f, 0.0f, 0.70f);
            return Mth.clamp(Math.max(local, perimeter) * (0.82f + wave(sector, time) * 0.34f), 0.0f, 1.0f);
        }

        private float contourRadius(int sector, float time) {
            float angle = sectorAngle(sector) - rotation;
            float anisotropy = (float) Math.sqrt(
                    Math.cos(angle) * Math.cos(angle) * stretchMajor * stretchMajor
                            + Math.sin(angle) * Math.sin(angle) * stretchMinor * stretchMinor);
            // Low-frequency, angular-CORRELATED organic deviation (two harmonics) instead of an
            // independent per-sector hash. The old hash was uncorrelated between neighbours and
            // immune to the angular smoothing, so it stamped a permanent lumpy edge onto the oval.
            float organic = 1.0f + (float) (Math.sin(angle * 2.0f + seed * 0.00031f) * 0.045f
                    + Math.sin(angle * 3.0f + seed * 0.00057f) * 0.025f);
            float ripple = 1.0f + ((float) Math.sin(angle * 3.0f + seed * 0.00017f) * 0.025f);
            float flickerRipple = 1.0f + ((float) Math.sin(time * 0.55f + sector * 0.67f + seed * 0.0003f) * 0.012f);
            return Math.max(0.03f, currentRadii[sector] * anisotropy * organic * ripple * flickerRipple);
        }

        private float acceptanceScore(float x, float z) {
            float dx = x - originX;
            float dz = z - originZ;
            float distance = (float) Math.sqrt(dx * dx + dz * dz);
            return distance - Math.max(Math.max(maxCurrentRadius, maxTargetRadius), MIN_FOOTPRINT_RADIUS) - 0.18f;
        }

        private float wave(int sector, float time) {
            return ((float) Math.sin(time * 12.0f + sector * 0.73f + seed * 0.00091f) + 1.0f) * 0.5f;
        }

        // Per-sector flame-tongue height multiplier (~0.55 short gap .. ~1.30 tall lick).
        // A static per-sector bias gives each sector a fixed character; a faster sine adds
        // the up-and-down licking motion so tongues visibly rise and fall over time.
        private float tongueHeight(int sector, float time) {
            // Range ~0.5 .. 1.05: low, dense flames — a tight ring of small tongues rather
            // than tall sparse spikes.
            float staticBias = 0.58f + noise(sector * 89 + 41) * 0.42f;
            float lick = 0.80f + 0.34f * (float) Math.sin(time * 4.6f + sector * 1.7f + seed * 0.0007f);
            return staticBias * lick;
        }

        private float noise(int salt) {
            return random01(seed ^ salt ^ (int) id * 0x632be5ab);
        }

        private static float approach(float value, float target, float step) {
            if (value < target) {
                return Math.min(target, value + step);
            }
            // Relax inward at a moderate rate (was 0.18) so the visible contour isn't a one-way
            // latch that re-freezes the asymmetry the upstream de-latching is trying to smooth.
            return Math.max(target, value - step * 0.5f);
        }
    }
}
