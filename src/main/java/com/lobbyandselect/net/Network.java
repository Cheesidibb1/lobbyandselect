package com.lobbyandselect.net;

import com.lobbyandselect.LobbyManager;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class Network {
    private Network() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        // Optional, so players without the mod can still connect and use the /lobby spawn command instead.
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToClient(OpenSpawnMapPayload.TYPE, OpenSpawnMapPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> com.lobbyandselect.client.ClientHooks.openSpawnMap(payload)));
        registrar.playToServer(ChooseSpawnPayload.TYPE, ChooseSpawnPayload.CODEC, Network::onChooseSpawn);
    }

    private static void onChooseSpawn(ChooseSpawnPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                LobbyManager.chooseSpawn(player, payload.latitude(), payload.longitude());
            }
        });
    }

    /** True if this player's client has the mod and can show the map. */
    public static boolean canOpenMap(ServerPlayer player) {
        return player.connection.hasChannel(OpenSpawnMapPayload.TYPE);
    }

    public static void openMap(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, OpenSpawnMapPayload.fromConfig(player));
    }
}
