package com.lobbyandselect.client;

import com.lobbyandselect.net.ChooseSpawnPayload;
import com.lobbyandselect.net.OpenSpawnMapPayload;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;

/**
 * The spawn picker: a zoomable Web Mercator map of the Earth. Drag to pan, scroll to zoom, click
 * to drop a pin (or click a marked place), then confirm. The choice is permanent.
 */
public final class SpawnMapScreen extends Screen {
    private static final int TOP = 28, BOTTOM = 52, SIDE = 10;
    private static final double MAX_ZOOM = 256;

    private final OpenSpawnMapPayload data;

    // View: centre in normalised Mercator coordinates and pixels per normalised unit.
    private double centreU = 0.5, centreV = 0.5, scale = -1;
    private boolean dragging;
    private double pressX, pressY;

    @Nullable private Double pickLat, pickLon;
    @Nullable private String pickName;
    private Button confirm;
    @Nullable private String lockedNote;

    public SpawnMapScreen(OpenSpawnMapPayload data) {
        super(Component.literal("Choose your spawn point"));
        this.data = data;
    }

    @Override
    protected void init() {
        int y = height - BOTTOM + 26;
        confirm = addRenderableWidget(Button.builder(Component.literal("Spawn here"), b -> askConfirm())
                .bounds(width / 2 - 154, y, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Later"), b -> onClose())
                .bounds(width / 2 + 4, y, 150, 20).build());
        double min = minScale();
        if (scale < min) {
            scale = min;
        }
        clampView();
        updateConfirm();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // Geometry --------------------------------------------------------------------------------

    private int mapLeft() { return SIDE; }
    private int mapTop() { return TOP; }
    private int mapRight() { return width - SIDE; }
    private int mapBottom() { return height - BOTTOM; }
    private int mapWidth() { return mapRight() - mapLeft(); }
    private int mapHeight() { return mapBottom() - mapTop(); }

    /** Smallest zoom: the map fills the view. */
    private double minScale() {
        return Math.max(mapWidth(), mapHeight());
    }

    private void clampView() {
        double halfU = mapWidth() / 2.0 / scale, halfV = mapHeight() / 2.0 / scale;
        centreU = Mth.clamp(centreU, halfU, 1 - halfU);
        centreV = Mth.clamp(centreV, halfV, 1 - halfV);
    }

    private double screenX(double u) { return mapLeft() + mapWidth() / 2.0 + (u - centreU) * scale; }
    private double screenY(double v) { return mapTop() + mapHeight() / 2.0 + (v - centreV) * scale; }
    private double toU(double x) { return centreU + (x - mapLeft() - mapWidth() / 2.0) / scale; }
    private double toV(double y) { return centreV + (y - mapTop() - mapHeight() / 2.0) / scale; }

    private boolean overMap(double x, double y) {
        return x >= mapLeft() && x < mapRight() && y >= mapTop() && y < mapBottom();
    }

    // Rendering -------------------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xF0101418);
        drawMap(g);
        drawOverlay(g, mouseX, mouseY);
    }

    private void drawMap(GuiGraphics g) {
        g.flush();
        float u0 = (float) toU(mapLeft()), u1 = (float) toU(mapRight());
        float v0 = (float) toV(mapTop()), v1 = (float) toV(mapBottom());
        RenderSystem.setShaderTexture(0, EarthMapTexture.get());
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        Matrix4f matrix = g.pose().last().pose();
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buffer.addVertex(matrix, mapLeft(), mapTop(), 0).setUv(u0, v0);
        buffer.addVertex(matrix, mapLeft(), mapBottom(), 0).setUv(u0, v1);
        buffer.addVertex(matrix, mapRight(), mapBottom(), 0).setUv(u1, v1);
        buffer.addVertex(matrix, mapRight(), mapTop(), 0).setUv(u1, v0);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    private void drawOverlay(GuiGraphics g, int mouseX, int mouseY) {
        g.enableScissor(mapLeft(), mapTop(), mapRight(), mapBottom());

        // Out-of-bounds bands near the poles.
        double max = data.maxLatitude();
        int north = (int) screenY(EarthMapTexture.v(max)), south = (int) screenY(EarthMapTexture.v(-max));
        g.fill(mapLeft(), mapTop(), mapRight(), Math.max(mapTop(), north), 0x99000000);
        g.fill(mapLeft(), Math.min(mapBottom(), south), mapRight(), mapBottom(), 0x99000000);

        // Graticule: every 30° when zoomed out, finer as you zoom in.
        double step = scale / minScale() > 16 ? 1 : scale / minScale() > 4 ? 5 : scale / minScale() > 1.5 ? 10 : 30;
        for (double lon = -180; lon <= 180; lon += step) {
            int x = (int) screenX(EarthMapTexture.u(lon));
            g.fill(x, mapTop(), x + 1, mapBottom(), lon == 0 ? 0x66FFFFFF : 0x33FFFFFF);
        }
        for (double lat = -80; lat <= 80; lat += step) {
            int y = (int) screenY(EarthMapTexture.v(lat));
            g.fill(mapLeft(), y, mapRight(), y + 1, lat == 0 ? 0x66FFFFFF : 0x33FFFFFF);
        }

        // Locked areas: red, and they can't be picked.
        for (OpenSpawnMapPayload.AreaEntry area : data.locked()) {
            int x0 = (int) screenX(EarthMapTexture.u(area.minLon())), x1 = (int) screenX(EarthMapTexture.u(area.maxLon()));
            int y0 = (int) screenY(EarthMapTexture.v(area.maxLat())), y1 = (int) screenY(EarthMapTexture.v(area.minLat()));
            g.fill(x0, y0, x1, y1, 0x66E03030);
            g.renderOutline(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0), 0xFFE03030);
            if (x1 - x0 > font.width(area.name()) + 4) g.drawString(font, "🔒 " + area.name(), x0 + 3, y0 + 3, 0xFFFFFFFF, true);
        }

        // Places.
        OpenSpawnMapPayload.PlaceEntry hovered = placeAt(mouseX, mouseY);
        for (OpenSpawnMapPayload.PlaceEntry place : data.places()) {
            int x = (int) screenX(EarthMapTexture.u(place.longitude())), y = (int) screenY(EarthMapTexture.v(place.latitude()));
            g.fill(x - 2, y - 2, x + 3, y + 3, 0xFF000000);
            g.fill(x - 1, y - 1, x + 2, y + 2, place == hovered ? 0xFFFFFF55 : 0xFFFFCC00);
            if (place == hovered || scale / minScale() > 2.5) {
                g.drawString(font, place.name(), x + 5, y - 4, 0xFFFFFFFF, true);
            }
        }

        // The pin.
        if (pickLat != null && pickLon != null) {
            int x = (int) screenX(EarthMapTexture.u(pickLon)), y = (int) screenY(EarthMapTexture.v(pickLat));
            g.fill(x - 1, y - 9, x + 2, y + 1, 0xFF000000);
            g.fill(x - 4, y - 13, x + 5, y - 6, 0xFF000000);
            g.fill(x - 3, y - 12, x + 4, y - 7, 0xFFE03030);
            g.fill(x, y - 8, x + 1, y, 0xFFE03030);
        }
        g.disableScissor();

        // Frame.
        g.renderOutline(mapLeft() - 1, mapTop() - 1, mapWidth() + 2, mapHeight() + 2, 0xFF808080);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        g.drawCenteredString(font, Component.literal("Drag to move, scroll to zoom, click to choose. You can only choose once!")
                .withStyle(ChatFormatting.GRAY), width / 2, 18, 0xFFFFFF);

        String info;
        if (pickLat != null && pickLon != null) {
            info = (pickName != null ? pickName + "  " : "") + coordinates(pickLat, pickLon);
        } else {
            info = lockedNote != null ? lockedNote : "Click the map to choose a spot";
        }
        g.drawCenteredString(font, info, width / 2, height - BOTTOM + 8,
                pickLat != null ? 0xFF55FF55 : lockedNote != null ? 0xFFFF5555 : 0xFFAAAAAA);

        if (overMap(mouseX, mouseY)) {
            double lat = EarthMapTexture.latitude(toV(mouseY)), lon = EarthMapTexture.longitude(toU(mouseX));
            g.drawString(font, coordinates(lat, lon), mapLeft() + 4, mapBottom() - 12, 0xFFFFFFFF, true);
        }
        if (!EarthMapTexture.hasImagery()) {
            g.drawString(font, "Earth imagery unavailable - install Earth Platform on your client for a full map",
                    mapLeft() + 4, mapTop() + 4, 0xFFFFAA00, true);
        }
    }

    private static String coordinates(double lat, double lon) {
        return String.format(Locale.ROOT, "%.4f°%s, %.4f°%s",
                Math.abs(lat), lat >= 0 ? "N" : "S", Math.abs(lon), lon >= 0 ? "E" : "W");
    }

    // Input -----------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button == 0 && overMap(mouseX, mouseY)) {
            dragging = true;
            pressX = mouseX;
            pressY = mouseY;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging && button == 0) {
            centreU -= dragX / scale;
            centreV -= dragY / scale;
            clampView();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging && button == 0) {
            dragging = false;
            if (Math.abs(mouseX - pressX) < 4 && Math.abs(mouseY - pressY) < 4) pick(mouseX, mouseY);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!overMap(mouseX, mouseY) || scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        // Zoom about the cursor: the point under it stays put.
        double u = toU(mouseX), v = toV(mouseY);
        scale = Mth.clamp(scale * Math.pow(1.25, scrollY), minScale(), minScale() * MAX_ZOOM);
        centreU = u - (mouseX - mapLeft() - mapWidth() / 2.0) / scale;
        centreV = v - (mouseY - mapTop() - mapHeight() / 2.0) / scale;
        clampView();
        return true;
    }

    @Nullable
    private OpenSpawnMapPayload.PlaceEntry placeAt(double mouseX, double mouseY) {
        if (!overMap(mouseX, mouseY)) return null;
        OpenSpawnMapPayload.PlaceEntry best = null;
        double bestDistance = 36;
        for (OpenSpawnMapPayload.PlaceEntry place : data.places()) {
            double dx = screenX(EarthMapTexture.u(place.longitude())) - mouseX, dy = screenY(EarthMapTexture.v(place.latitude())) - mouseY;
            double d = dx * dx + dy * dy;
            if (d < bestDistance) {
                bestDistance = d;
                best = place;
            }
        }
        return best;
    }

    private void pick(double mouseX, double mouseY) {
        OpenSpawnMapPayload.PlaceEntry place = placeAt(mouseX, mouseY);
        double lat, lon;
        if (place != null) {
            lat = place.latitude();
            lon = place.longitude();
        } else {
            lat = EarthMapTexture.latitude(toV(mouseY));
            lon = EarthMapTexture.longitude(toU(mouseX));
        }
        for (OpenSpawnMapPayload.AreaEntry area : data.locked()) {
            if (area.contains(lat, lon)) {
                pickLat = pickLon = null;
                pickName = null;
                lockedNote = area.name() + " is locked - request access first";
                updateConfirm();
                return;
            }
        }
        lockedNote = null;
        if (Math.abs(lat) > data.maxLatitude()) {
            pickLat = pickLon = null;
            pickName = null;
        } else {
            pickLat = lat;
            pickLon = Mth.clamp(lon, -180, 180);
            pickName = place != null ? place.name() : null;
        }
        updateConfirm();
    }

    private void updateConfirm() {
        if (confirm != null) confirm.active = pickLat != null;
    }

    private void askConfirm() {
        if (pickLat == null || pickLon == null || minecraft == null) return;
        double lat = pickLat, lon = pickLon;
        String where = (pickName != null ? pickName + " (" : "") + coordinates(lat, lon) + (pickName != null ? ")" : "");
        minecraft.setScreen(new ConfirmScreen(yes -> {
            if (yes) {
                PacketDistributor.sendToServer(new ChooseSpawnPayload(lat, lon));
                minecraft.setScreen(null);
            } else {
                minecraft.setScreen(this);
            }
        }, Component.literal("Spawn at " + where + "?"),
                Component.literal("This is permanent - you can't change your spawn point later.").withStyle(ChatFormatting.YELLOW)));
    }
}
