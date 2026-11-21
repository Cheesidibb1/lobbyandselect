package com.lobbyandselect.game;

import com.lobbyandselect.LobbyConfig;
import com.lobbyandselect.LobbyData;
import com.lobbyandselect.LobbyManager;
import com.lobbyandselect.LobbyWorld;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Spleef: dig the snow out from under the other players. Stand on the lapis pad (or /lobby spleef)
 * to join; a round starts once enough players are in, and the last one standing wins.
 */
public final class Spleef {
    private enum State { WAITING, COUNTDOWN, RUNNING }

    private static final String GEAR_TAG = "lobbyandselect_spleef";
    private static final int COUNTDOWN_SECONDS = 10;

    /** Everyone signed up, in join order; they stay in for the next round until they leave. */
    private static final Set<UUID> JOINED = new LinkedHashSet<>();
    /** Who is still standing in the current round. */
    private static final Set<UUID> ALIVE = new LinkedHashSet<>();
    private static State state = State.WAITING;
    private static int countdown;
    private static long ticks;
    /** No new round before this tick, for a short break between rounds. */
    private static long nextRoundAt;

    private Spleef() {}

    public static void reset() {
        JOINED.clear();
        ALIVE.clear();
        state = State.WAITING;
        ticks = 0;
        nextRoundAt = 0;
    }

    public static boolean isJoined(ServerPlayer player) {
        return JOINED.contains(player.getUUID());
    }

    public static boolean isPlaying(ServerPlayer player) {
        return state == State.RUNNING && ALIVE.contains(player.getUUID());
    }

    public static void join(ServerPlayer player) {
        if (!LobbyWorld.isLobby(player.level())) {
            player.sendSystemMessage(Component.literal("Spleef is only in the lobby.").withStyle(ChatFormatting.RED));
            return;
        }
        if (!JOINED.add(player.getUUID())) return;
        Parkour.leave(player);
        broadcast(player.server, Component.literal(player.getGameProfile().getName() + " joined spleef (" + JOINED.size() + " in). /lobby leave to quit.")
                .withStyle(ChatFormatting.AQUA));
        if (state == State.RUNNING) {
            player.sendSystemMessage(Component.literal("A round is on - you're in the next one.").withStyle(ChatFormatting.GRAY));
        }
    }

    /** Leaves spleef; {@code announce} tells the lobby. */
    public static void leave(ServerPlayer player, boolean announce) {
        boolean was = JOINED.remove(player.getUUID());
        boolean alive = ALIVE.remove(player.getUUID());
        if (alive) {
            stripGear(player);
            if (player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL && LobbyWorld.isLobby(player.level())) {
                player.setGameMode(GameType.ADVENTURE);
            }
        }
        if (was && announce) {
            player.sendSystemMessage(Component.literal("You left spleef.").withStyle(ChatFormatting.GRAY));
            if (alive && LobbyWorld.isLobby(player.level())) LobbyManager.teleportToLobbySpawn(player);
        }
    }

    public static void playerTick(ServerPlayer player) {
        // Stepping on the join pad signs you up.
        if (player.onGround() && !isJoined(player) && player.getOnPos().equals(LobbyWorld.SPLEEF_JOIN_PAD)) {
            join(player);
        }
        // Spectators can't stand on the floor during a round.
        if (state == State.RUNNING && !ALIVE.contains(player.getUUID())
                && player.getY() >= LobbyWorld.FLOOR_Y && player.getY() < LobbyWorld.FLOOR_Y + 4
                && LobbyWorld.inSpleefArena(player.getX(), player.getZ()) && !player.isSpectator()) {
            player.teleportTo(player.serverLevel(), LobbyWorld.SPLEEF_JOIN_PAD.getX() + 0.5, LobbyWorld.FLOOR_Y + 1,
                    LobbyWorld.SPLEEF_JOIN_PAD.getZ() + 0.5, 90, 0);
            player.displayClientMessage(Component.literal("A spleef round is in progress.").withStyle(ChatFormatting.GRAY), true);
        }
    }

    /** Lets a player in the round dig the snow floor (without drops). */
    public static void tryDig(ServerPlayer player, BlockPos pos) {
        if (!isPlaying(player) || !LobbyWorld.isSpleefFloor(pos)) return;
        ServerLevel level = player.serverLevel();
        if (level.getBlockState(pos).is(Blocks.SNOW_BLOCK)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
    }

    /** Called when a player drops below the lobby's catch height. Returns true if handled. */
    public static boolean catchFall(ServerPlayer player) {
        if (!ALIVE.contains(player.getUUID())) return false;
        eliminate(player);
        return true;
    }

    public static void tick(MinecraftServer server) {
        ticks++;
        ServerLevel lobby = LobbyWorld.level(server);
        if (lobby == null) return;
        // Drop anyone who left the lobby.
        JOINED.removeIf(id -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            return p == null || !LobbyWorld.isLobby(p.level());
        });
        int min = LobbyConfig.SPLEEF_MIN_PLAYERS.get();

        switch (state) {
            case WAITING -> {
                if (JOINED.size() >= min && ticks >= nextRoundAt && ticks % 20 == 0) {
                    state = State.COUNTDOWN;
                    countdown = COUNTDOWN_SECONDS;
                }
            }
            case COUNTDOWN -> {
                if (JOINED.size() < min) {
                    state = State.WAITING;
                    broadcastJoined(server, Component.literal("Not enough players for spleef - waiting for more.").withStyle(ChatFormatting.GRAY));
                    return;
                }
                if (ticks % 20 != 0) return;
                if (countdown == 0) {
                    start(server, lobby);
                } else {
                    if (countdown <= 5 || countdown == COUNTDOWN_SECONDS) {
                        for (UUID id : JOINED) {
                            ServerPlayer p = server.getPlayerList().getPlayer(id);
                            if (p == null) continue;
                            p.displayClientMessage(Component.literal("Spleef starts in " + countdown + "...").withStyle(ChatFormatting.YELLOW), true);
                            p.playNotifySound(SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 1f, 1f);
                        }
                    }
                    countdown--;
                }
            }
            case RUNNING -> {
                for (UUID id : List.copyOf(ALIVE)) {
                    ServerPlayer p = server.getPlayerList().getPlayer(id);
                    if (p == null || !LobbyWorld.isLobby(p.level())) {
                        ALIVE.remove(id);
                    } else if (p.getY() < LobbyWorld.SPLEEF_OUT_Y) {
                        eliminate(p);
                    }
                }
                if (ALIVE.size() <= 1 && (min > 1 || ALIVE.isEmpty())) end(server, lobby);
            }
        }
    }

    private static void start(MinecraftServer server, ServerLevel lobby) {
        LobbyWorld.resetSpleefFloor(lobby);
        ALIVE.clear();
        List<ServerPlayer> players = new ArrayList<>();
        for (UUID id : JOINED) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) players.add(p);
        }
        double radius = Math.min(LobbyWorld.SPLEEF_RADIUS - 2, 3 + players.size());
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer p = players.get(i);
            double angle = 2 * Math.PI * i / players.size();
            double x = LobbyWorld.SPLEEF_CENTER.getX() + 0.5 + Math.cos(angle) * radius;
            double z = LobbyWorld.SPLEEF_CENTER.getZ() + 0.5 + Math.sin(angle) * radius;
            float yaw = (float) Math.toDegrees(Math.atan2(-(LobbyWorld.SPLEEF_CENTER.getX() + 0.5 - x), LobbyWorld.SPLEEF_CENTER.getZ() + 0.5 - z));
            Parkour.leave(p);
            p.teleportTo(lobby, x, LobbyWorld.FLOOR_Y + 1, z, yaw, 0);
            p.setGameMode(GameType.SURVIVAL);
            giveShovel(p);
            ALIVE.add(p.getUUID());
            p.displayClientMessage(Component.literal("GO! Dig the snow from under the others!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), true);
        }
        state = State.RUNNING;
    }

    private static void eliminate(ServerPlayer player) {
        ALIVE.remove(player.getUUID());
        stripGear(player);
        player.setGameMode(GameType.ADVENTURE);
        player.fallDistance = 0;
        player.teleportTo(player.serverLevel(), LobbyWorld.SPLEEF_JOIN_PAD.getX() + 0.5, LobbyWorld.FLOOR_Y + 1,
                LobbyWorld.SPLEEF_JOIN_PAD.getZ() + 0.5, 90, 0);
        broadcastJoined(player.server, Component.literal(player.getGameProfile().getName() + " fell out! " + ALIVE.size() + " left.")
                .withStyle(ChatFormatting.GRAY));
    }

    private static void end(MinecraftServer server, ServerLevel lobby) {
        if (ALIVE.size() == 1) {
            ServerPlayer winner = server.getPlayerList().getPlayer(ALIVE.iterator().next());
            if (winner != null) {
                LobbyData data = LobbyData.get(server);
                LobbyData.PlayerRecord record = data.player(winner.getUUID());
                record.spleefWins++;
                data.setDirty();
                broadcast(server, Component.literal(winner.getGameProfile().getName() + " won spleef! (" + record.spleefWins + " wins)")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                winner.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1f, 1f);
                stripGear(winner);
                winner.setGameMode(GameType.ADVENTURE);
                winner.teleportTo(lobby, LobbyWorld.SPLEEF_JOIN_PAD.getX() + 0.5, LobbyWorld.FLOOR_Y + 1,
                        LobbyWorld.SPLEEF_JOIN_PAD.getZ() + 0.5, 90, 0);
            }
        }
        ALIVE.clear();
        LobbyWorld.resetSpleefFloor(lobby);
        state = State.WAITING;
        // Short break before the next round.
        nextRoundAt = ticks + 100;
    }

    private static void giveShovel(ServerPlayer player) {
        ItemStack shovel = new ItemStack(Items.DIAMOND_SHOVEL);
        var enchantments = player.server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        shovel.enchant(enchantments.getOrThrow(Enchantments.EFFICIENCY), 5);
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(GEAR_TAG, true);
        shovel.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        shovel.set(DataComponents.CUSTOM_NAME, Component.literal("Spleef Shovel").withStyle(ChatFormatting.AQUA));
        shovel.set(DataComponents.UNBREAKABLE, new net.minecraft.world.item.component.Unbreakable(false));
        Inventory inventory = player.getInventory();
        // Put it in hand if the slot is free, else anywhere.
        if (inventory.getSelected().isEmpty()) inventory.setItem(inventory.selected, shovel);
        else if (!inventory.add(shovel)) player.drop(shovel, false);
    }

    /** Removes any spleef shovels from the player's inventory. */
    public static void stripGear(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            CustomData data = stack.get(DataComponents.CUSTOM_DATA);
            if (data != null && data.copyTag().getBoolean(GEAR_TAG)) inventory.setItem(i, ItemStack.EMPTY);
        }
    }

    private static void broadcast(MinecraftServer server, Component message) {
        for (ServerPlayer p : LobbyManager.lobbyPlayers(server)) p.sendSystemMessage(message);
    }

    private static void broadcastJoined(MinecraftServer server, Component message) {
        for (UUID id : JOINED) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) p.sendSystemMessage(message);
        }
    }

    public static int joinedCount() {
        return JOINED.size();
    }

    public static String stateName() {
        return state.name().toLowerCase(java.util.Locale.ROOT);
    }
}
