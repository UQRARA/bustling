package dev.bustling;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Server-authoritative catalog, negotiated independently of layout synchronization. */
public final class SkinPackNetwork {
    private static List<SkinPackFiles.Pack> serverPacks = List.of();

    private SkinPackNetwork() {}

    public static void init(IEventBus modBus) {
        modBus.addListener(SkinPackNetwork::register);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, YsmModelImports::prepare);
        NeoForge.EVENT_BUS.addListener(SkinPackNetwork::load);
        NeoForge.EVENT_BUS.addListener(SkinPackNetwork::clear);
    }

    private static void load(ServerStartedEvent event) {
        serverPacks = SkinPackFiles.load();
    }

    private static void clear(ServerStoppedEvent event) {
        serverPacks = List.of();
    }

    private static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playToClient(Packet.TYPE, Packet.CODEC, (packet, context) ->
                context.enqueueWork(() -> ClientSkinPacks.receive(packet.pack())));
    }

    static void sendTo(ServerPlayer player) {
        if (player.connection == null || !player.connection.hasChannel(Packet.TYPE)) return;
        for (SkinPackFiles.Pack pack : serverPacks) {
            PacketDistributor.sendToPlayer(player, new Packet(pack));
        }
    }

    record Packet(SkinPackFiles.Pack pack) implements CustomPacketPayload {
        static final Type<Packet> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("bustling", "skin_pack"));
        static final StreamCodec<FriendlyByteBuf, Packet> CODEC = StreamCodec.of(Packet::encode, Packet::decode);

        private static void encode(FriendlyByteBuf buf, Packet packet) {
            SkinPackFiles.Pack pack = packet.pack();
            buf.writeUtf(pack.id(), 48);
            buf.writeUtf(pack.name(), SkinPackFiles.MAX_NAME);
            buf.writeUtf(pack.description(), SkinPackFiles.MAX_DESCRIPTION);
            buf.writeVarInt(pack.skins().size());
            for (SkinPackFiles.Skin skin : pack.skins()) {
                buf.writeBoolean(skin.slim());
                buf.writeVarInt(skin.weight());
                for (int pixel : skin.pixels()) buf.writeInt(pixel);
            }
        }

        private static Packet decode(FriendlyByteBuf buf) {
            String id = buf.readUtf(48);
            String name = buf.readUtf(SkinPackFiles.MAX_NAME);
            String description = buf.readUtf(SkinPackFiles.MAX_DESCRIPTION);
            int count = buf.readVarInt();
            if (!SkinPackFiles.ID.matcher(id).matches() || !SkinPackFiles.validName(name)
                    || !SkinPackFiles.validDescription(description) || count < 1 || count > SkinPackFiles.MAX_PACK_SKINS
                    || buf.readableBytes() < count * (2 + SkinPackFiles.PIXELS * Integer.BYTES)) {
                throw new DecoderException("Invalid Bustling skin pack");
            }
            List<SkinPackFiles.Skin> skins = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                boolean slim = buf.readBoolean();
                int weight = buf.readVarInt();
                if (weight < 1 || weight > SkinPackFiles.MAX_WEIGHT) throw new DecoderException("Invalid skin weight");
                int[] pixels = new int[SkinPackFiles.PIXELS];
                for (int pixel = 0; pixel < pixels.length; pixel++) pixels[pixel] = buf.readInt();
                skins.add(new SkinPackFiles.Skin(slim, weight, pixels));
            }
            return new Packet(new SkinPackFiles.Pack(id, name, description, List.copyOf(skins)));
        }

        @Override public Type<Packet> type() {
            return TYPE;
        }
    }
}
