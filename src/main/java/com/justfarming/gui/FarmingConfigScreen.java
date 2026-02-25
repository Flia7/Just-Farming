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

    private static final int PANEL_WIDTH  = 300;
    private static final int PANEL_HEIGHT = 300;
    private static final int BUTTON_WIDTH  = 200;
    private static final int BUTTON_HEIGHT = 20;
    private static final int PADDING = 8;

    private final Screen parent;
    private final FarmingConfig config;
    private final MacroManager macroManager;

    // Widgets
    private CyclingButtonWidget<CropType>  cropButton;
    private PitchSlider  pitchSlider;
    private YawSlider    yawSlider;
    private CyclingButtonWidget<Boolean>   toolSwitchButton;
    private ButtonWidget setRewarpButton;
    private ButtonWidget toggleMacroButton;
    private ButtonWidget saveCloseButton;

    public FarmingConfigScreen(Screen parent, FarmingConfig config, MacroManager macroManager) {
        super(Text.translatable("gui.just-farming.title"));
        this.parent = parent;
        this.config = config;
        this.macroManager = macroManager;
    }

    @Override
    protected void init() {
        int panelX  = (this.width - PANEL_WIDTH) / 2;
        int startY  = (this.height - PANEL_HEIGHT) / 2 + 20;
        int centerX = panelX + PANEL_WIDTH / 2;
        int widgetX = centerX - BUTTON_WIDTH / 2;

        int y = startY;

        // Crop selection – only Cocoa Beans for now
        cropButton = CyclingButtonWidget.builder(
                        (CropType crop) -> Text.translatable(crop.getTranslationKey()))
                .values(CropType.COCOA_BEANS)
                .initially(CropType.COCOA_BEANS)
                .build(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                        Text.translatable("gui.just-farming.crop_label"));
        this.addDrawableChild(cropButton);
        y += BUTTON_HEIGHT + PADDING;

        // Pitch angle slider
        pitchSlider = new PitchSlider(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT, config.farmingPitch);
        this.addDrawableChild(pitchSlider);
        y += BUTTON_HEIGHT + PADDING;

        // Yaw angle slider
        yawSlider = new YawSlider(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT, config.farmingYaw);
        this.addDrawableChild(yawSlider);
        y += BUTTON_HEIGHT + PADDING;

        // Auto tool switch toggle
        toolSwitchButton = CyclingButtonWidget.builder(
                        (Boolean val) -> val ? Text.literal("ON") : Text.literal("OFF"))
                .values(Boolean.TRUE, Boolean.FALSE)
                .initially(config.autoToolSwitch)
                .build(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                        Text.translatable("gui.just-farming.tool_switch_label"));
        this.addDrawableChild(toolSwitchButton);
        y += BUTTON_HEIGHT + PADDING + 4;

        // Set Rewarp Here button
        setRewarpButton = ButtonWidget.builder(
                        getRewarpButtonText(),
                        btn -> {
                            if (this.client != null && this.client.player != null) {
                                config.rewarpX  = this.client.player.getX();
                                config.rewarpY  = this.client.player.getY();
                                config.rewarpZ  = this.client.player.getZ();
                                config.rewarpSet = true;
                                config.save();
                                btn.setMessage(Text.literal("§aRewarp Set!"));
                            }
                        })
                .dimensions(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        this.addDrawableChild(setRewarpButton);
        y += BUTTON_HEIGHT + PADDING;

        // Toggle macro button
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

        // Close button
        saveCloseButton = ButtonWidget.builder(
                        Text.translatable("gui.just-farming.close"),
                        btn -> close())
                .dimensions(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        this.addDrawableChild(saveCloseButton);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Draw panel background
        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = (this.height - PANEL_HEIGHT) / 2;
        context.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, 0xC0101010);

        // Draw title
        context.drawCenteredTextWithShadow(
                this.textRenderer,
                this.title,
                this.width / 2,
                panelY + PADDING,
                0xFFFFFF);

        // Draw status line
        Text statusText = macroManager.isRunning()
                ? Text.translatable("gui.just-farming.status_running").withColor(0x55FF55)
                : Text.translatable("gui.just-farming.status_stopped").withColor(0xFF5555);
        context.drawCenteredTextWithShadow(
                this.textRenderer,
                statusText,
                this.width / 2,
                panelY + PADDING + 12,
                0xFFFFFF);

        super.render(context, mouseX, mouseY, delta);
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
                ? Text.literal(String.format("§7Rewarp: %.1f, %.1f, %.1f", config.rewarpX, config.rewarpY, config.rewarpZ))
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
        private static final float MAX = 90.0f;

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
            setMessage(Text.literal(String.format("Pitch: %.0f°", getPitchValue())));
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
            setMessage(Text.literal(String.format("Yaw: %.0f°", getYawValue())));
        }

        @Override
        protected void applyValue() {
            // value is stored in the parent field; read via getYawValue()
        }
    }
}
