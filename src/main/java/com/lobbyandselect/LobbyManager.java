package com.lobbyandselect;

import com.lobbyandselect.game.Parkour;
import com.lobbyandselect.game.Spleef;
import com.lobbyandselect.game.Trivia;
import com.lobbyandselect.net.Network;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The join flow: lobby on connect, a queue while the main world is full, then either a spawn pick
 * (first time) or straight back to where the player left off (returning).
 */
public final class LobbyManager {
    public enum Stage {
        /** Waiting in the lobby for a slot in the main world. */
        QUEUED,
        /** Has a slot and is picking a spawn point on the map. */
        CHOOSING,
        /** Has a slot: in the main world, or on the way there. */
        PLAYING
    }

    private static final class Session {
        Stage stage = Stage.QUEUED;
        long stageSince;
        long travelStarted = -1;
        int travelAttempts;
        @Nullable ServerBossEvent bar;
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Deque<UUID> QUEUE = new ArrayDeque<>();
    private static final Set<UUID> PENDING_LOBBY_MOVES = new HashSet<>();
    private static long ticks;

    private LobbyManager() {}

    public static void register(IEventBus bus) {
        bus.addListener(EventPriority.LOWEST, LobbyManager::onLogin);
        bus.addListener(LobbyManager::onLogout);
        bus.addListener(LobbyManager::onRespawn);
        bus.addListener(LobbyManager::onServerTick);
        bus.addListener(LobbyManager::onPlayerTick);
        bus.addListener(LobbyManager::onServerStarted);
        bus.addListener(LobbyManager::onServerStopped);
        bus.addListener(LobbyManager::onDamage);
        bus.addListener(LobbyManager::onBreak);
        bus.addListener(LobbyManager::onPlace);
        bus.addListener(LobbyManager::onToss);
        bus.addListener(LobbyManager::onChat);
    }

    // Lifecycle -------------------------------------------------------------------------------

    private static void onServerStarted(ServerStartedEvent event) {
        LobbyWorld.ensureBuilt(event.getServer());
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        SESSIONS.clear();
        QUEUE.clear();
        PENDING_LOBBY_MOVES.clear();
        Spleef.reset();
        Parkour.reset();
        Trivia.reset();
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.server;
        if (LobbyWorld.level(server) == null) return; // no lobby: let them play normally
        LobbyData data = LobbyData.get(server);
        LobbyData.PlayerRecord record = data.player(player.getUUID());
        record.name = player.getGameProfile().getName();
        data.setDirty();

        Spleef.stripGear(player);
        Session session = new Session();
        session.stageSince = ticks;
        SESSIONS.put(player.getUUID(), session);

        boolean inLobby = LobbyWorld.isLobby(player.level());
        boolean bypass = LobbyConfig.OPS_BYPASS_QUEUE.get() && player.hasPermissions(2);

        // Returning players who logged out in the world: remember exactly where, to put them back.
        if (!inLobby && record.spawnChosen) {
            record.returnPoint = new LobbyData.ReturnPoint(player.level().dimension(), player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot());
        }

        // Some players never need to see the lobby: they already have a home and there is room (or they're staff).
        boolean skipLobby = !inLobby && record.spawnChosen
                && (bypass || (!LobbyConfig.RETURNING_PLAYERS_USE_LOBBY.get() && hasRoom(server)));
        if (skipLobby) {
            setStage(player, session, Stage.PLAYING);
            record.joinedBefore = true;
            return;
        }

        if (!inLobby) {
            record.gameMode = player.gameMode.getGameModeForPlayer();
            PENDING_LOBBY_MOVES.add(player.getUUID());
        }
        if (bypass) QUEUE.addFirst(player.getUUID());
        else QUEUE.addLast(player.getUUID());

        player.sendSystemMessage(Component.literal(record.joinedBefore
                        ? "Welcome back, " + record.name + "! You're in the lobby."
                        : "Welcome, " + record.name + "! You're in the lobby.")
                .withStyle(ChatFormatting.GOLD));
        if (!hasRoom(server)) {
            player.sendSystemMessage(Component.literal("The world is full right now. You're in the queue - try the parkour, spleef or trivia while you wait!")
                    .withStyle(ChatFormatting.YELLOW));
        }
        updateBar(player, session);
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        UUID id = player.getUUID();
        Session session = SESSIONS.remove(id);
        QUEUE.remove(id);
        PENDING_LOBBY_MOVES.remove(id);
        if (session != null && session.bar != null) session.bar.removeAllPlayers();
        Spleef.leave(player, false);
        Parkour.leave(player);
        Spleef.stripGear(player);

        LobbyData data = LobbyData.get(player.server);
        LobbyData.PlayerRecord record = data.existing(id);
        if (record != null && record.spawnChosen && !LobbyWorld.isLobby(player.level())) {
            record.returnPoint = new LobbyData.ReturnPoint(player.level().dimension(), player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot());
            data.setDirty();
        }
    }

    private static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.isEndConquered()) return;
        if (!LobbyConfig.RESPAWN_AT_CHOSEN_SPAWN.get() || LobbyWorld.isLobby(player.level())) return;
        if (player.getRespawnPosition() != null) return; // their bed or anchor wins
        LobbyData.PlayerRecord record = LobbyData.get(player.server).existing(player.getUUID());
        if (record == null || !record.spawnChosen) return;
        EarthBridge.travel(player, record.spawnLatitude, record.spawnLongitude);
    }

    // Ticking ---------------------------------------------------------------------------------

    private static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ticks++;
        if (LobbyWorld.level(server) == null) return;

        if (!PENDING_LOBBY_MOVES.isEmpty()) {
            for (UUID id : List.copyOf(PENDING_LOBBY_MOVES)) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) sendToLobby(player);
            }
            PENDING_LOBBY_MOVES.clear();
        }

        if (ticks % 20 == 0) {
            admitFromQueue(server);
            checkChoosingAndTravel(server);
            for (UUID id : QUEUE) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                Session session = SESSIONS.get(id);
                if (player != null && session != null) updateBar(player, session);
            }
        }
        Spleef.tick(server);
        Trivia.tick(server);
    }

    private static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !LobbyWorld.isLobby(player.level())) return;
        // Nobody goes hungry in the lobby.
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5);
        if (player.tickCount % 20 == 0) enforceLobbyGameMode(player);

        Parkour.tick(player);
        Spleef.playerTick(player);

        if (player.getY() < LobbyWorld.CATCH_Y) {
            if (!Parkour.catchFall(player) && !Spleef.catchFall(player)) teleportToLobbySpawn(player);
        }
    }

    private static void admitFromQueue(MinecraftServer server) {
        while (!QUEUE.isEmpty()) {
            UUID id = QUEUE.peekFirst();
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            Session session = SESSIONS.get(id);
            if (player == null || session == null) {
                QUEUE.pollFirst();
                continue;
            }
            boolean bypass = LobbyConfig.OPS_BYPASS_QUEUE.get() && player.hasPermissions(2);
            if (!bypass && !hasRoom(server)) return;
            QUEUE.pollFirst();
            admit(player, session);
        }
    }

    private static void checkChoosingAndTravel(MinecraftServer server) {
        int timeout = LobbyConfig.CHOOSING_TIMEOUT_SECONDS.get();
        for (Map.Entry<UUID, Session> entry : SESSIONS.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            Session session = entry.getValue();
            if (player == null) continue;
            if (session.stage == Stage.CHOOSING) {
                updateBar(player, session);
                long waited = (ticks - session.stageSince) / 20;
                if (timeout > 0 && waited >= timeout) {
                    player.sendSystemMessage(Component.literal("You took too long to pick a spawn, so your slot went to the next player. You're back in the queue.")
                            .withStyle(ChatFormatting.RED));
                    setStage(player, session, Stage.QUEUED);
                    QUEUE.addLast(player.getUUID());
                } else if (timeout > 0 && timeout - waited == 60) {
                    player.sendSystemMessage(Component.literal("One minute left to pick your spawn point.").withStyle(ChatFormatting.YELLOW));
                }
            } else if (session.stage == Stage.PLAYING && session.travelStarted >= 0 && LobbyWorld.isLobby(player.level())) {
                // Earth terrain can take a while; if they're still here after a minute, try again.
                if (ticks - session.travelStarted > 20 * 60) {
                    if (session.travelAttempts >= 3) {
                        player.sendSystemMessage(Component.literal("We couldn't get you into the world. Please reconnect or ask staff for help.")
                                .withStyle(ChatFormatting.RED));
                        session.travelStarted = -1;
                    } else {
                        player.sendSystemMessage(Component.literal("Still loading your spot... trying again.").withStyle(ChatFormatting.YELLOW));
                        startTravel(player, session);
                    }
                }
            } else if (session.stage == Stage.PLAYING && !LobbyWorld.isLobby(player.level())) {
                session.travelStarted = -1;
            }
        }
    }

    // The flow --------------------------------------------------------------------------------

    private static void admit(ServerPlayer player, Session session) {
        LobbyData data = LobbyData.get(player.server);
        LobbyData.PlayerRecord record = data.player(player.getUUID());
        if (record.spawnChosen) {
            setStage(player, session, Stage.PLAYING);
            record.joinedBefore = true;
            data.setDirty();
            player.sendSystemMessage(Component.literal("A spot opened up - sending you back into the world!").withStyle(ChatFormatting.GREEN));
            startTravel(player, session);
        } else {
            setStage(player, session, Stage.CHOOSING);
            player.sendSystemMessage(Component.literal("It's your turn! Pick where on Earth you want to start. Choose carefully - you can only pick once.")
                    .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
            promptForSpawn(player);
        }
    }

    /** Opens the map if the client has the mod, otherwise explains the chat command. */
    public static void promptForSpawn(ServerPlayer player) {
        if (Network.canOpenMap(player)) {
            Network.openMap(player);
            player.sendSystemMessage(Component.literal("Closed the map? Run /lobby spawn to open it again.").withStyle(ChatFormatting.GRAY));
            return;
        }
        player.sendSystemMessage(Component.literal("Pick a spawn with /lobby spawn <latitude> <longitude>, or click a place:")
                .withStyle(ChatFormatting.YELLOW));
        MutableComponent line = Component.empty();
        for (LobbyConfig.Place place : LobbyConfig.places()) {
            String command = String.format(Locale.ROOT, "/lobby spawn %.4f %.4f", place.latitude(), place.longitude());
            line.append(Component.literal("[" + place.name() + "] ").withStyle(style -> style.withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command)))));
        }
        player.sendSystemMessage(line);
    }

    /** A spawn pick from the map or the command. Returns a reason it was refused, or null. */
    @Nullable
    public static String chooseSpawn(ServerPlayer player, double latitude, double longitude) {
        String refused = tryChooseSpawn(player, latitude, longitude);
        if (refused != null) player.sendSystemMessage(Component.literal(refused).withStyle(ChatFormatting.RED));
        return refused;
    }

    @Nullable
    private static String tryChooseSpawn(ServerPlayer player, double latitude, double longitude) {
        LobbyData data = LobbyData.get(player.server);
        LobbyData.PlayerRecord record = data.player(player.getUUID());
        if (record.spawnChosen) {
            return String.format(Locale.ROOT, "You already picked your spawn (%.4f, %.4f). It can't be changed.",
                    record.spawnLatitude, record.spawnLongitude);
        }
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.stage != Stage.CHOOSING) {
            return session != null && session.stage == Stage.QUEUED
                    ? "Wait for your turn in the queue - you'll pick your spawn once you're let in."
                    : "You can't pick a spawn right now.";
        }
        double max = LobbyConfig.MAX_LATITUDE.get();
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || Math.abs(latitude) > max || Math.abs(longitude) > 180) {
            return String.format(Locale.ROOT, "That spot is off the map. Latitude must be within ±%.0f and longitude within ±180.", max);
        }

        record.spawnChosen = true;
        record.spawnLatitude = latitude;
        record.spawnLongitude = longitude;
        record.chosenAt = System.currentTimeMillis();
        record.returnPoint = null;
        record.joinedBefore = true;
        data.setDirty();

        setStage(player, session, Stage.PLAYING);
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "Spawn set to %.4f, %.4f. Off you go!", latitude, longitude))
                .withStyle(ChatFormatting.GREEN));
        LobbyAndSelect.LOGGER.info("{} picked spawn {}, {}", player.getGameProfile().getName(), latitude, longitude);
        startTravel(player, session);
        return null;
    }

    private static void startTravel(ServerPlayer player, Session session) {
        LobbyData.PlayerRecord record = LobbyData.get(player.server).player(player.getUUID());
        Spleef.leave(player, false);
        Parkour.leave(player);
        Spleef.stripGear(player);
        player.setGameMode(record.gameMode != null ? record.gameMode : player.server.getDefaultGameType());
        session.travelStarted = ticks;
        session.travelAttempts++;
        if (record.returnPoint != null && EarthBridge.returnTo(player, record.returnPoint)) return;
        EarthBridge.travel(player, record.spawnLatitude, record.spawnLongitude);
    }

    private static void setStage(ServerPlayer player, Session session, Stage stage) {
        session.stage = stage;
        session.stageSince = ticks;
        session.travelStarted = -1;
        session.travelAttempts = 0;
        updateBar(player, session);
    }

    public static void sendToLobby(ServerPlayer player) {
        ServerLevel lobby = LobbyWorld.level(player.server);
        if (lobby == null) return;
        if (!LobbyWorld.isLobby(player.level())) {
            LobbyData.PlayerRecord record = LobbyData.get(player.server).player(player.getUUID());
            if (record.gameMode == null) record.gameMode = player.gameMode.getGameModeForPlayer();
        }
        teleportToLobbySpawn(player);
        enforceLobbyGameMode(player);
    }

    public static void teleportToLobbySpawn(ServerPlayer player) {
        ServerLevel lobby = LobbyWorld.level(player.server);
        if (lobby == null) return;
        player.fallDistance = 0;
        player.teleportTo(lobby, LobbyWorld.SPAWN.x, LobbyWorld.SPAWN.y, LobbyWorld.SPAWN.z, 0, 0);
    }

    private static void enforceLobbyGameMode(ServerPlayer player) {
        GameType current = player.gameMode.getGameModeForPlayer();
        if (Spleef.isPlaying(player)) return; // spleef needs survival to dig
        // Staff in creative or spectator keep it, to build or watch.
        if (player.hasPermissions(2) && (current == GameType.CREATIVE || current == GameType.SPECTATOR)) return;
        if (current != GameType.ADVENTURE) player.setGameMode(GameType.ADVENTURE);
    }

    private static void updateBar(ServerPlayer player, Session session) {
        if (session.bar == null) {
            session.bar = new ServerBossEvent(Component.empty(), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);
        }
        ServerBossEvent bar = session.bar;
        switch (session.stage) {
            case QUEUED -> {
                int position = queuePosition(player.getUUID());
                int size = QUEUE.size();
                bar.setName(Component.literal(String.format(Locale.ROOT, "Queue: #%d of %d   |   World: %d/%d",
                        position, size, mainCount(), LobbyConfig.MAX_MAIN_PLAYERS.get())));
                bar.setColor(BossEvent.BossBarColor.YELLOW);
                bar.setProgress(size <= 1 ? 1f : 1f - (position - 1f) / size);
            }
            case CHOOSING -> {
                int timeout = LobbyConfig.CHOOSING_TIMEOUT_SECONDS.get();
                long left = timeout - (ticks - session.stageSince) / 20;
                bar.setName(Component.literal(timeout > 0
                        ? String.format(Locale.ROOT, "Pick your spawn point - /lobby spawn  (%d:%02d left)", left / 60, Math.max(0, left % 60))
                        : "Pick your spawn point - /lobby spawn"));
                bar.setColor(BossEvent.BossBarColor.GREEN);
                bar.setProgress(timeout > 0 ? Math.max(0f, Math.min(1f, left / (float) timeout)) : 1f);
            }
            case PLAYING -> {
                bar.removeAllPlayers();
                return;
            }
        }
        if (!bar.getPlayers().contains(player)) bar.addPlayer(player);
    }

    // Queries ---------------------------------------------------------------------------------

    public static boolean hasRoom(MinecraftServer server) {
        return mainCount() < LobbyConfig.MAX_MAIN_PLAYERS.get();
    }

    /** Players holding a main-world slot: in it, on their way, or picking a spawn. */
    public static int mainCount() {
        int n = 0;
        for (Session s : SESSIONS.values()) if (s.stage != Stage.QUEUED) n++;
        return n;
    }

    public static int queueSize() {
        return QUEUE.size();
    }

    /** 1-based place in the queue, or 0 if not queued. */
    public static int queuePosition(UUID id) {
        int i = 1;
        for (UUID q : QUEUE) {
            if (q.equals(id)) return i;
            i++;
        }
        return 0;
    }

    @Nullable
    public static Stage stage(Player player) {
        Session s = SESSIONS.get(player.getUUID());
        return s == null ? null : s.stage;
    }

    /** Lets a player in now, ignoring the cap. */
    public static boolean admitNow(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.stage != Stage.QUEUED) return false;
        QUEUE.remove(player.getUUID());
        admit(player, session);
        return true;
    }

    /** Admin: forget a player's spawn so they can pick again next time they're let in. */
    public static void resetSpawn(MinecraftServer server, UUID id) {
        LobbyData data = LobbyData.get(server);
        LobbyData.PlayerRecord record = data.existing(id);
        if (record == null) return;
        record.spawnChosen = false;
        record.returnPoint = null;
        data.setDirty();
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        Session session = SESSIONS.get(id);
        if (online != null && session != null && session.stage == Stage.PLAYING) {
            // Already in the world: put them back in the lobby to pick.
            sendToLobby(online);
            setStage(online, session, Stage.CHOOSING);
            promptForSpawn(online);
        }
    }

    public static List<ServerPlayer> lobbyPlayers(MinecraftServer server) {
        ServerLevel lobby = LobbyWorld.level(server);
        return lobby == null ? List.of() : new ArrayList<>(lobby.players());
    }

    // Lobby protection ------------------------------------------------------------------------

    private static boolean staffBuilding(Player player) {
        return player.isCreative() && player.hasPermissions(2);
    }

    private static void onDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && LobbyWorld.isLobby(player.level())) event.setCanceled(true);
    }

    private static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || !LobbyWorld.isLobby(player.level())) return;
        if (staffBuilding(player)) return;
        event.setCanceled(true);
        Spleef.tryDig(player, event.getPos());
    }

    private static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player player && LobbyWorld.isLobby(player.level()) && !staffBuilding(player)) {
            event.setCanceled(true);
        }
    }

    private static void onToss(ItemTossEvent event) {
        Player player = event.getPlayer();
        if (!LobbyWorld.isLobby(player.level()) || staffBuilding(player)) return;
        event.setCanceled(true);
        // The tossed stack is already out of the inventory; hand it back.
        player.getInventory().add(event.getEntity().getItem().copy());
    }

    private static void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (LobbyWorld.isLobby(player.level())) {
            String text = event.getRawText();
            player.server.execute(() -> Trivia.onChat(player, text));
        }
    }
}
