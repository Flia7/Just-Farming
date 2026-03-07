package com.flia.gui;

import com.flia.MacroManager;
import com.flia.config.FarmingConfig;
import com.flia.pest.PestKillerManager;
import com.flia.visitor.VisitorManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

/**
 * Configuration GUI screen for FLIA.
 *
 * <p>Layout inspired by the sw mod: a left navigation sidebar with
 * category tabs and a right content panel. Five categories organise
 * the settings:
 * <ul>
 *   <li><b>Farming</b>  – crop selection, rewarp, start/stop macro, farming tool slot</li>
 *   <li><b>Pests</b>    – pest highlight, labels, ESP, tracer; auto pest killer settings</li>
 *   <li><b>Misc</b>     – freelook toggle, unlocked mouse</li>
 *   <li><b>Delays</b>   – all timing/delay sliders</li>
 *   <li><b>Visitors</b> – visitor macro settings and filters</li>
 * </ul>
 */
public class FarmingConfigScreen extends Screen {

    // ── Natural/maximum dimensions ────────────────────────────────────────────
    private static final int WINDOW_WIDTH   = 420;
    private static final int WINDOW_HEIGHT  = 490;
    private static final int NAV_WIDTH      = 110;
    private static final int BUTTON_WIDTH   = 220;
    private static final int BUTTON_HEIGHT  = 24;
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
    private static final int COL_SHADOW      = 0x60000000; // drop shadow

    // ── Tabs ──────────────────────────────────────────────────────────────────
    private static final String[] TAB_NAMES = { "Just Farming", "Pests", "Misc", "Delays", "Visitors" };
    private int activeTab = 0;

    // ── Preset mode ────────────────────────────────────────────────────────────
    private static final int PRESET_BLATANT = 0;
    private static final int PRESET_SMART   = 1;
    private static final int PRESET_CUSTOM  = 2;
    /** Currently active preset (0=Blatant, 1=Smart, 2=Custom). Not persisted. */
    private int presetMode = PRESET_CUSTOM;

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
    private final PestKillerManager pestKillerManager;

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
    private FlatBoolToggleWidget  macroEnabledInGuiButton;

    // ── Tab 3 – Delays widgets ────────────────────────────────────────────────
    private GlobalRandomSlider            globalRandomSlider;
    private LaneSwapDelaySlider           laneSwapDelaySlider;
    private LaneSwapRandomSlider          laneSwapRandomSlider;
    private RewarpDelaySlider             rewarpDelaySlider;
    private RewarpRandomSlider            rewarpRandomSlider;
    private MousematSwapToSlider          mousematSwapToSlider;
    private MousematPreDelaySlider        mousematPreDelaySlider;
    private MousematPostDelaySlider       mousematPostDelaySlider;
    private MousematResumeDelaySlider     mousematResumeDelaySlider;
    private PestKillerTeleportDelaySlider pestKillerTeleportDelaySliderInDelays;
    private PestKillerAfterTeleportSlider pestKillerAfterTeleportSlider;
    private PestKillerGoToNextPestSlider  pestKillerGoToNextPestSlider;

    // ── Tab 4 – Visitors widgets ──────────────────────────────────────────────
    private FlatBoolToggleWidget  visitorsEnabledButton;
    private FlatBoolToggleWidget  visitorsBuyFromBazaarButton;
    private FlatButtonWidget              visitorsBlacklistButton;
    private VisitorsDelaySlider           visitorsDelaySlider;
    private VisitorsRandomSlider          visitorsRandomSlider;
    private VisitorTeleportDelaySlider    visitorsTeleportDelaySlider;
    private BazaarSearchDelaySlider       bazaarSearchDelaySlider;
    private VisitorMinCountSlider         visitorsMinCountSlider;
    private VisitorMaxPriceSlider         visitorsMaxPriceSlider;

    // ── Tab 1 – Pests + Auto Pest Killer widgets (merged) ────────────────────
    private FlatBoolToggleWidget          pestKillerEnabledButton;
    private FlatBoolToggleWidget          pestKillerWarpToPlotButton;
    private PestKillerVacuumRangeSlider   pestKillerVacuumRangeSlider;
    private FarmingToolSlotSlider         farmingToolSlotSlider;


    // ── Always-visible widget ─────────────────────────────────────────────────
    private FlatButtonWidget saveCloseButton;
    private FlatButtonWidget presetButton;

    // ── Section-label Y positions (set in init, used in render) ───────────────
    private int sectionCropY, actionSeparatorY;
    private int sectionPestsY, sectionMiscY, miscSeparatorY;
    private int sectionGlobalRandomY, sectionLaneSwapY, sectionRewarpDelayY, sectionMousematDelayY, sectionVisitorDelaysY, sectionPestKillerDelaysY;
    private int sectionVisitorsY;
    private int sectionVisitorFiltersY;
    private int visitorStatusY;
    private int sectionPestKillerY;
    private int pestKillerStatusY;

    // ── Scroll state (persists across clearAndInit) ───────────────────────────
    private final int[] tabScrollOffsets  = new int[TAB_NAMES.length];
    private final int[] tabContentHeights = new int[TAB_NAMES.length];
    private int contentAreaTopY;
    private int contentAreaBotY;

    public FarmingConfigScreen(Screen parent, FarmingConfig config, MacroManager macroManager) {
        super(Text.translatable("gui.flia.title"));
        this.parent         = parent;
        this.config         = config;
        this.macroManager   = macroManager;
        this.visitorManager = com.flia.Flia.getVisitorManager();
        this.pestKillerManager = com.flia.Flia.getPestKillerManager();
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
        int col1X         = contentX + pad;
        int halfBW        = (contentW - 3 * pad) / 2;
        int col2X         = col1X + halfBW + pad;

        // ── Tab buttons in the left nav panel ─────────────────────────────────
        int firstTabY = winY + Math.round(30 * scale);
        tabButtons = new TabButton[TAB_NAMES.length];
        for (int i = 0; i < TAB_NAMES.length; i++) {
            tabButtons[i] = new TabButton(
                    winX, firstTabY + i * (tabH + 2),
                    navW, tabH, i);
            this.addDrawableChild(tabButtons[i]);
        }

        // Content area starts below a small title/header area (no search bar)
        int contentTop = winY + Math.round(24 * scale);
        contentAreaTopY = contentTop + pad;
        contentAreaBotY = winY + winH - bh - pad;

        int y;

        // ── Tab 0 – Farming ───────────────────────────────────────────────────
        y = contentAreaTopY - tabScrollOffsets[0];
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
        cropSettingsButton.setTooltip(Tooltip.of(Text.literal("Customize camera angle for this crop")));
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
                            macroManager.toggle();
                            btn.setMessage(getMacroToggleText());
                        });
        this.addDrawableChild(toggleMacroButton);
        toggleMacroButton.setTooltip(Tooltip.of(Text.literal("Start or stop the farming macro")));
        y += bh + pad;

        farmingToolSlotSlider = new FarmingToolSlotSlider(widgetX, y, bw, bh,
                        config.farmingToolHotbarSlot);
        this.addDrawableChild(farmingToolSlotSlider);
        farmingToolSlotSlider.setTooltip(Tooltip.of(Text.literal(
                "Hotbar slot for your farming tool.\n" +
                "Auto: detect automatically. Slot 1–9: pin a specific slot.")));
        tabContentHeights[0] = y + bh - contentAreaTopY + tabScrollOffsets[0];

        // ── Tab 1 – Pests ─────────────────────────────────────────────────────
        y = contentAreaTopY - tabScrollOffsets[1];
        sectionPestsY = y;
        y += sLH;

        pestHighlightButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.flia.pest_highlight_label"),
                        config.pestHighlightEnabled);
        this.addDrawableChild(pestHighlightButton);
        pestHighlightButton.setTooltip(Tooltip.of(Text.literal("Outline garden plots that contain pests")));
        y += bh + pad;

        pestLabelsButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.flia.pest_labels_label"),
                        config.pestLabelsEnabled);
        this.addDrawableChild(pestLabelsButton);
        pestLabelsButton.setTooltip(Tooltip.of(Text.literal("Show plot name")));
        y += bh + pad;

        titleScaleSlider = new TitleScaleSlider(widgetX, y, bw, bh, config.pestTitleScale);
        this.addDrawableChild(titleScaleSlider);
        titleScaleSlider.setTooltip(Tooltip.of(Text.literal("Adjust the size of the floating plot labels")));
        y += bh + pad;

        pestEspButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.flia.pest_esp_label"),
                        config.pestEspEnabled);
        this.addDrawableChild(pestEspButton);
        pestEspButton.setTooltip(Tooltip.of(Text.literal("Show wireframe boxes around pest mobs through walls")));
        y += bh + pad;

        pestTracerButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.flia.pest_tracer_label"),
                        config.pestTracerEnabled);
        this.addDrawableChild(pestTracerButton);
        pestTracerButton.setTooltip(Tooltip.of(Text.literal("Draw lines from your camera to each pest mob")));
        y += bh + pad + gap;

        sectionPestKillerY = y;
        y += sLH;

        pestKillerEnabledButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.flia.pest_killer_enabled_label"),
                        config.autoPestKillerEnabled);
        this.addDrawableChild(pestKillerEnabledButton);
        pestKillerEnabledButton.setTooltip(Tooltip.of(Text.literal(
                "Flies toward pests and kills them with a vacuum when detected.")));
        y += bh + pad;

        pestKillerWarpToPlotButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.flia.pest_killer_warp_to_plot_label"),
                        config.pestKillerWarpToPlot);
        this.addDrawableChild(pestKillerWarpToPlotButton);
        pestKillerWarpToPlotButton.setTooltip(Tooltip.of(Text.literal(
                "Warp directly to the infested plot via /tptoplot <plot>.")));
        y += bh + pad;

        pestKillerVacuumRangeSlider = new PestKillerVacuumRangeSlider(widgetX, y, bw, bh,
                        config.pestKillerVacuumRange);
        this.addDrawableChild(pestKillerVacuumRangeSlider);
        pestKillerVacuumRangeSlider.setTooltip(Tooltip.of(Text.literal(
                "Max distance (blocks) at which the vacuum activates.")));
        y += bh + pad + gap;
        pestKillerStatusY = y;
        tabContentHeights[1] = y - contentAreaTopY + tabScrollOffsets[1];

        // ── Tab 2 – Misc ──────────────────────────────────────────────────────
        y = contentAreaTopY - tabScrollOffsets[2];
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
                        Text.translatable("gui.flia.unlocked_mouse_label"),
                        config.unlockedMouseEnabled);
        this.addDrawableChild(unlockedMouseButton);
        unlockedMouseButton.setTooltip(Tooltip.of(Text.literal("Release the cursor while the macro runs")));
        y += bh + pad;

        gardenOnlyButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.flia.garden_only_label"),
                        config.gardenOnlyEnabled);
        this.addDrawableChild(gardenOnlyButton);
        gardenOnlyButton.setTooltip(Tooltip.of(Text.literal("When enabled, only allow the macro to run in the Hypixel Skyblock Garden.\nAuto-stops the macro if you leave the Garden.")));
        y += bh + pad;

        squeakyMousematButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.flia.squeaky_mousemat_label"),
                        config.squeakyMousematEnabled);
        this.addDrawableChild(squeakyMousematButton);
        squeakyMousematButton.setTooltip(Tooltip.of(Text.literal("Activate Squeaky Mousemat at startup to set camera angle")));
        y += bh + pad;

        macroEnabledInGuiButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.flia.macro_enabled_in_gui_label"),
                        config.macroEnabledInGui);
        this.addDrawableChild(macroEnabledInGuiButton);
        macroEnabledInGuiButton.setTooltip(Tooltip.of(Text.literal(
                "When enabled, the macro continues moving and breaking blocks\n" +
                "even while any GUI (including this screen) is open.\n" +
                "Removes the brief pause when opening or closing a screen.")));
        tabContentHeights[2] = y + bh - contentAreaTopY + tabScrollOffsets[2];

        // ── Tab 3 – Delays ────────────────────────────────────────────────────
        y = contentAreaTopY - tabScrollOffsets[3];
        sectionGlobalRandomY = y;
        y += sLH;
        globalRandomSlider = new GlobalRandomSlider(widgetX, y, bw, bh, config.globalRandomizationMs);
        this.addDrawableChild(globalRandomSlider);
        globalRandomSlider.setTooltip(Tooltip.of(Text.literal("Global extra random jitter added on top of every delay throughout the macro (ms)")));
        y += bh + pad + gap;

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
        y += bh + pad + gap;

        sectionVisitorDelaysY = y;
        y += sLH;
        visitorsDelaySlider = new VisitorsDelaySlider(widgetX, y, bw, bh, config.visitorsActionDelay);
        this.addDrawableChild(visitorsDelaySlider);
        visitorsDelaySlider.setTooltip(Tooltip.of(Text.literal(
                "Delay before each bazaar click and NPC interaction. (ms)")));
        y += bh + pad;

        visitorsRandomSlider = new VisitorsRandomSlider(widgetX, y, bw, bh, config.visitorsActionDelayRandom);
        this.addDrawableChild(visitorsRandomSlider);
        visitorsRandomSlider.setTooltip(Tooltip.of(Text.literal(
                "Extra random delay added on top of Bazaar Click Delay for each action. (ms)")));
        y += bh + pad;

        visitorsTeleportDelaySlider = new VisitorTeleportDelaySlider(widgetX, y, bw, bh, config.visitorsTeleportDelay);
        this.addDrawableChild(visitorsTeleportDelaySlider);
        visitorsTeleportDelaySlider.setTooltip(Tooltip.of(Text.literal(
                "How long to wait after /tptoplot barn\nbefore scanning for visitor NPCs. (ms)")));
        y += bh + pad;

        bazaarSearchDelaySlider = new BazaarSearchDelaySlider(widgetX, y, bw, bh, config.bazaarSearchDelay);
        this.addDrawableChild(bazaarSearchDelaySlider);
        bazaarSearchDelaySlider.setTooltip(Tooltip.of(Text.literal("How long to wait before opening the bazaar for each item. (ms)")));
        y += bh + pad + gap;

        sectionPestKillerDelaysY = y;
        y += sLH;

        pestKillerTeleportDelaySliderInDelays = new PestKillerTeleportDelaySlider(widgetX, y, bw, bh,
                config.pestKillerTeleportDelay);
        this.addDrawableChild(pestKillerTeleportDelaySliderInDelays);
        pestKillerTeleportDelaySliderInDelays.setTooltip(Tooltip.of(Text.literal(
                "Wait before sending the plot teleport command. (ms)")));
        y += bh + pad;

        pestKillerAfterTeleportSlider = new PestKillerAfterTeleportSlider(widgetX, y, bw, bh,
                config.pestKillerAfterTeleportDelay);
        this.addDrawableChild(pestKillerAfterTeleportSlider);
        pestKillerAfterTeleportSlider.setTooltip(Tooltip.of(Text.literal(
                "Wait after teleporting to a pest plot before scanning for pests. (ms)")));
        y += bh + pad;

        pestKillerGoToNextPestSlider = new PestKillerGoToNextPestSlider(widgetX, y, bw, bh,
                config.pestKillerGoToNextPestDelay);
        this.addDrawableChild(pestKillerGoToNextPestSlider);
        pestKillerGoToNextPestSlider.setTooltip(Tooltip.of(Text.literal(
                "Minimum delay after killing a pest before flying toward the next one. (ms)")));
        tabContentHeights[3] = y + bh - contentAreaTopY + tabScrollOffsets[3];

        // ── Tab 4 – Visitors ──────────────────────────────────────────────────
        y = contentAreaTopY - tabScrollOffsets[4];
        sectionVisitorsY = y;
        y += sLH;

        visitorsEnabledButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.flia.visitors_enabled_label"),
                        config.visitorsEnabled);
        this.addDrawableChild(visitorsEnabledButton);
        visitorsEnabledButton.setTooltip(Tooltip.of(Text.literal(
                "When enabled, the macro teleports to the barn at the rewarp point,\n" +
                "interacts with each garden visitor, and returns to farming.")));
        y += bh + pad;

        visitorsBuyFromBazaarButton = new FlatBoolToggleWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.flia.visitors_buy_bazaar_label"),
                        config.visitorsBuyFromBazaar);
        this.addDrawableChild(visitorsBuyFromBazaarButton);
        visitorsBuyFromBazaarButton.setTooltip(Tooltip.of(Text.literal(
                "Automatically run /bazaar <item> and buy required items\nbefore accepting each visitor's offer.")));
        y += bh + pad;

        visitorsBlacklistButton = new FlatButtonWidget(widgetX, y, bw, bh,
                        Text.translatable("gui.flia.visitors_blacklist_label"),
                        btn -> {
                            applyConfig();
                            if (this.client != null)
                                this.client.setScreen(new VisitorBlacklistScreen(this, config));
                        });
        this.addDrawableChild(visitorsBlacklistButton);
        visitorsBlacklistButton.setTooltip(Tooltip.of(Text.literal(
                "Choose which visitors to automatically skip, regardless of their required items.")));
        y += bh + pad + gap;

        sectionVisitorFiltersY = y;
        y += sLH;

        visitorsMinCountSlider = new VisitorMinCountSlider(widgetX, y, bw, bh, config.visitorsMinCount);
        this.addDrawableChild(visitorsMinCountSlider);
        visitorsMinCountSlider.setTooltip(Tooltip.of(Text.literal(
                "Minimum number of visitors that must be present at the barn for the visitor routine to run.\n" +
                "If fewer visitors are found the macro skips the barn and warps back to the Garden directly.")));
        y += bh + pad;

        visitorsMaxPriceSlider = new VisitorMaxPriceSlider(widgetX, y, bw, bh, config.visitorsMaxPrice);
        this.addDrawableChild(visitorsMaxPriceSlider);
        visitorsMaxPriceSlider.setTooltip(Tooltip.of(Text.literal(
                "Maximum total NPC sell value (coins) of a visitor's required items.\n" +
                "If the visitor's request exceeds this amount the offer is declined.\n" +
                "Set to 0 (Disabled) to accept all visitors regardless of cost.")));
        y += bh + pad + gap;
        visitorStatusY = y;
        tabContentHeights[4] = y - contentAreaTopY + tabScrollOffsets[4];

        // ── Always-visible: Close button anchored to the bottom ───────────────
        int closeBtnY = winY + winH - bh - pad;
        saveCloseButton = new FlatButtonWidget(widgetX, closeBtnY, bw, bh,
                        Text.translatable("gui.flia.close"),
                        btn -> close());
        this.addDrawableChild(saveCloseButton);

        // ── Always-visible: Preset cycling button in the bottom-left (nav panel) ─
        int presetBtnW = navW - pad * 2;
        presetButton = new FlatButtonWidget(winX + pad, closeBtnY, presetBtnW, bh,
                        getPresetButtonText(),
                        btn -> {
                            applyConfig();
                            cyclePreset();
                        });
        this.addDrawableChild(presetButton);
        presetButton.setTooltip(Tooltip.of(Text.literal(
                "Blatant: all features on, 0 delays.\n" +
                "Smart: human-like delays and default settings.\n" +
                "Custom: your current settings (shown when you change anything).")));

        // ── Register onChange callbacks so preset switches to "Custom" on user edits ─
        Runnable markCustom = this::markPresetCustom;
        // Tab 0
        farmingToolSlotSlider.setOnChange(markCustom);
        // Tab 1
        pestHighlightButton.setOnChange(markCustom);
        pestLabelsButton.setOnChange(markCustom);
        titleScaleSlider.setOnChange(markCustom);
        pestEspButton.setOnChange(markCustom);
        pestTracerButton.setOnChange(markCustom);
        pestKillerEnabledButton.setOnChange(markCustom);
        pestKillerWarpToPlotButton.setOnChange(markCustom);
        pestKillerVacuumRangeSlider.setOnChange(markCustom);
        // Tab 2
        unlockedMouseButton.setOnChange(markCustom);
        gardenOnlyButton.setOnChange(markCustom);
        squeakyMousematButton.setOnChange(markCustom);
        macroEnabledInGuiButton.setOnChange(markCustom);
        // Tab 3
        globalRandomSlider.setOnChange(markCustom);
        laneSwapDelaySlider.setOnChange(markCustom);
        laneSwapRandomSlider.setOnChange(markCustom);
        rewarpDelaySlider.setOnChange(markCustom);
        rewarpRandomSlider.setOnChange(markCustom);
        mousematSwapToSlider.setOnChange(markCustom);
        mousematPreDelaySlider.setOnChange(markCustom);
        mousematPostDelaySlider.setOnChange(markCustom);
        mousematResumeDelaySlider.setOnChange(markCustom);
        visitorsDelaySlider.setOnChange(markCustom);
        visitorsRandomSlider.setOnChange(markCustom);
        visitorsTeleportDelaySlider.setOnChange(markCustom);
        bazaarSearchDelaySlider.setOnChange(markCustom);
        pestKillerTeleportDelaySliderInDelays.setOnChange(markCustom);
        pestKillerAfterTeleportSlider.setOnChange(markCustom);
        pestKillerGoToNextPestSlider.setOnChange(markCustom);
        // Tab 4
        visitorsEnabledButton.setOnChange(markCustom);
        visitorsBuyFromBazaarButton.setOnChange(markCustom);
        visitorsMinCountSlider.setOnChange(markCustom);
        visitorsMaxPriceSlider.setOnChange(markCustom);

        refreshWidgetVisibility();
    }

    /** Returns {@code true} if the widget falls within the scrollable content area. */
    private boolean inContentBounds(net.minecraft.client.gui.widget.ClickableWidget w) {
        return w.getY() + w.getHeight() > contentAreaTopY && w.getY() < contentAreaBotY;
    }

    /** Returns {@code true} if the given Y coordinate is within the scrollable content area. */
    private boolean yInContentBounds(int y) {
        return y >= contentAreaTopY && y < contentAreaBotY;
    }

    /** Shows/hides content widgets according to activeTab and scroll position. */
    private void refreshWidgetVisibility() {

        boolean t0 = activeTab == 0;
        cropSelectButton.visible   = t0 && inContentBounds(cropSelectButton);
        cropSettingsButton.visible = t0 && inContentBounds(cropSettingsButton);
        setRewarpButton.visible    = t0 && inContentBounds(setRewarpButton);
        toggleMacroButton.visible  = t0 && inContentBounds(toggleMacroButton);
        farmingToolSlotSlider.visible = t0 && inContentBounds(farmingToolSlotSlider);

        boolean t1 = activeTab == 1;
        pestHighlightButton.visible = t1 && inContentBounds(pestHighlightButton);
        pestLabelsButton.visible    = t1 && inContentBounds(pestLabelsButton);
        titleScaleSlider.visible    = t1 && inContentBounds(titleScaleSlider);
        pestEspButton.visible       = t1 && inContentBounds(pestEspButton);
        pestTracerButton.visible    = t1 && inContentBounds(pestTracerButton);
        pestKillerEnabledButton.visible         = t1 && inContentBounds(pestKillerEnabledButton);
        pestKillerWarpToPlotButton.visible      = t1 && inContentBounds(pestKillerWarpToPlotButton);
        pestKillerVacuumRangeSlider.visible     = t1 && inContentBounds(pestKillerVacuumRangeSlider);

        boolean t2 = activeTab == 2;
        freelookButton.visible        = t2 && inContentBounds(freelookButton);
        unlockedMouseButton.visible   = t2 && inContentBounds(unlockedMouseButton);
        gardenOnlyButton.visible      = t2 && inContentBounds(gardenOnlyButton);
        squeakyMousematButton.visible = t2 && inContentBounds(squeakyMousematButton);
        macroEnabledInGuiButton.visible = t2 && inContentBounds(macroEnabledInGuiButton);

        boolean t3 = activeTab == 3;
        globalRandomSlider.visible            = t3 && inContentBounds(globalRandomSlider);
        laneSwapDelaySlider.visible       = t3 && inContentBounds(laneSwapDelaySlider);
        laneSwapRandomSlider.visible      = t3 && inContentBounds(laneSwapRandomSlider);
        rewarpDelaySlider.visible         = t3 && inContentBounds(rewarpDelaySlider);
        rewarpRandomSlider.visible        = t3 && inContentBounds(rewarpRandomSlider);
        mousematSwapToSlider.visible      = t3 && inContentBounds(mousematSwapToSlider);
        mousematPreDelaySlider.visible    = t3 && inContentBounds(mousematPreDelaySlider);
        mousematPostDelaySlider.visible   = t3 && inContentBounds(mousematPostDelaySlider);
        mousematResumeDelaySlider.visible = t3 && inContentBounds(mousematResumeDelaySlider);
        visitorsDelaySlider.visible           = t3 && inContentBounds(visitorsDelaySlider);
        visitorsRandomSlider.visible          = t3 && inContentBounds(visitorsRandomSlider);
        visitorsTeleportDelaySlider.visible   = t3 && inContentBounds(visitorsTeleportDelaySlider);
        bazaarSearchDelaySlider.visible       = t3 && inContentBounds(bazaarSearchDelaySlider);
        pestKillerTeleportDelaySliderInDelays.visible = t3 && inContentBounds(pestKillerTeleportDelaySliderInDelays);
        pestKillerAfterTeleportSlider.visible = t3 && inContentBounds(pestKillerAfterTeleportSlider);
        pestKillerGoToNextPestSlider.visible  = t3 && inContentBounds(pestKillerGoToNextPestSlider);

        boolean t4 = activeTab == 4;
        visitorsEnabledButton.visible         = t4 && inContentBounds(visitorsEnabledButton);
        visitorsBuyFromBazaarButton.visible   = t4 && inContentBounds(visitorsBuyFromBazaarButton);
        visitorsBlacklistButton.visible       = t4 && inContentBounds(visitorsBlacklistButton);
        visitorsMinCountSlider.visible        = t4 && inContentBounds(visitorsMinCountSlider);
        visitorsMaxPriceSlider.visible        = t4 && inContentBounds(visitorsMaxPriceSlider);
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

        // ── Nav: mod title (two-tone: "Just " dim, "Farming" accented) ──────────
        int navCenterX = winX + navW / 2;
        int navTitleY  = winY + Math.max(4, Math.round(8 * scale));
        context.drawCenteredTextWithShadow(this.textRenderer,
                net.minecraft.text.Text.empty()
                        .append(net.minecraft.text.Text.literal("Just ").withColor(COL_TEXT_MUTED))
                        .append(net.minecraft.text.Text.literal("Farming").withColor(COL_ACCENT)),
                navCenterX, navTitleY, COL_TEXT);

        // ── Nav: separator line below title ─────────────────────────────────
        int navSepY = navTitleY + 14;
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
            if (yInContentBounds(sectionCropY))
                drawSectionLabel(context, "Crop", sectionCropY);
            if (yInContentBounds(actionSeparatorY))
                context.fill(contentX + 16, actionSeparatorY, winR - 16, actionSeparatorY + 1, COL_SEP);
        } else if (activeTab == 1) {
            if (yInContentBounds(sectionPestsY))
                drawSectionLabel(context, "Pests", sectionPestsY);
            if (yInContentBounds(sectionPestKillerY))
                drawSectionLabel(context, "Pest Killer", sectionPestKillerY);
            // Show current pest killer state below the buttons when active
            if (pestKillerManager != null && pestKillerManager.isActive() && yInContentBounds(pestKillerStatusY)) {
                String stateText = "State: " + pestKillerManager.getState().name();
                int statusX = contentX + Math.round(12 * scale);
                context.drawTextWithShadow(this.textRenderer,
                        net.minecraft.text.Text.literal(stateText).withColor(COL_TEXT_MUTED),
                        statusX, pestKillerStatusY, COL_TEXT_MUTED);
            }
        } else if (activeTab == 2) {
            if (yInContentBounds(sectionMiscY))
                drawSectionLabel(context, "Misc", sectionMiscY);
            if (yInContentBounds(miscSeparatorY))
                context.fill(contentX + 16, miscSeparatorY, winR - 16, miscSeparatorY + 1, COL_SEP);
        } else if (activeTab == 3) {
            if (yInContentBounds(sectionGlobalRandomY))
                drawSectionLabel(context, "Global Randomization", sectionGlobalRandomY);
            if (yInContentBounds(sectionLaneSwapY))
                drawSectionLabel(context, "Lane Swap", sectionLaneSwapY);
            if (yInContentBounds(sectionRewarpDelayY))
                drawSectionLabel(context, "Rewarp", sectionRewarpDelayY);
            if (yInContentBounds(sectionMousematDelayY))
                drawSectionLabel(context, "Mousemat", sectionMousematDelayY);
            if (yInContentBounds(sectionVisitorDelaysY))
                drawSectionLabel(context, "Visitor Delays", sectionVisitorDelaysY);
            if (yInContentBounds(sectionPestKillerDelaysY))
                drawSectionLabel(context, "Pest Killer Delays", sectionPestKillerDelaysY);
        } else if (activeTab == 4) {
            if (yInContentBounds(sectionVisitorsY))
                drawSectionLabel(context, "Visitors", sectionVisitorsY);
            if (yInContentBounds(sectionVisitorFiltersY))
                drawSectionLabel(context, "Visitor Filters", sectionVisitorFiltersY);
            // Show current visitor routine status below the buttons when active
            if (visitorManager != null && visitorManager.isActive() && yInContentBounds(visitorStatusY)) {
                String stateText = "State: " + visitorManager.getState().name();
                int visitorStatusX = contentX + Math.round(12 * scale);
                context.drawTextWithShadow(this.textRenderer,
                        net.minecraft.text.Text.literal(stateText).withColor(COL_TEXT_MUTED),
                        visitorStatusX, visitorStatusY, COL_TEXT_MUTED);
            }
        }

        // ── Scroll indicators ─────────────────────────────────────────────────
        int maxScroll = Math.max(0, tabContentHeights[activeTab] - (contentAreaBotY - contentAreaTopY));
        int indicatorX = winX + navW + contentW - Math.max(6, Math.round(10 * scale));
        if (tabScrollOffsets[activeTab] > 0) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("▲").withColor(COL_TEXT_MUTED),
                    indicatorX, contentAreaTopY, COL_TEXT_MUTED);
        }
        if (tabScrollOffsets[activeTab] < maxScroll) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("▼").withColor(COL_TEXT_MUTED),
                    indicatorX, contentAreaBotY - 8, COL_TEXT_MUTED);
        }

        // ── Widgets ───────────────────────────────────────────────────────────
        super.render(context, mouseX, mouseY, delta);
    }

    /** Draws a labelled section header spanning the content area. */
    private void drawSectionLabel(DrawContext context, String label, int y) {
        int winR = winX + winW;
        // Accent bar: starts at contentX+6, width 3 px
        int accentBarEnd = contentX + 9;
        context.fill(contentX + 4, y, winR - 4, y + sLH, COL_SECTION_BG);
        context.fill(contentX + 6, y + 1, accentBarEnd, y + sLH - 1, COL_ACCENT);
        context.drawTextWithShadow(this.textRenderer,
                Text.literal(label).withColor(COL_TEXT),
                accentBarEnd + 5, y + 1, COL_TEXT);
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
        // Only scroll when the cursor is over the content panel
        if (mouseX >= contentX) {
            int maxScroll = Math.max(0, tabContentHeights[activeTab] - (contentAreaBotY - contentAreaTopY));
            if (maxScroll > 0) {
                int delta = verticalAmount > 0 ? -12 : 12;
                int newOffset = Math.max(0, Math.min(maxScroll, tabScrollOffsets[activeTab] + delta));
                if (newOffset != tabScrollOffsets[activeTab]) {
                    tabScrollOffsets[activeTab] = newOffset;
                    applyConfig();
                    clearAndInit();
                }
            }
        }
        // Always consume so sliders/cycling buttons aren't accidentally changed.
        return true;
    }

    /** Read widget values back into the config object. */
    private void applyConfig() {
        config.globalRandomizationMs    = globalRandomSlider.getRandomValue();
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
        config.macroEnabledInGui    = macroEnabledInGuiButton.getValue();
        config.visitorsEnabled          = visitorsEnabledButton.getValue();
        config.visitorsBuyFromBazaar    = visitorsBuyFromBazaarButton.getValue();
        config.visitorsActionDelay      = visitorsDelaySlider.getDelayValue();
        config.visitorsActionDelayRandom = visitorsRandomSlider.getRandomValue();
        config.visitorsTeleportDelay    = visitorsTeleportDelaySlider.getDelayValue();
        config.bazaarSearchDelay        = bazaarSearchDelaySlider.getDelayValue();
        config.visitorsMinCount         = visitorsMinCountSlider.getCountValue();
        config.visitorsMaxPrice         = visitorsMaxPriceSlider.getPriceValue();
        config.autoPestKillerEnabled    = pestKillerEnabledButton.getValue();
        config.pestKillerWarpToPlot     = pestKillerWarpToPlotButton.getValue();
        config.pestKillerTeleportDelay  = pestKillerTeleportDelaySliderInDelays.getDelayValue();
        config.pestKillerAfterTeleportDelay = pestKillerAfterTeleportSlider.getDelayValue();
        config.pestKillerGoToNextPestDelay = pestKillerGoToNextPestSlider.getDelayValue();
        config.pestKillerVacuumRange    = pestKillerVacuumRangeSlider.getRangeValue();
        config.farmingToolHotbarSlot    = farmingToolSlotSlider.getSlotValue();
        macroManager.setConfig(config);
        if (visitorManager != null) visitorManager.setConfig(config);
        if (pestKillerManager != null) pestKillerManager.setConfig(config);
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
        boolean visitorActive = visitorManager != null && visitorManager.isActive();
        boolean pestKillerIndependent = pestKillerManager != null && pestKillerManager.isActive()
                && !macroManager.isRunning() && !macroManager.isWaitingForPestKiller();
        if (!macroManager.isRunning() && visitorActive) {
            return Text.translatable("gui.flia.stop_visitor");
        }
        if (pestKillerIndependent) {
            return Text.translatable("gui.flia.stop_pest");
        }
        return macroManager.isRunning() || visitorActive || macroManager.isWaitingForPestKiller()
                ? Text.translatable("gui.flia.stop_macro")
                : Text.translatable("gui.flia.start_macro");
    }

    private Text getFreelookButtonText() {
        return macroManager.isFreelookActive()
                ? Text.translatable("gui.flia.freelook_on")
                : Text.translatable("gui.flia.freelook_off");
    }

    private Text getRewarpButtonText() {
        return Text.translatable("gui.flia.set_rewarp");
    }

    // ── Preset helpers ────────────────────────────────────────────────────────

    private Text getPresetButtonText() {
        return switch (presetMode) {
            case PRESET_BLATANT -> Text.literal("Preset: Blatant");
            case PRESET_SMART   -> Text.literal("Preset: Smart");
            default             -> Text.literal("Preset: Custom");
        };
    }

    /**
     * Called by any widget when the user modifies a value.
     * Switches the preset indicator to "Custom" if it was previously Blatant or Smart.
     */
    private void markPresetCustom() {
        if (presetMode != PRESET_CUSTOM) {
            presetMode = PRESET_CUSTOM;
            if (presetButton != null) {
                presetButton.setMessage(getPresetButtonText());
            }
        }
    }

    /**
     * Cycle through presets:
     * <ul>
     *   <li>Blatant → Smart</li>
     *   <li>Smart   → Blatant</li>
     *   <li>Custom  → Blatant</li>
     * </ul>
     */
    private void cyclePreset() {
        if (presetMode == PRESET_BLATANT) {
            applySmartPreset();
        } else {
            applyBlatantPreset();
        }
    }

    /** Apply the Blatant preset: all features enabled, 0 ms delays throughout. */
    private void applyBlatantPreset() {
        presetMode = PRESET_BLATANT;
        config.globalRandomizationMs        = 0;
        config.laneSwapDelayMin             = 0;
        config.laneSwapDelayRandom          = 0;
        config.rewarpDelayMin               = 0;
        config.rewarpDelayRandom            = 0;
        config.mousematSwapToDelay          = 0;
        config.mousematPreDelay             = 0;
        config.mousematPostDelay            = 0;
        config.mousematResumeDelay          = 0;
        config.visitorsActionDelay          = 0;
        config.visitorsActionDelayRandom    = 0;
        config.visitorsTeleportDelay        = 0;
        config.bazaarSearchDelay            = 0;
        config.pestKillerTeleportDelay      = 0;
        config.pestKillerGoToNextPestDelay  = 0;
        config.pestKillerAfterTeleportDelay = 0;
        config.pestHighlightEnabled         = true;
        config.pestLabelsEnabled            = true;
        config.pestEspEnabled               = true;
        config.pestTracerEnabled            = true;
        config.unlockedMouseEnabled         = true;
        config.gardenOnlyEnabled            = true;
        config.squeakyMousematEnabled       = true;
        config.macroEnabledInGui            = true;
        config.visitorsEnabled              = true;
        config.visitorsBuyFromBazaar        = true;
        config.autoPestKillerEnabled        = true;
        config.pestKillerWarpToPlot         = true;
        config.visitorsMinCount             = 1;
        config.visitorsMaxPrice             = 0;
        config.farmingToolHotbarSlot        = -1;
        config.pestKillerVacuumRange        = 15;
        config.save();
        clearAndInit();
    }

    /** Apply the Smart preset: human-like delays and a recommended default configuration. */
    private void applySmartPreset() {
        presetMode = PRESET_SMART;
        config.globalRandomizationMs        = 150;
        config.laneSwapDelayMin             = 400;
        config.laneSwapDelayRandom          = 500;
        config.rewarpDelayMin               = 1000;
        config.rewarpDelayRandom            = 400;
        config.mousematSwapToDelay          = 400;
        config.mousematPreDelay             = 200;
        config.mousematPostDelay            = 350;
        config.mousematResumeDelay          = 450;
        config.visitorsActionDelay          = 500;
        config.visitorsActionDelayRandom    = 550;
        config.visitorsTeleportDelay        = 2000;
        config.bazaarSearchDelay            = 1250;
        config.pestKillerTeleportDelay      = 1000;
        config.pestKillerGoToNextPestDelay  = 350;
        config.pestKillerAfterTeleportDelay = 500;
        config.pestHighlightEnabled         = true;
        config.pestLabelsEnabled            = true;
        config.pestEspEnabled               = true;
        config.pestTitleScale               = 0.5f;
        config.pestTracerEnabled            = true;
        config.unlockedMouseEnabled         = false;
        config.gardenOnlyEnabled            = true;
        config.squeakyMousematEnabled       = true;
        config.macroEnabledInGui            = true;
        config.visitorsEnabled              = true;
        config.visitorsBuyFromBazaar        = true;
        config.autoPestKillerEnabled        = true;
        config.pestKillerWarpToPlot         = true;
        config.visitorsMinCount             = 3;
        config.visitorsMaxPrice             = 450000;
        config.farmingToolHotbarSlot        = -1;
        config.pestKillerVacuumRange        = 10;
        config.visitorBlacklist.clear();
        config.visitorBlacklist.add("Gold Forger");
        config.visitorBlacklist.add("Spaceman");
        config.visitorBlacklist.add("Rhys");
        config.save();
        clearAndInit();
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
        private Runnable onChange;

        FlatBoolToggleWidget(int x, int y, int width, int height,
                             net.minecraft.text.Text label, boolean initialValue) {
            super(x, y, width, height, label);
            this.label = label;
            this.value = initialValue;
        }

        void setOnChange(Runnable r) { this.onChange = r; }

        boolean getValue() {
            return value;
        }

        @Override
        public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            int bg = isHovered() ? COL_BG_HOVER : COL_BG_NORMAL;
            context.fill(x, y, x + w, y + h, bg);
            // Subtle inner bottom shadow for depth
            context.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0x18000000);
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
            // Pill-style toggle switch (right-aligned)
            int pillW = 28, pillH = Math.max(8, h - 10);
            int pillX = x + w - pillW - 8;
            int pillY = y + (h - pillH) / 2;
            // Track
            context.fill(pillX,          pillY,          pillX + pillW, pillY + pillH,
                    value ? 0x9050E890 : 0x40FFFFFF);
            // Track border
            context.fill(pillX,              pillY,              pillX + pillW, pillY + 1,          0x70FFFFFF);
            context.fill(pillX,              pillY + pillH - 1,  pillX + pillW, pillY + pillH,      0x70FFFFFF);
            context.fill(pillX,              pillY + 1,          pillX + 1,     pillY + pillH - 1,  0x70FFFFFF);
            context.fill(pillX + pillW - 1,  pillY + 1,          pillX + pillW, pillY + pillH - 1,  0x70FFFFFF);
            // Knob (slides right when ON, left when OFF)
            int knobSize = pillH - 2;
            int knobX = value ? (pillX + pillW - knobSize - 1) : (pillX + 1);
            context.fill(knobX, pillY + 1, knobX + knobSize, pillY + 1 + knobSize,
                    value ? COL_ON : 0xB0FFFFFF);
        }

        @Override
        public void onClick(net.minecraft.client.gui.Click click, boolean toggle) {
            value = !value;
            if (onChange != null) onChange.run();
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
            // Lightweight visibility update: no clearAndInit() so the scroll
            // position and search field focus are both preserved per-tab.
            refreshWidgetVisibility();
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
        private Runnable onChange;

        IntStepSlider(int x, int y, int width, int height, int min, int max, int initialValue) {
            super(x, y, width, height, Text.empty(),
                    (max > min) ? (double)(Math.max(min, Math.min(max, initialValue)) - min) / (max - min) : 0.0);
            this.min = min;
            this.max = max;
            updateMessage();
        }

        void setOnChange(Runnable r) { this.onChange = r; }

        /** Returns the current value rounded to the nearest multiple of 5, clamped to [min, max]. */
        int getIntValue() {
            if (max == min) return min;
            int raw = min + (int)Math.round(value * (max - min));
            int snapped = (int)Math.round(raw / 5.0) * 5;
            return Math.max(min, Math.min(max, snapped));
        }

        @Override
        protected void applyValue() {
            if (max == min) { this.value = 0.0; if (onChange != null) onChange.run(); return; }
            // Snap mouse-drag value to nearest multiple of 5.
            int steps = max - min;
            int rawInt = min + (int)Math.round(this.value * steps);
            int snapped = (int)Math.round(rawInt / 5.0) * 5;
            snapped = Math.max(min, Math.min(max, snapped));
            this.value = (double)(snapped - min) / steps;
            if (onChange != null) onChange.run();
        }

        @Override
        public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
            if (input.key() == GLFW_KEY_LEFT || input.key() == GLFW_KEY_RIGHT) {
                double step = 5.0 / (max - min);
                this.value = (input.key() == GLFW_KEY_LEFT)
                        ? Math.max(0.0, this.value - step)
                        : Math.min(1.0, this.value + step);
                applyValue(); // also fires onChange
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

    /** Slider for the global randomization jitter applied to every delay (0–2000 ms). */
    private static class GlobalRandomSlider extends IntStepSlider {

        GlobalRandomSlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, 0, 2000, initialValue);
        }

        int getRandomValue() { return getIntValue(); }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Global Randomization: %d ms", getIntValue())));
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

        private Runnable onChange;

        TitleScaleSlider(int x, int y, int width, int height, float initialValue) {
            super(x, y, width, height, Text.empty(),
                    (double)(initialValue - MIN) / (MAX - MIN));
            updateMessage();
        }

        void setOnChange(Runnable r) { this.onChange = r; }

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
        protected void applyValue() {
            if (onChange != null) onChange.run();
        }
    }

    /** Slider for the visitors action delay – applies to all visitor interactions (0–3000 ms). */
    private static class VisitorsDelaySlider extends IntStepSlider {

        VisitorsDelaySlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, 0, 3000, initialValue);
        }

        int getDelayValue() { return getIntValue(); }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Bazaar Click Delay: %d ms", getIntValue())));
        }
    }

    /** Slider for the random extra visitors delay (0–2000 ms). */
    private static class VisitorsRandomSlider extends IntStepSlider {

        VisitorsRandomSlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, 0, 2000, initialValue);
        }

        int getRandomValue() { return getIntValue(); }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Visitors Randomization: %d ms", getIntValue())));
        }
    }

    /** Slider for the visitor teleport-to-barn wait time (0–8000 ms). */
    private static class VisitorTeleportDelaySlider extends IntStepSlider {

        VisitorTeleportDelaySlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, 0, 8000, initialValue);
        }

        int getDelayValue() { return getIntValue(); }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Teleport to Visitor's Delay: %d ms", getIntValue())));
        }
    }

    /** Slider for the bazaar search delay (0–5000 ms). */
    private static class BazaarSearchDelaySlider extends IntStepSlider {

        BazaarSearchDelaySlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, 0, 5000, initialValue);
        }

        int getDelayValue() { return getIntValue(); }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Bazaar Open Delay: %d ms", getIntValue())));
        }
    }

    /**
     * Slider for the minimum visitor count (1–6, step 1).
     * Displays the selected count and "Disabled" hint at 1 (i.e. always run).
     */
    private static class VisitorMinCountSlider extends SliderWidget {

        private static final int MIN = 1;
        private static final int MAX = 6;
        private static final int GLFW_KEY_LEFT  = 263;
        private static final int GLFW_KEY_RIGHT = 262;

        private Runnable onChange;

        VisitorMinCountSlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, Text.empty(),
                    (double)(Math.max(MIN, Math.min(MAX, initialValue)) - MIN) / (MAX - MIN));
            updateMessage();
        }

        void setOnChange(Runnable r) { this.onChange = r; }

        int getCountValue() {
            return MIN + (int) Math.round(value * (MAX - MIN));
        }

        @Override
        protected void applyValue() {
            // Snap to nearest integer step.
            int steps = MAX - MIN;
            int rawInt = MIN + (int) Math.round(this.value * steps);
            rawInt = Math.max(MIN, Math.min(MAX, rawInt));
            this.value = (double)(rawInt - MIN) / steps;
            if (onChange != null) onChange.run();
        }

        @Override
        public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
            if (input.key() == GLFW_KEY_LEFT || input.key() == GLFW_KEY_RIGHT) {
                double step = 1.0 / (MAX - MIN);
                this.value = (input.key() == GLFW_KEY_LEFT)
                        ? Math.max(0.0, this.value - step)
                        : Math.min(1.0, this.value + step);
                applyValue();
                updateMessage();
                return true;
            }
            return super.keyPressed(input);
        }

        @Override
        protected void updateMessage() {
            int v = getCountValue();
            String label = (v == 1)
                    ? "Min. Visitors: 1 (always run)"
                    : String.format("Min. Visitors: %d", v);
            setMessage(Text.literal(label));
        }

        @Override
        public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            FlatButtonWidget.renderFlatSlider(context, getX(), getY(), getWidth(), getHeight(), value, getMessage());
        }
    }

    /**
     * Slider for the maximum visitor NPC price (0 = disabled, 50,000–5,000,000
     * in steps of 50,000).
     *
     * <p>The slider has 101 integer positions:
     * <ul>
     *   <li>Position 0 → 0 coins (feature disabled)</li>
     *   <li>Position N → N × 50,000 coins (1 ≤ N ≤ 100)</li>
     * </ul>
     */
    private static class VisitorMaxPriceSlider extends SliderWidget {

        private static final int STEP     = 50_000;
        /** Number of steps of size {@link #STEP}; slider has STEPS+1 positions (0–STEPS). */
        private static final int STEPS    = 100;  // positions 0..100 → 0 or 50k..5M
        private static final int GLFW_KEY_LEFT  = 263;
        private static final int GLFW_KEY_RIGHT = 262;

        private Runnable onChange;

        VisitorMaxPriceSlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, Text.empty(),
                    (double) Math.max(0, Math.min(STEPS, initialValue / STEP)) / STEPS);
            updateMessage();
        }

        void setOnChange(Runnable r) { this.onChange = r; }

        /** Returns the configured price limit in coins, or {@code 0} if disabled. */
        int getPriceValue() {
            int position = (int) Math.round(value * STEPS);
            return position * STEP;
        }

        @Override
        protected void applyValue() {
            // Snap to nearest step.
            int pos = (int) Math.round(this.value * STEPS);
            pos = Math.max(0, Math.min(STEPS, pos));
            this.value = (double) pos / STEPS;
            if (onChange != null) onChange.run();
        }

        @Override
        public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
            if (input.key() == GLFW_KEY_LEFT || input.key() == GLFW_KEY_RIGHT) {
                double step = 1.0 / STEPS;
                this.value = (input.key() == GLFW_KEY_LEFT)
                        ? Math.max(0.0, this.value - step)
                        : Math.min(1.0, this.value + step);
                applyValue();
                updateMessage();
                return true;
            }
            return super.keyPressed(input);
        }

        @Override
        protected void updateMessage() {
            int coins = getPriceValue();
            String label;
            if (coins == 0) {
                label = "Max Visitor Price: Disabled";
            } else if (coins >= 1_000_000) {
                label = String.format("Max Visitor Price: %.1fM coins", coins / 1_000_000.0);
            } else {
                label = String.format("Max Visitor Price: %,d coins", coins);
            }
            setMessage(Text.literal(label));
        }

        @Override
        public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            FlatButtonWidget.renderFlatSlider(context, getX(), getY(), getWidth(), getHeight(), value, getMessage());
        }
    }

    /** Slider for the pest killer teleport delay (0–10000 ms). */
    private static class PestKillerTeleportDelaySlider extends IntStepSlider {

        PestKillerTeleportDelaySlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, 0, 10000, initialValue);
        }

        int getDelayValue() { return getIntValue(); }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Plot Teleport Delay: %d ms", getIntValue())));
        }
    }

    /** Slider for the pest killer after-teleport scan wait (0–5000 ms). */
    private static class PestKillerAfterTeleportSlider extends IntStepSlider {

        PestKillerAfterTeleportSlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, 0, 5000, initialValue);
        }

        int getDelayValue() { return getIntValue(); }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("After Teleport Timer: %d ms", getIntValue())));
        }
    }

    /** Slider for the pest killer go-to-next-pest delay (0–5000 ms). */
    private static class PestKillerGoToNextPestSlider extends IntStepSlider {

        PestKillerGoToNextPestSlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, 0, 5000, initialValue);
        }

        int getDelayValue() { return getIntValue(); }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Go to Next Pest Delay: %d ms", getIntValue())));
        }
    }

    /**
     * Slider for the preferred farming tool hotbar slot.
     *
     * <p>The slider has 10 positions:
     * <ul>
     *   <li>Internal value {@code -1} → displayed as "Auto" (mod detects the farming tool automatically)</li>
     *   <li>Internal values {@code 0–8} → displayed as "Slot 1–9" (user-visible hotbar slots 1 through 9)</li>
     * </ul>
     */
    private static class FarmingToolSlotSlider extends SliderWidget {

        private static final int MIN = -1;
        private static final int MAX =  8;
        private static final int GLFW_KEY_LEFT  = 263;
        private static final int GLFW_KEY_RIGHT = 262;

        private Runnable onChange;

        FarmingToolSlotSlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, Text.empty(),
                    (double)(Math.max(MIN, Math.min(MAX, initialValue)) - MIN) / (MAX - MIN));
            updateMessage();
        }

        void setOnChange(Runnable r) { this.onChange = r; }

        /** Returns the selected slot (-1 = Auto, 0–8 = specific hotbar slot). */
        int getSlotValue() {
            return MIN + (int) Math.round(value * (MAX - MIN));
        }

        @Override
        protected void applyValue() {
            int steps = MAX - MIN;
            int rawInt = MIN + (int) Math.round(this.value * steps);
            rawInt = Math.max(MIN, Math.min(MAX, rawInt));
            this.value = (double)(rawInt - MIN) / steps;
            if (onChange != null) onChange.run();
        }

        @Override
        public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
            if (input.key() == GLFW_KEY_LEFT || input.key() == GLFW_KEY_RIGHT) {
                double step = 1.0 / (MAX - MIN);
                this.value = (input.key() == GLFW_KEY_LEFT)
                        ? Math.max(0.0, this.value - step)
                        : Math.min(1.0, this.value + step);
                applyValue();
                updateMessage();
                return true;
            }
            return super.keyPressed(input);
        }

        @Override
        protected void updateMessage() {
            int slot = getSlotValue();
            String label = (slot == -1)
                    ? "Farming Tool: Auto"
                    : String.format("Farming Tool: %d", slot + 1);
            setMessage(Text.literal(label));
        }

        @Override
        public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            FlatButtonWidget.renderFlatSlider(context, getX(), getY(), getWidth(), getHeight(), value, getMessage());
        }
    }

    /**
     * Slider for the pest killer vacuum activation range (1–15 blocks, step 1).
     * Higher values allow the vacuum to be used from further away.
     */
    private static class PestKillerVacuumRangeSlider extends SliderWidget {

        private static final int MIN = 1;
        private static final int MAX = 15;
        private static final int GLFW_KEY_LEFT  = 263;
        private static final int GLFW_KEY_RIGHT = 262;

        private Runnable onChange;

        PestKillerVacuumRangeSlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height, Text.empty(),
                    (double)(Math.max(MIN, Math.min(MAX, initialValue)) - MIN) / (MAX - MIN));
            updateMessage();
        }

        void setOnChange(Runnable r) { this.onChange = r; }

        int getRangeValue() {
            return MIN + (int) Math.round(value * (MAX - MIN));
        }

        @Override
        protected void applyValue() {
            int steps = MAX - MIN;
            int rawInt = MIN + (int) Math.round(this.value * steps);
            rawInt = Math.max(MIN, Math.min(MAX, rawInt));
            this.value = (double)(rawInt - MIN) / steps;
            if (onChange != null) onChange.run();
        }

        @Override
        public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
            if (input.key() == GLFW_KEY_LEFT || input.key() == GLFW_KEY_RIGHT) {
                double step = 1.0 / (MAX - MIN);
                this.value = (input.key() == GLFW_KEY_LEFT)
                        ? Math.max(0.0, this.value - step)
                        : Math.min(1.0, this.value + step);
                applyValue();
                updateMessage();
                return true;
            }
            return super.keyPressed(input);
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Vacuum Range: %d blocks", getRangeValue())));
        }

        @Override
        public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            FlatButtonWidget.renderFlatSlider(context, getX(), getY(), getWidth(), getHeight(), value, getMessage());
        }
    }
}
