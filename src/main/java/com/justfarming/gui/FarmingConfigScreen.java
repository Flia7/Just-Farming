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
    private static final int PANEL_WIDTH   = 310;
    private static final int PANEL_HEIGHT  = 300;
    private static final int HEADER_HEIGHT = 42;
    private static final int BUTTON_WIDTH  = 220;
    private static final int BUTTON_HEIGHT = 20;
    private static final int PADDING       = 6;

    // ── Colour palette (farming / nature theme) ───────────────────────────────
    private static final int COL_BG             = 0xEE0C110C; // very dark green-black
    private static final int COL_HEADER_TOP     = 0xFF1B3D0C; // dark forest green
    private static final int COL_HEADER_BOTTOM  = 0xFF0E2106; // deeper green
    private static final int COL_BORDER_OUTER   = 0xFF2E5F18; // medium green outer border
    private static final int COL_BORDER_INNER   = 0xFF55AA30; // bright green inner border
    private static final int COL_SEPARATOR      = 0xFF3A7020; // section separator
    private static final int COL_SECTION_BG     = 0x22448833; // subtle section tint
    private static final int COL_TITLE          = 0xFFEDD14A; // warm wheat/gold
    private static final int COL_LABEL          = 0xFF90DC5A; // light green label

    // ── State ─────────────────────────────────────────────────────────────────
    private final Screen parent;
    private final FarmingConfig config;
    private final MacroManager macroManager;

    // Widgets
    private CyclingButtonWidget<CropType> cropButton;
    private PitchSlider                   pitchSlider;
    private YawSlider                     yawSlider;
    private CyclingButtonWidget<Boolean>  toolSwitchButton;
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
        toolSwitchButton = CyclingButtonWidget.builder(
                        (Boolean val) -> val ? Text.literal("ON") : Text.literal("OFF"))
                .values(Boolean.TRUE, Boolean.FALSE)
                .initially(config.autoToolSwitch)
                .build(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                        Text.translatable("gui.just-farming.tool_switch_label"));
        this.addDrawableChild(toolSwitchButton);
        y += BUTTON_HEIGHT + PADDING + 10;

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

        // ── Outer border (2 px) ───────────────────────────────────────────────
        context.fill(panelX - 2, panelY - 2, panelR + 2, panelB + 2, COL_BORDER_OUTER);

        // ── Inner border (1 px highlight) ─────────────────────────────────────
        context.fill(panelX - 1, panelY - 1, panelR + 1, panelB + 1, COL_BORDER_INNER);

        // ── Panel background ──────────────────────────────────────────────────
        context.fill(panelX, panelY, panelR, panelB, COL_BG);

        // ── Header gradient ───────────────────────────────────────────────────
        context.fillGradient(panelX, panelY, panelR, panelY + HEADER_HEIGHT,
                COL_HEADER_TOP, COL_HEADER_BOTTOM);

        // ── Header bottom separator ───────────────────────────────────────────
        context.fill(panelX, panelY + HEADER_HEIGHT, panelR, panelY + HEADER_HEIGHT + 1, COL_BORDER_INNER);

        // ── Title (with decorative flowers) ───────────────────────────────────
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("\u273F ").append(this.title).append(" \u273F"),
                this.width / 2, panelY + 10, COL_TITLE);

        // ── Status badge ──────────────────────────────────────────────────────
        boolean running = macroManager.isRunning();
        String statusStr   = running ? "\u25CF RUNNING" : "\u25CF STOPPED";
        int    statusColor = running ? 0xFF55FF55 : 0xFFFF5555;
        int    badgeW  = this.textRenderer.getWidth(statusStr) + 10;
        int    badgeX  = this.width / 2 - badgeW / 2;
        int    badgeY  = panelY + 26;
        // Badge fill
        context.fill(badgeX + 1, badgeY + 1, badgeX + badgeW - 1, badgeY + 11,
                running ? 0x4400CC44 : 0x44CC0000);
        // Badge border (top/bottom/left/right lines)
        context.fill(badgeX,             badgeY,      badgeX + badgeW, badgeY + 1,      statusColor);
        context.fill(badgeX,             badgeY + 11, badgeX + badgeW, badgeY + 12,     statusColor);
        context.fill(badgeX,             badgeY,      badgeX + 1,      badgeY + 12,     statusColor);
        context.fill(badgeX + badgeW - 1, badgeY,    badgeX + badgeW, badgeY + 12,     statusColor);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(statusStr).withColor(statusColor),
                this.width / 2, badgeY + 2, 0xFFFFFF);

        // ── Section labels ────────────────────────────────────────────────────
        drawSectionLabel(context, "\u2B25 Crop",    panelX, sectionCropY,    panelR);
        drawSectionLabel(context, "\u2B25 Angles",  panelX, sectionAnglesY,  panelR);
        drawSectionLabel(context, "\u2B25 Options", panelX, sectionOptionsY, panelR);

        // ── Action-area separator ─────────────────────────────────────────────
        context.fill(panelX + 8, actionSeparatorY, panelR - 8, actionSeparatorY + 1, COL_SEPARATOR);

        // ── Widgets ───────────────────────────────────────────────────────────
        super.render(context, mouseX, mouseY, delta);
    }

    /** Draws a subtle tint strip and coloured label for a settings section. */
    private void drawSectionLabel(DrawContext context, String label, int panelX, int y, int panelR) {
        context.fill(panelX + 4, y, panelR - 4, y + 10, COL_SECTION_BG);
        context.drawTextWithShadow(this.textRenderer,
                Text.literal(label).withColor(COL_LABEL),
                panelX + 10, y, COL_LABEL);
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
        config.selectedCrop   = cropButton.getValue();
        config.farmingPitch   = pitchSlider.getPitchValue();
        config.farmingYaw     = yawSlider.getYawValue();
        config.autoToolSwitch = toolSwitchButton.getValue();
        macroManager.setConfig(config);
    }

    private Text getMacroToggleText() {
        return macroManager.isRunning()
                ? Text.translatable("gui.just-farming.stop_macro")
                : Text.translatable("gui.just-farming.start_macro");
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
}
