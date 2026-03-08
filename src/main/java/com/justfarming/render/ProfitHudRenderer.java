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

    /** Panel width in pixels. */
    private static final int PANEL_W     = 200;
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

    /** Semi-transparent black panel background. */
    private static final int COL_BG     = 0xA8000000;
    /** Separator / border tint. */
    private static final int COL_SEP    = 0x28FFFFFF;
    /** White – sub-titles. */
    private static final int COL_TITLE  = 0xFFFFFFFF;
    /** Light grey – individual item rows. */
    private static final int COL_ITEM   = 0xFFBBBBBB;
    /** Green – positive profit values. */
    private static final int COL_PROFIT = 0xFF55FF55;
    /** "Other" row colour (dimmer). */
    private static final int COL_OTHER  = 0xFF999999;

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

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Called from the HUD render callback every frame.  Renders the panel only
     * when {@link FarmingConfig#profitTrackerEnabled} is {@code true} and the
     * tracker has accumulated at least one item gain.
     *
     * @param context the draw context
     * @param tracker the profit tracker holding accumulated session data
     */
    public void render(DrawContext context, FarmingProfitTracker tracker) {
        if (!config.profitTrackerEnabled) return;
        if (tracker == null || !tracker.hasData()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;

        TextRenderer tr = mc.textRenderer;
        int x = config.profitHudX;
        int y = config.profitHudY;

        int height = computeHeight(tracker);

        // Background + thin border
        context.fill(x, y, x + PANEL_W, y + height, COL_BG);
        context.fill(x, y, x + PANEL_W, y + 1, COL_SEP);
        context.fill(x, y + height - 1, x + PANEL_W, y + height, COL_SEP);
        context.fill(x, y, x + 1, y + height, COL_SEP);
        context.fill(x + PANEL_W - 1, y, x + PANEL_W, y + height, COL_SEP);

        int curY = y + PAD_Y;

        // ── Crop title: "Cocoa Beans (20m)" in the crop's colour ─────────────
        CropType crop = config.selectedCrop != null ? config.selectedCrop : CropType.COCOA_BEANS;
        int cropColor = crop.getDisplayColor();
        String cropName = Text.translatable(crop.getTranslationKey()).getString();
        String timeStr  = formatElapsedTime(tracker.getSessionElapsedMs());
        String titleStr = cropName + " (" + timeStr + ")";
        context.drawTextWithShadow(tr, titleStr, x + PAD_X, curY, cropColor);
        curY += LINE_H;

        // ── BPS row (item scale) ──────────────────────────────────────────────
        drawScaledText(context, tr, x + PAD_X, curY,
                "BPS: " + formatBps(tracker.getAverageBps()), COL_ITEM);
        curY += scaledLineH();

        // ── Farming sub-section ───────────────────────────────────────────────
        curY = drawSeparator(context, x, curY);
        context.drawTextWithShadow(tr, "Farming", x + PAD_X, curY, COL_TITLE);
        curY += LINE_H;
        curY = drawItemsAndTotal(context, tr, x, curY,
                tracker.getFarmingEntries(), tracker.getFarmingProfit(),
                "Total Farming Profit");

        // ── Pests sub-section (optional) ──────────────────────────────────────
        if (config.pestProfitEnabled) {
            curY = drawSeparator(context, x, curY);
            context.drawTextWithShadow(tr, "Pests", x + PAD_X, curY, COL_TITLE);
            curY += LINE_H;
            curY = drawItemsAndTotal(context, tr, x, curY,
                    tracker.getPestEntries(), tracker.getPestProfit(),
                    "Total Pest Profit");
        }

        // ── Stats sub-section ─────────────────────────────────────────────────
        curY = drawSeparator(context, x, curY);
        context.drawTextWithShadow(tr, "Stats", x + PAD_X, curY, COL_TITLE);
        curY += LINE_H;

        // Combined Profit/Hour (farming + pests) at item scale
        double combinedPh = tracker.getFarmingProfitPerHour()
                + (config.pestProfitEnabled ? tracker.getPestProfitPerHour() : 0.0);
        String phLabel = "Profit/Hour:";
        String phValue = "+" + formatCoins(combinedPh) + "/h";
        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(x + PAD_X, curY);
        matrices.scale(ITEM_SCALE, ITEM_SCALE);
        int unscaledW = Math.round((PANEL_W - PAD_X * 2) / ITEM_SCALE);
        int phRightX  = unscaledW - tr.getWidth(phValue);
        context.drawTextWithShadow(tr, phLabel, 0, 0, COL_TITLE);
        context.drawTextWithShadow(tr, phValue, phRightX, 0, COL_PROFIT);
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
                        entry.displayName(), entry.count(), entry.profit(), COL_ITEM);
                curY += scaledLineH();
                shown++;
            }
        }
        if (otherProfit > 0) {
            drawItemRow(ctx, tr, x, curY, "Other", -1, otherProfit, COL_OTHER);
            curY += scaledLineH();
        }
        if (entries.isEmpty()) {
            drawScaledText(ctx, tr, x + PAD_X, curY, "  No items yet", COL_OTHER);
            curY += scaledLineH();
        }

        // Total line (item scale) – white label, green value right-aligned
        String totalStr = "+" + formatCoins(totalProfit);
        var matrices = ctx.getMatrices();
        matrices.pushMatrix();
        matrices.translate(x + PAD_X, curY);
        matrices.scale(ITEM_SCALE, ITEM_SCALE);
        int unscaledW = Math.round((PANEL_W - PAD_X * 2) / ITEM_SCALE);
        int rightX    = unscaledW - tr.getWidth(totalStr);
        ctx.drawTextWithShadow(tr, totalLabel, 0, 0, COL_TITLE);
        ctx.drawTextWithShadow(tr, totalStr, rightX, 0, COL_PROFIT);
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

        int unscaledW    = Math.round((PANEL_W - PAD_X * 2) / ITEM_SCALE);
        String countStr  = (count >= 0) ? formatCount(count) + " " : "";
        String profitStr = "+" + formatCoins(profit);
        int rightX       = unscaledW - tr.getWidth(profitStr);

        ctx.drawTextWithShadow(tr, countStr + name, 0, 0, textColor);
        ctx.drawTextWithShadow(tr, profitStr, rightX, 0, COL_PROFIT);

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
        ctx.fill(x + PAD_X, curY, x + PANEL_W - PAD_X, curY + SEP_H, COL_SEP);
        curY += SEP_H + SECTION_GAP;
        return curY;
    }

    // ── Height calculation ────────────────────────────────────────────────────

    private int computeHeight(FarmingProfitTracker tracker) {
        int h = PAD_Y * 2;

        // Crop title + BPS
        h += LINE_H;            // crop name + time
        h += scaledLineH();     // BPS row

        // Separator + Farming subtitle + items + total
        h += separatorH();
        h += LINE_H;            // "Farming" label
        h += sectionItemsH(tracker.getFarmingEntries());

        // Pests (optional)
        if (config.pestProfitEnabled) {
            h += separatorH();
            h += LINE_H;        // "Pests" label
            h += sectionItemsH(tracker.getPestEntries());
        }

        // Separator + Stats + profit/hour row
        h += separatorH();
        h += LINE_H;            // "Stats" label
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

    /** Returns the panel width in pixels (constant, independent of content). */
    public static int getPanelWidth() {
        return PANEL_W;
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
        // Stats section (separator + label + profit/hour)
        h += separatorH() + LINE_H + scaledLineH();
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
        if (bps <= 0) return "0.0";
        return String.format(Locale.US, "%.1f", bps);
    }

    /** Formats elapsed milliseconds as "Xm" or "Xh Ym". */
    static String formatElapsedTime(long ms) {
        long totalSeconds = ms / 1000L;
        long minutes = totalSeconds / 60L;
        long hours   = minutes / 60L;
        if (hours > 0) {
            return hours + "h " + (minutes % 60) + "m";
        }
        return minutes + "m";
    }

    /** Pixel height of one item row at {@link #ITEM_SCALE}. */
    private static int scaledLineH() {
        return Math.round(LINE_H * ITEM_SCALE) + 1;
    }
}
