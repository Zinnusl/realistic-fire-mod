package dev.zinnusl.realisticfire.client;

import dev.zinnusl.realisticfire.nativebridge.RealisticFireNativeSolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.Map;

public final class ClientFireParticles {
    private static final long VISUAL_TTL_NANOS = 5_000_000_000L;
    private static final int MAX_PARTICLES_PER_TICK = 48;

    private ClientFireParticles() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ClientFireParticles::onClientTick);
        ClientFireRenderer.register();
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.isPaused()) {
            return;
        }

        long now = System.nanoTime();
        String dimension = level.dimension().location().toString();
        int emitted = 0;
        for (Map.Entry<ClientFireVisuals.Key, ClientFireVisuals.TileVisuals> entry : ClientFireVisuals.snapshot().entrySet()) {
            ClientFireVisuals.TileVisuals tile = entry.getValue();
            if (now - tile.updatedNanos() > VISUAL_TTL_NANOS) {
                ClientFireVisuals.remove(entry.getKey(), tile);
                continue;
            }
            if (!entry.getKey().dimension().equals(dimension)) {
                continue;
            }
            emitted += emitParticles(level, tile.records(), MAX_PARTICLES_PER_TICK - emitted);
            if (emitted >= MAX_PARTICLES_PER_TICK) {
                return;
            }
        }
    }

    private static int emitParticles(ClientLevel level, float[] records, int budget) {
        int emitted = 0;
        for (int base = 0; base + RealisticFireNativeSolver.VISUAL_RECORD_FLOATS <= records.length && emitted < budget; base += RealisticFireNativeSolver.VISUAL_RECORD_FLOATS) {
            double x = records[base];
            double y = records[base + 1];
            double z = records[base + 2];
            float temperature = records[base + 3];
            float flame = records[base + 4];
            float smoke = records[base + 5];
            if (flame > 0.18f && level.random.nextFloat() < Math.min(0.28f, flame * 0.12f)) {
                level.addParticle(
                        temperature > 950.0f ? ParticleTypes.FLAME : ParticleTypes.SMALL_FLAME,
                        x + randomOffset(level, 0.35),
                        y + randomOffset(level, 0.25),
                        z + randomOffset(level, 0.35),
                        randomOffset(level, 0.015),
                        0.025 + flame * 0.018,
                        randomOffset(level, 0.015));
                emitted++;
            }
            if (emitted < budget && smoke > 0.03f && level.random.nextFloat() < Math.min(0.45f, smoke * 0.12f)) {
                level.addParticle(
                        ParticleTypes.SMOKE,
                        x + randomOffset(level, 0.4),
                        y + 0.2 + randomOffset(level, 0.25),
                        z + randomOffset(level, 0.4),
                        randomOffset(level, 0.01),
                        0.02 + smoke * 0.006,
                        randomOffset(level, 0.01));
                emitted++;
            }
        }
        return emitted;
    }

    private static double randomOffset(ClientLevel level, double scale) {
        return (level.random.nextDouble() - 0.5) * scale;
    }
}
