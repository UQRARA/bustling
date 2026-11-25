package dev.bustling;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public record DiffuserPowerPayload(String deviceId, boolean powered) implements CustomPacketPayload {
    public static final Type<DiffuserPowerPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("bustling", "diffuser_power"));
    public static final StreamCodec<FriendlyByteBuf, DiffuserPowerPayload> CODEC = StreamCodec.of(
            (buffer, packet) -> { buffer.writeUtf(packet.deviceId()); buffer.writeBoolean(packet.powered()); },
            buffer -> new DiffuserPowerPayload(buffer.readUtf(), buffer.readBoolean()));

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playToClient(TYPE, CODEC, (payload, context) ->
                context.enqueueWork(() -> ClientCrowdStore.applyDevicePower(payload.deviceId(), payload.powered())));
    }

    @Override public Type<DiffuserPowerPayload> type() { return TYPE; }
}
