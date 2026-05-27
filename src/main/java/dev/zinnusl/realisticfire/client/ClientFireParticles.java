package dev.zinnusl.realisticfire.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.Map;

public final class ClientFireParticles {
    private static final long VISUAL_TTL_NANOS = 5_000_000_000L;

    private ClientFireParticles() {
    }

    public static void register() {
        register(null);
    }

    public static void register(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(ClientFireParticles::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientFireParticles::onClientLoggingIn);
        NeoForge.EVENT_BUS.addListener(ClientFireParticles::onClientLoggingOut);
        if (modEventBus != null) {
            ClientFireShaders.register(modEventBus);
            ClientFireDistortion.register(modEventBus);
        }
        ClientFireRenderer.register();
        ClientFireSounds.register();
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.isPaused()) {
            return;
        }

        long now = System.nanoTime();
        String dimension = level.dimension().location().toString();
        ClientFireVisuals.clearIfDimensionChanged(dimension);
        for (Map.Entry<ClientFireVisuals.Key, ClientFireVisuals.TileVisuals> entry : ClientFireVisuals.snapshot().entrySet()) {
            ClientFireVisuals.TileVisuals tile = entry.getValue();
            if (now - tile.updatedNanos() > VISUAL_TTL_NANOS) {
                ClientFireVisuals.remove(entry.getKey(), tile);
                continue;
            }
            // Fire visuals are renderer-owned. Emitting vanilla particles directly from raw solver
            // records creates a second, unsynchronized fire front: particles can appear where the
            // exact burn-ledger renderer has no legal boundary. Keep this tick hook only for stale
            // visual cleanup and let ClientFireRenderer render the front, smoke, and embers.
        }
    }

    public static void onClientLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (event.getPlayer() != null) {
            ClientFireVisuals.clear();
            ClientFireRenderer.clear();
        }
    }

    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (event.getPlayer() != null) {
            ClientFireVisuals.clear();
            ClientFireRenderer.clear();
        }
    }

}
