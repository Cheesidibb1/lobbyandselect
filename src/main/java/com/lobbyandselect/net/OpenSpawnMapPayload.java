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
public record OpenSpawnMapPayload(double maxLatitude, List<PlaceEntry> places) implements CustomPacketPayload {
    public static final Type<OpenSpawnMapPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LobbyAndSelect.MOD_ID, "open_spawn_map"));

    public record PlaceEntry(String name, double latitude, double longitude) {
        public static final StreamCodec<ByteBuf, PlaceEntry> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, PlaceEntry::name,
                ByteBufCodecs.DOUBLE, PlaceEntry::latitude,
                ByteBufCodecs.DOUBLE, PlaceEntry::longitude,
                PlaceEntry::new);
    }

    public static final StreamCodec<ByteBuf, OpenSpawnMapPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, OpenSpawnMapPayload::maxLatitude,
            PlaceEntry.CODEC.apply(ByteBufCodecs.list(256)), OpenSpawnMapPayload::places,
            OpenSpawnMapPayload::new);

    public static OpenSpawnMapPayload fromConfig() {
        List<PlaceEntry> places = LobbyConfig.places().stream()
                .limit(256)
                .map(p -> new PlaceEntry(p.name(), p.latitude(), p.longitude()))
                .toList();
        return new OpenSpawnMapPayload(LobbyConfig.MAX_LATITUDE.get(), places);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
