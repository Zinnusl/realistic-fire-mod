package dev.zinnusl.realisticfire.client;

import dev.zinnusl.realisticfire.network.ClientboundFireVisualDeltaPayload;
import dev.zinnusl.realisticfire.nativebridge.RealisticFireNativeSolver;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientFireVisuals {
    private static final long VISUAL_RECORD_TTL_NANOS = 3_000_000_000L;
    private static final int MAX_CACHED_RECORDS_PER_TILE = 32768;
    private static final Map<Key, TileVisuals> VISUALS = new ConcurrentHashMap<>();
    private static String previousDimension;

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
            long now = System.nanoTime();
            return merge(payload.sequence(), payload.visuals(), existing, now);
        });
    }

    private static TileVisuals merge(int sequence, float[] incoming, TileVisuals existing, long now) {
        int recordSize = RealisticFireNativeSolver.VISUAL_RECORD_FLOATS;
        int incomingRecords = incoming.length / recordSize;
        int oldRecords = existing == null ? 0 : existing.records().length / recordSize;
        int maxRecords = Math.min(MAX_CACHED_RECORDS_PER_TILE, incomingRecords + oldRecords);
        float[] merged = new float[maxRecords * recordSize];
        long[] mergedNanos = new long[maxRecords];
        Map<Long, Integer> offsets = new HashMap<>(Math.max(16, maxRecords * 2));
        int count = 0;

        for (int base = 0; base + recordSize <= incoming.length && count < maxRecords; base += recordSize) {
            long key = visualRecordKey(incoming, base);
            Integer previousOffset = offsets.get(key);
            if (previousOffset != null) {
                System.arraycopy(incoming, base, merged, previousOffset, recordSize);
                mergedNanos[previousOffset / recordSize] = now;
                continue;
            }
            int offset = count * recordSize;
            System.arraycopy(incoming, base, merged, offset, recordSize);
            mergedNanos[count] = now;
            offsets.put(key, offset);
            count++;
        }

        if (existing != null) {
            float[] oldRecordsArray = existing.records();
            long[] oldNanos = existing.recordNanos();
            for (int oldIndex = 0, base = 0;
                 oldIndex < oldRecords && base + recordSize <= oldRecordsArray.length && count < maxRecords;
                 oldIndex++, base += recordSize) {
                if (oldIndex >= oldNanos.length || now - oldNanos[oldIndex] > VISUAL_RECORD_TTL_NANOS) {
                    continue;
                }
                long key = visualRecordKey(oldRecordsArray, base);
                if (offsets.containsKey(key)) {
                    continue;
                }
                int offset = count * recordSize;
                System.arraycopy(oldRecordsArray, base, merged, offset, recordSize);
                mergedNanos[count] = oldNanos[oldIndex];
                offsets.put(key, offset);
                count++;
            }
        }

        float[] records = count == maxRecords ? merged : Arrays.copyOf(merged, count * recordSize);
        long[] recordNanos = count == maxRecords ? mergedNanos : Arrays.copyOf(mergedNanos, count);
        return new TileVisuals(sequence, records, recordNanos, now);
    }

    private static long visualRecordKey(float[] records, int base) {
        long x = Float.floatToIntBits(records[base]);
        long y = Float.floatToIntBits(records[base + 1]);
        long z = Float.floatToIntBits(records[base + 2]);
        long hash = x * 73428767L ^ y * 912931L ^ z * 19349663L;
        return hash ^ (hash >>> 32);
    }

    public static int trackedTileCount() {
        return VISUALS.size();
    }

    public static Map<Key, TileVisuals> snapshot() {
        return Map.copyOf(VISUALS);
    }

    public static void clear() {
        VISUALS.clear();
        previousDimension = null;
    }

    public static void clearDimension(String dimension) {
        if (dimension == null) {
            clear();
            return;
        }
        VISUALS.entrySet().removeIf(entry -> dimension.equals(entry.getKey().dimension()));
    }

    public static void clearIfDimensionChanged(String dimension) {
        if (dimension == null) {
            clear();
            return;
        }
        if (!Objects.equals(previousDimension, dimension)) {
            clearOtherDimensions(dimension);
            previousDimension = dimension;
        }
    }

    private static void clearOtherDimensions(String dimension) {
        if (dimension == null) {
            clear();
            return;
        }
        VISUALS.entrySet().removeIf(entry -> !dimension.equals(entry.getKey().dimension()));
    }

    public static void remove(Key key, TileVisuals visuals) {
        VISUALS.remove(key, visuals);
    }

    public record Key(String dimension, int sectionX, int sectionY, int sectionZ) {
    }

    public record TileVisuals(int sequence, float[] records, long[] recordNanos, long updatedNanos) {
    }
}
