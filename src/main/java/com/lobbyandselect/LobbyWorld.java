package com.lobbyandselect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** The lobby dimension and the layout of everything built in it. */
public final class LobbyWorld {
    public static final ResourceKey<Level> LOBBY =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(LobbyAndSelect.MOD_ID, "lobby"));

    /** Bump to rebuild the lobby on next start after changing the layout. */
    public static final int LAYOUT_VERSION = 1;

    public static final int FLOOR_Y = 99;
    public static final Vec3 SPAWN = new Vec3(0.5, FLOOR_Y + 1, 0.5);
    /** Anyone this far down is caught and put back. */
    public static final int CATCH_Y = 80;

    // Main platform: a square of radius 15 around the origin.
    public static final int PLATFORM_RADIUS = 15;

    // Parkour runs east from the end of a bridge.
    public static final BlockPos PARKOUR_START = new BlockPos(22, FLOOR_Y, 0);
    public static final Block PARKOUR_START_BLOCK = Blocks.EMERALD_BLOCK;
    public static final Block PARKOUR_CHECKPOINT_BLOCK = Blocks.GOLD_BLOCK;
    public static final Block PARKOUR_FINISH_BLOCK = Blocks.DIAMOND_BLOCK;

    // Spleef arena to the west.
    public static final BlockPos SPLEEF_CENTER = new BlockPos(-40, FLOOR_Y, 0);
    public static final int SPLEEF_RADIUS = 11;
    /** Below this a spleef player is out. */
    public static final int SPLEEF_OUT_Y = FLOOR_Y - 3;
    public static final BlockPos SPLEEF_JOIN_PAD = new BlockPos(-20, FLOOR_Y, 0);
    public static final Block SPLEEF_JOIN_BLOCK = Blocks.LAPIS_BLOCK;

    private static List<BlockPos> parkourCourse;

    private LobbyWorld() {}

    @Nullable
    public static ServerLevel level(MinecraftServer server) {
        return server.getLevel(LOBBY);
    }

    public static boolean isLobby(Level level) {
        return level.dimension() == LOBBY;
    }

    /** The jump blocks of the parkour course in order; the last is the finish. */
    public static List<BlockPos> parkourCourse() {
        if (parkourCourse == null) {
            List<BlockPos> course = new ArrayList<>();
            int x = PARKOUR_START.getX(), y = PARKOUR_START.getY(), z = PARKOUR_START.getZ();
            // A repeating pattern of jumps that are all makeable with a sprint jump.
            int[][] pattern = {{3, 0, 0}, {2, 1, 1}, {3, 0, -1}, {2, 1, 0}, {3, 0, 1}, {2, 0, -2}};
            for (int i = 0; i < 24; i++) {
                int[] step = pattern[i % pattern.length];
                x += step[0];
                y += step[1];
                z += step[2];
                course.add(new BlockPos(x, y, z));
            }
            parkourCourse = List.copyOf(course);
        }
        return parkourCourse;
    }

    public static boolean isParkourCheckpoint(int index) {
        return index > 0 && index % 6 == 5 && index < parkourCourse().size() - 1;
    }

    public static boolean inSpleefArena(double x, double z) {
        double dx = x - (SPLEEF_CENTER.getX() + 0.5), dz = z - (SPLEEF_CENTER.getZ() + 0.5);
        return dx * dx + dz * dz <= (SPLEEF_RADIUS + 0.5) * (SPLEEF_RADIUS + 0.5);
    }

    public static boolean isSpleefFloor(BlockPos pos) {
        return pos.getY() == FLOOR_Y && inSpleefArena(pos.getX() + 0.5, pos.getZ() + 0.5);
    }

    /** Builds the lobby the first time (or when the layout version changes). */
    public static void ensureBuilt(MinecraftServer server) {
        LobbyData data = LobbyData.get(server);
        ServerLevel level = level(server);
        if (level == null) {
            LobbyAndSelect.LOGGER.error("Lobby dimension {} is missing; is the mod's data pack enabled?", LOBBY.location());
            return;
        }
        if (data.builtVersion() >= LAYOUT_VERSION) return;
        build(level);
        data.setBuiltVersion(LAYOUT_VERSION);
    }

    public static void build(ServerLevel level) {
        LobbyAndSelect.LOGGER.info("Building the lobby");
        BlockState floor = Blocks.SMOOTH_STONE.defaultBlockState();
        BlockState border = Blocks.QUARTZ_BRICKS.defaultBlockState();
        BlockState light = Blocks.SEA_LANTERN.defaultBlockState();
        BlockState rail = Blocks.GLASS_PANE.defaultBlockState();

        // Main platform with a low glass rail, except where the bridges leave.
        for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS; x++) {
            for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
                boolean edge = Math.abs(x) == PLATFORM_RADIUS || Math.abs(z) == PLATFORM_RADIUS;
                boolean lamp = x % 5 == 0 && z % 5 == 0;
                set(level, x, FLOOR_Y, z, edge ? border : lamp ? light : floor);
                boolean gap = Math.abs(z) <= 1 && Math.abs(x) == PLATFORM_RADIUS;
                set(level, x, FLOOR_Y + 1, z, edge && !gap ? rail : Blocks.AIR.defaultBlockState());
            }
        }
        // A compass rose in the middle.
        set(level, 0, FLOOR_Y, 0, Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState());

        // Bridge east to the parkour start.
        for (int x = PLATFORM_RADIUS + 1; x < PARKOUR_START.getX(); x++) {
            for (int z = -1; z <= 1; z++) set(level, x, FLOOR_Y, z, border);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) set(level, PARKOUR_START.getX() + dx, FLOOR_Y, dz, floor);
        }
        set(level, PARKOUR_START.getX(), FLOOR_Y, PARKOUR_START.getZ(), PARKOUR_START_BLOCK.defaultBlockState());

        // Clear the course's air, then lay the jump blocks.
        for (int x = PARKOUR_START.getX() + 2; x <= PARKOUR_START.getX() + 70; x++) {
            for (int y = FLOOR_Y; y <= FLOOR_Y + 20; y++) {
                for (int z = -6; z <= 6; z++) set(level, x, y, z, Blocks.AIR.defaultBlockState());
            }
        }
        Block[] colours = {Blocks.LIGHT_BLUE_CONCRETE, Blocks.LIME_CONCRETE, Blocks.YELLOW_CONCRETE, Blocks.ORANGE_CONCRETE,
                Blocks.MAGENTA_CONCRETE, Blocks.CYAN_CONCRETE};
        List<BlockPos> course = parkourCourse();
        for (int i = 0; i < course.size(); i++) {
            BlockPos p = course.get(i);
            Block block = i == course.size() - 1 ? PARKOUR_FINISH_BLOCK
                    : isParkourCheckpoint(i) ? PARKOUR_CHECKPOINT_BLOCK : colours[i % colours.length];
            set(level, p.getX(), p.getY(), p.getZ(), block.defaultBlockState());
        }
        // A landing pad around the finish.
        BlockPos finish = course.get(course.size() - 1);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) set(level, finish.getX() + dx, finish.getY(), finish.getZ() + dz, border);
            }
        }

        // Bridge west to the spleef join pad and the arena.
        for (int x = SPLEEF_CENTER.getX() + SPLEEF_RADIUS + 1; x <= -PLATFORM_RADIUS - 1; x++) {
            for (int z = -1; z <= 1; z++) set(level, x, FLOOR_Y, z, border);
        }
        set(level, SPLEEF_JOIN_PAD.getX(), FLOOR_Y, SPLEEF_JOIN_PAD.getZ(), SPLEEF_JOIN_BLOCK.defaultBlockState());
        buildSpleefArena(level);

        placeLabels(level);
    }

    public static void buildSpleefArena(ServerLevel level) {
        int cx = SPLEEF_CENTER.getX(), cz = SPLEEF_CENTER.getZ();
        int r = SPLEEF_RADIUS + 1;
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                double dx = x - cx, dz = z - cz;
                double d = Math.sqrt(dx * dx + dz * dz);
                // A catch floor below, so a fallen player lands softly in sight of the game.
                if (d <= r + 1) set(level, x, FLOOR_Y - 8, z, Blocks.BLUE_STAINED_GLASS.defaultBlockState());
                if (d <= SPLEEF_RADIUS + 0.5) {
                    set(level, x, FLOOR_Y, z, Blocks.SNOW_BLOCK.defaultBlockState());
                } else if (d <= r + 0.5) {
                    boolean entrance = x > cx && Math.abs(z - cz) <= 1;
                    set(level, x, FLOOR_Y, z, Blocks.PACKED_ICE.defaultBlockState());
                    for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 2; y++) {
                        set(level, x, y, z, entrance ? Blocks.AIR.defaultBlockState() : Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState());
                    }
                }
            }
        }
    }

    /** Puts the snow floor back after a round. */
    public static void resetSpleefFloor(ServerLevel level) {
        int cx = SPLEEF_CENTER.getX(), cz = SPLEEF_CENTER.getZ();
        for (int x = cx - SPLEEF_RADIUS; x <= cx + SPLEEF_RADIUS; x++) {
            for (int z = cz - SPLEEF_RADIUS; z <= cz + SPLEEF_RADIUS; z++) {
                if (inSpleefArena(x + 0.5, z + 0.5)) set(level, x, FLOOR_Y, z, Blocks.SNOW_BLOCK.defaultBlockState());
            }
        }
    }

    /** Floating text labels, made with the vanilla text_display entity. */
    private static void placeLabels(ServerLevel level) {
        CommandSourceStack source = level.getServer().createCommandSourceStack()
                .withLevel(level).withSuppressedOutput().withPermission(4);
        run(level, source, "kill @e[type=minecraft:text_display,tag=lobbyandselect]");
        label(level, source, SPAWN.x, FLOOR_Y + 4, SPAWN.z,
                "{\"text\":\"Welcome to the Lobby\",\"color\":\"gold\",\"bold\":true}");
        label(level, source, SPAWN.x, FLOOR_Y + 3.4, SPAWN.z,
                "{\"text\":\"Wait here for a spot in the world. /lobby for help\",\"color\":\"gray\"}");
        label(level, source, PARKOUR_START.getX() + 0.5, FLOOR_Y + 3, 0.5,
                "{\"text\":\"Parkour - step on the emerald to start\",\"color\":\"green\"}");
        BlockPos finish = parkourCourse().get(parkourCourse().size() - 1);
        label(level, source, finish.getX() + 0.5, finish.getY() + 3, finish.getZ() + 0.5,
                "{\"text\":\"Finish!\",\"color\":\"aqua\",\"bold\":true}");
        label(level, source, SPLEEF_JOIN_PAD.getX() + 0.5, FLOOR_Y + 3, 0.5,
                "{\"text\":\"Spleef - stand on the lapis to join\",\"color\":\"aqua\"}");
        label(level, source, SPLEEF_CENTER.getX() + 0.5, FLOOR_Y + 6, SPLEEF_CENTER.getZ() + 0.5,
                "{\"text\":\"SPLEEF\",\"color\":\"white\",\"bold\":true}");
    }

    private static void label(ServerLevel level, CommandSourceStack source, double x, double y, double z, String json) {
        String escaped = json.replace("\\", "\\\\").replace("'", "\\'");
        run(level, source, String.format(Locale.ROOT,
                "summon minecraft:text_display %.2f %.2f %.2f {Tags:[\"lobbyandselect\"],billboard:\"center\",alignment:\"center\",text:'%s'}",
                x, y, z, escaped));
    }

    private static void run(ServerLevel level, CommandSourceStack source, String command) {
        level.getServer().getCommands().performPrefixedCommand(source, command);
    }

    private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
        level.setBlock(new BlockPos(x, y, z), state, Block.UPDATE_CLIENTS);
    }
}
