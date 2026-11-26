package com.lobbyandselect;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Expansion and conquest on top of Open Parties and Claims. Players grow their territory chunk by
 * chunk next to land they already own, and take enemy chunks by holding them for a while. Claims
 * live in OPAC, so its claim limits, parties and Xaero's map overlay all keep working. Land inside
 * a locked area (see {@link LockedAreas}) can't be expanded into or conquered.
 */
public final class Territory {
    private record Capture(ResourceLocation dimension, ChunkPos chunk, UUID defender, int startTick) {}

    private static final Map<UUID, Capture> CAPTURES = new HashMap<>();
    private static int ticks;

    private Territory() {}

    public static void register(IEventBus bus) {
        bus.addListener(Territory::onTick);
    }

    private static String precheck(ServerPlayer player) {
        if (!LobbyConfig.TERRITORY_ENABLED.get()) return "Territory expansion is disabled.";
        if (!OpenPacBridge.available()) return "Open Parties and Claims is not available on this server.";
        if (LobbyWorld.isLobby(player.level())) return "You can't claim land in the lobby.";
        return null;
    }

    private static boolean adjacentToOwn(ServerPlayer player, ChunkPos pos) {
        ResourceLocation dim = player.level().dimension().location();
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] o : d) {
            UUID n = OpenPacBridge.owner(player.server, dim, pos.x + o[0], pos.z + o[1]);
            if (n != null && OpenPacBridge.sameParty(player.server, n, player.getUUID())) return true;
        }
        return false;
    }

    /** Returns an error message or null on success. */
    public static String expand(ServerPlayer player) {
        String err = precheck(player);
        if (err != null) return err;
        ChunkPos pos = player.chunkPosition();
        ResourceLocation dim = player.level().dimension().location();
        if (LockedAreas.chunkLocked(player, pos)) return "This land is in a locked area - you can't expand here.";
        UUID owner = OpenPacBridge.owner(player.server, dim, pos.x, pos.z);
        if (owner != null) {
            return OpenPacBridge.sameParty(player.server, owner, player.getUUID())
                    ? "You already own this chunk." : "Someone else owns this chunk - use /lobby territory conquer.";
        }
        if (LobbyConfig.TERRITORY_REQUIRE_ADJACENT.get() && !adjacentToOwn(player, pos)) {
            return "You can only expand next to land you already own.";
        }
        OpenPacBridge.claim(player.server, dim, player.getUUID(), pos.x, pos.z);
        if (!player.getUUID().equals(OpenPacBridge.owner(player.server, dim, pos.x, pos.z))) {
            return "The claim was refused (claim limit reached or protected land).";
        }
        player.sendSystemMessage(Component.literal("Claimed chunk " + pos.x + ", " + pos.z + ".").withStyle(ChatFormatting.GREEN));
        return null;
    }

    public static String startConquest(ServerPlayer player) {
        String err = precheck(player);
        if (err != null) return err;
        ChunkPos pos = player.chunkPosition();
        ResourceLocation dim = player.level().dimension().location();
        if (LockedAreas.chunkLocked(player, pos)) return "This land is in a locked area - it can't be conquered.";
        UUID owner = OpenPacBridge.owner(player.server, dim, pos.x, pos.z);
        if (owner == null) return "Nobody owns this chunk - use /lobby territory expand.";
        if (OpenPacBridge.sameParty(player.server, owner, player.getUUID())) return "That's your own land.";
        if (LobbyConfig.TERRITORY_REQUIRE_ADJACENT.get() && !adjacentToOwn(player, pos)) {
            return "You can only conquer chunks that border land you own.";
        }
        CAPTURES.put(player.getUUID(), new Capture(dim, pos, owner, ticks));
        player.sendSystemMessage(Component.literal("Capturing... hold this chunk for " + LobbyConfig.CAPTURE_SECONDS.get()
                + "s with no defenders present.").withStyle(ChatFormatting.GOLD));
        return null;
    }

    public static void cancel(UUID id) {
        CAPTURES.remove(id);
    }

    private static void onTick(ServerTickEvent.Post event) {
        ticks++;
        if (CAPTURES.isEmpty() || ticks % 10 != 0) return;
        MinecraftServer server = event.getServer();
        Iterator<Map.Entry<UUID, Capture>> it = CAPTURES.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Capture c = e.getValue();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null || !p.isAlive() || !p.level().dimension().location().equals(c.dimension())
                    || !p.chunkPosition().equals(c.chunk())) {
                if (p != null) p.sendSystemMessage(Component.literal("Capture cancelled.").withStyle(ChatFormatting.RED));
                it.remove();
                continue;
            }
            if (!c.defender().equals(OpenPacBridge.owner(server, c.dimension(), c.chunk().x, c.chunk().z))) {
                it.remove(); // changed hands meanwhile
                continue;
            }
            if (defendersPresent(server, p, c)) {
                // Contested: progress resets while a defender stands in the chunk.
                CAPTURES.put(e.getKey(), new Capture(c.dimension(), c.chunk(), c.defender(), ticks));
                p.displayClientMessage(Component.literal("Contested by a defender!").withStyle(ChatFormatting.RED), true);
                continue;
            }
            int total = LobbyConfig.CAPTURE_SECONDS.get() * 20, done = ticks - c.startTick();
            if (done < total) {
                p.displayClientMessage(Component.literal("Capturing: " + (total - done) / 20 + "s").withStyle(ChatFormatting.GOLD), true);
                continue;
            }
            it.remove();
            OpenPacBridge.unclaim(server, c.dimension(), c.defender(), c.chunk().x, c.chunk().z);
            OpenPacBridge.claim(server, c.dimension(), p.getUUID(), c.chunk().x, c.chunk().z);
            boolean won = p.getUUID().equals(OpenPacBridge.owner(server, c.dimension(), c.chunk().x, c.chunk().z));
            p.sendSystemMessage(Component.literal(won ? "Chunk conquered!" : "The defender lost the chunk but your claim was refused (claim limit?).")
                    .withStyle(won ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
            ServerPlayer old = server.getPlayerList().getPlayer(c.defender());
            if (old != null) old.sendSystemMessage(Component.literal(p.getGameProfile().getName() + " conquered your chunk "
                    + c.chunk().x + ", " + c.chunk().z + "!").withStyle(ChatFormatting.RED));
        }
    }

    private static boolean defendersPresent(MinecraftServer server, ServerPlayer attacker, Capture c) {
        for (ServerPlayer o : server.getPlayerList().getPlayers()) {
            if (o == attacker || !o.isAlive() || !o.level().dimension().location().equals(c.dimension())) continue;
            if (o.chunkPosition().equals(c.chunk()) && OpenPacBridge.sameParty(server, c.defender(), o.getUUID())) return true;
        }
        return false;
    }
}
