package dev.zinnusl.realisticfire.network;

import dev.zinnusl.realisticfire.RealisticFire;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jetbrains.annotations.NotNull;

public record ClientboundFireVisualDeltaPayload(
        String dimension,
        int sequence,
        int sectionX,
        int sectionY,
        int sectionZ,
        float[] visuals) implements CustomPacketPayload {
    private static final int MAX_VISUAL_FLOATS = 32768 * 8;
    public static final Type<ClientboundFireVisualDeltaPayload> TYPE =
            new Type<>(RealisticFire.id("fire_visual_delta"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundFireVisualDeltaPayload> CODEC =
            StreamCodec.of(ClientboundFireVisualDeltaPayload::write, ClientboundFireVisualDeltaPayload::read);

    public ClientboundFireVisualDeltaPayload {
        visuals = visuals == null ? new float[0] : visuals;
        validateVisualLength(visuals.length);
    }

    private static void write(RegistryFriendlyByteBuf buf, ClientboundFireVisualDeltaPayload payload) {
        buf.writeUtf(payload.dimension);
        buf.writeVarInt(payload.sequence);
        buf.writeInt(payload.sectionX);
        buf.writeInt(payload.sectionY);
        buf.writeInt(payload.sectionZ);
        buf.writeVarInt(payload.visuals.length);
        for (float visual : payload.visuals) {
            buf.writeFloat(visual);
        }
    }

    private static ClientboundFireVisualDeltaPayload read(RegistryFriendlyByteBuf buf) {
        String dimension = buf.readUtf();
        int sequence = buf.readVarInt();
        int sectionX = buf.readInt();
        int sectionY = buf.readInt();
        int sectionZ = buf.readInt();
        int length = buf.readVarInt();
        validateVisualLength(length);
        float[] visuals = new float[length];
        for (int i = 0; i < length; i++) {
            visuals[i] = buf.readFloat();
        }
        return new ClientboundFireVisualDeltaPayload(dimension, sequence, sectionX, sectionY, sectionZ, visuals);
    }

    private static void validateVisualLength(int length) {
        if (length < 0 || length > MAX_VISUAL_FLOATS || length % 8 != 0) {
            throw new IllegalArgumentException("Invalid realistic fire visual payload length: " + length);
        }
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
