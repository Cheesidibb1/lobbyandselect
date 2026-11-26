package com.lobbyandselect.net;

import com.lobbyandselect.LobbyAndSelect;
import com.lobbyandselect.LobbyConfig;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server → client: open the spawn picker map. */
public record OpenSpawnMapPayload(double maxLatitude, List<PlaceEntry> places, List<AreaEntry> locked) implements CustomPacketPayload {
    public static final Type<OpenSpawnMapPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LobbyAndSelect.MOD_ID, "open_spawn_map"));

    public record PlaceEntry(String name, double latitude, double longitude) {
        public static final StreamCodec<ByteBuf, PlaceEntry> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, PlaceEntry::name,
                ByteBufCodecs.DOUBLE, PlaceEntry::latitude,
                ByteBufCodecs.DOUBLE, PlaceEntry::longitude,
                PlaceEntry::new);
    }

    public record AreaEntry(String name, double minLat, double minLon, double maxLat, double maxLon) {
        public static final StreamCodec<ByteBuf, AreaEntry> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, AreaEntry::name,
                ByteBufCodecs.DOUBLE, AreaEntry::minLat,
                ByteBufCodecs.DOUBLE, AreaEntry::minLon,
                ByteBufCodecs.DOUBLE, AreaEntry::maxLat,
                ByteBufCodecs.DOUBLE, AreaEntry::maxLon,
                AreaEntry::new);

        public boolean contains(double lat, double lon) {
            return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
        }
    }

    public static final StreamCodec<ByteBuf, OpenSpawnMapPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, OpenSpawnMapPayload::maxLatitude,
            PlaceEntry.CODEC.apply(ByteBufCodecs.list(256)), OpenSpawnMapPayload::places,
            AreaEntry.CODEC.apply(ByteBufCodecs.list(256)), OpenSpawnMapPayload::locked,
            OpenSpawnMapPayload::new);

    public static OpenSpawnMapPayload fromConfig(net.minecraft.server.level.ServerPlayer player) {
        List<PlaceEntry> places = LobbyConfig.places().stream()
                .limit(256)
                .map(p -> new PlaceEntry(p.name(), p.latitude(), p.longitude()))
                .toList();
        boolean exempt = LobbyConfig.OPS_IGNORE_LOCKS.get() && player.hasPermissions(2);
        List<AreaEntry> locked = exempt ? List.of() : LobbyConfig.lockedAreas().stream().limit(256)
                .map(a -> new AreaEntry(a.name(), a.minLat(), a.minLon(), a.maxLat(), a.maxLon())).toList();
        return new OpenSpawnMapPayload(LobbyConfig.MAX_LATITUDE.get(), places, locked);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
