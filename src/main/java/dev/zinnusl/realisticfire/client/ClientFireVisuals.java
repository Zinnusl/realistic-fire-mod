package dev.zinnusl.realisticfire.client;

import dev.zinnusl.realisticfire.network.ClientboundFireVisualDeltaPayload;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientFireVisuals {
    private static final Map<Key, TileVisuals> VISUALS = new ConcurrentHashMap<>();

    private ClientFireVisuals() {
    }

    public static void apply(ClientboundFireVisualDeltaPayload payload) {
        Key key = new Key(payload.dimension(), payload.sectionX(), payload.sectionY(), payload.sectionZ());
        VISUALS.compute(key, (ignored, existing) -> {
            if (existing != null && payload.sequence() <= existing.sequence()) {
                return existing;
            }
            if (payload.visuals().length == 0) {
                return null;
            }
            return new TileVisuals(payload.sequence(), payload.visuals(), System.nanoTime());
        });
    }

    public static int trackedTileCount() {
        return VISUALS.size();
    }

    public static Map<Key, TileVisuals> snapshot() {
        return Map.copyOf(VISUALS);
    }

    public static void remove(Key key, TileVisuals visuals) {
        VISUALS.remove(key, visuals);
    }

    public record Key(String dimension, int sectionX, int sectionY, int sectionZ) {
    }

    public record TileVisuals(int sequence, float[] records, long updatedNanos) {
    }
}
