package com.justfarming.gui;

import com.justfarming.CropType;
import com.justfarming.config.FarmingConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

/**
 * Per-crop settings screen.
 *
 * <p>Lets the player adjust the camera yaw and pitch for the currently
 * selected crop.  A <em>Reset to Default</em> button removes any saved
 * override so the built-in values take effect again.
 */
public class CropSettingsScreen extends Screen {

    // ── Colour palette (matches FarmingConfigScreen new style) ──────────────
    private static final int COL_SCREEN_DIM  = 0x60000000;
    private static final int COL_WIN_BG      = 0xBF000000;
    private static final int COL_BORDER      = 0x28FFFFFF;
    private static final int COL_SEP         = 0x14FFFFFF;
    private static final int COL_SECTION_BG  = 0x14FFFFFF;
    private static final int COL_TEXT        = 0xF2FFFFFF;
    private static final int COL_TEXT_MUTED  = 0x66FFFFFF;
    private static final int COL_ACCENT      = 0xFF7C4DFF;
    private static final int COL_SHADOW      = 0x60000000;

    // ── Natural panel dimensions ───────────────────────────────────────────────
    private static final int PANEL_WIDTH   = 320;
    private static final int PANEL_HEIGHT  = 200;
    private static final int HEADER_HEIGHT = 42;
    private static final int BUTTON_WIDTH  = 240;
    private static final int BUTTON_HEIGHT = 20;
    private static final int PADDING       = 6;

    private final Screen        parent;
    private final FarmingConfig config;
    private final CropType      crop;

    // ── Widgets ───────────────────────────────────────────────────────────────
    private YawSlider         yawSlider;
    private PitchSlider       pitchSlider;
    private FlatButtonWidget  resetButton;
    private FlatButtonWidget  saveCloseButton;

    // ── Section-label Y positions (set in init, used in render) ───────────────
    private int sectionCameraY;

    // ── Scale computed in init (used in render) ────────────────────────────────
    private float scale;
    private int   panelX, panelY, panelW, panelH;

    // ── Initial (working) values ──────────────────────────────────────────────
    private final float   initYaw, initPitch;

    public CropSettingsScreen(Screen parent, FarmingConfig config) {
        super(Text.literal("Crop Settings"));
        this.parent = parent;
        this.config = config;
        this.crop   = config.selectedCrop;

        // Load values from saved override, or fall back to crop defaults
        FarmingConfig.CropCustomSettings cs = config.getCropSettings(crop);
        FarmingConfig.CropCustomSettings defaults = FarmingConfig.CropCustomSettings.fromDefaults(crop);
        if (cs != null) {
            initYaw   = cs.yaw;
            initPitch = cs.pitch;
        } else {
            initYaw   = defaults.yaw;
            initPitch = defaults.pitch;
        }
    }

    @Override
    protected void init() {
        float sw = (float)(this.width  - 4) / PANEL_WIDTH;
        float sh = (float)(this.height - 4) / PANEL_HEIGHT;
        scale  = Math.min(1.0f, Math.min(sw, sh));
        panelW = Math.round(PANEL_WIDTH  * scale);
        panelH = Math.round(PANEL_HEIGHT * scale);
        panelX = (this.width  - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        int hdrH = Math.round(HEADER_HEIGHT * scale);
        int bw   = Math.round(BUTTON_WIDTH  * scale);
        int bh   = Math.max(8,  Math.round(BUTTON_HEIGHT * scale));
        int pad  = Math.max(2,  Math.round(PADDING       * scale));
        int sLH  = Math.max(8,  Math.round(10            * scale));
        int gap  = Math.max(2,  Math.round(6             * scale));

        int widgetX = panelX + panelW / 2 - bw / 2;
        int y       = panelY + hdrH + pad;

        // ── Camera section ────────────────────────────────────────────────────
        sectionCameraY = y;
        y += sLH;

        yawSlider = new YawSlider(widgetX, y, bw, bh, initYaw);
        this.addDrawableChild(yawSlider);
        y += bh + pad;

        pitchSlider = new PitchSlider(widgetX, y, bw, bh, initPitch);
        this.addDrawableChild(pitchSlider);
        y += bh + pad + gap;

        // ── Reset + Close (anchored to the bottom of the panel) ───────────────
        int closeY = panelY + panelH - bh - pad;
        int resetY = closeY - bh - pad;

        resetButton = new FlatButtonWidget(widgetX, resetY, bw, bh,
                Text.literal("Reset to Default"),
                btn -> resetToDefault());
        this.addDrawableChild(resetButton);

        saveCloseButton = new FlatButtonWidget(widgetX, closeY, bw, bh,
                Text.literal("Save & Close"),
                btn -> close());
        this.addDrawableChild(saveCloseButton);
    }

    /** Remove any saved override for this crop and refresh the screen. */
    private void resetToDefault() {
        config.cropSettings.remove(crop.name());
        config.save();
        if (this.client != null) {
            this.client.setScreen(new CropSettingsScreen(parent, config));
        }
    }

    @Override
    public void close() {
        // Persist the current widget values as a crop override, using default key settings
        FarmingConfig.CropCustomSettings defaults = FarmingConfig.CropCustomSettings.fromDefaults(crop);
        config.cropSettings.put(crop.name(), new FarmingConfig.CropCustomSettings(
                yawSlider.getYaw(), pitchSlider.getPitch(),
                defaults.forward, defaults.back,
                defaults.left, defaults.right,
                defaults.attack));
        config.save();
        if (this.client != null) this.client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int panelR = panelX + panelW;
        int panelB = panelY + panelH;
        int hdrH   = Math.round(HEADER_HEIGHT * scale);
        int sLH    = Math.max(8, Math.round(10 * scale));

        // Full-screen dim
        context.fill(0, 0, this.width, this.height, COL_SCREEN_DIM);
        // Drop shadow
        context.fill(panelX + 4, panelY + 4, panelR + 4, panelB + 4, COL_SHADOW);
        // Outer border
        context.fill(panelX - 1, panelY - 1, panelR + 1, panelB + 1, COL_BORDER);
        // Panel body
        context.fill(panelX, panelY, panelR, panelB, COL_WIN_BG);
        // Header accent bar (left edge)
        context.fill(panelX, panelY, panelX + 3, panelY + hdrH, COL_ACCENT);
        // Header separator
        context.fill(panelX + 3, panelY + hdrH - 1, panelR, panelY + hdrH, COL_SEP);

        // Title + recommended speed (two-line header block, vertically centred)
        // 18 = 8px (first text line) + 2px gap + 8px (second text line)
        String cropName = Text.translatable(crop.getTranslationKey()).getString();
        int firstLineY = panelY + Math.max(2, (hdrH - 18) / 2);
        context.drawTextWithShadow(this.textRenderer,
                Text.literal(cropName + " Settings").withColor(COL_TEXT),
                panelX + 10, firstLineY, COL_TEXT);
        context.drawTextWithShadow(this.textRenderer,
                Text.literal("Recommended Speed: " + crop.getRecommendedSpeed()).withColor(COL_TEXT_MUTED),
                panelX + 10, firstLineY + 10, COL_TEXT_MUTED);

        // Section labels
        drawSectionLabel(context, "Camera", sectionCameraY, sLH, panelR);

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawSectionLabel(DrawContext context, String label,
                                   int y, int sLH, int panelR) {
        context.fill(panelX + 4, y, panelR - 4, y + sLH, COL_SECTION_BG);
        context.fill(panelX + 6, y + 1, panelX + 8, y + sLH - 1, COL_ACCENT);
        context.drawTextWithShadow(this.textRenderer,
                Text.literal(label).withColor(COL_TEXT_MUTED),
                panelX + 14, y + 1, COL_TEXT_MUTED);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // Consume scroll events so widgets (sliders) cannot be accidentally
        // changed by scrolling while the crop settings screen is open.
        return true;
    }

    // ── Inner slider classes ──────────────────────────────────────────────────

    /** Slider for camera yaw: −180 ° … +180 °. */
    private static class YawSlider extends SliderWidget {
        private static final float MIN = -180f;
        private static final float MAX =  180f;

        YawSlider(int x, int y, int width, int height, float initial) {
            super(x, y, width, height, Text.empty(),
                    (double)(initial - MIN) / (MAX - MIN));
            updateMessage();
        }

        float getYaw() {
            return MIN + (float) value * (MAX - MIN);
        }

        @Override protected void updateMessage() {
            setMessage(Text.literal(String.format("Yaw: %.1f°", getYaw())));
        }
        @Override public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            renderFlatSlider(context, getX(), getY(), getWidth(), getHeight(), value, getMessage());
        }
        @Override protected void applyValue() {}
    }

    /** Slider for camera pitch: −90 ° … +90 °. */
    private static class PitchSlider extends SliderWidget {
        private static final float MIN = -90f;
        private static final float MAX =  90f;

        PitchSlider(int x, int y, int width, int height, float initial) {
            super(x, y, width, height, Text.empty(),
                    (double)(initial - MIN) / (MAX - MIN));
            updateMessage();
        }

        float getPitch() {
            return MIN + (float) value * (MAX - MIN);
        }

        @Override protected void updateMessage() {
            setMessage(Text.literal(String.format("Pitch: %.1f°", getPitch())));
        }
        @Override public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            renderFlatSlider(context, getX(), getY(), getWidth(), getHeight(), value, getMessage());
        }
        @Override protected void applyValue() {}
    }

    /** Shared flat slider renderer for this screen's slider inner classes. */
    private static void renderFlatSlider(DrawContext context, int x, int y, int w, int h,
                                          double value, net.minecraft.text.Text message) {
        FlatButtonWidget.renderFlatSlider(context, x, y, w, h, value, message);
    }
}
