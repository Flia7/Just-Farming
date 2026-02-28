package com.justfarming.gui;

import com.justfarming.MacroManager;
import com.justfarming.config.FarmingConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

/**
 * Configuration GUI screen for Just Farming.
 *
 * <p>Layout inspired by the sw mod: a left navigation sidebar with
 * category tabs and a right content panel. Three categories organise
 * the settings:
 * <ul>
 *   <li><b>Farming</b> – crop selection, rewarp delay, set-rewarp, start/stop macro</li>
 *   <li><b>Pests</b>   – pest highlight, labels, ESP options, tracer</li>
 *   <li><b>Misc</b>    – freelook toggle, unlocked mouse</li>
 * </ul>
 */
public class FarmingConfigScreen extends Screen {

    // ── Natural/maximum dimensions ────────────────────────────────────────────
    private static final int WINDOW_WIDTH   = 420;
    private static final int WINDOW_HEIGHT  = 280;
    private static final int NAV_WIDTH      = 110;
    private static final int BUTTON_WIDTH   = 220;
    private static final int BUTTON_HEIGHT  = 20;
    private static final int PADDING        = 10;
    private static final int TAB_HEIGHT     = 26;

    // ── Colour palette (inspired by sw DEFAULT / MONOCHROME theme) ────────────
    private static final int COL_SCREEN_DIM  = 0x60000000; // full-screen dim
    private static final int COL_WIN_BG      = 0xBF000000; // rgba(0,0,0,0.75) window
    private static final int COL_NAV_BG      = 0x99000000; // rgba(0,0,0,0.6)  nav panel
    private static final int COL_BORDER      = 0x28FFFFFF; // rgba(1,1,1,0.16) border
    private static final int COL_SEP         = 0x14FFFFFF; // rgba(1,1,1,0.08) separator
    private static final int COL_SECTION_BG  = 0x14FFFFFF; // section label tint
    private static final int COL_TEXT        = 0xF2FFFFFF; // rgba(1,1,1,0.95) text
    private static final int COL_TEXT_MUTED  = 0x66FFFFFF; // rgba(1,1,1,0.40) muted text
    private static final int COL_ACCENT      = 0xFF7C4DFF; // purple accent
    private static final int COL_TAB_ACTIVE  = 0x26FFFFFF; // active tab tint
    private static final int COL_STATUS_ON   = 0xFF50E890; // green  (running)
    private static final int COL_STATUS_OFF  = 0xFFFF5060; // red    (stopped)
    private static final int COL_SHADOW      = 0x60000000; // drop shadow

    // ── Tabs ──────────────────────────────────────────────────────────────────
    private static final String[] TAB_NAMES = { "Farming", "Pests", "Misc", "Delays" };
    private int activeTab = 0;

    // ── Dynamic layout (computed in init) ─────────────────────────────────────
    private float scale = 1.0f;
    private int winX, winY, winW, winH;
    private int navW;
    private int contentX, contentW;
    private int bw, bh, pad, sLH;

    // ── State ─────────────────────────────────────────────────────────────────
    private final Screen parent;
    private final FarmingConfig config;
    private final MacroManager macroManager;

    // ── Tab selector buttons (in left nav panel) ──────────────────────────────
    private TabButton[] tabButtons;

    // ── Tab 0 – Farming widgets ───────────────────────────────────────────────
    private FlatButtonWidget                  cropSelectButton;
    private FlatButtonWidget                  cropSettingsButton;
    private FlatButtonWidget                  setRewarpButton;
    private FlatButtonWidget                  toggleMacroButton;

    // ── Tab 1 – Pests widgets ─────────────────────────────────────────────────
    private FlatBoolToggleWidget  pestHighlightButton;
    private FlatBoolToggleWidget  pestLabelsButton;
    private TitleScaleSlider              titleScaleSlider;
    private FlatBoolToggleWidget  pestEspButton;
    private FlatBoolToggleWidget  pestTracerButton;

    // ── Tab 2 – Misc widgets ──────────────────────────────────────────────────
    private FlatButtonWidget                  freelookButton;
    private FlatBoolToggleWidget  unlockedMouseButton;
    private FlatBoolToggleWidget  squeakyMousematButton;

    // ── Tab 3 – Delays widgets ────────────────────────────────────────────────
    private LaneSwapDelaySlider           laneSwapDelaySlider;
    private LaneSwapRandomSlider          laneSwapRandomSlider;
    private RewarpDelaySlider             rewarpDelaySlider;
    private RewarpRandomSlider            rewarpRandomSlider;

    // ── Always-visible widget ─────────────────────────────────────────────────
    private FlatButtonWidget saveCloseButton;

    // ── Section-label Y positions (set in init, used in render) ───────────────
    private int sectionCropY, actionSeparatorY;
    private int sectionPestsY, sectionMiscY;
    private int sectionLaneSwapY, sectionRewarpDelayY;

    public FarmingConfigScreen(Screen parent, FarmingConfig config, MacroManager macroManager) {
        super(Text.translatable("gui.just-farming.title"));
        this.parent       = parent;
        this.config       = config;
        this.macroManager = macroManager;
    }

    @Override
    protected void init() {
        // ── Compute scale so the window fits the screen with a small margin ────
        float sw = (float)(this.width  - 4) / WINDOW_WIDTH;
        float sh = (float)(this.height - 4) / WINDOW_HEIGHT;
        scale   = Math.min(1.0f, Math.min(sw, sh));
        winW    = Math.round(WINDOW_WIDTH   * scale);
        winH    = Math.round(WINDOW_HEIGHT  * scale);
        navW    = Math.round(NAV_WIDTH      * scale);
        bw      = Math.round(BUTTON_WIDTH   * scale);
        bh      = Math.max(8, Math.round(BUTTON_HEIGHT * scale));
        pad     = Math.max(2, Math.round(PADDING       * scale));
        sLH     = Math.max(8, Math.round(10            * scale));
        int tabH = Math.max(16, Math.round(TAB_HEIGHT  * scale));
        int gap  = Math.max(2, Math.round(8            * scale));

        winX     = (this.width  - winW) / 2;
        winY     = (this.height - winH) / 2;
        contentX = winX + navW + 1;          // +1 for the separator pixel
        contentW = winW - navW - 1;

        int centerContent = contentX + contentW / 2;
        int widgetX       = centerContent - bw / 2;

        // ── Tab buttons in the left nav panel ─────────────────────────────────
        int firstTabY = winY + Math.round(46 * scale);
        tabButtons = new TabButton[TAB_NAMES.length];
        for (int i = 0; i < TAB_NAMES.length; i++) {
            tabButtons[i] = new TabButton(
                    winX, firstTabY + i * (tabH + 2),
                    navW, tabH, i);
            this.addDrawableChild(tabButtons[i]);
        }

        // Content area starts below a small title/header area
        int contentTop = winY + Math.round(24 * scale);
        int y;

        // ── Tab 0 – Farming ───────────────────────────────────────────────────
        y = contentTop;
        sectionCropY = y;
        y += sLH;
        cropSelectButton = new FlatButtonWidget(widgetX, y, bw, bh,
                        getCropSelectText(),
                        btn -> {
                            applyConfig();
                            if (this.client != null)
                                this.client.setScreen(new CropSelectScreen(this, config));
                        });
        this.addDrawableChild(cropSelectButton);
        y += bh + pad;

        cropSettingsButton = new FlatButtonWidget(widgetX, y, bw, bh,
                        getCropSettingsText(),
                        btn -> {
                            applyConfig();
                            if (this.client != null)
                                this.client.setScreen(new CropSettingsScreen(this, config));
                        });
        this.addDrawableChild(cropSettingsButton);
        y += bh + pad + gap;

        actionSeparatorY = y;
        y += Math.max(2, Math.round(4 * scale));

        setRewarpButton = new FlatButtonWidget(widgetX, y, bw, bh,
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
                        });
        this.addDrawableChild(setRewarpButton);
        y += bh + pad;

        toggleMacroButton = new FlatButtonWidget(widgetX, y, bw, bh,
                        getMacroToggleText(),
                        btn -> {
                            applyConfig();
                            macroManager.toggle();
                            btn.setMessage(getMacroToggleText());
                        });
        this.addDrawableChild(toggleMacroButton);
        y += bh + pad;

        squeakyMousematButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.just-farming.squeaky_mousemat_label"),
                        config.squeakyMousematEnabled);
        this.addDrawableChild(squeakyMousematButton);

        // ── Tab 1 – Pests ─────────────────────────────────────────────────────
        y = contentTop;
        sectionPestsY = y;
        y += sLH;

        pestHighlightButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.just-farming.pest_highlight_label"),
                        config.pestHighlightEnabled);
        this.addDrawableChild(pestHighlightButton);
        y += bh + pad;

        pestLabelsButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.just-farming.pest_labels_label"),
                        config.pestLabelsEnabled);
        this.addDrawableChild(pestLabelsButton);
        y += bh + pad;

        titleScaleSlider = new TitleScaleSlider(widgetX, y, bw, bh, config.pestTitleScale);
        this.addDrawableChild(titleScaleSlider);
        y += bh + pad;

        pestEspButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.just-farming.pest_esp_label"),
                        config.pestEspEnabled);
        this.addDrawableChild(pestEspButton);
        y += bh + pad;

        pestTracerButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.just-farming.pest_tracer_label"),
                        config.pestTracerEnabled);
        this.addDrawableChild(pestTracerButton);

        // ── Tab 2 – Misc ──────────────────────────────────────────────────────
        y = contentTop;
        sectionMiscY = y;
        y += sLH;

        freelookButton = new FlatButtonWidget(widgetX, y, bw, bh,
                        getFreelookButtonText(),
                        btn -> {
                            macroManager.toggleFreelook();
                            btn.setMessage(getFreelookButtonText());
                        });
        this.addDrawableChild(freelookButton);
        y += bh + pad;

        unlockedMouseButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.just-farming.unlocked_mouse_label"),
                        config.unlockedMouseEnabled);
        this.addDrawableChild(unlockedMouseButton);

        // ── Tab 3 – Delays ────────────────────────────────────────────────────
        y = contentTop;
        sectionLaneSwapY = y;
        y += sLH;
        laneSwapDelaySlider = new LaneSwapDelaySlider(widgetX, y, bw, bh, config.laneSwapDelayMin);
        this.addDrawableChild(laneSwapDelaySlider);
        y += bh + pad;

        laneSwapRandomSlider = new LaneSwapRandomSlider(widgetX, y, bw, bh, config.laneSwapDelayRandom);
        this.addDrawableChild(laneSwapRandomSlider);
        y += bh + pad + gap;

        sectionRewarpDelayY = y;
        y += sLH;
        rewarpDelaySlider = new RewarpDelaySlider(widgetX, y, bw, bh, config.rewarpDelayMin);
        this.addDrawableChild(rewarpDelaySlider);
        y += bh + pad;

        rewarpRandomSlider = new RewarpRandomSlider(widgetX, y, bw, bh, config.rewarpDelayRandom);
        this.addDrawableChild(rewarpRandomSlider);

        // ── Always-visible: Close button anchored to the bottom ───────────────
        int closeBtnY = winY + winH - bh - pad;
        saveCloseButton = new FlatButtonWidget(widgetX, closeBtnY, bw, bh,
                        Text.translatable("gui.just-farming.close"),
                        btn -> close());
        this.addDrawableChild(saveCloseButton);

        updateTabVisibility();
    }

    /** Shows/hides content widgets according to {@link #activeTab}. */
    private void updateTabVisibility() {
        boolean t0 = activeTab == 0;
        cropSelectButton.visible      = t0;
        cropSettingsButton.visible    = t0;
        setRewarpButton.visible       = t0;
        toggleMacroButton.visible     = t0;
        squeakyMousematButton.visible = t0;

        boolean t1 = activeTab == 1;
        pestHighlightButton.visible = t1;
        pestLabelsButton.visible    = t1;
        titleScaleSlider.visible    = t1;
        pestEspButton.visible       = t1;
        pestTracerButton.visible    = t1;

        boolean t2 = activeTab == 2;
        freelookButton.visible      = t2;
        unlockedMouseButton.visible = t2;

        boolean t3 = activeTab == 3;
        laneSwapDelaySlider.visible  = t3;
        laneSwapRandomSlider.visible = t3;
        rewarpDelaySlider.visible    = t3;
        rewarpRandomSlider.visible   = t3;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int winR = winX + winW;
        int winB = winY + winH;

        // ── Full-screen dim overlay ───────────────────────────────────────────
        context.fill(0, 0, this.width, this.height, COL_SCREEN_DIM);

        // ── Drop shadow ───────────────────────────────────────────────────────
        context.fill(winX + 4, winY + 4, winR + 4, winB + 4, COL_SHADOW);

        // ── Outer border (1 px) ───────────────────────────────────────────────
        context.fill(winX - 1, winY - 1, winR + 1, winB + 1, COL_BORDER);

        // ── Window background ─────────────────────────────────────────────────
        context.fill(winX, winY, winR, winB, COL_WIN_BG);

        // ── Left nav panel background ─────────────────────────────────────────
        context.fill(winX, winY, winX + navW, winB, COL_NAV_BG);

        // ── Nav / content vertical separator ─────────────────────────────────
        context.fill(winX + navW, winY + 6, winX + navW + 1, winB - 6, COL_BORDER);

        // ── Nav: mod title ────────────────────────────────────────────────────
        int navCenterX = winX + navW / 2;
        int navTitleY  = winY + Math.max(4, Math.round(8 * scale));
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Just Farming").withColor(COL_TEXT),
                navCenterX, navTitleY, COL_TEXT);

        // ── Nav: status indicator ─────────────────────────────────────────────
        boolean running   = macroManager.isRunning();
        int statusColor   = running ? COL_STATUS_ON : COL_STATUS_OFF;
        String statusTxt  = running ? "\u25CF Running" : "\u25CF Stopped";
        int statusY       = navTitleY + 11;
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(statusTxt).withColor(statusColor),
                navCenterX, statusY, statusColor);

        // ── Nav: separator line below status ─────────────────────────────────
        int navSepY = statusY + 12;
        context.fill(winX + 8, navSepY, winX + navW - 8, navSepY + 1, COL_SEP);

        // ── Content: current tab section title ────────────────────────────────
        int contentTitleY = winY + Math.max(4, Math.round(8 * scale));
        context.drawTextWithShadow(this.textRenderer,
                Text.literal(TAB_NAMES[activeTab]).withColor(COL_TEXT),
                contentX + Math.round(8 * scale), contentTitleY, COL_TEXT);

        // ── Content: thin separator below title ───────────────────────────────
        int contentSepY = contentTitleY + 10;
        context.fill(contentX + 4, contentSepY, winR - 4, contentSepY + 1, COL_SEP);

        // ── Section labels for the active tab ─────────────────────────────────
        if (activeTab == 0) {
            drawSectionLabel(context, "Crop",   sectionCropY);
            context.fill(contentX + 16, actionSeparatorY,
                    winR - 16, actionSeparatorY + 1, COL_SEP);
        } else if (activeTab == 1) {
            drawSectionLabel(context, "Pests", sectionPestsY);
        } else if (activeTab == 2) {
            drawSectionLabel(context, "Misc", sectionMiscY);
        } else {
            drawSectionLabel(context, "Lane Swap", sectionLaneSwapY);
            drawSectionLabel(context, "Rewarp",    sectionRewarpDelayY);
        }

        // ── Widgets ───────────────────────────────────────────────────────────
        super.render(context, mouseX, mouseY, delta);
    }

    /** Draws a labelled section header spanning the content area. */
    private void drawSectionLabel(DrawContext context, String label, int y) {
        int winR = winX + winW;
        context.fill(contentX + 4, y, winR - 4, y + sLH, COL_SECTION_BG);
        context.fill(contentX + 6, y + 1, contentX + 8, y + sLH - 1, COL_ACCENT);
        context.drawTextWithShadow(this.textRenderer,
                Text.literal(label).withColor(COL_TEXT_MUTED),
                contentX + 12, y + 1, COL_TEXT_MUTED);
    }

    @Override
    public void close() {
        applyConfig();
        config.save();
        if (this.client != null) this.client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                  double horizontalAmount, double verticalAmount) {
        // Consume scroll so sliders/cycling buttons aren't accidentally changed.
        return true;
    }

    /** Read widget values back into the config object. */
    private void applyConfig() {
        config.laneSwapDelayMin     = laneSwapDelaySlider.getDelayValue();
        config.laneSwapDelayRandom  = laneSwapRandomSlider.getRandomValue();
        config.rewarpDelayMin       = rewarpDelaySlider.getDelayValue();
        config.rewarpDelayRandom    = rewarpRandomSlider.getRandomValue();
        config.pestHighlightEnabled = pestHighlightButton.getValue();
        config.pestLabelsEnabled    = pestLabelsButton.getValue();
        config.pestTitleScale       = titleScaleSlider.getTitleScaleValue();
        config.pestEspEnabled       = pestEspButton.getValue();
        config.pestTracerEnabled    = pestTracerButton.getValue();
        config.unlockedMouseEnabled = unlockedMouseButton.getValue();
        config.squeakyMousematEnabled = squeakyMousematButton.getValue();
        macroManager.setConfig(config);
    }

    private Text getCropSelectText() {
        String name = Text.translatable(config.selectedCrop.getTranslationKey()).getString();
        return Text.literal("Select Crop: " + name);
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
        return Text.translatable("gui.just-farming.set_rewarp");
    }

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    /** Draws a flat custom slider – shared by all slider inner classes. */
    private static void renderFlatSlider(DrawContext context, int x, int y, int w, int h,
                                          double value, net.minecraft.text.Text message) {
        FlatButtonWidget.renderFlatSlider(context, x, y, w, h, value, message);
    }

    /**
     * Boolean toggle widget that renders in the dark flat style instead of
     * using the default Minecraft button texture.
     */
    private static class FlatBoolToggleWidget extends net.minecraft.client.gui.widget.ClickableWidget {

        private static final int COL_BG_NORMAL = 0x1AFFFFFF;
        private static final int COL_BG_HOVER  = 0x33FFFFFF;
        private static final int COL_BORDER    = 0x28FFFFFF;
        private static final int COL_ACCENT    = 0xFF7C4DFF;
        private static final int COL_TEXT      = 0xF2FFFFFF;
        private static final int COL_ON        = 0xFF50E890;
        private static final int COL_OFF       = 0xFFFF5060;

        private final net.minecraft.text.Text label;
        private boolean value;

        FlatBoolToggleWidget(int x, int y, int width, int height,
                             net.minecraft.text.Text label, boolean initialValue) {
            super(x, y, width, height, label);
            this.label = label;
            this.value = initialValue;
        }

        boolean getValue() {
            return value;
        }

        @Override
        public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            int bg = isHovered() ? COL_BG_HOVER : COL_BG_NORMAL;
            context.fill(x, y, x + w, y + h, bg);
            // 1-px border
            context.fill(x,         y,         x + w, y + 1,     COL_BORDER);
            context.fill(x,         y + h - 1, x + w, y + h,     COL_BORDER);
            context.fill(x,         y + 1,     x + 1, y + h - 1, COL_BORDER);
            context.fill(x + w - 1, y + 1,     x + w, y + h - 1, COL_BORDER);
            // 2-px left accent bar (purple when ON, dim when OFF)
            context.fill(x, y, x + 2, y + h, value ? COL_ACCENT : COL_BORDER);
            // Label (left-aligned)
            var tr = MinecraftClient.getInstance().textRenderer;
            context.drawTextWithShadow(tr, label, x + 8, y + (h - 8) / 2, COL_TEXT);
            // ON / OFF indicator (right-aligned, coloured)
            String indicator     = value ? "\u25CF ON" : "\u25CF OFF";
            int    indicatorColor = value ? COL_ON : COL_OFF;
            int    indicatorX    = x + w - tr.getWidth(indicator) - 8;
            context.drawTextWithShadow(tr,
                    net.minecraft.text.Text.literal(indicator).withColor(indicatorColor),
                    indicatorX, y + (h - 8) / 2, indicatorColor);
        }

        @Override
        public void onClick(net.minecraft.client.gui.Click click, boolean toggle) {
            value = !value;
        }

        @Override
        protected void appendClickableNarrations(
                net.minecraft.client.gui.screen.narration.NarrationMessageBuilder builder) {
            appendDefaultNarrations(builder);
        }
    }

    /**
     * Category tab button rendered in the left navigation sidebar.
     * Draws a custom background with an accent left-edge bar when active.
     */
    private class TabButton extends net.minecraft.client.gui.widget.ClickableWidget {

        private final int tabIndex;

        TabButton(int x, int y, int width, int height, int tabIndex) {
            super(x, y, width, height, Text.literal(TAB_NAMES[tabIndex]));
            this.tabIndex = tabIndex;
        }

        @Override
        public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            boolean active  = activeTab == tabIndex;
            boolean hovered = this.isHovered();

            if (active) {
                context.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                        COL_TAB_ACTIVE);
                // Accent left-edge indicator (3 px wide)
                context.fill(getX(), getY(), getX() + 3, getY() + getHeight(),
                        COL_ACCENT);
            } else if (hovered) {
                context.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                        0x14FFFFFF);
            }

            int textColor = active ? COL_TEXT : COL_TEXT_MUTED;
            context.drawTextWithShadow(
                    FarmingConfigScreen.this.textRenderer,
                    getMessage(),
                    getX() + (active ? 8 : 6),
                    getY() + (getHeight() - 8) / 2,
                    textColor);
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

    /** Slider for the minimum lane-swap delay (0–2000 ms). */
    private static class LaneSwapDelaySlider extends SliderWidget {

        private static final int MIN =    0;
        private static final int MAX = 2000;

        LaneSwapDelaySlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, Text.empty(),
                    (double)(initialValue - MIN) / (MAX - MIN));
            updateMessage();
        }

        int getDelayValue() {
            return MIN + (int)Math.round(value * (MAX - MIN));
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Lane Swap Delay: %d ms", getDelayValue())));
        }

        @Override
        public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            renderFlatSlider(context, getX(), getY(), getWidth(), getHeight(), value, getMessage());
        }

        @Override
        protected void applyValue() {}
    }

    /** Slider for the random extra lane-swap delay (0–1000 ms). */
    private static class LaneSwapRandomSlider extends SliderWidget {

        private static final int MIN =    0;
        private static final int MAX = 1000;

        LaneSwapRandomSlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, Text.empty(),
                    (double)(initialValue - MIN) / (MAX - MIN));
            updateMessage();
        }

        int getRandomValue() {
            return MIN + (int)Math.round(value * (MAX - MIN));
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Lane Swap Randomization: %d ms", getRandomValue())));
        }

        @Override
        public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            renderFlatSlider(context, getX(), getY(), getWidth(), getHeight(), value, getMessage());
        }

        @Override
        protected void applyValue() {}
    }

    /** Slider for the minimum rewarp delay (0–2000 ms). */
    private static class RewarpDelaySlider extends SliderWidget {

        private static final int MIN =    0;
        private static final int MAX = 2000;

        RewarpDelaySlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, Text.empty(),
                    (double)(initialValue - MIN) / (MAX - MIN));
            updateMessage();
        }

        int getDelayValue() {
            return MIN + (int)Math.round(value * (MAX - MIN));
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Rewarp Delay: %d ms", getDelayValue())));
        }

        @Override
        public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            renderFlatSlider(context, getX(), getY(), getWidth(), getHeight(), value, getMessage());
        }

        @Override
        protected void applyValue() {}
    }

    /** Slider for the random extra rewarp delay (0–1000 ms). */
    private static class RewarpRandomSlider extends SliderWidget {

        private static final int MIN =    0;
        private static final int MAX = 1000;

        RewarpRandomSlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, Text.empty(),
                    (double)(initialValue - MIN) / (MAX - MIN));
            updateMessage();
        }

        int getRandomValue() {
            return MIN + (int)Math.round(value * (MAX - MIN));
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Rewarp Randomization: %d ms", getRandomValue())));
        }

        @Override
        public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            renderFlatSlider(context, getX(), getY(), getWidth(), getHeight(), value, getMessage());
        }

        @Override
        protected void applyValue() {}
    }

    /** Slider for the floating pest plot title scale (0.5–6.0). */
    private static class TitleScaleSlider extends SliderWidget {

        private static final float MIN = 0.5f;
        private static final float MAX = 6.0f;

        TitleScaleSlider(int x, int y, int width, int height, float initialValue) {
            super(x, y, width, height, Text.empty(),
                    (double)(initialValue - MIN) / (MAX - MIN));
            updateMessage();
        }

        float getTitleScaleValue() {
            return MIN + (float)value * (MAX - MIN);
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Pest Plot Label Scale: %.2f", getTitleScaleValue())));
        }

        @Override
        public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            renderFlatSlider(context, getX(), getY(), getWidth(), getHeight(), value, getMessage());
        }

        @Override
        protected void applyValue() {}
    }
}
