package com.lobbyandselect.net;

import com.lobbyandselect.LobbyAndSelect;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → server: the player picked this spawn point. */
public record ChooseSpawnPayload(double latitude, double longitude) implements CustomPacketPayload {
    public static final Type<ChooseSpawnPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LobbyAndSelect.MOD_ID, "choose_spawn"));

    public static final StreamCodec<ByteBuf, ChooseSpawnPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, ChooseSpawnPayload::latitude,
            ByteBufCodecs.DOUBLE, ChooseSpawnPayload::longitude,
            ChooseSpawnPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
