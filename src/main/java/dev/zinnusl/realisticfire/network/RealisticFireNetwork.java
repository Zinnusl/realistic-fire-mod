package dev.zinnusl.realisticfire.network;

import dev.zinnusl.realisticfire.client.ClientFireVisuals;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class RealisticFireNetwork {
    private static final String PROTOCOL = "1";

    private RealisticFireNetwork() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(RealisticFireNetwork::registerPayloads);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL);
        registrar.playToClient(
                ClientboundFireVisualDeltaPayload.TYPE,
                ClientboundFireVisualDeltaPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientFireVisuals.apply(payload)));
    }
}
