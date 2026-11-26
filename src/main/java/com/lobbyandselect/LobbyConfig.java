package com.lobbyandselect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Server config, saved per world in serverconfig/lobbyandselect-server.toml. */
public final class LobbyConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.ConfigValue<String> MAIN_DIMENSION;
    public static final ModConfigSpec.IntValue MAX_MAIN_PLAYERS;
    public static final ModConfigSpec.BooleanValue OPS_BYPASS_QUEUE;
    public static final ModConfigSpec.BooleanValue RETURNING_PLAYERS_USE_LOBBY;
    public static final ModConfigSpec.IntValue CHOOSING_TIMEOUT_SECONDS;

    public static final ModConfigSpec.BooleanValue USE_EARTH_COMMAND;
    public static final ModConfigSpec.DoubleValue METRES_PER_BLOCK;
    public static final ModConfigSpec.DoubleValue MAX_LATITUDE;
    public static final ModConfigSpec.BooleanValue RESPAWN_AT_CHOSEN_SPAWN;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> PLACES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> LOCKED_AREAS;
    public static final ModConfigSpec.BooleanValue RESPECT_OPENPAC_CLAIMS;
    public static final ModConfigSpec.BooleanValue OPS_IGNORE_LOCKS;

    public static final ModConfigSpec.IntValue SPLEEF_MIN_PLAYERS;
    public static final ModConfigSpec.BooleanValue TRIVIA_ENABLED;
    public static final ModConfigSpec.IntValue TRIVIA_INTERVAL_SECONDS;
    public static final ModConfigSpec.IntValue TRIVIA_ANSWER_SECONDS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("queue");
        MAIN_DIMENSION = b.comment("The dimension that counts as the main world. Earth Platform's Earth is 'earthplatform:cubic'.",
                        "If this dimension does not exist the overworld is used.")
                .define("mainDimension", "earthplatform:cubic");
        MAX_MAIN_PLAYERS = b.comment("How many players may be in the main world (any dimension other than the lobby) at once.",
                        "Everyone else waits in the lobby queue.")
                .defineInRange("maxMainWorldPlayers", 50, 0, 10_000);
        OPS_BYPASS_QUEUE = b.comment("Operators (permission level 2+) skip the queue.")
                .define("opsBypassQueue", true);
        RETURNING_PLAYERS_USE_LOBBY = b.comment("If false, players who already picked a spawn skip the lobby entirely when there is room.")
                .define("returningPlayersUseLobby", true);
        CHOOSING_TIMEOUT_SECONDS = b.comment("Seconds a let-in player may take to pick a spawn before their slot goes to the next in line (0 = no limit).")
                .defineInRange("choosingTimeoutSeconds", 300, 0, 86_400);
        b.pop();

        b.push("spawn");
        USE_EARTH_COMMAND = b.comment("Teleport with Earth Platform's '/earth goto' when it is installed (finds safe ground and loads terrain).")
                .define("useEarthCommand", true);
        METRES_PER_BLOCK = b.comment("Fallback only (Earth Platform missing or disabled): metres per block of the Web Mercator grid.",
                        "Earth Platform uses 1/cos(home latitude); 1.0 means home latitude on the equator.")
                .defineInRange("fallbackMetresPerBlock", 1.0, 0.0001, 1_000_000.0);
        MAX_LATITUDE = b.comment("Furthest north/south a spawn may be picked. Earth Platform's /earth goto accepts up to 80.")
                .defineInRange("maxLatitude", 80.0, 1.0, 85.0);
        RESPAWN_AT_CHOSEN_SPAWN = b.comment("Players who die without a bed or anchor respawn at the spawn point they picked.")
                .define("respawnAtChosenSpawn", true);
        PLACES = b.comment("Suggested places shown on the map, as 'Name;latitude;longitude'.")
                .defineListAllowEmpty("places", LobbyConfig::defaultPlaces, () -> "Name;0;0", LobbyConfig::validPlace);
        LOCKED_AREAS = b.comment("Areas nobody may pick as a spawn (e.g. countries that need a request first), as 'Name;minLat;minLon;maxLat;maxLon'.",
                        "Players can still be sent there by an admin or by the request system your server uses.")
                .defineListAllowEmpty("lockedAreas", List::of, () -> "Name;0;0;1;1", LobbyConfig::validArea);
        RESPECT_OPENPAC_CLAIMS = b.comment("If Open Parties and Claims is installed, a spot inside a chunk claimed by another player/party is locked.")
                .define("respectOpenPacClaims", true);
        OPS_IGNORE_LOCKS = b.comment("Operators (permission level 2+) may pick locked spawns.")
                .define("opsIgnoreLocks", false);
        b.pop();

        b.push("minigames");
        SPLEEF_MIN_PLAYERS = b.comment("Players needed before a spleef round starts.")
                .defineInRange("spleefMinPlayers", 2, 1, 64);
        TRIVIA_ENABLED = b.comment("Ask geography trivia questions in lobby chat.")
                .define("triviaEnabled", true);
        TRIVIA_INTERVAL_SECONDS = b.comment("Seconds between trivia questions.")
                .defineInRange("triviaIntervalSeconds", 90, 10, 3600);
        TRIVIA_ANSWER_SECONDS = b.comment("Seconds players have to answer.")
                .defineInRange("triviaAnswerSeconds", 30, 5, 600);
        b.pop();

        SPEC = b.build();
    }

    private LobbyConfig() {}

    public record Place(String name, double latitude, double longitude) {}

    /** The configured places that parse; bad entries are skipped. */
    public static List<Place> places() {
        List<Place> out = new ArrayList<>();
        for (String entry : PLACES.get()) {
            Place place = parsePlace(entry);
            if (place != null) out.add(place);
        }
        return out;
    }

    static Place parsePlace(String entry) {
        String[] parts = entry.split(";");
        if (parts.length != 3) return null;
        try {
            double lat = Double.parseDouble(parts[1].trim()), lon = Double.parseDouble(parts[2].trim());
            if (Math.abs(lat) > 85 || Math.abs(lon) > 180 || parts[0].isBlank()) return null;
            return new Place(parts[0].trim(), lat, lon);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record Area(String name, double minLat, double minLon, double maxLat, double maxLon) {
        public boolean contains(double lat, double lon) {
            return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
        }
    }

    public static List<Area> lockedAreas() {
        List<Area> out = new ArrayList<>();
        for (String entry : LOCKED_AREAS.get()) {
            Area area = parseArea(entry);
            if (area != null) out.add(area);
        }
        return out;
    }

    static Area parseArea(String entry) {
        String[] p = entry.split(";");
        if (p.length != 5 || p[0].isBlank()) return null;
        try {
            double a = Double.parseDouble(p[1].trim()), b = Double.parseDouble(p[2].trim());
            double c = Double.parseDouble(p[3].trim()), d = Double.parseDouble(p[4].trim());
            if (Math.abs(a) > 90 || Math.abs(c) > 90 || Math.abs(b) > 180 || Math.abs(d) > 180) return null;
            return new Area(p[0].trim(), Math.min(a, c), Math.min(b, d), Math.max(a, c), Math.max(b, d));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean validArea(Object o) {
        return o instanceof String s && parseArea(s) != null;
    }

    private static boolean validPlace(Object o) {
        return o instanceof String s && parsePlace(s) != null;
    }

    private static List<String> defaultPlaces() {
        List<String> list = new ArrayList<>();
        Object[][] places = {
                {"London", 51.5074, -0.1278}, {"New York", 40.7128, -74.0060}, {"Tokyo", 35.6762, 139.6503},
                {"Sydney", -33.8688, 151.2093}, {"Rio de Janeiro", -22.9068, -43.1729}, {"Cairo", 30.0444, 31.2357},
                {"Cape Town", -33.9249, 18.4241}, {"Reykjavik", 64.1466, -21.9426}, {"Grand Canyon", 36.1069, -112.1129},
                {"Mount Fuji", 35.3606, 138.7274}, {"Swiss Alps", 46.5197, 7.9603}, {"Vancouver", 49.2827, -123.1207},
                {"Mumbai", 19.0760, 72.8777}, {"Machu Picchu", -13.1631, -72.5450}, {"Yosemite", 37.8651, -119.5383},
                {"Nairobi", -1.2921, 36.8219}, {"Singapore", 1.3521, 103.8198}, {"Patagonia", -50.9423, -73.4068}
        };
        for (Object[] p : places) list.add(String.format(Locale.ROOT, "%s;%.4f;%.4f", p[0], p[1], p[2]));
        return list;
    }
}
