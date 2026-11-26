package com.lobbyandselect;

import com.lobbyandselect.game.Parkour;
import com.lobbyandselect.game.Spleef;
import com.lobbyandselect.game.Trivia;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** /lobby for players, /lobby admin for operators. */
public final class LobbyCommands {
    private LobbyCommands() {}

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        var root = Commands.literal("lobby").executes(LobbyCommands::help);

        root.then(Commands.literal("status").executes(LobbyCommands::status));
        root.then(Commands.literal("spawn").executes(LobbyCommands::openMap)
                .then(Commands.argument("latitude", DoubleArgumentType.doubleArg(-85, 85))
                        .then(Commands.argument("longitude", DoubleArgumentType.doubleArg(-180, 180))
                                .executes(context -> confirmSpawn(context, false))
                                .then(Commands.literal("confirm").executes(context -> confirmSpawn(context, true))))));
        root.then(Commands.literal("spleef").executes(context -> {
            Spleef.join(context.getSource().getPlayerOrException());
            return 1;
        }));
        root.then(Commands.literal("parkour").executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!LobbyWorld.isLobby(player.level())) {
                context.getSource().sendFailure(Component.literal("Parkour is only in the lobby."));
                return 0;
            }
            Spleef.leave(player, true);
            var start = LobbyWorld.PARKOUR_START;
            player.teleportTo(player.serverLevel(), start.getX() - 1.5, start.getY() + 1, start.getZ() + 0.5, -90, 0);
            return 1;
        }).then(Commands.literal("top").executes(LobbyCommands::parkourTop)));
        root.then(Commands.literal("leave").executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            Spleef.leave(player, true);
            Parkour.leave(player);
            if (LobbyWorld.isLobby(player.level())) LobbyManager.teleportToLobbySpawn(player);
            return 1;
        }));
        root.then(Commands.literal("territory")
                .then(Commands.literal("expand").executes(context -> territory(context, true)))
                .then(Commands.literal("conquer").executes(context -> territory(context, false)))
                .then(Commands.literal("cancel").executes(context -> {
                    Territory.cancel(context.getSource().getPlayerOrException().getUUID());
                    return 1;
                })));
        root.then(Commands.literal("stats").executes(LobbyCommands::stats));

        var admin = Commands.literal("admin").requires(source -> source.hasPermission(2));
        admin.then(Commands.literal("resetspawn").then(Commands.argument("players", GameProfileArgument.gameProfile())
                .executes(context -> {
                    Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(context, "players");
                    for (GameProfile profile : profiles) {
                        LobbyManager.resetSpawn(context.getSource().getServer(), profile.getId());
                    }
                    context.getSource().sendSuccess(() -> Component.literal("Reset the spawn of " + profiles.size() + " player(s)."), true);
                    return profiles.size();
                })));
        admin.then(Commands.literal("admit").then(Commands.argument("player", EntityArgument.player()).executes(context -> {
            ServerPlayer player = EntityArgument.getPlayer(context, "player");
            boolean ok = LobbyManager.admitNow(player);
            if (ok) context.getSource().sendSuccess(() -> Component.literal("Let " + player.getGameProfile().getName() + " in."), true);
            else context.getSource().sendFailure(Component.literal(player.getGameProfile().getName() + " isn't in the queue."));
            return ok ? 1 : 0;
        })));
        admin.then(Commands.literal("send").then(Commands.argument("player", EntityArgument.player()).executes(context -> {
            ServerPlayer player = EntityArgument.getPlayer(context, "player");
            LobbyManager.sendToLobby(player);
            context.getSource().sendSuccess(() -> Component.literal("Sent " + player.getGameProfile().getName() + " to the lobby (they keep their slot)."), true);
            return 1;
        })));
        admin.then(Commands.literal("max").then(Commands.argument("players", IntegerArgumentType.integer(0, 10_000)).executes(context -> {
            int max = IntegerArgumentType.getInteger(context, "players");
            LobbyConfig.MAX_MAIN_PLAYERS.set(max);
            LobbyConfig.MAX_MAIN_PLAYERS.save();
            context.getSource().sendSuccess(() -> Component.literal("Main world cap set to " + max + "."), true);
            return 1;
        })));
        admin.then(Commands.literal("bypass").then(Commands.argument("enabled", BoolArgumentType.bool()).executes(context -> {
            boolean enabled = BoolArgumentType.getBool(context, "enabled");
            LobbyConfig.OPS_BYPASS_QUEUE.set(enabled);
            LobbyConfig.OPS_BYPASS_QUEUE.save();
            context.getSource().sendSuccess(() -> Component.literal("Operators " + (enabled ? "skip" : "wait in") + " the queue."), true);
            return 1;
        })));
        admin.then(Commands.literal("rebuild").executes(context -> {
            var level = LobbyWorld.level(context.getSource().getServer());
            if (level == null) {
                context.getSource().sendFailure(Component.literal("The lobby dimension is missing."));
                return 0;
            }
            LobbyWorld.build(level);
            context.getSource().sendSuccess(() -> Component.literal("Lobby rebuilt."), true);
            return 1;
        }));
        admin.then(Commands.literal("trivia").executes(context -> {
            Trivia.ask(context.getSource().getServer());
            return 1;
        }));
        root.then(admin);
        dispatcher.register(root);
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String[] lines = {
                "/lobby status - your place in the queue",
                "/lobby spawn - pick your spawn point (once you're let in)",
                "/lobby parkour - go to the parkour course   (/lobby parkour top)",
                "/lobby spleef - join spleef",
                "/lobby leave - leave the minigame you're in",
                "/lobby stats - your minigame stats",
                "Trivia questions pop up in chat - just type the answer!"
        };
        source.sendSystemMessage(Component.literal("Lobby").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        for (String line : lines) source.sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        LobbyManager.Stage stage = LobbyManager.stage(player);
        int max = LobbyConfig.MAX_MAIN_PLAYERS.get();
        String world = "World: " + LobbyManager.mainCount() + "/" + max + ", queue: " + LobbyManager.queueSize();
        String mine = stage == null ? "" : switch (stage) {
            case QUEUED -> "You are #" + LobbyManager.queuePosition(player.getUUID()) + " in the queue.";
            case CHOOSING -> "It's your turn - pick a spawn with /lobby spawn.";
            case PLAYING -> "You're in the world.";
        };
        LobbyData.PlayerRecord record = LobbyData.get(player.server).player(player.getUUID());
        String spawn = record.spawnChosen
                ? String.format(Locale.ROOT, "Your spawn: %.4f, %.4f (permanent)", record.spawnLatitude, record.spawnLongitude)
                : "You haven't picked a spawn yet.";
        context.getSource().sendSystemMessage(Component.literal(mine).withStyle(ChatFormatting.YELLOW));
        context.getSource().sendSystemMessage(Component.literal(world).withStyle(ChatFormatting.GRAY));
        context.getSource().sendSystemMessage(Component.literal(spawn).withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static int openMap(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        LobbyData.PlayerRecord record = LobbyData.get(player.server).player(player.getUUID());
        if (record.spawnChosen) {
            context.getSource().sendFailure(Component.literal(String.format(Locale.ROOT,
                    "You already picked your spawn (%.4f, %.4f). It can't be changed.", record.spawnLatitude, record.spawnLongitude)));
            return 0;
        }
        if (LobbyManager.stage(player) != LobbyManager.Stage.CHOOSING) {
            context.getSource().sendFailure(Component.literal("Wait for your turn in the queue - you'll pick your spawn once you're let in."));
            return 0;
        }
        LobbyManager.promptForSpawn(player);
        return 1;
    }

    private static int confirmSpawn(CommandContext<CommandSourceStack> context, boolean confirmed) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        double lat = DoubleArgumentType.getDouble(context, "latitude"), lon = DoubleArgumentType.getDouble(context, "longitude");
        if (!confirmed) {
            String command = String.format(Locale.ROOT, "/lobby spawn %.6f %.6f confirm", lat, lon);
            context.getSource().sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                    "Spawn at %.4f, %.4f? This is permanent. ", lat, lon)).withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("[Click to confirm]").withStyle(style -> style.withColor(ChatFormatting.GREEN).withBold(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)))));
            return 1;
        }
        return LobbyManager.chooseSpawn(player, lat, lon) == null ? 1 : 0;
    }

    private static int territory(CommandContext<CommandSourceStack> context, boolean expand) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String err = expand ? Territory.expand(player) : Territory.startConquest(player);
        if (err != null) context.getSource().sendFailure(Component.literal(err));
        return err == null ? 1 : 0;
    }

    private static int parkourTop(CommandContext<CommandSourceStack> context) {
        List<LobbyData.PlayerRecord> top = Parkour.top(LobbyData.get(context.getSource().getServer()), 10);
        context.getSource().sendSystemMessage(Component.literal("Parkour best times").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        if (top.isEmpty()) context.getSource().sendSystemMessage(Component.literal("Nobody has finished yet.").withStyle(ChatFormatting.GRAY));
        for (int i = 0; i < top.size(); i++) {
            LobbyData.PlayerRecord r = top.get(i);
            context.getSource().sendSystemMessage(Component.literal((i + 1) + ". " + r.name + " - " + Parkour.format(r.parkourBestTicks)));
        }
        return top.size();
    }

    private static int stats(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        LobbyData data = LobbyData.get(player.server);
        LobbyData.PlayerRecord r = data.player(player.getUUID());
        int triviaRank = 1 + (int) data.all().values().stream().filter(o -> o.triviaPoints > r.triviaPoints).count();
        String best = r.parkourBestTicks < 0 ? "none" : Parkour.format(r.parkourBestTicks);
        context.getSource().sendSystemMessage(Component.literal("Your lobby stats").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        context.getSource().sendSystemMessage(Component.literal("Parkour best: " + best));
        context.getSource().sendSystemMessage(Component.literal("Spleef wins: " + r.spleefWins));
        context.getSource().sendSystemMessage(Component.literal("Trivia points: " + r.triviaPoints + " (rank #" + triviaRank + ")"));
        String topTrivia = data.all().values().stream().filter(o -> o.triviaPoints > 0)
                .sorted(Comparator.comparingInt((LobbyData.PlayerRecord o) -> o.triviaPoints).reversed()).limit(3)
                .map(o -> o.name + " " + o.triviaPoints).reduce((a, b) -> a + ", " + b).orElse("-");
        context.getSource().sendSystemMessage(Component.literal("Trivia leaders: " + topTrivia).withStyle(ChatFormatting.GRAY));
        return 1;
    }
}
