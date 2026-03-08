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
    /** Electric-purple accent colour (same in both themes). */
    public final int ACCENT      = 0xFF8B5CF6;
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
            // ── Cyberpunk / Deep-Space dark theme ────────────────────────────
            SCREEN_DIM    = 0x70000510;
            WIN_BG        = 0xD2080C1A;
            NAV_BG        = 0xCC050A16;
            BORDER        = 0x6000C8FF;
            SEP           = 0x2800C8FF;
            SECTION_BG    = 0x1600C8FF;
            TEXT          = 0xFFEAF2FF;
            TEXT_MUTED    = 0x8090B8FF;
            TAB_ACTIVE    = 0x3000C8FF;
            SHADOW        = 0x90000010;
            BTN_BG_NORMAL = 0x2000C8FF;
            BTN_BG_HOVER  = 0x4400C8FF;
            SLIDER_TRACK  = 0x1800C8FF;
            SLIDER_FILL   = 0x4400C8FF;
            SLIDER_THUMB  = 0xFF00D8FF;
        } else {
            // ── Clean-Tech / Pearl light theme ───────────────────────────────
            SCREEN_DIM    = 0x50081030;
            WIN_BG        = 0xF0EEF4F8;
            NAV_BG        = 0xE8DDE6F0;
            BORDER        = 0x60203060;
            SEP           = 0x28203060;
            SECTION_BG    = 0x14203060;
            TEXT          = 0xFF0F1E3C;
            TEXT_MUTED    = 0x80425880;
            TAB_ACTIVE    = 0x30203060;
            SHADOW        = 0x40000010;
            BTN_BG_NORMAL = 0x1E203060;
            BTN_BG_HOVER  = 0x3C203060;
            SLIDER_TRACK  = 0x1A203060;
            SLIDER_FILL   = 0x4A203060;
            SLIDER_THUMB  = 0xFF0F1E3C;
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
