package com.justfarming.gui;

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
 * <p>Three tabs organise the settings:
 * <ul>
 *   <li><b>Farming</b> – crop (with recommended speed), rewarp delay, set-rewarp, start/stop macro</li>
 *   <li><b>Pests</b>   – pest highlight, labels, ESP options, tracer</li>
 *   <li><b>Misc</b>    – freelook toggle</li>
 * </ul>
 */
public class FarmingConfigScreen extends Screen {

    // ── Layout (natural/maximum dimensions) ──────────────────────────────────
    private static final int PANEL_WIDTH    = 320;
    private static final int PANEL_HEIGHT   = 420;
    private static final int HEADER_HEIGHT  = 46;
    private static final int TAB_BAR_HEIGHT = 22;
    private static final int BUTTON_WIDTH   = 240;
    private static final int BUTTON_HEIGHT  = 20;
    private static final int PADDING        = 6;

    // ── Dynamic layout (computed in init, used in render) ─────────────────────
    private float scale = 1.0f;
    private int panelX, panelY, panelW, panelH;
    private int hdrH, tabH, bw, bh, pad, sectionLH;

    // ── Colour palette (modern dark theme) ────────────────────────────────────
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
    private static final int COL_TAB_ACTIVE    = 0xFF3D2B8F;
    private static final int COL_TAB_INACTIVE  = 0xFF1A1040;

    // ── Tabs ──────────────────────────────────────────────────────────────────
    private static final String[] TAB_NAMES = { "Farming", "Pests", "Misc" };
    private int activeTab = 0;

    // ── State ─────────────────────────────────────────────────────────────────
    private final Screen parent;
    private final FarmingConfig config;
    private final MacroManager macroManager;

    // ── Tab selector buttons ──────────────────────────────────────────────────
    private TabButton[] tabButtons;

    // ── Tab 0 – Farming widgets ───────────────────────────────────────────────
    private ButtonWidget                  cropSelectButton;
    private ButtonWidget                  cropSettingsButton;
    private SwapDelaySlider               swapDelaySlider;
    private SwapRandomSlider              swapRandomSlider;
    private ButtonWidget                  setRewarpButton;
    private ButtonWidget                  toggleMacroButton;

    // ── Tab 1 – Pests widgets ─────────────────────────────────────────────────
    private CyclingButtonWidget<Boolean>  pestHighlightButton;
    private CyclingButtonWidget<Boolean>  pestLabelsButton;
    private TitleScaleSlider              titleScaleSlider;
    private CyclingButtonWidget<Boolean>  pestEspButton;
    private CyclingButtonWidget<Boolean>  pestTracerButton;

    // ── Tab 2 – Misc widgets ──────────────────────────────────────────────────
    private ButtonWidget freelookButton;
    private CyclingButtonWidget<Boolean> unlockedMouseButton;

    // ── Always-visible widget ─────────────────────────────────────────────────
    private ButtonWidget saveCloseButton;

    // ── Section-label Y positions (set in init, used in render) ───────────────
    private int sectionCropY;
    private int sectionDelaysY;
    private int actionSeparatorY;
    private int sectionPestsY;
    private int sectionMiscY;

    public FarmingConfigScreen(Screen parent, FarmingConfig config, MacroManager macroManager) {
        super(Text.translatable("gui.just-farming.title"));
        this.parent       = parent;
        this.config       = config;
        this.macroManager = macroManager;
    }

    @Override
    protected void init() {
        // ── Compute scale so the panel fits the screen with a small margin ────
        float sw = (float)(this.width  - 4) / PANEL_WIDTH;
        float sh = (float)(this.height - 4) / PANEL_HEIGHT;
        scale     = Math.min(1.0f, Math.min(sw, sh));
        panelW    = Math.round(PANEL_WIDTH      * scale);
        panelH    = Math.round(PANEL_HEIGHT     * scale);
        hdrH      = Math.round(HEADER_HEIGHT    * scale);
        tabH      = Math.round(TAB_BAR_HEIGHT   * scale);
        bw        = Math.round(BUTTON_WIDTH     * scale);
        bh        = Math.max(8, Math.round(BUTTON_HEIGHT * scale));
        pad       = Math.max(2, Math.round(PADDING       * scale));
        sectionLH = Math.max(8, Math.round(10            * scale));
        int gap   = Math.max(2, Math.round(8             * scale));

        panelX = (this.width  - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int centerX = panelX + panelW / 2;
        int widgetX = centerX - bw / 2;

        // ── Tab selector buttons ──────────────────────────────────────────────
        int tabBarY  = panelY + hdrH + 1;
        int tabW     = (panelW - 4) / TAB_NAMES.length;
        tabButtons   = new TabButton[TAB_NAMES.length];
        for (int i = 0; i < TAB_NAMES.length; i++) {
            tabButtons[i] = new TabButton(
                    panelX + 2 + i * (tabW + 1), tabBarY,
                    tabW, tabH - 2, i);
            this.addDrawableChild(tabButtons[i]);
        }

        // Content starts below the tab bar
        int contentTop = panelY + hdrH + tabH + pad;
        int y;

        // ── Tab 0 – Farming ───────────────────────────────────────────────────
        y = contentTop;
        sectionCropY = y;
        y += sectionLH;
        cropSelectButton = ButtonWidget.builder(
                        getCropSelectText(),
                        btn -> {
                            applyConfig();
                            if (this.client != null) {
                                this.client.setScreen(new CropSelectScreen(this, config));
                            }
                        })
                .dimensions(widgetX, y, bw, bh)
                .build();
        this.addDrawableChild(cropSelectButton);
        y += bh + pad;

        cropSettingsButton = ButtonWidget.builder(
                        getCropSettingsText(),
                        btn -> {
                            applyConfig();
                            if (this.client != null) {
                                this.client.setScreen(new CropSettingsScreen(this, config));
                            }
                        })
                .dimensions(widgetX, y, bw, bh)
                .build();
        this.addDrawableChild(cropSettingsButton);
        y += bh + pad + gap;

        sectionDelaysY = y;
        y += sectionLH;
        swapDelaySlider = new SwapDelaySlider(widgetX, y, bw, bh, config.rewarpDelayMin);
        this.addDrawableChild(swapDelaySlider);
        y += bh + pad;

        swapRandomSlider = new SwapRandomSlider(widgetX, y, bw, bh, config.rewarpDelayRandom);
        this.addDrawableChild(swapRandomSlider);
        y += bh + pad + gap;

        actionSeparatorY = y;
        y += Math.max(2, Math.round(4 * scale));

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
                .dimensions(widgetX, y, bw, bh)
                .build();
        this.addDrawableChild(setRewarpButton);
        y += bh + pad;

        toggleMacroButton = ButtonWidget.builder(
                        getMacroToggleText(),
                        btn -> {
                            applyConfig();
                            macroManager.toggle();
                            btn.setMessage(getMacroToggleText());
                        })
                .dimensions(widgetX, y, bw, bh)
                .build();
        this.addDrawableChild(toggleMacroButton);

        // ── Tab 1 – Pests ─────────────────────────────────────────────────────
        y = contentTop;
        sectionPestsY = y;
        y += sectionLH;

        pestHighlightButton = CyclingButtonWidget.builder(
                        (Boolean val) -> val ? Text.literal("ON") : Text.literal("OFF"))
                .values(Boolean.TRUE, Boolean.FALSE)
                .initially(config.pestHighlightEnabled)
                .build(widgetX, y, bw, bh,
                        Text.translatable("gui.just-farming.pest_highlight_label"));
        this.addDrawableChild(pestHighlightButton);
        y += bh + pad;

        pestLabelsButton = CyclingButtonWidget.builder(
                        (Boolean val) -> val ? Text.literal("ON") : Text.literal("OFF"))
                .values(Boolean.TRUE, Boolean.FALSE)
                .initially(config.pestLabelsEnabled)
                .build(widgetX, y, bw, bh,
                        Text.translatable("gui.just-farming.pest_labels_label"));
        this.addDrawableChild(pestLabelsButton);
        y += bh + pad;

        titleScaleSlider = new TitleScaleSlider(widgetX, y, bw, bh, config.pestTitleScale);
        this.addDrawableChild(titleScaleSlider);
        y += bh + pad;

        pestEspButton = CyclingButtonWidget.builder(
                        (Boolean val) -> val ? Text.literal("ON") : Text.literal("OFF"))
                .values(Boolean.TRUE, Boolean.FALSE)
                .initially(config.pestEspEnabled)
                .build(widgetX, y, bw, bh,
                        Text.translatable("gui.just-farming.pest_esp_label"));
        this.addDrawableChild(pestEspButton);
        y += bh + pad;

        pestTracerButton = CyclingButtonWidget.builder(
                        (Boolean val) -> val ? Text.literal("ON") : Text.literal("OFF"))
                .values(Boolean.TRUE, Boolean.FALSE)
                .initially(config.pestTracerEnabled)
                .build(widgetX, y, bw, bh,
                        Text.translatable("gui.just-farming.pest_tracer_label"));
        this.addDrawableChild(pestTracerButton);

        // ── Tab 2 – Misc ──────────────────────────────────────────────────────
        y = contentTop;
        sectionMiscY = y;
        y += sectionLH;

        freelookButton = ButtonWidget.builder(
                        getFreelookButtonText(),
                        btn -> {
                            macroManager.toggleFreelook();
                            btn.setMessage(getFreelookButtonText());
                        })
                .dimensions(widgetX, y, bw, bh)
                .build();
        this.addDrawableChild(freelookButton);
        y += bh + pad;

        unlockedMouseButton = CyclingButtonWidget.builder(
                        (Boolean val) -> val ? Text.literal("ON") : Text.literal("OFF"))
                .values(Boolean.TRUE, Boolean.FALSE)
                .initially(config.unlockedMouseEnabled)
                .build(widgetX, y, bw, bh,
                        Text.translatable("gui.just-farming.unlocked_mouse_label"));
        this.addDrawableChild(unlockedMouseButton);

        // ── Always-visible: Close button at the very bottom ───────────────────
        int closeBtnY = panelY + panelH - bh - pad;
        saveCloseButton = ButtonWidget.builder(
                        Text.translatable("gui.just-farming.close"),
                        btn -> close())
                .dimensions(widgetX, closeBtnY, bw, bh)
                .build();
        this.addDrawableChild(saveCloseButton);

        // Apply initial visibility
        updateTabVisibility();
    }

    /** Shows/hides content widgets according to {@link #activeTab}. */
    private void updateTabVisibility() {
        boolean t0 = activeTab == 0;
        cropSelectButton.visible   = t0;
        cropSettingsButton.visible = t0;
        swapDelaySlider.visible    = t0;
        swapRandomSlider.visible  = t0;
        setRewarpButton.visible   = t0;
        toggleMacroButton.visible = t0;

        boolean t1 = activeTab == 1;
        pestHighlightButton.visible     = t1;
        pestLabelsButton.visible        = t1;
        titleScaleSlider.visible        = t1;
        pestEspButton.visible           = t1;
        pestTracerButton.visible        = t1;

        boolean t2 = activeTab == 2;
        freelookButton.visible     = t2;
        unlockedMouseButton.visible = t2;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int panelR = panelX + panelW;
        int panelB = panelY + panelH;

        // ── Drop shadow ───────────────────────────────────────────────────────
        context.fill(panelX + 3, panelY + 3, panelR + 3, panelB + 3, COL_SHADOW);

        // ── Outer border (2 px) ───────────────────────────────────────────────
        context.fill(panelX - 2, panelY - 2, panelR + 2, panelB + 2, COL_BORDER_OUTER);

        // ── Accent border (1 px) ──────────────────────────────────────────────
        context.fill(panelX - 1, panelY - 1, panelR + 1, panelB + 1, COL_BORDER_INNER);

        // ── Panel background ──────────────────────────────────────────────────
        context.fill(panelX, panelY, panelR, panelB, COL_BG);

        // ── Header gradient ───────────────────────────────────────────────────
        context.fillGradient(panelX, panelY, panelR, panelY + hdrH,
                COL_HEADER_TOP, COL_HEADER_BOTTOM);

        // ── Header accent line (bottom) ───────────────────────────────────────
        context.fillGradient(panelX, panelY + hdrH - 1, panelR,
                panelY + hdrH + 1, COL_ACCENT, COL_BORDER_OUTER);

        // ── Corner accents (top-left / top-right) ─────────────────────────────
        context.fill(panelX - 2, panelY - 2, panelX + 6, panelY - 1, COL_ACCENT);
        context.fill(panelX - 2, panelY - 2, panelX - 1, panelY + 6, COL_ACCENT);
        context.fill(panelR - 6, panelY - 2, panelR + 2, panelY - 1, COL_ACCENT);
        context.fill(panelR + 1, panelY - 2, panelR + 2, panelY + 6, COL_ACCENT);

        // ── Title ─────────────────────────────────────────────────────────────
        int titleY = panelY + Math.max(4, Math.round(10 * scale));
        context.drawCenteredTextWithShadow(this.textRenderer,
                this.title, this.width / 2, titleY, COL_TITLE);

        // ── Status badge ──────────────────────────────────────────────────────
        boolean running    = macroManager.isRunning();
        String  statusStr  = running ? "\u25CF RUNNING" : "\u25CF STOPPED";
        int statusColor    = running ? 0xFF50E890 : 0xFFFF6070;
        int badgeGlow      = running ? 0x2030D870 : 0x20FF4050;
        int badgeW         = this.textRenderer.getWidth(statusStr) + 14;
        int badgeX         = this.width / 2 - badgeW / 2;
        int badgeY         = panelY + Math.max(14, Math.round(27 * scale));
        context.fill(badgeX - 2, badgeY - 1, badgeX + badgeW + 2, badgeY + 13, badgeGlow);
        context.fill(badgeX, badgeY, badgeX + badgeW, badgeY + 12,
                running ? 0x3020C060 : 0x30D03040);
        context.fill(badgeX, badgeY, badgeX + badgeW, badgeY + 1, statusColor);
        context.fill(badgeX, badgeY + 11, badgeX + badgeW, badgeY + 12, statusColor);
        context.fill(badgeX, badgeY, badgeX + 1, badgeY + 12, statusColor);
        context.fill(badgeX + badgeW - 1, badgeY, badgeX + badgeW, badgeY + 12, statusColor);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(statusStr).withColor(statusColor),
                this.width / 2, badgeY + 2, 0xFFFFFF);

        // ── Tab bar background ────────────────────────────────────────────────
        int tabBarY = panelY + hdrH + 1;
        context.fill(panelX, tabBarY - 1, panelR, tabBarY + tabH, COL_TAB_INACTIVE);
        // Highlight active tab
        int tabW       = (panelW - 4) / TAB_NAMES.length;
        int activeTabX = panelX + 2 + activeTab * (tabW + 1);
        context.fill(activeTabX, tabBarY, activeTabX + tabW, tabBarY + tabH - 2,
                COL_TAB_ACTIVE);
        // Bottom separator below tab bar
        context.fill(panelX, tabBarY + tabH - 1, panelR, tabBarY + tabH,
                COL_ACCENT);

        // ── Section labels per tab ────────────────────────────────────────────
        if (activeTab == 0) {
            drawSectionLabel(context, "Crop",    panelX, sectionCropY,    panelR);
            drawSectionLabel(context, "Delays",  panelX, sectionDelaysY,  panelR);
            context.fillGradient(panelX + 20, actionSeparatorY,
                    panelR - 20, actionSeparatorY + 1, COL_ACCENT, 0x00000000);
        } else if (activeTab == 1) {
            drawSectionLabel(context, "Pests", panelX, sectionPestsY, panelR);
        } else {
            drawSectionLabel(context, "Misc", panelX, sectionMiscY, panelR);
        }

        // ── Widgets ───────────────────────────────────────────────────────────
        super.render(context, mouseX, mouseY, delta);
    }

    /** Draws a section label with an accent bar and subtle background. */
    private void drawSectionLabel(DrawContext context, String label, int panelX, int y, int panelR) {
        context.fill(panelX + 4, y, panelR - 4, y + sectionLH, COL_SECTION_BG);
        context.fill(panelX + 6, y + 1, panelX + 8, y + sectionLH - 1, COL_ACCENT);
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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // Consume scroll events so widgets (sliders, cycling buttons) cannot be
        // accidentally changed by scrolling while the config GUI is open.
        return true;
    }

    /** Read widget values back into the config object. */
    private void applyConfig() {
        // config.selectedCrop is managed directly by CropSelectScreen
        config.rewarpDelayMin       = swapDelaySlider.getDelayValue();
        config.rewarpDelayRandom    = swapRandomSlider.getRandomValue();
        config.pestHighlightEnabled = pestHighlightButton.getValue();
        config.pestLabelsEnabled    = pestLabelsButton.getValue();
        config.pestTitleScale       = titleScaleSlider.getTitleScaleValue();
        config.pestEspEnabled       = pestEspButton.getValue();
        config.pestTracerEnabled    = pestTracerButton.getValue();
        config.unlockedMouseEnabled = unlockedMouseButton.getValue();
        macroManager.setConfig(config);
    }

    private Text getCropSelectText() {
        String name = Text.translatable(config.selectedCrop.getTranslationKey()).getString();
        return Text.literal("Select Crop: " + name
                + "  (Speed: " + config.selectedCrop.getRecommendedSpeed() + ")");
    }

    private Text getCropSettingsText() {
        String name = Text.translatable(config.selectedCrop.getTranslationKey()).getString();
        return Text.literal(name + " Settings");
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
     * A lightweight tab button that renders as a browser-style tab.
     * It draws a custom coloured background (no default Minecraft button
     * texture) and highlights itself when it is the active tab.
     */
    private class TabButton extends net.minecraft.client.gui.widget.ClickableWidget {

        private final int tabIndex;

        TabButton(int x, int y, int width, int height, int tabIndex) {
            super(x, y, width, height, Text.literal(TAB_NAMES[tabIndex]));
            this.tabIndex = tabIndex;
        }

        @Override
        protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            boolean active  = activeTab == tabIndex;
            boolean hovered = this.isHovered();
            int bg = active  ? COL_TAB_ACTIVE
                   : hovered ? 0xFF261952
                   :           COL_TAB_INACTIVE;
            context.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), bg);
            if (active) {
                context.fill(getX(), getY(), getX() + getWidth(), getY() + 2, COL_ACCENT);
            }
            context.drawCenteredTextWithShadow(
                    FarmingConfigScreen.this.textRenderer,
                    getMessage(),
                    getX() + getWidth() / 2,
                    getY() + (getHeight() - 8) / 2,
                    active ? COL_TITLE : COL_LABEL);
        }

        @Override
        public void onClick(net.minecraft.client.gui.Click click, boolean toggle) {
            applyConfig();
            activeTab = tabIndex;
            updateTabVisibility();
        }

        @Override
        protected void appendClickableNarrations(
                net.minecraft.client.gui.screen.narration.NarrationMessageBuilder builder) {
            this.appendDefaultNarrations(builder);
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

    /**
     * Slider for the floating pest plot title scale (0.5–6.0).
     */
    private static class TitleScaleSlider extends SliderWidget {

        private static final float MIN = 0.5f;
        private static final float MAX = 6.0f;

        TitleScaleSlider(int x, int y, int width, int height, float initialValue) {
            super(x, y, width, height,
                    Text.empty(),
                    (double) (initialValue - MIN) / (MAX - MIN));
            updateMessage();
        }

        float getTitleScaleValue() {
            return MIN + (float) value * (MAX - MIN);
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Pest Plot Label Scale: %.2f", getTitleScaleValue())));
        }

        @Override
        protected void applyValue() {}
    }
}
