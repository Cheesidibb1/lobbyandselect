package com.lobbyandselect.game;

import com.lobbyandselect.LobbyData;
import com.lobbyandselect.LobbyManager;
import com.lobbyandselect.LobbyWorld;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;

/** A timed jump course: emerald to start, gold blocks are checkpoints, diamond is the finish. */
public final class Parkour {
    private static final class Run {
        BlockPos checkpoint;
        long serverTicks;
    }

    private static final Map<UUID, Run> RUNS = new HashMap<>();

    private Parkour() {}

    public static void reset() {
        RUNS.clear();
    }

    public static void leave(ServerPlayer player) {
        RUNS.remove(player.getUUID());
    }

    public static void tick(ServerPlayer player) {
        Run run = RUNS.get(player.getUUID());
        if (run != null) {
            run.serverTicks++;
            if (missedJump(player)) {
                catchFall(player);
                return;
            }
            if (player.getX() < LobbyWorld.PARKOUR_START.getX() - 1.5) {
                // Walked back to the lobby: the run is over.
                RUNS.remove(player.getUUID());
                player.displayClientMessage(Component.literal("Parkour run ended.").withStyle(ChatFormatting.GRAY), true);
                return;
            }
        }
        if (!player.onGround()) return;

        BlockPos below = player.getOnPos();
        BlockState state = player.level().getBlockState(below);
        if (state.is(LobbyWorld.PARKOUR_START_BLOCK)) {
            // Standing on the start holds the clock at zero; it runs from the moment they jump off.
            if (run == null) {
                player.sendSystemMessage(Component.literal("Parkour started! Reach the diamond block. Gold blocks are checkpoints. /lobby leave to quit.")
                        .withStyle(ChatFormatting.GREEN));
                run = new Run();
                RUNS.put(player.getUUID(), run);
            }
            run.checkpoint = below;
            run.serverTicks = 0;
        } else if (run != null && state.is(LobbyWorld.PARKOUR_CHECKPOINT_BLOCK) && !below.equals(run.checkpoint)) {
            run.checkpoint = below;
            player.displayClientMessage(Component.literal("Checkpoint!").withStyle(ChatFormatting.GOLD), true);
            player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.6f, 1.2f);
        } else if (run != null && state.is(LobbyWorld.PARKOUR_FINISH_BLOCK)) {
            finish(player, run);
            return;
        }
        if (run != null && player.tickCount % 2 == 0) {
            player.displayClientMessage(Component.literal("Parkour  " + format(run.serverTicks)).withStyle(ChatFormatting.AQUA), true);
        }
    }

    private static void finish(ServerPlayer player, Run run) {
        RUNS.remove(player.getUUID());
        long time = run.serverTicks;
        LobbyData data = LobbyData.get(player.server);
        LobbyData.PlayerRecord record = data.player(player.getUUID());
        boolean best = record.parkourBestTicks < 0 || time < record.parkourBestTicks;
        if (best) {
            record.parkourBestTicks = time;
            data.setDirty();
        }
        player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1f);
        Component message = Component.literal(player.getGameProfile().getName() + " finished the parkour in " + format(time)
                + (best ? " (personal best!)" : "")).withStyle(ChatFormatting.AQUA);
        for (ServerPlayer other : LobbyManager.lobbyPlayers(player.server)) other.sendSystemMessage(message);
        LobbyManager.teleportToLobbySpawn(player);
    }

    /** Called when a player drops below the catch height. Returns true if handled. */
    public static boolean catchFall(ServerPlayer player) {
        Run run = RUNS.get(player.getUUID());
        if (run == null) return false;
        BlockPos cp = run.checkpoint;
        player.fallDistance = 0;
        player.teleportTo(player.serverLevel(), cp.getX() + 0.5, cp.getY() + 1, cp.getZ() + 0.5, -90, 0);
        return true;
    }

    /** Also catch a missed jump right away rather than at the lobby's catch height. */
    public static boolean missedJump(ServerPlayer player) {
        Run run = RUNS.get(player.getUUID());
        return run != null && player.getY() < run.checkpoint.getY() - 3;
    }

    public static String format(long ticks) {
        long millis = ticks * 50;
        return String.format(Locale.ROOT, "%d:%02d.%02d", millis / 60000, millis / 1000 % 60, millis / 10 % 100);
    }

    public static List<LobbyData.PlayerRecord> top(LobbyData data, int n) {
        return data.all().values().stream()
                .filter(r -> r.parkourBestTicks >= 0)
                .sorted((a, b) -> Long.compare(a.parkourBestTicks, b.parkourBestTicks))
                .limit(n)
                .toList();
    }
}
