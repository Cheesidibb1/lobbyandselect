package com.lobbyandselect;

import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Decides whether a latitude/longitude may be picked as a spawn by a given player. */
public final class LockedAreas {
    private LockedAreas() {}

    /** A reason the spot is locked for this player, or null if they may pick it. */
    @Nullable
    public static String reason(ServerPlayer player, double lat, double lon) {
        if (LobbyConfig.OPS_IGNORE_LOCKS.get() && player.hasPermissions(2)) return null;
        for (LobbyConfig.Area area : LobbyConfig.lockedAreas()) {
            if (area.contains(lat, lon)) {
                return area.name() + " is locked - you have to request access before you can spawn there.";
            }
        }
        if (LobbyConfig.RESPECT_OPENPAC_CLAIMS.get() && OpenPacBridge.available()) {
            String claim = claimedBy(player, lat, lon);
            if (claim != null) return "That spot is inside land claimed by " + claim + " - request to spawn there from them first.";
        }
        return null;
    }

    /** True if the chunk lies in a configured locked area (ops may be exempt). */
    public static boolean chunkLocked(ServerPlayer player, net.minecraft.world.level.ChunkPos pos) {
        if (LobbyConfig.OPS_IGNORE_LOCKS.get() && player.hasPermissions(2)) return false;
        double[] ll = EarthBridge.toLatLon(pos.getMiddleBlockX(), pos.getMiddleBlockZ(), LobbyConfig.METRES_PER_BLOCK.get());
        for (LobbyConfig.Area area : LobbyConfig.lockedAreas()) if (area.contains(ll[0], ll[1])) return true;
        return false;
    }

    @Nullable
    private static String claimedBy(ServerPlayer player, double lat, double lon) {
        ServerLevel level = EarthBridge.mainLevel(player.server);
        double metres = LobbyConfig.METRES_PER_BLOCK.get();
        UUID owner = OpenPacBridge.owner(player.server, level.dimension().location(),
                EarthBridge.blockX(lon, metres) >> 4, EarthBridge.blockZ(lat, metres) >> 4);
        if (owner == null || OpenPacBridge.sameParty(player.server, owner, player.getUUID())) return null;
        var cache = player.server.getProfileCache();
        var profile = cache == null ? null : cache.get(owner).orElse(null);
        return profile != null ? profile.getName() : owner.toString();
    }
}
