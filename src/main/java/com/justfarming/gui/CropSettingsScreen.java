package com.justfarming.gui;

import com.justfarming.CropType;
import com.justfarming.config.FarmingConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

/**
 * Per-crop settings screen.
 *
 * <p>Lets the player adjust the camera yaw, pitch and the five farming keys
 * (forward, back, left strafe, right strafe, attack) for the currently selected
 * crop.  A <em>Reset to Default</em> button removes any saved override so the
 * built-in values take effect again.
 */
public class CropSettingsScreen extends Screen {

    // ── Colour palette (matches FarmingConfigScreen) ──────────────────────────
    private static final int COL_BG            = 0xF00E1018;
    private static final int COL_HEADER_TOP    = 0xFF1A1040;
    private static final int COL_HEADER_BOTTOM = 0xFF0D0820;
    private static final int COL_BORDER_OUTER  = 0xFF2D1B69;
    private static final int COL_BORDER_INNER  = 0xFF6C3DFF;
    private static final int COL_SECTION_BG    = 0x18654DFF;
    private static final int COL_TITLE         = 0xFFEEEEFF;
    private static final int COL_LABEL         = 0xFFB0A0E0;
    private static final int COL_ACCENT        = 0xFF7C4DFF;
    private static final int COL_SHADOW        = 0x60000000;

    // ── Natural panel dimensions ───────────────────────────────────────────────
    private static final int PANEL_WIDTH   = 320;
    private static final int PANEL_HEIGHT  = 330;
    private static final int HEADER_HEIGHT = 42;
    private static final int BUTTON_WIDTH  = 240;
    private static final int BUTTON_HEIGHT = 20;
    private static final int PADDING       = 6;

    private final Screen        parent;
    private final FarmingConfig config;
    private final CropType      crop;

    // ── Widgets ───────────────────────────────────────────────────────────────
    private YawSlider   yawSlider;
    private PitchSlider pitchSlider;
    private CyclingButtonWidget<Boolean> forwardBtn;
    private CyclingButtonWidget<Boolean> backBtn;
    private CyclingButtonWidget<Boolean> leftBtn;
    private CyclingButtonWidget<Boolean> rightBtn;
    private CyclingButtonWidget<Boolean> attackBtn;
    private ButtonWidget resetButton;
    private ButtonWidget saveCloseButton;

    // ── Section-label Y positions (set in init, used in render) ───────────────
    private int sectionCameraY;
    private int sectionKeysY;

    // ── Scale computed in init (used in render) ────────────────────────────────
    private float scale;
    private int   panelX, panelY, panelW, panelH;

    // ── Initial (working) values ──────────────────────────────────────────────
    private final float   initYaw, initPitch;
    private final boolean initForward, initBack, initLeft, initRight, initAttack;

    public CropSettingsScreen(Screen parent, FarmingConfig config) {
        super(Text.literal("Crop Settings"));
        this.parent = parent;
        this.config = config;
        this.crop   = config.selectedCrop;

        // Load values from saved override, or fall back to crop defaults
        FarmingConfig.CropCustomSettings cs = config.getCropSettings(crop);
        FarmingConfig.CropCustomSettings defaults = FarmingConfig.CropCustomSettings.fromDefaults(crop);
        if (cs != null) {
            initYaw     = cs.yaw;
            initPitch   = cs.pitch;
            initForward = cs.forward;
            initBack    = cs.back;
            initLeft    = cs.left;
            initRight   = cs.right;
            initAttack  = cs.attack;
        } else {
            initYaw     = defaults.yaw;
            initPitch   = defaults.pitch;
            initForward = defaults.forward;
            initBack    = defaults.back;
            initLeft    = defaults.left;
            initRight   = defaults.right;
            initAttack  = defaults.attack;
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

        // ── Keys section ──────────────────────────────────────────────────────
        sectionKeysY = y;
        y += sLH;

        forwardBtn = buildKeyToggle(widgetX, y, bw, bh, "Forward Key:", initForward);
        this.addDrawableChild(forwardBtn);
        y += bh + pad;

        backBtn = buildKeyToggle(widgetX, y, bw, bh, "Back Key:", initBack);
        this.addDrawableChild(backBtn);
        y += bh + pad;

        leftBtn = buildKeyToggle(widgetX, y, bw, bh, "Left Strafe Key:", initLeft);
        this.addDrawableChild(leftBtn);
        y += bh + pad;

        rightBtn = buildKeyToggle(widgetX, y, bw, bh, "Right Strafe Key:", initRight);
        this.addDrawableChild(rightBtn);
        y += bh + pad;

        attackBtn = buildKeyToggle(widgetX, y, bw, bh, "Attack Key:", initAttack);
        this.addDrawableChild(attackBtn);

        // ── Reset + Close (anchored to the bottom of the panel) ───────────────
        int closeY = panelY + panelH - bh - pad;
        int resetY = closeY - bh - pad;

        resetButton = ButtonWidget.builder(
                Text.literal("Reset to Default"),
                btn -> resetToDefault())
                .dimensions(widgetX, resetY, bw, bh)
                .build();
        this.addDrawableChild(resetButton);

        saveCloseButton = ButtonWidget.builder(
                Text.literal("Save & Close"),
                btn -> close())
                .dimensions(widgetX, closeY, bw, bh)
                .build();
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
        // Persist the current widget values as a crop override
        config.cropSettings.put(crop.name(), new FarmingConfig.CropCustomSettings(
                yawSlider.getYaw(), pitchSlider.getPitch(),
                forwardBtn.getValue(), backBtn.getValue(),
                leftBtn.getValue(), rightBtn.getValue(),
                attackBtn.getValue()));
        config.save();
        if (this.client != null) this.client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int panelR = panelX + panelW;
        int panelB = panelY + panelH;
        int hdrH   = Math.round(HEADER_HEIGHT * scale);
        int sLH    = Math.max(8, Math.round(10 * scale));

        // Drop shadow
        context.fill(panelX + 3, panelY + 3, panelR + 3, panelB + 3, COL_SHADOW);
        // Outer / accent borders
        context.fill(panelX - 2, panelY - 2, panelR + 2, panelB + 2, COL_BORDER_OUTER);
        context.fill(panelX - 1, panelY - 1, panelR + 1, panelB + 1, COL_BORDER_INNER);
        // Panel body
        context.fill(panelX, panelY, panelR, panelB, COL_BG);
        // Header gradient
        context.fillGradient(panelX, panelY, panelR, panelY + hdrH,
                COL_HEADER_TOP, COL_HEADER_BOTTOM);
        // Header accent line
        context.fillGradient(panelX, panelY + hdrH - 1,
                panelR, panelY + hdrH + 1, COL_ACCENT, COL_BORDER_OUTER);
        // Corner accents
        context.fill(panelX - 2, panelY - 2, panelX + 6, panelY - 1, COL_ACCENT);
        context.fill(panelX - 2, panelY - 2, panelX - 1, panelY + 6, COL_ACCENT);
        context.fill(panelR - 6, panelY - 2, panelR + 2, panelY - 1, COL_ACCENT);
        context.fill(panelR + 1, panelY - 2, panelR + 2, panelY + 6, COL_ACCENT);

        // Title: "Crop Settings: <CropName>"
        String cropName = Text.translatable(crop.getTranslationKey()).getString();
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Crop Settings: " + cropName),
                this.width / 2, panelY + Math.max(4, Math.round(8 * scale)), COL_TITLE);

        // Section labels
        drawSectionLabel(context, "Camera", sectionCameraY, sLH, panelR);
        drawSectionLabel(context, "Keys Pressed", sectionKeysY, sLH, panelR);

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawSectionLabel(DrawContext context, String label,
                                   int y, int sLH, int panelR) {
        context.fill(panelX + 4, y, panelR - 4, y + sLH, COL_SECTION_BG);
        context.fill(panelX + 6, y + 1, panelX + 8, y + sLH - 1, COL_ACCENT);
        context.drawTextWithShadow(this.textRenderer,
                Text.literal(label).withColor(COL_LABEL),
                panelX + 14, y + 1, COL_LABEL);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static CyclingButtonWidget<Boolean> buildKeyToggle(
            int x, int y, int w, int h, String label, boolean initial) {
        return CyclingButtonWidget.builder(
                        (Boolean v) -> v ? Text.literal("ON") : Text.literal("OFF"))
                .values(Boolean.TRUE, Boolean.FALSE)
                .initially(initial)
                .build(x, y, w, h, Text.literal(label));
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
        @Override protected void applyValue() {}
    }
}
