package com.lobbyandselect;

import java.util.Locale;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.fml.ModList;

/**
 * Sends players to a latitude and longitude on the Earth.
 *
 * <p>With Earth Platform installed this runs its {@code /earth goto <lat> <lon> <player>}, which
 * fetches the terrain, finds dry safe ground and lands the player in the Earth dimension. Without
 * it the same Web Mercator projection Earth Platform uses (origin at 0°, 0°; X east, Z south;
 * {@code x = rad(lon) * R / m}, {@code z = -ln(tan(π/4 + rad(lat)/2)) * R / m}) is applied to the
 * main dimension and the player is put on its surface.
 */
public final class EarthBridge {
    /** Earth Platform's Web Mercator sphere radius, in metres. */
    private static final double RADIUS = 6_378_137.0;

    private EarthBridge() {}

    public static boolean earthPlatformLoaded() {
        return ModList.get().isLoaded("earthplatform");
    }

    /** The configured main dimension if it exists, else the overworld. */
    public static ServerLevel mainLevel(MinecraftServer server) {
        ResourceLocation id = ResourceLocation.tryParse(LobbyConfig.MAIN_DIMENSION.get());
        if (id != null) {
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
            if (level != null) return level;
        }
        return server.overworld();
    }

    public static boolean isMainSide(Level level) {
        return !LobbyWorld.isLobby(level);
    }

    /** Starts the trip; arrival may come a few seconds later while Earth terrain loads. */
    public static void travel(ServerPlayer player, double latitude, double longitude) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        if (LobbyConfig.USE_EARTH_COMMAND.get() && earthPlatformLoaded()
                && server.getCommands().getDispatcher().getRoot().getChild("earth") != null) {
            // Run as the player but with operator rights, so Earth Platform's progress and errors reach them.
            String command = String.format(Locale.ROOT, "earth goto %.6f %.6f %s", latitude, longitude, player.getGameProfile().getName());
            server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4), command);
            return;
        }
        fallbackTravel(player, latitude, longitude);
    }

    public static int blockX(double longitude, double metresPerBlock) {
        return (int) Math.floor(Math.toRadians(longitude) * RADIUS / metresPerBlock);
    }

    public static int blockZ(double latitude, double metresPerBlock) {
        double lat = Math.max(-85.0, Math.min(85.0, latitude));
        return (int) Math.floor(-Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2)) * RADIUS / metresPerBlock);
    }

    /** Inverse of {@link #blockX}/{@link #blockZ}: [latitude, longitude] of a block position. */
    public static double[] toLatLon(double x, double z, double metresPerBlock) {
        double lon = Math.toDegrees(x * metresPerBlock / RADIUS);
        double lat = Math.toDegrees(2 * Math.atan(Math.exp(-z * metresPerBlock / RADIUS)) - Math.PI / 2);
        return new double[] {lat, lon};
    }

    private static void fallbackTravel(ServerPlayer player, double latitude, double longitude) {
        ServerLevel level = mainLevel(player.server);
        double metres = LobbyConfig.METRES_PER_BLOCK.get();
        double lat = Math.max(-85.0, Math.min(85.0, latitude));
        int x = blockX(longitude, metres), z = blockZ(lat, metres);
        level.getChunk(x >> 4, z >> 4); // generate it so the heightmap is real
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= level.getMinBuildHeight()) y = level.getSeaLevel() + 1;
        player.teleportTo(level, x + 0.5, y + 1, z + 0.5, player.getYRot(), player.getXRot());
        player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 200, 0, false, false));
    }

    /** Puts a returning player back where they left the main world. */
    public static boolean returnTo(ServerPlayer player, LobbyData.ReturnPoint point) {
        ServerLevel level = player.server.getLevel(point.dimension());
        if (level == null || LobbyWorld.isLobby(level)) return false;
        // Terrain may still be streaming in when they arrive; don't let them drop through it.
        player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 200, 0, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 200, 4, false, false));
        player.teleportTo(level, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
        return true;
    }
}
