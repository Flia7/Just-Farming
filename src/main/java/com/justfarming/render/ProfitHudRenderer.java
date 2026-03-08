package com.justfarming.render;

import com.justfarming.CropType;
import com.justfarming.config.FarmingConfig;
import com.justfarming.profit.FarmingProfitTracker;
import com.justfarming.profit.FarmingProfitTracker.ProfitEntry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Renders the Farming Profit HUD overlay.
 *
 * <p>Layout:
 * <pre>
 *  ┌─────────────────────────────────────────┐
 *  │  Cocoa Beans (20m)                      │  ← crop title (crop colour)
 *  │  BPS: 368.0                             │  ← average blocks/sec (item scale)
 *  ├─────────────────────────────────────────┤
 *  │  Farming                                │  ← sub-title (white)
 *  │  1,234 Cocoa Beans          +3,702      │  ← items (item scale, grey)
 *  │    567 Enchanted Cocoa B.  +272,160     │
 *  │  Other                          +12     │
 *  │  Total Farming Profit       +275,874    │  ← total (item scale, white)
 *  ├─────────────────────────────────────────┤
 *  │  Pests                                  │  ← sub-title (white, optional)
 *  │  120 Compost                   +480     │
 *  │  Total Pest Profit             +480     │
 *  ├─────────────────────────────────────────┤
 *  │  Stats                                  │  ← sub-title (white)
 *  │  Profit/Hour:          +276,354/h       │  ← combined farming+pest (item scale)
 *  └─────────────────────────────────────────┘
 * </pre>
 */
public class ProfitHudRenderer {

    // ── Layout constants ─────────────────────────────────────────────────────

    /** Horizontal padding inside the panel. */
    private static final int PAD_X       = 6;
    /** Vertical padding at the top and bottom of the panel. */
    private static final int PAD_Y       = 5;
    /** Vertical spacing between full-scale rows. */
    private static final int LINE_H      = 10;
    /** Extra vertical gap inserted after separator lines. */
    private static final int SECTION_GAP = 4;
    /** Separator line height. */
    private static final int SEP_H       = 1;
    /** Small gap inserted before a separator line (visual breathing room). */
    private static final int SEPARATOR_PRE_GAP = 2;

    // ── Colour palette ─────────────────────────────────────────────────────────

    // Dark mode colours
    /** Semi-transparent dark blue panel background matching the config GUI (dark mode). */
    private static final int COL_BG_DARK     = 0xD2080C1A;
    /** Cyan border/separator tint (dark mode). */
    private static final int COL_SEP_DARK    = 0x2800C8FF;
    /** Cyan border outline (dark mode). */
    private static final int COL_BORDER_DARK = 0x6000C8FF;
    /** Bright cyan accent stripe (dark mode). */
    private static final int COL_ACCENT_DARK = 0xA000C8FF;
    /** Light lavender – sub-titles (dark mode). */
    private static final int COL_TITLE_DARK  = 0xFFEAF2FF;
    /** Light grey – individual item rows (dark mode). */
    private static final int COL_ITEM_DARK   = 0xFFBBBBBB;
    /** Green – positive profit values (dark mode). */
    private static final int COL_PROFIT_DARK = 0xFF55FF55;
    /** "Other" row colour (dark mode, dimmer). */
    private static final int COL_OTHER_DARK  = 0xFF999999;

    // Light mode colours
    /** Semi-transparent light panel background (light mode). */
    private static final int COL_BG_LIGHT     = 0xF0EEF4F8;
    /** Separator tint (light mode). */
    private static final int COL_SEP_LIGHT    = 0x50304870;
    /** Border outline (light mode). */
    private static final int COL_BORDER_LIGHT = 0x60203060;
    /** Accent stripe (light mode). */
    private static final int COL_ACCENT_LIGHT = 0xA0203060;
    /** Dark – sub-titles (light mode). */
    private static final int COL_TITLE_LIGHT  = 0xFF0F1E3C;
    /** Medium dark – individual item rows (light mode). */
    private static final int COL_ITEM_LIGHT   = 0xFF304870;
    /** Dark green – positive profit values (light mode). */
    private static final int COL_PROFIT_LIGHT = 0xFF1A8040;
    /** Muted row colour (light mode). */
    private static final int COL_OTHER_LIGHT  = 0xFF607090;

    /** Maximum individual item rows before collapsing the rest into "Other". */
    private static final int MAX_ITEMS = 4;

    /**
     * Scale applied to item rows (names, counts, profits, totals) so they
     * appear smaller than the full-size sub-title lines.
     */
    private static final float ITEM_SCALE = 0.85f;

    private final FarmingConfig config;

    public ProfitHudRenderer(FarmingConfig config) {
        this.config = config;
    }

    // ── Theme helpers ─────────────────────────────────────────────────────────

    private boolean isDark() { return config.darkMode; }

    private int COL_BG()     { return isDark() ? COL_BG_DARK     : COL_BG_LIGHT;     }
    private int COL_SEP()    { return isDark() ? COL_SEP_DARK    : COL_SEP_LIGHT;    }
    private int COL_BORDER() { return isDark() ? COL_BORDER_DARK : COL_BORDER_LIGHT; }
    private int COL_ACCENT() { return isDark() ? COL_ACCENT_DARK : COL_ACCENT_LIGHT; }
    private int COL_TITLE()  { return isDark() ? COL_TITLE_DARK  : COL_TITLE_LIGHT;  }
    private int COL_ITEM()   { return isDark() ? COL_ITEM_DARK   : COL_ITEM_LIGHT;   }
    private int COL_PROFIT() { return isDark() ? COL_PROFIT_DARK : COL_PROFIT_LIGHT; }
    private int COL_OTHER()  { return isDark() ? COL_OTHER_DARK  : COL_OTHER_LIGHT;  }

    /**
     * Returns the panel width in pixels, matching the current inventory HUD width
     * so both overlays stay the same width regardless of the user's scale setting.
     */
    private int panelW() {
        return InventoryHudRenderer.getOverlayWidth(config.inventoryOverlayScale);
    }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Called from the HUD render callback every frame.  Renders the panel only
     * when {@link FarmingConfig#profitTrackerEnabled} is {@code true} and the
     * tracker has accumulated at least one item gain.
     *
     * @param context  the draw context
     * @param tracker  the profit tracker holding accumulated session data
     * @param inGarden whether the player is currently in the Garden (controls pest section visibility)
     */
    public void render(DrawContext context, FarmingProfitTracker tracker, boolean inGarden) {
        if (!config.profitTrackerEnabled) return;
        if (!inGarden) return;  // #1: only show profit HUD inside the garden
        if (tracker == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;

        TextRenderer tr = mc.textRenderer;
        int x = config.profitHudX;
        int y = config.profitHudY;

        int height = computeHeight(tracker, inGarden);
        int pw = panelW();

        // Background
        context.fill(x, y, x + pw, y + height, COL_BG());

        // Top accent stripe (1px) – bright cyan matching the config GUI header.
        context.fill(x, y, x + pw, y + 1, COL_ACCENT());

        // Thin border outline around the remaining three sides (accent covers top).
        context.fill(x,          y + height - 1, x + pw,     y + height,     COL_BORDER());
        context.fill(x,          y,              x + 1,      y + height,     COL_BORDER());
        context.fill(x + pw - 1, y,              x + pw,     y + height,     COL_BORDER());

        int curY = y + PAD_Y;

        // ── Crop title: crop name in the crop's colour ────────────────────────
        CropType crop = config.selectedCrop != null ? config.selectedCrop : CropType.COCOA_BEANS;
        int cropColor = crop.getDisplayColor();
        String cropName = Text.translatable(crop.getTranslationKey()).getString();
        context.drawTextWithShadow(tr, cropName, x + PAD_X, curY, cropColor);
        curY += LINE_H;

        // ── BPS row (item scale) ──────────────────────────────────────────────
        drawScaledText(context, tr, x + PAD_X, curY,
                "BPS: " + formatBps(tracker.getAverageBps()), COL_ITEM());
        curY += scaledLineH();

        // ── Farming sub-section ───────────────────────────────────────────────
        curY = drawSeparator(context, x, curY);
        context.drawTextWithShadow(tr, "Farming", x + PAD_X, curY, COL_TITLE());
        curY += LINE_H;
        curY = drawItemsAndTotal(context, tr, x, curY,
                tracker.getFarmingEntries(), tracker.getFarmingProfit(),
                "Total Farming Profit");

        // ── Pests sub-section (optional, garden-only) ──────────────────────────
        if (config.pestProfitEnabled && inGarden) {
            curY = drawSeparator(context, x, curY);
            context.drawTextWithShadow(tr, "Pests", x + PAD_X, curY, COL_TITLE());
            curY += LINE_H;
            curY = drawItemsAndTotal(context, tr, x, curY,
                    tracker.getPestEntries(), tracker.getPestProfit(),
                    "Total Pest Profit");
        }

        // ── Stats sub-section ─────────────────────────────────────────────────
        curY = drawSeparator(context, x, curY);
        context.drawTextWithShadow(tr, "Stats", x + PAD_X, curY, COL_TITLE());
        curY += LINE_H;

        // Time Elapsed at item scale
        String timeElapsed = "Time Elapsed: " + formatElapsedTime(tracker.getSessionElapsedMs());
        drawScaledText(context, tr, x + PAD_X, curY, timeElapsed, COL_ITEM());
        curY += scaledLineH();

        // Combined Profit/Hour (farming + pests) at item scale
        double combinedPh = tracker.getFarmingProfitPerHour()
                + (config.pestProfitEnabled ? tracker.getPestProfitPerHour() : 0.0);
        String phLabel = "Profit/Hour:";
        String phValue = "+" + formatCoins(combinedPh) + "/h";
        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(x + PAD_X, curY);
        matrices.scale(ITEM_SCALE, ITEM_SCALE);
        int unscaledW = Math.round((panelW() - PAD_X * 2) / ITEM_SCALE);
        int phRightX  = unscaledW - tr.getWidth(phValue);
        context.drawTextWithShadow(tr, phLabel, 0, 0, COL_TITLE());
        context.drawTextWithShadow(tr, phValue, phRightX, 0, COL_PROFIT());
        matrices.popMatrix();
    }

    // ── Section helpers ───────────────────────────────────────────────────────

    /**
     * Draws item rows then the total line for one section.
     * Both item rows and the total are drawn at {@link #ITEM_SCALE}.
     *
     * @return the Y coordinate immediately after the last drawn row
     */
    private int drawItemsAndTotal(DrawContext ctx, TextRenderer tr,
                                  int x, int curY,
                                  List<ProfitEntry> entries, double totalProfit,
                                  String totalLabel) {
        double otherProfit = 0;
        int shown = 0;
        for (ProfitEntry entry : entries) {
            if (shown >= MAX_ITEMS) {
                otherProfit += entry.profit();
            } else {
                drawItemRow(ctx, tr, x, curY,
                        entry.displayName(), entry.count(), entry.profit(), COL_ITEM());
                curY += scaledLineH();
                shown++;
            }
        }
        if (otherProfit > 0) {
            drawItemRow(ctx, tr, x, curY, "Other", -1, otherProfit, COL_OTHER());
            curY += scaledLineH();
        }
        if (entries.isEmpty()) {
            drawScaledText(ctx, tr, x + PAD_X, curY, "  No items yet", COL_OTHER());
            curY += scaledLineH();
        }

        // Total line (item scale) – white label, green value right-aligned
        String totalStr = "+" + formatCoins(totalProfit);
        var matrices = ctx.getMatrices();
        matrices.pushMatrix();
        matrices.translate(x + PAD_X, curY);
        matrices.scale(ITEM_SCALE, ITEM_SCALE);
        int unscaledW = Math.round((panelW() - PAD_X * 2) / ITEM_SCALE);
        int rightX    = unscaledW - tr.getWidth(totalStr);
        ctx.drawTextWithShadow(tr, totalLabel, 0, 0, COL_TITLE());
        ctx.drawTextWithShadow(tr, totalStr, rightX, 0, COL_PROFIT());
        matrices.popMatrix();
        curY += scaledLineH();

        return curY;
    }

    /**
     * Draws one item row at reduced scale:
     * {@code "  1,234 Sugar Cane   +4,936"}.
     *
     * @param count item count, or {@code -1} to omit the count (for "Other")
     */
    private void drawItemRow(DrawContext ctx, TextRenderer tr,
                             int panelX, int y,
                             String name, long count, double profit, int textColor) {
        var matrices = ctx.getMatrices();
        matrices.pushMatrix();
        matrices.translate(panelX + PAD_X, y);
        matrices.scale(ITEM_SCALE, ITEM_SCALE);

        int unscaledW    = Math.round((panelW() - PAD_X * 2) / ITEM_SCALE);
        String countStr  = (count >= 0) ? formatCount(count) + " " : "";
        String profitStr = "+" + formatCoins(profit);
        int rightX       = unscaledW - tr.getWidth(profitStr);

        ctx.drawTextWithShadow(tr, countStr + name, 0, 0, textColor);
        ctx.drawTextWithShadow(tr, profitStr, rightX, 0, COL_PROFIT());

        matrices.popMatrix();
    }

    /** Draws a single text string at item scale (no right-align). */
    private void drawScaledText(DrawContext ctx, TextRenderer tr,
                                int x, int y, String text, int color) {
        var matrices = ctx.getMatrices();
        matrices.pushMatrix();
        matrices.translate(x, y);
        matrices.scale(ITEM_SCALE, ITEM_SCALE);
        ctx.drawTextWithShadow(tr, text, 0, 0, color);
        matrices.popMatrix();
    }

    /** Draws a separator line and returns the Y position after the resulting gap. */
    private int drawSeparator(DrawContext ctx, int x, int curY) {
        curY += SEPARATOR_PRE_GAP;
        ctx.fill(x + PAD_X, curY, x + panelW() - PAD_X, curY + SEP_H, COL_SEP());
        curY += SEP_H + SECTION_GAP;
        return curY;
    }

    // ── Height calculation ────────────────────────────────────────────────────

    private int computeHeight(FarmingProfitTracker tracker, boolean inGarden) {
        int h = PAD_Y * 2;

        // Crop title + BPS
        h += LINE_H;            // crop name + time
        h += scaledLineH();     // BPS row

        // Separator + Farming subtitle + items + total
        h += separatorH();
        h += LINE_H;            // "Farming" label
        h += sectionItemsH(tracker.getFarmingEntries());

        // Pests (optional, garden-only)
        if (config.pestProfitEnabled && inGarden) {
            h += separatorH();
            h += LINE_H;        // "Pests" label
            h += sectionItemsH(tracker.getPestEntries());
        }

        // Separator + Stats + time elapsed + profit/hour row
        h += separatorH();
        h += LINE_H;            // "Stats" label
        h += scaledLineH();     // Time Elapsed row
        h += scaledLineH();     // Profit/Hour row

        return h;
    }

    /** Pixel height for all item rows + total line of one section. */
    private static int sectionItemsH(List<ProfitEntry> entries) {
        int shown = Math.min(entries.size(), MAX_ITEMS);
        int h = shown * scaledLineH();
        if (entries.size() > MAX_ITEMS) h += scaledLineH(); // Other row
        if (entries.isEmpty())          h += scaledLineH(); // "No items yet"
        h += scaledLineH();                                  // total line
        return h;
    }

    /** Pixel height consumed by one separator (gap before + line + gap after). */
    private static int separatorH() {
        return SEPARATOR_PRE_GAP + SEP_H + SECTION_GAP;
    }

    // ── Static dimension helpers (used by Edit HUD screen) ────────────────────

    /**
     * Returns the panel width in pixels for the given inventory-overlay scale,
     * matching the inventory HUD width so both overlays share the same horizontal size.
     *
     * @param inventoryScale the current {@link FarmingConfig#inventoryOverlayScale}
     */
    public static int getPanelWidth(float inventoryScale) {
        return InventoryHudRenderer.getOverlayWidth(inventoryScale);
    }

    /**
     * Returns an approximate panel height in pixels for the given configuration.
     * Used by the Edit HUD screen to determine drag bounds before tracker data is
     * available.  The value errs slightly tall to ensure the full panel is covered.
     *
     * @param pestProfitEnabled whether the pest-profit section is shown
     */
    public static int getApproxHeight(boolean pestProfitEnabled) {
        // crop title + BPS
        int h = PAD_Y * 2 + LINE_H + scaledLineH();
        // Farming section (separator + label + ~2 item rows + total)
        h += separatorH() + LINE_H + 3 * scaledLineH();
        // Pests section (optional)
        if (pestProfitEnabled) {
            h += separatorH() + LINE_H + 2 * scaledLineH();
        }
        // Stats section (separator + label + time elapsed + profit/hour)
        h += separatorH() + LINE_H + 2 * scaledLineH();
        return h;
    }

    // ── Formatting helpers ────────────────────────────────────────────────────

    private static final NumberFormat NUM_FMT =
            NumberFormat.getIntegerInstance(Locale.US);

    static String formatCoins(double coins) {
        if (coins <= 0) return "0";
        return NUM_FMT.format(Math.round(coins));
    }

    static String formatCount(long count) {
        return NUM_FMT.format(count);
    }

    static String formatBps(double bps) {
        if (bps <= 0) return "0.00";
        return String.format(Locale.US, "%.2f", bps);
    }

    /** Formats elapsed milliseconds as "Xh Ym Zs", "Ym Zs", or "Zs". */
    static String formatElapsedTime(long ms) {
        long totalSeconds = ms / 1000L;
        long seconds = totalSeconds % 60L;
        long totalMinutes = totalSeconds / 60L;
        long minutes = totalMinutes % 60L;
        long hours   = totalMinutes / 60L;
        if (hours > 0) {
            return hours + "h" + minutes + "m" + seconds + "s";
        }
        if (minutes > 0) {
            return minutes + "m" + seconds + "s";
        }
        return seconds + "s";
    }

    /** Pixel height of one item row at {@link #ITEM_SCALE}. */
    private static int scaledLineH() {
        return Math.round(LINE_H * ITEM_SCALE) + 1;
    }
}
