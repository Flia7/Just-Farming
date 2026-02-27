package com.justfarming.gui;

import com.justfarming.CropType;
import com.justfarming.MacroManager;
import com.justfarming.config.FarmingConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

/**
 * Configuration GUI screen for Just Farming.
 *
 * <p>Allows the player to:
 * <ul>
 *   <li>Select the crop type to farm (Cocoa Beans only for now)</li>
 *   <li>Set the pitch angle (vertical look angle while farming)</li>
 *   <li>Set the yaw angle (horizontal rotation while farming)</li>
 *   <li>Toggle auto-tool-switch</li>
 *   <li>Set the rewarp trigger position to the player's current location</li>
 *   <li>Start or stop the macro directly from the GUI</li>
 * </ul>
 */
public class FarmingConfigScreen extends Screen {

    // ── Layout ────────────────────────────────────────────────────────────────
    private static final int PANEL_WIDTH   = 320;
    private static final int PANEL_HEIGHT  = 462; // header + 10 widgets × (20+6) + separators + padding
    private static final int HEADER_HEIGHT = 46;
    private static final int BUTTON_WIDTH  = 240;
    private static final int BUTTON_HEIGHT = 20;
    private static final int PADDING       = 6;

    // ── Colour palette (modern dark theme) ────────────────────────────────────
    private static final int COL_BG             = 0xF00E1018; // near-black with cool tint
    private static final int COL_HEADER_TOP     = 0xFF1A1040; // deep purple
    private static final int COL_HEADER_BOTTOM  = 0xFF0D0820; // darker purple
    private static final int COL_BORDER_OUTER   = 0xFF2D1B69; // dark purple outer
    private static final int COL_BORDER_INNER   = 0xFF6C3DFF; // vivid purple accent
    private static final int COL_SEPARATOR      = 0xFF2A1D55; // muted purple separator
    private static final int COL_SECTION_BG     = 0x18654DFF; // subtle purple tint
    private static final int COL_TITLE          = 0xFFEEEEFF; // clean white
    private static final int COL_LABEL          = 0xFFB0A0E0; // soft lavender
    private static final int COL_ACCENT         = 0xFF7C4DFF; // primary accent
    private static final int COL_SHADOW         = 0x60000000; // drop shadow

    // ── State ─────────────────────────────────────────────────────────────────
    private final Screen parent;
    private final FarmingConfig config;
    private final MacroManager macroManager;

    // Widgets
    private CyclingButtonWidget<CropType> cropButton;
    private PitchSlider                   pitchSlider;
    private YawSlider                     yawSlider;
    private SwapDelaySlider               swapDelaySlider;
    private SwapRandomSlider              swapRandomSlider;
    private CyclingButtonWidget<Boolean>  pestHighlightButton;
    private CyclingButtonWidget<Boolean>  pestLabelsButton;
    private CyclingButtonWidget<Boolean>  pestEspButton;
    private CyclingButtonWidget<Boolean>  pestTracerButton;
    private ButtonWidget                  freelookButton;
    private ButtonWidget                  setRewarpButton;
    private ButtonWidget                  toggleMacroButton;
    private ButtonWidget                  saveCloseButton;

    // Section-label Y positions (set in init, used in render)
    private int sectionCropY;
    private int sectionAnglesY;
    private int sectionOptionsY;
    private int actionSeparatorY;

    public FarmingConfigScreen(Screen parent, FarmingConfig config, MacroManager macroManager) {
        super(Text.translatable("gui.just-farming.title"));
        this.parent = parent;
        this.config = config;
        this.macroManager = macroManager;
    }

    @Override
    protected void init() {
        int panelX  = (this.width  - PANEL_WIDTH)  / 2;
        int panelY  = (this.height - PANEL_HEIGHT) / 2;
        int centerX = panelX + PANEL_WIDTH / 2;
        int widgetX = centerX - BUTTON_WIDTH / 2;

        // First widget row starts just below the header
        int y = panelY + HEADER_HEIGHT + PADDING + 6;

        // ── Crop section ──────────────────────────────────────────────────────
        sectionCropY = y;
        y += 10;
        cropButton = CyclingButtonWidget.builder(
                        (CropType crop) -> Text.translatable(crop.getTranslationKey()))
                .values(CropType.COCOA_BEANS)
                .initially(CropType.COCOA_BEANS)
                .build(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                        Text.translatable("gui.just-farming.crop_label"));
        this.addDrawableChild(cropButton);
        y += BUTTON_HEIGHT + PADDING + 8;

        // ── Angles section ────────────────────────────────────────────────────
        sectionAnglesY = y;
        y += 10;
        pitchSlider = new PitchSlider(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT, config.farmingPitch);
        this.addDrawableChild(pitchSlider);
        y += BUTTON_HEIGHT + PADDING;

        yawSlider = new YawSlider(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT, config.farmingYaw);
        this.addDrawableChild(yawSlider);
        y += BUTTON_HEIGHT + PADDING + 8;

        // ── Options section ───────────────────────────────────────────────────
        sectionOptionsY = y;
        y += 10;
        swapDelaySlider = new SwapDelaySlider(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT, config.rewarpDelayMin);
        this.addDrawableChild(swapDelaySlider);
        y += BUTTON_HEIGHT + PADDING;

        swapRandomSlider = new SwapRandomSlider(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT, config.rewarpDelayRandom);
        this.addDrawableChild(swapRandomSlider);
        y += BUTTON_HEIGHT + PADDING;

        pestHighlightButton = CyclingButtonWidget.builder(
                        (Boolean val) -> val ? Text.literal("ON") : Text.literal("OFF"))
                .values(Boolean.TRUE, Boolean.FALSE)
                .initially(config.pestHighlightEnabled)
                .build(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                        Text.translatable("gui.just-farming.pest_highlight_label"));
        this.addDrawableChild(pestHighlightButton);
        y += BUTTON_HEIGHT + PADDING;

        pestLabelsButton = CyclingButtonWidget.builder(
                        (Boolean val) -> val ? Text.literal("ON") : Text.literal("OFF"))
                .values(Boolean.TRUE, Boolean.FALSE)
                .initially(config.pestLabelsEnabled)
                .build(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                        Text.translatable("gui.just-farming.pest_labels_label"));
        this.addDrawableChild(pestLabelsButton);
        y += BUTTON_HEIGHT + PADDING;

        pestEspButton = CyclingButtonWidget.builder(
                        (Boolean val) -> val ? Text.literal("ON") : Text.literal("OFF"))
                .values(Boolean.TRUE, Boolean.FALSE)
                .initially(config.pestEspEnabled)
                .build(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                        Text.translatable("gui.just-farming.pest_esp_label"));
        this.addDrawableChild(pestEspButton);
        y += BUTTON_HEIGHT + PADDING;

        pestTracerButton = CyclingButtonWidget.builder(
                        (Boolean val) -> val ? Text.literal("ON") : Text.literal("OFF"))
                .values(Boolean.TRUE, Boolean.FALSE)
                .initially(config.pestTracerEnabled)
                .build(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                        Text.translatable("gui.just-farming.pest_tracer_label"));
        this.addDrawableChild(pestTracerButton);
        y += BUTTON_HEIGHT + PADDING;

        freelookButton = ButtonWidget.builder(
                        getFreelookButtonText(),
                        btn -> {
                            macroManager.toggleFreelook();
                            btn.setMessage(getFreelookButtonText());
                        })
                .dimensions(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        this.addDrawableChild(freelookButton);
        y += BUTTON_HEIGHT + PADDING + 4;

        // ── Action buttons (below separator) ──────────────────────────────────
        actionSeparatorY = y - 4;

        setRewarpButton = ButtonWidget.builder(
                        getRewarpButtonText(),
                        btn -> {
                            if (this.client != null && this.client.player != null) {
                                config.rewarpX   = this.client.player.getX();
                                config.rewarpY   = this.client.player.getY();
                                config.rewarpZ   = this.client.player.getZ();
                                config.rewarpSet = true;
                                config.save();
                                btn.setMessage(Text.literal("§aRewarp Set!"));
                            }
                        })
                .dimensions(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        this.addDrawableChild(setRewarpButton);
        y += BUTTON_HEIGHT + PADDING;

        toggleMacroButton = ButtonWidget.builder(
                        getMacroToggleText(),
                        btn -> {
                            applyConfig();
                            macroManager.toggle();
                            btn.setMessage(getMacroToggleText());
                        })
                .dimensions(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        this.addDrawableChild(toggleMacroButton);
        y += BUTTON_HEIGHT + PADDING;

        saveCloseButton = ButtonWidget.builder(
                        Text.translatable("gui.just-farming.close"),
                        btn -> close())
                .dimensions(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        this.addDrawableChild(saveCloseButton);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int panelX = (this.width  - PANEL_WIDTH)  / 2;
        int panelY = (this.height - PANEL_HEIGHT) / 2;
        int panelR = panelX + PANEL_WIDTH;
        int panelB = panelY + PANEL_HEIGHT;

        // ── Drop shadow ───────────────────────────────────────────────────────
        context.fill(panelX + 3, panelY + 3, panelR + 3, panelB + 3, COL_SHADOW);

        // ── Outer border (2 px) ───────────────────────────────────────────────
        context.fill(panelX - 2, panelY - 2, panelR + 2, panelB + 2, COL_BORDER_OUTER);

        // ── Accent border (1 px) ──────────────────────────────────────────────
        context.fill(panelX - 1, panelY - 1, panelR + 1, panelB + 1, COL_BORDER_INNER);

        // ── Panel background ──────────────────────────────────────────────────
        context.fill(panelX, panelY, panelR, panelB, COL_BG);

        // ── Header gradient ───────────────────────────────────────────────────
        context.fillGradient(panelX, panelY, panelR, panelY + HEADER_HEIGHT,
                COL_HEADER_TOP, COL_HEADER_BOTTOM);

        // ── Header accent line (bottom) ───────────────────────────────────────
        context.fillGradient(panelX, panelY + HEADER_HEIGHT - 1, panelR,
                panelY + HEADER_HEIGHT + 1, COL_ACCENT, COL_BORDER_OUTER);

        // ── Corner accents (top-left / top-right) ─────────────────────────────
        context.fill(panelX - 2, panelY - 2, panelX + 6, panelY - 1, COL_ACCENT);
        context.fill(panelX - 2, panelY - 2, panelX - 1, panelY + 6, COL_ACCENT);
        context.fill(panelR - 6, panelY - 2, panelR + 2, panelY - 1, COL_ACCENT);
        context.fill(panelR + 1, panelY - 2, panelR + 2, panelY + 6, COL_ACCENT);

        // ── Title ─────────────────────────────────────────────────────────────
        context.drawCenteredTextWithShadow(this.textRenderer,
                this.title, this.width / 2, panelY + 10, COL_TITLE);

        // ── Status badge ──────────────────────────────────────────────────────
        boolean running = macroManager.isRunning();
        String statusStr   = running ? "\u25CF RUNNING" : "\u25CF STOPPED";
        int    statusColor = running ? 0xFF50E890 : 0xFFFF6070;
        int    badgeGlow   = running ? 0x2030D870 : 0x20FF4050;
        int    badgeW  = this.textRenderer.getWidth(statusStr) + 14;
        int    badgeX  = this.width / 2 - badgeW / 2;
        int    badgeY  = panelY + 27;

        // Glow layer behind badge
        context.fill(badgeX - 2, badgeY - 1, badgeX + badgeW + 2, badgeY + 13, badgeGlow);

        // Badge fill
        context.fill(badgeX, badgeY, badgeX + badgeW, badgeY + 12,
                running ? 0x3020C060 : 0x30D03040);

        // Badge border
        context.fill(badgeX, badgeY, badgeX + badgeW, badgeY + 1, statusColor);
        context.fill(badgeX, badgeY + 11, badgeX + badgeW, badgeY + 12, statusColor);
        context.fill(badgeX, badgeY, badgeX + 1, badgeY + 12, statusColor);
        context.fill(badgeX + badgeW - 1, badgeY, badgeX + badgeW, badgeY + 12, statusColor);

        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(statusStr).withColor(statusColor),
                this.width / 2, badgeY + 2, 0xFFFFFF);

        // ── Section labels ────────────────────────────────────────────────────
        drawSectionLabel(context, "Crop",    panelX, sectionCropY,    panelR);
        drawSectionLabel(context, "Angles",  panelX, sectionAnglesY,  panelR);
        drawSectionLabel(context, "Options", panelX, sectionOptionsY, panelR);

        // ── Action-area separator ─────────────────────────────────────────────
        context.fillGradient(panelX + 20, actionSeparatorY,
                panelR - 20, actionSeparatorY + 1,
                COL_ACCENT, 0x00000000);

        // ── Widgets ───────────────────────────────────────────────────────────
        super.render(context, mouseX, mouseY, delta);
    }

    /** Draws a section label with an accent bar and subtle background. */
    private void drawSectionLabel(DrawContext context, String label, int panelX, int y, int panelR) {
        // Subtle background strip
        context.fill(panelX + 4, y, panelR - 4, y + 10, COL_SECTION_BG);
        // Accent bar on the left
        context.fill(panelX + 6, y + 1, panelX + 8, y + 9, COL_ACCENT);
        // Label text
        context.drawTextWithShadow(this.textRenderer,
                Text.literal(label).withColor(COL_LABEL),
                panelX + 14, y + 1, COL_LABEL);
    }

    @Override
    public void close() {
        applyConfig();
        config.save();
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** Read widget values back into the config object. */
    private void applyConfig() {
        config.selectedCrop        = cropButton.getValue();
        config.farmingPitch        = pitchSlider.getPitchValue();
        config.farmingYaw          = yawSlider.getYawValue();
        config.rewarpDelayMin      = swapDelaySlider.getDelayValue();
        config.rewarpDelayRandom   = swapRandomSlider.getRandomValue();
        config.pestHighlightEnabled = pestHighlightButton.getValue();
        config.pestLabelsEnabled    = pestLabelsButton.getValue();
        config.pestEspEnabled       = pestEspButton.getValue();
        config.pestTracerEnabled    = pestTracerButton.getValue();
        macroManager.setConfig(config);
    }

    private Text getMacroToggleText() {
        return macroManager.isRunning()
                ? Text.translatable("gui.just-farming.stop_macro")
                : Text.translatable("gui.just-farming.start_macro");
    }

    private Text getFreelookButtonText() {
        return macroManager.isFreelookEnabled()
                ? Text.translatable("gui.just-farming.freelook_on")
                : Text.translatable("gui.just-farming.freelook_off");
    }

    private Text getRewarpButtonText() {
        return config.rewarpSet
                ? Text.literal(String.format("§7Rewarp: %.1f, %.1f, %.1f",
                        config.rewarpX, config.rewarpY, config.rewarpZ))
                : Text.translatable("gui.just-farming.set_rewarp");
    }

    // -------------------------------------------------------------------------
    // Inner slider classes
    // -------------------------------------------------------------------------

    /**
     * Slider for pitch angle in range [-90, 90] degrees.
     */
    private static class PitchSlider extends SliderWidget {

        private static final float MIN = -90.0f;
        private static final float MAX =  90.0f;

        PitchSlider(int x, int y, int width, int height, float initialPitch) {
            super(x, y, width, height,
                    Text.empty(),
                    (double) (initialPitch - MIN) / (MAX - MIN));
            updateMessage();
        }

        float getPitchValue() {
            return MIN + (float) value * (MAX - MIN);
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Pitch: %.0f\u00B0", getPitchValue())));
        }

        @Override
        protected void applyValue() {
            // value is stored in the parent field; read via getPitchValue()
        }
    }

    /**
     * Slider for yaw angle in range [-180, 180] degrees.
     */
    private static class YawSlider extends SliderWidget {

        private static final float MIN = -180.0f;
        private static final float MAX =  180.0f;

        YawSlider(int x, int y, int width, int height, float initialYaw) {
            super(x, y, width, height,
                    Text.empty(),
                    (double) (initialYaw - MIN) / (MAX - MIN));
            updateMessage();
        }

        float getYawValue() {
            return MIN + (float) value * (MAX - MIN);
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Yaw: %.0f\u00B0", getYawValue())));
        }

        @Override
        protected void applyValue() {
            // value is stored in the parent field; read via getYawValue()
        }
    }

    /**
     * Slider for the minimum lane-swap delay (0–2000 ms).
     */
    private static class SwapDelaySlider extends SliderWidget {

        private static final int MIN =    0;
        private static final int MAX = 2000;

        SwapDelaySlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height,
                    Text.empty(),
                    (double) (initialValue - MIN) / (MAX - MIN));
            updateMessage();
        }

        int getDelayValue() {
            return MIN + (int) Math.round(value * (MAX - MIN));
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Min Swap Delay: %d ms", getDelayValue())));
        }

        @Override
        protected void applyValue() {}
    }

    /**
     * Slider for the random extra lane-swap delay (0–1000 ms).
     */
    private static class SwapRandomSlider extends SliderWidget {

        private static final int MIN =    0;
        private static final int MAX = 1000;

        SwapRandomSlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height,
                    Text.empty(),
                    (double) (initialValue - MIN) / (MAX - MIN));
            updateMessage();
        }

        int getRandomValue() {
            return MIN + (int) Math.round(value * (MAX - MIN));
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Swap Randomization: %d ms", getRandomValue())));
        }

        @Override
        protected void applyValue() {}
    }
}
