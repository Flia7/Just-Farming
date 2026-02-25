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
 *   <li>Select the crop type to farm</li>
 *   <li>Adjust macro speed (Slow / Normal / Fast)</li>
 *   <li>Set the pitch angle (look-down angle while farming)</li>
 *   <li>Toggle auto-replant and auto-tool-switch</li>
 *   <li>Set row length</li>
 *   <li>Start or stop the macro directly from the GUI</li>
 * </ul>
 */
public class FarmingConfigScreen extends Screen {

    private static final int PANEL_WIDTH = 300;
    private static final int PANEL_HEIGHT = 310;
    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;
    private static final int PADDING = 8;

    private final Screen parent;
    private final FarmingConfig config;
    private final MacroManager macroManager;

    // Widgets
    private CyclingButtonWidget<CropType> cropButton;
    private CyclingButtonWidget<Integer> speedButton;
    private PitchSlider pitchSlider;
    private CyclingButtonWidget<Boolean> replantButton;
    private CyclingButtonWidget<Boolean> toolSwitchButton;
    private RowLengthSlider rowLengthSlider;
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
        int panelX = (this.width - PANEL_WIDTH) / 2;
        int startY = (this.height - PANEL_HEIGHT) / 2 + 20;
        int centerX = panelX + PANEL_WIDTH / 2;
        int widgetX = centerX - BUTTON_WIDTH / 2;

        int y = startY;

        // Crop selection
        cropButton = CyclingButtonWidget.builder(
                        (CropType crop) -> Text.translatable(crop.getTranslationKey()))
                .values(CropType.values())
                .initially(config.selectedCrop)
                .build(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                        Text.translatable("gui.just-farming.crop_label"));
        this.addDrawableChild(cropButton);
        y += BUTTON_HEIGHT + PADDING;

        // Macro speed
        speedButton = CyclingButtonWidget.builder(
                        (Integer speed) -> switch (speed) {
                            case 1 -> Text.literal("Slow");
                            case 3 -> Text.literal("Fast");
                            default -> Text.literal("Normal");
                        })
                .values(1, 2, 3)
                .initially(config.macroSpeed)
                .build(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                        Text.translatable("gui.just-farming.speed_label"));
        this.addDrawableChild(speedButton);
        y += BUTTON_HEIGHT + PADDING;

        // Pitch angle slider
        pitchSlider = new PitchSlider(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT, config.farmingPitch);
        this.addDrawableChild(pitchSlider);
        y += BUTTON_HEIGHT + PADDING;

        // Row length slider
        rowLengthSlider = new RowLengthSlider(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT, config.rowLength);
        this.addDrawableChild(rowLengthSlider);
        y += BUTTON_HEIGHT + PADDING;

        // Auto replant toggle
        replantButton = CyclingButtonWidget.builder(
                        (Boolean val) -> val ? Text.literal("ON") : Text.literal("OFF"))
                .values(Boolean.TRUE, Boolean.FALSE)
                .initially(config.autoReplant)
                .build(widgetX, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                        Text.translatable("gui.just-farming.replant_label"));
        this.addDrawableChild(replantButton);
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

        // Save & Close button
        saveCloseButton = ButtonWidget.builder(
                        Text.translatable("gui.just-farming.save_close"),
                        btn -> {
                            applyConfig();
                            config.save();
                            close();
                        })
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
        config.selectedCrop = cropButton.getValue();
        config.macroSpeed = speedButton.getValue();
        config.farmingPitch = pitchSlider.getPitchValue();
        config.rowLength = rowLengthSlider.getRowLength();
        config.autoReplant = replantButton.getValue();
        config.autoToolSwitch = toolSwitchButton.getValue();
        macroManager.setConfig(config);
    }

    private Text getMacroToggleText() {
        return macroManager.isRunning()
                ? Text.translatable("gui.just-farming.stop_macro")
                : Text.translatable("gui.just-farming.start_macro");
    }

    // -------------------------------------------------------------------------
    // Inner slider classes
    // -------------------------------------------------------------------------

    /**
     * Slider for pitch angle in range [20, 90] degrees.
     */
    private static class PitchSlider extends SliderWidget {

        private static final float MIN = 20.0f;
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
     * Slider for row length in range [10, 200] blocks.
     */
    private static class RowLengthSlider extends SliderWidget {

        private static final int MIN = 10;
        private static final int MAX = 200;

        RowLengthSlider(int x, int y, int width, int height, int initialLength) {
            super(x, y, width, height,
                    Text.empty(),
                    (double) (initialLength - MIN) / (MAX - MIN));
            updateMessage();
        }

        int getRowLength() {
            return MIN + (int) Math.round(value * (MAX - MIN));
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal("Row Length: " + getRowLength() + " blocks"));
        }

        @Override
        protected void applyValue() {
            // value is stored in the parent field; read via getRowLength()
        }
    }
}
