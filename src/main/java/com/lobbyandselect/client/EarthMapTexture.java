package com.lobbyandselect.client;

import com.lobbyandselect.LobbyAndSelect;
import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

/**
 * The world map texture, in the same Web Mercator projection Earth Platform builds its world with,
 * so a point on the map is exactly where the player lands. Uses the NASA Blue Marble image that
 * ships with Earth Platform; without it, draws a plain ocean so the grid and places still work.
 */
public final class EarthMapTexture {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(LobbyAndSelect.MOD_ID, "dynamic/earth_map");
    private static final ResourceLocation BLUE_MARBLE = ResourceLocation.fromNamespaceAndPath("earthplatform", "textures/globe/blue_marble.jpg");
    /** Latitude at the top and bottom edge of a square Web Mercator map. */
    public static final double MERCATOR_MAX_LAT = 85.05112877980659;
    public static final int SIZE = 2048;

    private static boolean built;
    private static boolean imagery;

    private EarthMapTexture() {}

    /** Builds the texture the first time it's needed. */
    public static ResourceLocation get() {
        if (!built) {
            built = true;
            build();
        }
        return ID;
    }

    public static boolean hasImagery() {
        return imagery;
    }

    private static void build() {
        BufferedImage source = loadSource();
        imagery = source != null;
        NativeImage image = new NativeImage(SIZE, SIZE, false);
        int sw = source == null ? 0 : source.getWidth(), sh = source == null ? 0 : source.getHeight();
        int[] row = source == null ? null : new int[sw];
        int lastSourceRow = -1;
        for (int y = 0; y < SIZE; y++) {
            double v = (y + 0.5) / SIZE;
            double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * v))));
            if (source != null) {
                int sy = Math.min(sh - 1, Math.max(0, (int) ((90 - lat) / 180.0 * sh)));
                if (sy != lastSourceRow) {
                    source.getRGB(0, sy, sw, 1, row, 0, sw);
                    lastSourceRow = sy;
                }
            }
            for (int x = 0; x < SIZE; x++) {
                int argb;
                if (source != null) {
                    int sx = Math.min(sw - 1, (int) ((x + 0.5) / SIZE * sw));
                    argb = row[sx];
                } else {
                    // Plain ocean, a little lighter toward the equator.
                    int shade = (int) (20 * Math.cos(Math.toRadians(lat)));
                    argb = 0xFF000000 | (20 + shade) << 16 | (60 + shade) << 8 | (120 + shade);
                }
                int r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, b = argb & 0xFF;
                image.setPixelRGBA(x, y, 0xFF000000 | b << 16 | g << 8 | r);
            }
        }
        DynamicTexture texture = new DynamicTexture(image);
        texture.setFilter(true, false);
        Minecraft.getInstance().getTextureManager().register(ID, texture);
    }

    @Nullable
    private static BufferedImage loadSource() {
        try {
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(BLUE_MARBLE);
            if (resource.isEmpty()) {
                LobbyAndSelect.LOGGER.info("Earth Platform's Blue Marble isn't available; the spawn map shows a plain grid.");
                return null;
            }
            try (InputStream in = resource.get().open()) {
                return ImageIO.read(in);
            }
        } catch (Exception e) {
            LobbyAndSelect.LOGGER.warn("Could not load the Earth image for the spawn map", e);
            return null;
        }
    }

    /** Web Mercator normalised X (0..1, west to east) for a longitude. */
    public static double u(double longitude) {
        return (longitude + 180) / 360;
    }

    /** Web Mercator normalised Y (0..1, north to south) for a latitude. */
    public static double v(double latitude) {
        double phi = Math.toRadians(Math.max(-MERCATOR_MAX_LAT, Math.min(MERCATOR_MAX_LAT, latitude)));
        return (1 - Math.log(Math.tan(Math.PI / 4 + phi / 2)) / Math.PI) / 2;
    }

    public static double longitude(double u) {
        return u * 360 - 180;
    }

    public static double latitude(double v) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * v))));
    }
}
