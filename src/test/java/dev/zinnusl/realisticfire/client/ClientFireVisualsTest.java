package dev.zinnusl.realisticfire.client;

import dev.zinnusl.realisticfire.network.ClientboundFireVisualDeltaPayload;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientFireVisualsTest {
    @AfterEach
    void clearVisuals() {
        ClientFireVisuals.clear();
    }

    @Test
    void currentDimensionPayloadReceivedBeforeFirstTickIsPreserved() {
        ClientFireVisuals.apply(payload("minecraft:overworld", 1, 0, 0, 0));

        ClientFireVisuals.clearIfDimensionChanged("minecraft:overworld");

        assertEquals(1, ClientFireVisuals.trackedTileCount());
    }

    @Test
    void dimensionChangeKeepsTargetDimensionAndDropsStaleOnes() {
        ClientFireVisuals.apply(payload("minecraft:overworld", 1, 0, 0, 0));
        ClientFireVisuals.apply(payload("minecraft:the_nether", 2, 0, 0, 0));

        ClientFireVisuals.clearIfDimensionChanged("minecraft:the_nether");

        assertEquals(1, ClientFireVisuals.trackedTileCount());
        assertTrue(ClientFireVisuals.snapshot().keySet().stream()
                .allMatch(key -> key.dimension().equals("minecraft:the_nether")));
    }

    @Test
    void nullDimensionStillClearsEverything() {
        ClientFireVisuals.apply(payload("minecraft:overworld", 1, 0, 0, 0));

        ClientFireVisuals.clearIfDimensionChanged(null);

        assertEquals(0, ClientFireVisuals.trackedTileCount());
    }

    private static ClientboundFireVisualDeltaPayload payload(String dimension, int sequence, int sectionX, int sectionY, int sectionZ) {
        return new ClientboundFireVisualDeltaPayload(dimension, sequence, sectionX, sectionY, sectionZ, visualRecord());
    }

    private static float[] visualRecord() {
        return new float[] {
                0.5f, 64.0f, 0.5f,
                720.0f,
                0.45f,
                0.12f,
                0.0f,
                0.55f
        };
    }
}
