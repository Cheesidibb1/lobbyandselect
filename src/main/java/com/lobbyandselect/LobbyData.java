package com.lobbyandselect;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/** Everything the lobby remembers across restarts, stored with the overworld's data. */
public final class LobbyData extends SavedData {
    private static final String NAME = LobbyAndSelect.MOD_ID;

    /** Where a player left the main world, to put them back there. */
    public record ReturnPoint(ResourceKey<Level> dimension, double x, double y, double z, float yaw, float pitch) {}

    public static final class PlayerRecord {
        public String name = "";
        /** Has this player ever been let into the main world? */
        public boolean joinedBefore;
        /** Has this player picked their spawn? Once true it never changes (except by an admin reset). */
        public boolean spawnChosen;
        public double spawnLatitude, spawnLongitude;
        public long chosenAt;
        @Nullable public ReturnPoint returnPoint;
        @Nullable public GameType gameMode;
        public long parkourBestTicks = -1;
        public int spleefWins;
        public int triviaPoints;

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("name", name);
            tag.putBoolean("joinedBefore", joinedBefore);
            tag.putBoolean("spawnChosen", spawnChosen);
            tag.putDouble("lat", spawnLatitude);
            tag.putDouble("lon", spawnLongitude);
            tag.putLong("chosenAt", chosenAt);
            if (returnPoint != null) {
                CompoundTag r = new CompoundTag();
                r.putString("dim", returnPoint.dimension().location().toString());
                r.putDouble("x", returnPoint.x());
                r.putDouble("y", returnPoint.y());
                r.putDouble("z", returnPoint.z());
                r.putFloat("yaw", returnPoint.yaw());
                r.putFloat("pitch", returnPoint.pitch());
                tag.put("return", r);
            }
            if (gameMode != null) tag.putString("gameMode", gameMode.getName());
            tag.putLong("parkourBest", parkourBestTicks);
            tag.putInt("spleefWins", spleefWins);
            tag.putInt("triviaPoints", triviaPoints);
            return tag;
        }

        static PlayerRecord load(CompoundTag tag) {
            PlayerRecord p = new PlayerRecord();
            p.name = tag.getString("name");
            p.joinedBefore = tag.getBoolean("joinedBefore");
            p.spawnChosen = tag.getBoolean("spawnChosen");
            p.spawnLatitude = tag.getDouble("lat");
            p.spawnLongitude = tag.getDouble("lon");
            p.chosenAt = tag.getLong("chosenAt");
            if (tag.contains("return", Tag.TAG_COMPOUND)) {
                CompoundTag r = tag.getCompound("return");
                ResourceLocation dim = ResourceLocation.tryParse(r.getString("dim"));
                if (dim != null) {
                    p.returnPoint = new ReturnPoint(ResourceKey.create(Registries.DIMENSION, dim),
                            r.getDouble("x"), r.getDouble("y"), r.getDouble("z"), r.getFloat("yaw"), r.getFloat("pitch"));
                }
            }
            if (tag.contains("gameMode")) p.gameMode = GameType.byName(tag.getString("gameMode"), null);
            p.parkourBestTicks = tag.contains("parkourBest") ? tag.getLong("parkourBest") : -1;
            p.spleefWins = tag.getInt("spleefWins");
            p.triviaPoints = tag.getInt("triviaPoints");
            return p;
        }
    }

    private final Map<UUID, PlayerRecord> players = new HashMap<>();
    private int builtVersion;

    public static LobbyData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(LobbyData::new, LobbyData::load, null), NAME);
    }

    private static LobbyData load(CompoundTag tag, HolderLookup.Provider registries) {
        LobbyData data = new LobbyData();
        data.builtVersion = tag.getInt("builtVersion");
        CompoundTag list = tag.getCompound("players");
        for (String key : list.getAllKeys()) {
            try {
                data.players.put(UUID.fromString(key), PlayerRecord.load(list.getCompound(key)));
            } catch (IllegalArgumentException ignored) {
                // A corrupt key; skip it.
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("builtVersion", builtVersion);
        CompoundTag list = new CompoundTag();
        players.forEach((id, record) -> list.put(id.toString(), record.save()));
        tag.put("players", list);
        return tag;
    }

    public PlayerRecord player(UUID id) {
        return players.computeIfAbsent(id, k -> new PlayerRecord());
    }

    @Nullable
    public PlayerRecord existing(UUID id) {
        return players.get(id);
    }

    public Map<UUID, PlayerRecord> all() {
        return players;
    }

    public int builtVersion() {
        return builtVersion;
    }

    public void setBuiltVersion(int version) {
        builtVersion = version;
        setDirty();
    }
}
