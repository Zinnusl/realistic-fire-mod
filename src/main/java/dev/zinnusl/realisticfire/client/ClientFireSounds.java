package dev.zinnusl.realisticfire.client;

import dev.zinnusl.realisticfire.nativebridge.RealisticFireNativeSolver;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.Map;

public final class ClientFireSounds {
    private static final long CRACKLE_INTERVAL_NANOS = 240_000_000L;
    private static final long AMBIENT_INTERVAL_NANOS = 900_000_000L;
    private static final double MAX_DISTANCE_SQ = 32.0 * 32.0;
    private static final float MIN_FLAME_FOR_SOUND = 0.22f;

    private static long nextCrackleNanos;
    private static long nextAmbientNanos;

    private ClientFireSounds() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ClientFireSounds::onClientTick);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null || mc.isPaused()) {
            return;
        }

        long now = System.nanoTime();
        boolean wantCrackle = now >= nextCrackleNanos;
        boolean wantAmbient = now >= nextAmbientNanos;
        if (!wantCrackle && !wantAmbient) {
            return;
        }

        Sample picked = reservoirSampleHotCell(level, player.getX(), player.getY(), player.getZ());
        if (picked == null) {
            return;
        }

        if (wantCrackle) {
            nextCrackleNanos = now + CRACKLE_INTERVAL_NANOS + level.random.nextInt(260_000_000);
            float volume = Mth.clamp(picked.flame * 0.55f + 0.10f, 0.10f, 0.70f);
            float pitch = 0.80f + level.random.nextFloat() * 0.35f;
            level.playLocalSound(
                    picked.x, picked.y, picked.z,
                    SoundEvents.CAMPFIRE_CRACKLE,
                    SoundSource.BLOCKS,
                    volume, pitch, false);
        }
        if (wantAmbient) {
            nextAmbientNanos = now + AMBIENT_INTERVAL_NANOS + level.random.nextInt(650_000_000);
            float volume = Mth.clamp(picked.flame * 0.35f, 0.04f, 0.45f);
            float pitch = 0.88f + level.random.nextFloat() * 0.18f;
            level.playLocalSound(
                    picked.x, picked.y, picked.z,
                    SoundEvents.FIRE_AMBIENT,
                    SoundSource.BLOCKS,
                    volume, pitch, false);
        }
    }

    /**
     * Reservoir-sample a single hot fire cell within range of the player. Each candidate
     * has an equal chance of being chosen regardless of total count, in one pass.
     */
    private static Sample reservoirSampleHotCell(ClientLevel level, double px, double py, double pz) {
        String dimension = level.dimension().location().toString();
        Map<ClientFireVisuals.Key, ClientFireVisuals.TileVisuals> snapshot = ClientFireVisuals.snapshot();
        if (snapshot.isEmpty()) {
            return null;
        }
        Sample picked = null;
        int seen = 0;
        for (Map.Entry<ClientFireVisuals.Key, ClientFireVisuals.TileVisuals> entry : snapshot.entrySet()) {
            if (!entry.getKey().dimension().equals(dimension)) {
                continue;
            }
            float[] records = entry.getValue().records();
            for (int base = 0; base + RealisticFireNativeSolver.VISUAL_RECORD_FLOATS <= records.length;
                 base += RealisticFireNativeSolver.VISUAL_RECORD_FLOATS) {
                float flame = records[base + 4];
                if (flame < MIN_FLAME_FOR_SOUND) {
                    continue;
                }
                double dx = records[base] - px;
                double dy = records[base + 1] - py;
                double dz = records[base + 2] - pz;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq > MAX_DISTANCE_SQ) {
                    continue;
                }
                seen++;
                if (level.random.nextInt(seen) == 0) {
                    picked = new Sample(records[base], records[base + 1], records[base + 2], flame);
                }
            }
        }
        return picked;
    }

    private record Sample(double x, double y, double z, float flame) {
    }
}
