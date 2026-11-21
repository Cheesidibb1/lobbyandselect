package com.lobbyandselect;

import com.lobbyandselect.net.Network;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Lobby and Select: every player who connects lands in a lobby dimension first. If the main
 * (Earth) world is full they wait in a queue and can play minigames; once let in, first-time
 * players pick a permanent spawn point on the Earth map, and returning players go straight back.
 */
@Mod(LobbyAndSelect.MOD_ID)
public final class LobbyAndSelect {
    public static final String MOD_ID = "lobbyandselect";
    public static final Logger LOGGER = LogUtils.getLogger();

    public LobbyAndSelect(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, LobbyConfig.SPEC);
        modBus.addListener(Network::register);
        LobbyManager.register(NeoForge.EVENT_BUS);
        NeoForge.EVENT_BUS.addListener(LobbyCommands::register);
    }
}
