package dev.bustling;

import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

public final class BustlingNet {
    private BustlingNet() {}

    private static boolean canSend(ServerPlayer player) {
        return player.connection != null && player.connection.hasChannel(SyncPayload.TYPE);
    }

    public static void sendTo(ServerPlayer player) {
        if (canSend(player)) PacketDistributor.sendToPlayer(player, new SyncPayload(ServerCrowdStore.networkJson(0)));
        SkinPackNetwork.sendTo(player);
    }

    static void acknowledge(ServerPlayer player, long sequence, boolean accepted) {
        if (canSend(player)) PacketDistributor.sendToPlayer(player, new SyncPayload(ServerCrowdStore.networkJson(sequence, accepted)));
    }

    public static void broadcast() {
        broadcastExcept(null);
    }

    static void broadcastExcept(ServerPlayer excluded) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        SyncPayload packet = null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == excluded || !canSend(player)) continue;
            if (packet == null) packet = new SyncPayload(ServerCrowdStore.networkJson(0));
            PacketDistributor.sendToPlayer(player, packet);
        }
    }

    public static void broadcastDevicePower(MinecraftServer server, Map<String, Boolean> changes) {
        SyncPayload fallback = null;
        DiffuserPowerPayload[] packets = null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!canSend(player)) continue;
            if (player.connection.hasChannel(DiffuserPowerPayload.TYPE)) {
                if (packets == null) {
                    packets = changes.entrySet().stream()
                            .map(change -> new DiffuserPowerPayload(change.getKey(), change.getValue()))
                            .toArray(DiffuserPowerPayload[]::new);
                }
                for (DiffuserPowerPayload packet : packets) PacketDistributor.sendToPlayer(player, packet);
            } else {
                if (fallback == null) fallback = new SyncPayload(ServerCrowdStore.networkJson(0));
                PacketDistributor.sendToPlayer(player, fallback);
            }
        }
    }
}
