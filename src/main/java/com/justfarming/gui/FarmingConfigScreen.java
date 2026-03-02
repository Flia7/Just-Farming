package com.justfarming.gui;

import com.justfarming.MacroManager;
import com.justfarming.config.FarmingConfig;
import com.justfarming.visitor.VisitorManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
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
    private static final int WINDOW_HEIGHT  = 380;
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
    private static final String[] TAB_NAMES = { "Farming", "Pests", "Misc", "Delays", "Visitor's macro" };
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
    private final VisitorManager visitorManager;

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
    private FlatBoolToggleWidget  gardenOnlyButton;
    private FlatBoolToggleWidget  squeakyMousematButton;

    // ── Tab 3 – Delays widgets ────────────────────────────────────────────────
    private LaneSwapDelaySlider           laneSwapDelaySlider;
    private LaneSwapRandomSlider          laneSwapRandomSlider;
    private RewarpDelaySlider             rewarpDelaySlider;
    private RewarpRandomSlider            rewarpRandomSlider;
    private MousematSwapToSlider          mousematSwapToSlider;
    private MousematPreDelaySlider        mousematPreDelaySlider;
    private MousematPostDelaySlider       mousematPostDelaySlider;
    private MousematResumeDelaySlider     mousematResumeDelaySlider;

    // ── Tab 4 – Visitors widgets ──────────────────────────────────────────────
    private FlatBoolToggleWidget  visitorsEnabledButton;
    private FlatBoolToggleWidget  visitorsBuyFromBazaarButton;
    private FlatButtonWidget              visitorsBlacklistButton;
    private VisitorInteractDelaySlider    visitorsInteractDelaySlider;
    private VisitorTeleportDelaySlider    visitorsTeleportDelaySlider;
    private BazaarSearchDelaySlider       bazaarSearchDelaySlider;
    private BazaarClickDelaySlider        bazaarClickDelaySlider;


    // ── Always-visible widget ─────────────────────────────────────────────────
    private FlatButtonWidget saveCloseButton;

    // ── Section-label Y positions (set in init, used in render) ───────────────
    private int sectionCropY, actionSeparatorY;
    private int sectionPestsY, sectionMiscY, miscSeparatorY;
    private int sectionLaneSwapY, sectionRewarpDelayY, sectionMousematDelayY;
    private int sectionVisitorsY;

    public FarmingConfigScreen(Screen parent, FarmingConfig config, MacroManager macroManager) {
        super(Text.translatable("gui.just-farming.title"));
        this.parent         = parent;
        this.config         = config;
        this.macroManager   = macroManager;
        this.visitorManager = com.justfarming.JustFarming.getVisitorManager();
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
        cropSelectButton.setTooltip(Tooltip.of(Text.literal("Choose the crop type to farm")));
        y += bh + pad;

        cropSettingsButton = new FlatButtonWidget(widgetX, y, bw, bh,
                        getCropSettingsText(),
                        btn -> {
                            applyConfig();
                            if (this.client != null)
                                this.client.setScreen(new CropSettingsScreen(this, config));
                        });
        this.addDrawableChild(cropSettingsButton);
        cropSettingsButton.setTooltip(Tooltip.of(Text.literal("Customize camera angle and movement keys for this crop")));
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
        setRewarpButton.setTooltip(Tooltip.of(Text.literal("Save your current position as the rewarp waypoint.\nThe macro sends /warp garden when it reaches this point.")));
        y += bh + pad;

        toggleMacroButton = new FlatButtonWidget(widgetX, y, bw, bh,
                        getMacroToggleText(),
                        btn -> {
                            applyConfig();
                            // If the visitor routine is active and farming is not running,
                            // stop the visitor instead of starting the farming macro.
                            if (visitorManager != null && visitorManager.isActive() && !macroManager.isRunning()) {
                                visitorManager.stop();
                            } else {
                                macroManager.toggle();
                            }
                            btn.setMessage(getMacroToggleText());
                        });
        this.addDrawableChild(toggleMacroButton);
        toggleMacroButton.setTooltip(Tooltip.of(Text.literal("Start or stop the farming macro")));

        // ── Tab 1 – Pests ─────────────────────────────────────────────────────
        y = contentTop;
        sectionPestsY = y;
        y += sLH;

        pestHighlightButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.just-farming.pest_highlight_label"),
                        config.pestHighlightEnabled);
        this.addDrawableChild(pestHighlightButton);
        pestHighlightButton.setTooltip(Tooltip.of(Text.literal("Outline garden plots that contain pests")));
        y += bh + pad;

        pestLabelsButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.just-farming.pest_labels_label"),
                        config.pestLabelsEnabled);
        this.addDrawableChild(pestLabelsButton);
        pestLabelsButton.setTooltip(Tooltip.of(Text.literal("Show plot name")));
        y += bh + pad;

        titleScaleSlider = new TitleScaleSlider(widgetX, y, bw, bh, config.pestTitleScale);
        this.addDrawableChild(titleScaleSlider);
        titleScaleSlider.setTooltip(Tooltip.of(Text.literal("Adjust the size of the floating plot labels")));
        y += bh + pad;

        pestEspButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.just-farming.pest_esp_label"),
                        config.pestEspEnabled);
        this.addDrawableChild(pestEspButton);
        pestEspButton.setTooltip(Tooltip.of(Text.literal("Show wireframe boxes around pest mobs through walls")));
        y += bh + pad;

        pestTracerButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.just-farming.pest_tracer_label"),
                        config.pestTracerEnabled);
        this.addDrawableChild(pestTracerButton);
        pestTracerButton.setTooltip(Tooltip.of(Text.literal("Draw lines from your camera to each pest mob")));

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
        freelookButton.setTooltip(Tooltip.of(Text.literal("Enable third-person camera behind the player.\nScroll wheel adjusts zoom distance.")));
        y += bh + pad + gap;

        miscSeparatorY = y;
        y += Math.max(2, Math.round(4 * scale));

        unlockedMouseButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.just-farming.unlocked_mouse_label"),
                        config.unlockedMouseEnabled);
        this.addDrawableChild(unlockedMouseButton);
        unlockedMouseButton.setTooltip(Tooltip.of(Text.literal("Release the cursor while the macro runs so you can interact with other windows")));
        y += bh + pad;

        gardenOnlyButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.just-farming.garden_only_label"),
                        config.gardenOnlyEnabled);
        this.addDrawableChild(gardenOnlyButton);
        gardenOnlyButton.setTooltip(Tooltip.of(Text.literal("When enabled, only allow the macro to run in the Hypixel Skyblock Garden.\nAuto-stops the macro if you leave the Garden.")));
        y += bh + pad;

        squeakyMousematButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.just-farming.squeaky_mousemat_label"),
                        config.squeakyMousematEnabled);
        this.addDrawableChild(squeakyMousematButton);
        squeakyMousematButton.setTooltip(Tooltip.of(Text.literal("Activate Squeaky Mousemat at startup to set camera angle")));

        // ── Tab 3 – Delays ────────────────────────────────────────────────────
        y = contentTop;
        sectionLaneSwapY = y;
        y += sLH;
        laneSwapDelaySlider = new LaneSwapDelaySlider(widgetX, y, bw, bh, config.laneSwapDelayMin);
        this.addDrawableChild(laneSwapDelaySlider);
        laneSwapDelaySlider.setTooltip(Tooltip.of(Text.literal("Minimum wait before flipping direction at end of row (ms)")));
        y += bh + pad;

        laneSwapRandomSlider = new LaneSwapRandomSlider(widgetX, y, bw, bh, config.laneSwapDelayRandom);
        this.addDrawableChild(laneSwapRandomSlider);
        laneSwapRandomSlider.setTooltip(Tooltip.of(Text.literal("Extra random delay added on top of the lane swap delay (ms)")));
        y += bh + pad + gap;

        sectionRewarpDelayY = y;
        y += sLH;
        rewarpDelaySlider = new RewarpDelaySlider(widgetX, y, bw, bh, config.rewarpDelayMin);
        this.addDrawableChild(rewarpDelaySlider);
        rewarpDelaySlider.setTooltip(Tooltip.of(Text.literal("Minimum wait before sending /warp garden (ms)")));
        y += bh + pad;

        rewarpRandomSlider = new RewarpRandomSlider(widgetX, y, bw, bh, config.rewarpDelayRandom);
        this.addDrawableChild(rewarpRandomSlider);
        rewarpRandomSlider.setTooltip(Tooltip.of(Text.literal("Extra random delay added on top of the rewarp delay (ms)")));
        y += bh + pad + gap;

        sectionMousematDelayY = y;
        y += sLH;
        mousematSwapToSlider = new MousematSwapToSlider(widgetX, y, bw, bh, config.mousematSwapToDelay);
        this.addDrawableChild(mousematSwapToSlider);
        mousematSwapToSlider.setTooltip(Tooltip.of(Text.literal("Wait before switching to Squeaky Mousemat.\nOnly applies when not already holding it. (ms)")));
        y += bh + pad;

        mousematPreDelaySlider = new MousematPreDelaySlider(widgetX, y, bw, bh, config.mousematPreDelay);
        this.addDrawableChild(mousematPreDelaySlider);
        mousematPreDelaySlider.setTooltip(Tooltip.of(Text.literal("Wait after switching to Squeaky Mousemat\nbefore activating its ability. (ms)")));
        y += bh + pad;

        mousematPostDelaySlider = new MousematPostDelaySlider(widgetX, y, bw, bh, config.mousematPostDelay);
        this.addDrawableChild(mousematPostDelaySlider);
        mousematPostDelaySlider.setTooltip(Tooltip.of(Text.literal("Wait after using the Squeaky Mousemat ability\nbefore swapping back to the farming tool. (ms)")));
        y += bh + pad;

        mousematResumeDelaySlider = new MousematResumeDelaySlider(widgetX, y, bw, bh, config.mousematResumeDelay);
        this.addDrawableChild(mousematResumeDelaySlider);
        mousematResumeDelaySlider.setTooltip(Tooltip.of(Text.literal("Wait after swapping back to the farming tool\nbefore resuming farming. (ms)")));

        // ── Tab 4 – Visitors ──────────────────────────────────────────────────
        y = contentTop;
        sectionVisitorsY = y;
        y += sLH;

        visitorsEnabledButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.just-farming.visitors_enabled_label"),
                        config.visitorsEnabled);
        this.addDrawableChild(visitorsEnabledButton);
        visitorsEnabledButton.setTooltip(Tooltip.of(Text.literal(
                "When enabled, the macro teleports to the barn at the rewarp point,\n" +
                "interacts with each garden visitor, and returns to farming.")));
        y += bh + pad;

        visitorsBuyFromBazaarButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.just-farming.visitors_buy_bazaar_label"),
                        config.visitorsBuyFromBazaar);
        this.addDrawableChild(visitorsBuyFromBazaarButton);
        visitorsBuyFromBazaarButton.setTooltip(Tooltip.of(Text.literal(
                "Automatically run /bazaar <item> and buy required items\nbefore accepting each visitor's offer.")));
        y += bh + pad;

        visitorsBlacklistButton = new FlatButtonWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.just-farming.visitors_blacklist_label"),
                        btn -> {
                            applyConfig();
                            if (this.client != null)
                                this.client.setScreen(new VisitorBlacklistScreen(this, config));
                        });
        this.addDrawableChild(visitorsBlacklistButton);
        visitorsBlacklistButton.setTooltip(Tooltip.of(Text.literal(
                "Choose which visitors to automatically skip, regardless of their required items.")));
        y += bh + pad + gap;

        visitorsInteractDelaySlider = new VisitorInteractDelaySlider(widgetX, y, bw, bh, config.visitorsInteractDelay);
        this.addDrawableChild(visitorsInteractDelaySlider);
        visitorsInteractDelaySlider.setTooltip(Tooltip.of(Text.literal("Delay between visitor NPC right-click attempts (ms)")));
        y += bh + pad;

        visitorsTeleportDelaySlider = new VisitorTeleportDelaySlider(widgetX, y, bw, bh, config.visitorsTeleportDelay);
        this.addDrawableChild(visitorsTeleportDelaySlider);
        visitorsTeleportDelaySlider.setTooltip(Tooltip.of(Text.literal("How long to wait after /tptoplot barn\nbefore scanning for visitor NPCs. (ms)")));
        y += bh + pad;

        bazaarSearchDelaySlider = new BazaarSearchDelaySlider(widgetX, y, bw, bh, config.bazaarSearchDelay);
        this.addDrawableChild(bazaarSearchDelaySlider);
        bazaarSearchDelaySlider.setTooltip(Tooltip.of(Text.literal("How long to wait after /bazaar <item>\nbefore interacting with the search results. (ms)")));
        y += bh + pad;

        bazaarClickDelaySlider = new BazaarClickDelaySlider(widgetX, y, bw, bh, config.bazaarClickDelay);
        this.addDrawableChild(bazaarClickDelaySlider);
        bazaarClickDelaySlider.setTooltip(Tooltip.of(Text.literal("Delay between consecutive click actions inside\nthe Bazaar GUI (item click, Buy Instantly click). (ms)")));

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

        boolean t1 = activeTab == 1;
        pestHighlightButton.visible = t1;
        pestLabelsButton.visible    = t1;
        titleScaleSlider.visible    = t1;
        pestEspButton.visible       = t1;
        pestTracerButton.visible    = t1;

        boolean t2 = activeTab == 2;
        freelookButton.visible        = t2;
        unlockedMouseButton.visible   = t2;
        gardenOnlyButton.visible      = t2;
        squeakyMousematButton.visible = t2;

        boolean t3 = activeTab == 3;
        laneSwapDelaySlider.visible       = t3;
        laneSwapRandomSlider.visible      = t3;
        rewarpDelaySlider.visible         = t3;
        rewarpRandomSlider.visible        = t3;
        mousematSwapToSlider.visible      = t3;
        mousematPreDelaySlider.visible    = t3;
        mousematPostDelaySlider.visible   = t3;
        mousematResumeDelaySlider.visible = t3;

        boolean t4 = activeTab == 4;
        visitorsEnabledButton.visible         = t4;
        visitorsBuyFromBazaarButton.visible   = t4;
        visitorsBlacklistButton.visible       = t4;
        visitorsInteractDelaySlider.visible   = t4;
        visitorsTeleportDelaySlider.visible   = t4;
        bazaarSearchDelaySlider.visible       = t4;
        bazaarClickDelaySlider.visible        = t4;
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
            context.fill(contentX + 16, miscSeparatorY,
                    winR - 16, miscSeparatorY + 1, COL_SEP);
        } else if (activeTab == 3) {
            drawSectionLabel(context, "Lane Swap", sectionLaneSwapY);
            drawSectionLabel(context, "Rewarp",    sectionRewarpDelayY);
            drawSectionLabel(context, "Mousemat",  sectionMousematDelayY);
        } else if (activeTab == 4) {
            drawSectionLabel(context, "Visitor's macro", sectionVisitorsY);
            // Show current visitor routine status below the sliders when active
            if (visitorManager != null && visitorManager.isActive()) {
                String stateText = "State: " + visitorManager.getState().name();
                int visitorStatusX = contentX + Math.round(12 * scale);
                // Position below the two toggle buttons + gap + two sliders
                int visitorStatusY = visitorsTeleportDelaySlider.getY() + bh + pad * 2;
                context.drawTextWithShadow(this.textRenderer,
                        net.minecraft.text.Text.literal(stateText).withColor(COL_TEXT_MUTED),
                        visitorStatusX, visitorStatusY, COL_TEXT_MUTED);
            }
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
        config.mousematSwapToDelay  = mousematSwapToSlider.getDelayValue();
        config.mousematPreDelay     = mousematPreDelaySlider.getDelayValue();
        config.mousematPostDelay    = mousematPostDelaySlider.getDelayValue();
        config.mousematResumeDelay  = mousematResumeDelaySlider.getDelayValue();
        config.pestHighlightEnabled = pestHighlightButton.getValue();
        config.pestLabelsEnabled    = pestLabelsButton.getValue();
        config.pestTitleScale       = titleScaleSlider.getTitleScaleValue();
        config.pestEspEnabled       = pestEspButton.getValue();
        config.pestTracerEnabled    = pestTracerButton.getValue();
        config.unlockedMouseEnabled = unlockedMouseButton.getValue();
        config.gardenOnlyEnabled    = gardenOnlyButton.getValue();
        config.squeakyMousematEnabled = squeakyMousematButton.getValue();
        config.visitorsEnabled          = visitorsEnabledButton.getValue();
        config.visitorsBuyFromBazaar    = visitorsBuyFromBazaarButton.getValue();
        config.visitorsInteractDelay    = visitorsInteractDelaySlider.getDelayValue();
        config.visitorsTeleportDelay    = visitorsTeleportDelaySlider.getDelayValue();
        config.bazaarSearchDelay        = bazaarSearchDelaySlider.getDelayValue();
        config.bazaarClickDelay         = bazaarClickDelaySlider.getDelayValue();
        macroManager.setConfig(config);
        if (visitorManager != null) visitorManager.setConfig(config);
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
        if (visitorManager != null && visitorManager.isActive() && !macroManager.isRunning()) {
            return Text.translatable("gui.just-farming.stop_visitor");
        }
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

    /**
     * Abstract base for integer-stepped sliders (1 unit per arrow-key press;
     * mouse drag snaps to nearest integer).
     *
     * <p>GLFW_KEY_LEFT = 263, GLFW_KEY_RIGHT = 262.
     */
    private abstract static class IntStepSlider extends SliderWidget {

        private static final int GLFW_KEY_LEFT  = 263;
        private static final int GLFW_KEY_RIGHT = 262;

        private final int min;
        private final int max;

        IntStepSlider(int x, int y, int width, int height, int min, int max, int initialValue) {
            super(x, y, width, height, Text.empty(), (double)(initialValue - min) / (max - min));
            this.min = min;
            this.max = max;
            updateMessage();
        }

        /** Returns the current value as a concrete integer in [min, max]. */
        int getIntValue() {
            return min + (int)Math.round(value * (max - min));
        }

        @Override
        protected void applyValue() {
            // Snap mouse-drag value to nearest integer step.
            int steps = max - min;
            this.value = Math.round(this.value * steps) / (double)steps;
        }

        @Override
        public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
            if (input.key() == GLFW_KEY_LEFT || input.key() == GLFW_KEY_RIGHT) {
                double step = 1.0 / (max - min);
                this.value = (input.key() == GLFW_KEY_LEFT)
                        ? Math.max(0.0, this.value - step)
                        : Math.min(1.0, this.value + step);
                updateMessage();
                return true;
            }
            return super.keyPressed(input);
        }

        @Override
        public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            renderFlatSlider(context, getX(), getY(), getWidth(), getHeight(), value, getMessage());
        }
    }

    /** Slider for the minimum lane-swap delay (0–2000 ms). */
    private static class LaneSwapDelaySlider extends IntStepSlider {

        LaneSwapDelaySlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, 0, 2000, initialValue);
        }

        int getDelayValue() { return getIntValue(); }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Lane Swap Delay: %d ms", getIntValue())));
        }
    }

    /** Slider for the random extra lane-swap delay (0–1000 ms). */
    private static class LaneSwapRandomSlider extends IntStepSlider {

        LaneSwapRandomSlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, 0, 1000, initialValue);
        }

        int getRandomValue() { return getIntValue(); }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Lane Swap Randomization: %d ms", getIntValue())));
        }
    }

    /** Slider for the minimum rewarp delay (0–2000 ms). */
    private static class RewarpDelaySlider extends IntStepSlider {

        RewarpDelaySlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, 0, 2000, initialValue);
        }

        int getDelayValue() { return getIntValue(); }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Rewarp Delay: %d ms", getIntValue())));
        }
    }

    /** Slider for the random extra rewarp delay (0–1000 ms). */
    private static class RewarpRandomSlider extends IntStepSlider {

        RewarpRandomSlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, 0, 1000, initialValue);
        }

        int getRandomValue() { return getIntValue(); }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Rewarp Randomization: %d ms", getIntValue())));
        }
    }

    /** Slider for the Squeaky Mousemat swap-to delay (0–2000 ms). */
    private static class MousematSwapToSlider extends IntStepSlider {

        MousematSwapToSlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, 0, 2000, initialValue);
        }

        int getDelayValue() { return getIntValue(); }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Swap to Mousemat Delay: %d ms", getIntValue())));
        }
    }

    /** Slider for the Squeaky Mousemat pre-click delay (0–2000 ms). */
    private static class MousematPreDelaySlider extends IntStepSlider {

        MousematPreDelaySlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, 0, 2000, initialValue);
        }

        int getDelayValue() { return getIntValue(); }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Mousemat Ability Delay: %d ms", getIntValue())));
        }
    }

    /** Slider for the Squeaky Mousemat post-click (swap-back) delay (0–2000 ms). */
    private static class MousematPostDelaySlider extends IntStepSlider {

        MousematPostDelaySlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, 0, 2000, initialValue);
        }

        int getDelayValue() { return getIntValue(); }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Swap Back Delay: %d ms", getIntValue())));
        }
    }

    /** Slider for the Squeaky Mousemat resume-farming delay (0–2000 ms). */
    private static class MousematResumeDelaySlider extends IntStepSlider {

        MousematResumeDelaySlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, 0, 2000, initialValue);
        }

        int getDelayValue() { return getIntValue(); }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Resume Farming Delay: %d ms", getIntValue())));
        }
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

    /** Slider for the visitor NPC interact delay (200–3000 ms). */
    private static class VisitorInteractDelaySlider extends IntStepSlider {

        VisitorInteractDelaySlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, 200, 3000, initialValue);
        }

        int getDelayValue() { return getIntValue(); }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Visitor Interact Delay: %d ms", getIntValue())));
        }
    }

    /** Slider for the visitor teleport-to-barn wait time (1000–8000 ms). */
    private static class VisitorTeleportDelaySlider extends IntStepSlider {

        VisitorTeleportDelaySlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, 1000, 8000, initialValue);
        }

        int getDelayValue() { return getIntValue(); }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Barn Teleport Wait: %d ms", getIntValue())));
        }
    }

    /** Slider for the bazaar search delay (500–5000 ms). */
    private static class BazaarSearchDelaySlider extends IntStepSlider {

        BazaarSearchDelaySlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, 500, 5000, initialValue);
        }

        int getDelayValue() { return getIntValue(); }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Bazaar Search Delay: %d ms", getIntValue())));
        }
    }

    /** Slider for the delay between consecutive bazaar click actions (200–3000 ms). */
    private static class BazaarClickDelaySlider extends IntStepSlider {

        BazaarClickDelaySlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, 200, 3000, initialValue);
        }

        int getDelayValue() { return getIntValue(); }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Bazaar Click Delay: %d ms", getIntValue())));
        }
    }
}
