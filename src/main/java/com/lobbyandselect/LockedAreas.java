package com.lobbyandselect;

import java.lang.reflect.Method;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

/** Decides whether a latitude/longitude may be picked as a spawn by a given player. */
public final class LockedAreas {
    private static boolean openPacBroken;

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
        if (LobbyConfig.RESPECT_OPENPAC_CLAIMS.get() && ModList.get().isLoaded("openpartiesandclaims")) {
            String claim = claimedBy(player, lat, lon);
            if (claim != null) return "That spot is inside land claimed by " + claim + " - request to spawn there from them first.";
        }
        return null;
    }

    /** Owner name if the spot lies in another player's Open Parties and Claims claim (via reflection, so it stays optional). */
    @Nullable
    private static String claimedBy(ServerPlayer player, double lat, double lon) {
        if (openPacBroken) return null;
        try {
            ServerLevel level = EarthBridge.mainLevel(player.server);
            double metres = LobbyConfig.METRES_PER_BLOCK.get();
            int cx = EarthBridge.blockX(lon, metres) >> 4, cz = EarthBridge.blockZ(lat, metres) >> 4;

            Class<?> api = Class.forName("xaero.pac.common.server.api.OpenPACServerAPI");
            Object server = api.getMethod("get", net.minecraft.server.MinecraftServer.class).invoke(null, player.server);
            Object claims = server.getClass().getMethod("getServerClaimsManager").invoke(server);
            Method get = findMethod(claims.getClass(), "get", 3);
            Object claim = get.invoke(claims, level.dimension().location(), cx, cz);
            if (claim == null) return null;
            UUID owner = (UUID) claim.getClass().getMethod("getPlayerId").invoke(claim);
            if (owner.equals(player.getUUID())) return null;
            if (sameParty(server, owner, player.getUUID())) return null;
            var profile = player.server.getProfileCache() == null ? null : player.server.getProfileCache().get(owner).orElse(null);
            return profile != null ? profile.getName() : owner.toString();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            openPacBroken = true;
            LobbyAndSelect.LOGGER.warn("Open Parties and Claims claim check failed; claims will not lock spawns", e);
            return null;
        }
    }

    private static boolean sameParty(Object server, UUID a, UUID b) throws ReflectiveOperationException {
        Object parties = server.getClass().getMethod("getPartyManager").invoke(server);
        Method byMember = parties.getClass().getMethod("getPartyByMember", UUID.class);
        Object pa = byMember.invoke(parties, a), pb = byMember.invoke(parties, b);
        if (pa == null || pb == null) return false;
        Object ida = pa.getClass().getMethod("getId").invoke(pa), idb = pb.getClass().getMethod("getId").invoke(pb);
        return ida.equals(idb);
    }

    private static Method findMethod(Class<?> type, String name, int args) throws NoSuchMethodException {
        for (Method m : type.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == args) return m;
        throw new NoSuchMethodException(name);
    }
}
