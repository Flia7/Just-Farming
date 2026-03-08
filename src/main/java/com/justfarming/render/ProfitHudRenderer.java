package com.justfarming.render;

import com.justfarming.config.FarmingConfig;
import com.justfarming.profit.FarmingProfitTracker;
import com.justfarming.profit.FarmingProfitTracker.ProfitEntry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Renders the Farming Profit and (optionally) Pest Profit HUD overlay.
 *
 * <p>Layout:
 * <pre>
 *  ┌──────────────────────────────────────┐
 *  │  Farming Profit                      │  ← title (normal scale, white)
 *  │  1,234 Sugar Cane           +4,936   │  ← items (smaller, gray)
 *  │    567 Wheat                +3,402   │
 *  │     23 Enchanted Wheat     +22,080   │
 *  │     45 Carrot                 +135   │
 *  │  Other                        +890   │
 *  │  Total Farming Profit       +31,443  │  ← total (white)
 *  ├──────────────────────────────────────┤
 *  │  Pest Profit                         │  ← second title (optional)
 *  │  ...                                 │
 *  │  Total Pest Profit          +1,234   │
 *  ├──────────────────────────────────────┤
 *  │  Profit/Hour                         │  ← section header (medium)
 *  │  Farming: 123,456/h                  │
 *  │  Pests:    23,456/h                  │  ← optional
 *  └──────────────────────────────────────┘
 * </pre>
 *
 * <p>Colours and background match the mod's existing aesthetic
 * ({@link InventoryHudRenderer#BG_COLOR}, purple accent {@code 0xFF7C4DFF}).
 */
public class ProfitHudRenderer {

    // ── Layout constants ─────────────────────────────────────────────────────

    /** Panel width in pixels. */
    private static final int PANEL_W   = 200;
    /** Horizontal padding inside the panel. */
    private static final int PAD_X     = 6;
    /** Vertical padding at the top and bottom of the panel. */
    private static final int PAD_Y     = 5;
    /** Vertical spacing between rows (pixels). */
    private static final int LINE_H    = 10;
    /** Extra pixels added above section titles. */
    private static final int SECTION_GAP = 5;
    /** Separator line height in pixels. */
    private static final int SEP_H     = 1;

    // ── Colour palette (same theme as the rest of the mod) ───────────────────

    /** Semi-transparent black panel background. */
    private static final int COL_BG        = 0xA8000000;
    /** Separator / border tint. */
    private static final int COL_SEP       = 0x28FFFFFF;
    /** White – used for titles and totals. */
    private static final int COL_TITLE     = 0xFFFFFFFF;
    /** Light grey – used for individual item rows. */
    private static final int COL_ITEM      = 0xFFBBBBBB;
    /** Green – positive profit values. */
    private static final int COL_PROFIT    = 0xFF55FF55;
    /** Yellow – profit/hour header and values. */
    private static final int COL_PH_HEADER = 0xFFFFDD55;
    /** "Other" row text colour (slightly dimmer than items). */
    private static final int COL_OTHER     = 0xFF999999;

    /** Number of top items to show individually before collapsing to "Other". */
    private static final int MAX_ITEMS = 4;

    // ── Scale for the item rows (smaller than titles) ────────────────────────

    /**
     * Scale applied to item-row and value text so they appear smaller than
     * the full-size title and total lines.
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

        // ── Measure panel height ──────────────────────────────────────────────
        int height = computeHeight(tracker, tr);

        // ── Background ────────────────────────────────────────────────────────
        context.fill(x, y, x + PANEL_W, y + height, COL_BG);
        // thin border
        context.fill(x, y, x + PANEL_W, y + 1,          COL_SEP);
        context.fill(x, y + height - 1, x + PANEL_W, y + height, COL_SEP);
        context.fill(x, y, x + 1,      y + height,  COL_SEP);
        context.fill(x + PANEL_W - 1, y, x + PANEL_W, y + height, COL_SEP);

        int curY = y + PAD_Y;

        // ── Section 1: Farming Profit ─────────────────────────────────────────
        curY = drawSection(context, tr, x, curY,
                "Farming Profit",
                tracker.getFarmingEntries(),
                tracker.getFarmingProfit(),
                "Total Farming Profit");

        // ── Section 2: Pest Profit (optional) ────────────────────────────────
        if (config.pestProfitEnabled) {
            // Separator
            curY += SEP_H + 1;
            context.fill(x + PAD_X, curY, x + PANEL_W - PAD_X, curY + SEP_H, COL_SEP);
            curY += SEP_H + SECTION_GAP;

            curY = drawSection(context, tr, x, curY,
                    "Pest Profit",
                    tracker.getPestEntries(),
                    tracker.getPestProfit(),
                    "Total Pest Profit");
        }

        // ── Section 3: Profit/Hour ────────────────────────────────────────────
        curY += SEP_H + 1;
        context.fill(x + PAD_X, curY, x + PANEL_W - PAD_X, curY + SEP_H, COL_SEP);
        curY += SEP_H + SECTION_GAP;

        drawProfitPerHour(context, tr, x, curY, tracker);
    }

    // ── Section renderer ──────────────────────────────────────────────────────

    /**
     * Draws a single profit section (title + items + total) and returns the Y
     * coordinate after the last line drawn.
     */
    private int drawSection(DrawContext ctx, TextRenderer tr,
                            int x, int curY,
                            String title,
                            List<ProfitEntry> entries,
                            double totalProfit,
                            String totalLabel) {

        // Title (full scale, white)
        ctx.drawTextWithShadow(tr, title, x + PAD_X, curY, COL_TITLE);
        curY += LINE_H;

        // Item rows (small scale, grey)
        double otherProfit = 0;
        int shown = 0;
        for (ProfitEntry entry : entries) {
            if (shown >= MAX_ITEMS) {
                otherProfit += entry.profit();
            } else {
                drawItemRow(ctx, tr, x, curY, entry.displayName(),
                        entry.count(), entry.profit(), COL_ITEM);
                curY += scaledLineH();
                shown++;
            }
        }

        // "Other" row (if any items were collapsed)
        if (otherProfit > 0) {
            drawItemRow(ctx, tr, x, curY, "Other", -1, otherProfit, COL_OTHER);
            curY += scaledLineH();
        }

        // Total line (full scale, white)
        if (entries.isEmpty()) {
            // Show a placeholder when no items have been tracked yet
            ctx.drawTextWithShadow(tr, "  No items yet", x + PAD_X, curY, COL_OTHER);
            curY += LINE_H;
        }

        String totalStr = formatCoins(totalProfit);
        String label    = totalLabel;
        int labelW      = tr.getWidth(label);
        int valueW      = tr.getWidth("+" + totalStr);
        int rightEdge   = x + PANEL_W - PAD_X;

        ctx.drawTextWithShadow(tr, label, x + PAD_X, curY, COL_TITLE);
        ctx.drawTextWithShadow(tr, "+" + totalStr,
                rightEdge - valueW, curY, COL_PROFIT);
        curY += LINE_H;

        return curY;
    }

    // ── Item row renderer ─────────────────────────────────────────────────────

    /**
     * Draws one item row at a reduced scale:
     * {@code "  1,234 Sugar Cane                +4,936"}.
     *
     * @param count  item count, or {@code -1} to omit the count (for "Other")
     */
    private void drawItemRow(DrawContext ctx, TextRenderer tr,
                             int panelX, int y,
                             String name, long count, double profit, int textColor) {
        var matrices = ctx.getMatrices();
        matrices.pushMatrix();

        // Scale around the left edge of this row
        float s = ITEM_SCALE;
        int absX = panelX + PAD_X;
        int absY = y;
        matrices.translate(absX, absY);
        matrices.scale(s, s);

        int unscaledW    = Math.round((PANEL_W - PAD_X * 2) / s);
        String countStr  = (count >= 0) ? formatCount(count) + " " : "";
        String rowLeft   = countStr + name;
        String profitStr = "+" + formatCoins(profit);
        int profitW      = tr.getWidth(profitStr);
        int rightX       = unscaledW - profitW;

        // Draw item name (with count)
        ctx.drawTextWithShadow(tr, rowLeft, 0, 0, textColor);
        // Draw profit value right-aligned
        ctx.drawTextWithShadow(tr, profitStr, rightX, 0, COL_PROFIT);

        matrices.popMatrix();
    }

    // ── Profit / Hour section ─────────────────────────────────────────────────

    private void drawProfitPerHour(DrawContext ctx, TextRenderer tr,
                                   int x, int curY,
                                   FarmingProfitTracker tracker) {
        // "Profit/Hour" header – one step smaller than section titles but
        // larger than item rows; we use full scale with a distinct colour.
        ctx.drawTextWithShadow(tr, "Profit/Hour", x + PAD_X, curY, COL_PH_HEADER);
        curY += LINE_H;

        // Farming value row (item-scale)
        double farmPH = tracker.getFarmingProfitPerHour();
        drawItemRow(ctx, tr, x, curY,
                "Farming:", -1, farmPH, COL_ITEM);
        curY += scaledLineH();

        // Pest value row (item-scale) – only shown when pest profit enabled
        if (config.pestProfitEnabled) {
            double pestPH = tracker.getPestProfitPerHour();
            drawItemRow(ctx, tr, x, curY, "Pests:", -1, pestPH, COL_ITEM);
        }
    }

    // ── Height calculation (for background rectangle) ────────────────────────

    private int computeHeight(FarmingProfitTracker tracker, TextRenderer tr) {
        int h = PAD_Y * 2;

        // Section 1: Farming
        h += sectionHeight(tracker.getFarmingEntries());

        // Section 2: Pest (optional)
        if (config.pestProfitEnabled) {
            h += SEP_H + 1 + SECTION_GAP;         // separator
            h += sectionHeight(tracker.getPestEntries());
        }

        // Profit/hour separator + header + farming row [+ pest row]
        h += SEP_H + 1 + SECTION_GAP;
        h += LINE_H;                                // "Profit/Hour" header
        h += scaledLineH();                         // Farming: row
        if (config.pestProfitEnabled) {
            h += scaledLineH();                     // Pests: row
        }

        return h;
    }

    private int sectionHeight(List<ProfitEntry> entries) {
        int h = LINE_H;   // title
        int shown = Math.min(entries.size(), MAX_ITEMS);
        h += shown * scaledLineH();
        boolean hasOther = entries.size() > MAX_ITEMS;
        if (hasOther) h += scaledLineH();
        if (entries.isEmpty()) h += LINE_H; // "No items yet" placeholder
        h += LINE_H;      // total line
        return h;
    }

    /** Pixel height of one item row at {@link #ITEM_SCALE}. */
    private static int scaledLineH() {
        return Math.round(LINE_H * ITEM_SCALE) + 1;
    }

    // ── Formatting helpers ────────────────────────────────────────────────────

    private static final NumberFormat NUM_FMT =
            NumberFormat.getIntegerInstance(Locale.US);

    static String formatCoins(double coins) {
        // Profit values are always non-negative in this context (NPC price × count).
        if (coins <= 0) return "0";
        return NUM_FMT.format(Math.round(coins));
    }

    static String formatCount(long count) {
        return NUM_FMT.format(count);
    }
}
