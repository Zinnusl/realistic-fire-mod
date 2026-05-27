package dev.zinnusl.realisticfire.simulation.persistence;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

public final class FireSavedData extends SavedData {
    private static final String FILE_ID = "realisticfire_state";
    private static final int VERSION = 1;

    private byte[] snapshot = new byte[0];
    private long[] activeSections = new long[0];
    private String materialSignature = "empty";

    public static FireSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(FireSavedData::new, (tag, provider) -> load(tag), null),
                FILE_ID);
    }

    private static FireSavedData load(CompoundTag tag) {
        FireSavedData data = new FireSavedData();
        if (tag.getInt("version") == VERSION) {
            data.snapshot = tag.getByteArray("snapshot");
            data.activeSections = tag.getLongArray("active_sections");
            data.materialSignature = tag.getString("material_signature");
        }
        return data;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider provider) {
        tag.putInt("version", VERSION);
        tag.putByteArray("snapshot", snapshot);
        tag.putLongArray("active_sections", activeSections);
        tag.putString("material_signature", materialSignature);
        return tag;
    }

    public byte[] snapshot() {
        return snapshot;
    }

    public long[] activeSections() {
        return activeSections;
    }

    public String materialSignature() {
        return materialSignature;
    }

    public void update(byte[] snapshot, long[] activeSections, String materialSignature) {
        this.snapshot = snapshot == null ? new byte[0] : snapshot;
        this.activeSections = activeSections == null ? new long[0] : activeSections;
        this.materialSignature = materialSignature == null ? "empty" : materialSignature;
        setDirty();
    }
}
