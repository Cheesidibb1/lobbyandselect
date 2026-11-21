package com.lobbyandselect.client;

import com.lobbyandselect.net.OpenSpawnMapPayload;
import net.minecraft.client.Minecraft;

/** Client-only entry points, kept apart so a dedicated server never loads client classes. */
public final class ClientHooks {
    private ClientHooks() {}

    public static void openSpawnMap(OpenSpawnMapPayload payload) {
        Minecraft.getInstance().setScreen(new SpawnMapScreen(payload));
    }
}
