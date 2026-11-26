package com.lobbyandselect;

import java.lang.reflect.Method;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

/**
 * Talks to Open Parties and Claims by reflection so it stays an optional dependency. OPAC itself
 * syncs claim changes to Xaero's World Map / Minimap, so claims made here show up there too.
 * Any reflection failure disables the bridge (logged once) rather than crashing.
 */
public final class OpenPacBridge {
    private static boolean broken;

    private OpenPacBridge() {}

    public static boolean available() {
        return !broken && ModList.get().isLoaded("openpartiesandclaims");
    }

    private static Object claims(MinecraftServer server) throws ReflectiveOperationException {
        Class<?> api = Class.forName("xaero.pac.common.server.api.OpenPACServerAPI");
        Object s = api.getMethod("get", MinecraftServer.class).invoke(null, server);
        return s.getClass().getMethod("getServerClaimsManager").invoke(s);
    }

    private static Object parties(MinecraftServer server) throws ReflectiveOperationException {
        Class<?> api = Class.forName("xaero.pac.common.server.api.OpenPACServerAPI");
        Object s = api.getMethod("get", MinecraftServer.class).invoke(null, server);
        return s.getClass().getMethod("getPartyManager").invoke(s);
    }

    private static Method method(Class<?> type, String name, int args) throws NoSuchMethodException {
        for (Method m : type.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == args) return m;
        throw new NoSuchMethodException(name);
    }

    private static void fail(Exception e) {
        broken = true;
        LobbyAndSelect.LOGGER.warn("Open Parties and Claims integration failed and is now disabled", e);
    }

    /** Owner of the claimed chunk, or null if unclaimed / unavailable. */
    @Nullable
    public static UUID owner(MinecraftServer server, ResourceLocation dimension, int cx, int cz) {
        if (!available()) return null;
        try {
            Object claims = claims(server);
            Object claim = method(claims.getClass(), "get", 3).invoke(claims, dimension, cx, cz);
            return claim == null ? null : (UUID) claim.getClass().getMethod("getPlayerId").invoke(claim);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail(e instanceof Exception ex ? ex : new RuntimeException(e));
            return null;
        }
    }

    public static boolean sameParty(MinecraftServer server, UUID a, UUID b) {
        if (a.equals(b)) return true;
        if (!available()) return false;
        try {
            Object parties = parties(server);
            Method byMember = parties.getClass().getMethod("getPartyByMember", UUID.class);
            Object pa = byMember.invoke(parties, a), pb = byMember.invoke(parties, b);
            if (pa == null || pb == null) return false;
            return pa.getClass().getMethod("getId").invoke(pa).equals(pb.getClass().getMethod("getId").invoke(pb));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail(e instanceof Exception ex ? ex : new RuntimeException(e));
            return false;
        }
    }

    /** Claim one chunk for a player (OPAC applies its own per-player claim limits). Verify with {@link #owner}. */
    public static void claim(MinecraftServer server, ResourceLocation dimension, UUID player, int cx, int cz) {
        if (!available()) return;
        try {
            Object claims = claims(server);
            Method m = method(claims.getClass(), "tryToClaim", 8); // dim, player, subConfig, fromX, fromZ, toX, toZ, replace
            m.invoke(claims, dimension, player, -1, cx, cz, cx, cz, false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail(e instanceof Exception ex ? ex : new RuntimeException(e));
        }
    }

    public static void unclaim(MinecraftServer server, ResourceLocation dimension, UUID player, int cx, int cz) {
        if (!available()) return;
        try {
            Object claims = claims(server);
            Method m = method(claims.getClass(), "tryToUnclaim", 7); // dim, player, fromX, fromZ, toX, toZ, replace
            m.invoke(claims, dimension, player, cx, cz, cx, cz, false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail(e instanceof Exception ex ? ex : new RuntimeException(e));
        }
    }
}
