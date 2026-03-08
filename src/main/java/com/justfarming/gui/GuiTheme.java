package com.justfarming.gui;

import com.justfarming.config.FarmingConfig;

/**
 * Two-mode colour palette for all Just Farming GUI screens.
 *
 * <p>A single static {@link #current} instance is updated by each screen's
 * constructor from {@link FarmingConfig#darkMode} so that every widget
 * automatically uses the correct colours without needing extra constructor
 * parameters.
 *
 * <p>Usage in a screen:
 * <pre>
 *     GuiTheme.activate(config.darkMode);
 *     // then reference GuiTheme.current.WIN_BG, etc.
 * </pre>
 */
public final class GuiTheme {

    // ── Singleton instances ───────────────────────────────────────────────────

    /** Dark theme (default – near-black backgrounds, white text). */
    public static final GuiTheme DARK  = new GuiTheme(true);

    /** Light theme (near-white backgrounds, dark text). */
    public static final GuiTheme LIGHT = new GuiTheme(false);

    /** Currently active theme; updated by {@link #activate(boolean)}. */
    public static GuiTheme current = DARK;

    // ── Colour fields ─────────────────────────────────────────────────────────

    /** Full-screen dim overlay drawn behind the window. */
    public final int SCREEN_DIM;
    /** Main window background. */
    public final int WIN_BG;
    /** Navigation sidebar background. */
    public final int NAV_BG;
    /** Thin border lines around panels and buttons. */
    public final int BORDER;
    /** Very subtle separator / section-background tint. */
    public final int SEP;
    /** Section-label background tint. */
    public final int SECTION_BG;
    /** Primary text colour. */
    public final int TEXT;
    /** Muted / secondary text colour. */
    public final int TEXT_MUTED;
    /** Purple accent colour (same in both themes). */
    public final int ACCENT      = 0xFF7C4DFF;
    /** Active tab background tint. */
    public final int TAB_ACTIVE;
    /** Drop-shadow tint. */
    public final int SHADOW;

    // Button colours
    /** Normal button background. */
    public final int BTN_BG_NORMAL;
    /** Hovered button background. */
    public final int BTN_BG_HOVER;

    // Slider colours
    /** Slider track background. */
    public final int SLIDER_TRACK;
    /** Slider filled portion. */
    public final int SLIDER_FILL;
    /** Slider thumb colour. */
    public final int SLIDER_THUMB;

    // ── Constructor ───────────────────────────────────────────────────────────

    private GuiTheme(boolean dark) {
        if (dark) {
            SCREEN_DIM   = 0x60000000;
            WIN_BG       = 0xBF000000;
            NAV_BG       = 0x99000000;
            BORDER       = 0x28FFFFFF;
            SEP          = 0x14FFFFFF;
            SECTION_BG   = 0x14FFFFFF;
            TEXT         = 0xF2FFFFFF;
            TEXT_MUTED   = 0x66FFFFFF;
            TAB_ACTIVE   = 0x26FFFFFF;
            SHADOW       = 0x60000000;
            BTN_BG_NORMAL = 0x1AFFFFFF;
            BTN_BG_HOVER  = 0x33FFFFFF;
            SLIDER_TRACK  = 0x14FFFFFF;
            SLIDER_FILL   = 0x28FFFFFF;
            SLIDER_THUMB  = 0xF2FFFFFF;
        } else {
            SCREEN_DIM   = 0x40000000;
            WIN_BG       = 0xEFF5F5F5;
            NAV_BG       = 0xCCDCDCDC;
            BORDER       = 0x40000000;
            SEP          = 0x18000000;
            SECTION_BG   = 0x10000000;
            TEXT         = 0xFF1A1A1A;
            TEXT_MUTED   = 0x80333333;
            TAB_ACTIVE   = 0x20000000;
            SHADOW       = 0x30000000;
            BTN_BG_NORMAL = 0x14000000;
            BTN_BG_HOVER  = 0x28000000;
            SLIDER_TRACK  = 0x14000000;
            SLIDER_FILL   = 0x28000000;
            SLIDER_THUMB  = 0xFF1A1A1A;
        }
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    /**
     * Set the active theme from the given config flag and return it.
     * Call this at the start of each screen's constructor.
     */
    public static GuiTheme activate(boolean dark) {
        current = dark ? DARK : LIGHT;
        return current;
    }

    /** Convenience: activate from a {@link FarmingConfig} instance. */
    public static GuiTheme activate(FarmingConfig config) {
        return activate(config != null && config.darkMode);
    }
}
