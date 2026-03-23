package com.justfarming.render;

import com.justfarming.CropType;
import com.justfarming.config.FarmingConfig;
import com.justfarming.input.KeystrokesTracker;
import com.justfarming.profit.FarmingProfitTracker;
import com.justfarming.profit.FarmingProfitTracker.ProfitEntry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

public class ProfitHudRenderer {

    // ── Layout constants ─────────────────────────────────────────────────────

    private static final int PAD_X       = 6;
    private static final int PAD_Y       = 5;
    private static final int LINE_H      = 10;
    private static final int SECTION_GAP = 4;
    private static final int SEP_H       = 1;
    private static final int SEPARATOR_PRE_GAP = 2;

    // ── Colour palette ─────────────────────────────────────────────────────────

    // Dark mode colours
    private static final int COL_BG_DARK     = 0xD2080C1A;
    private static final int COL_SEP_DARK    = 0x2800C8FF;
    private static final int COL_BORDER_DARK = 0x6000C8FF;
    private static final int COL_ACCENT_DARK = 0xA000C8FF;
    private static final int COL_TITLE_DARK  = 0xFFEAF2FF;
    private static final int COL_ITEM_DARK   = 0xFFBBBBBB;
    private static final int COL_PROFIT_DARK = 0xFF55FF55;
    private static final int COL_OTHER_DARK  = 0xFF999999;

    // Light mode colours
    private static final int COL_BG_LIGHT     = 0xF0EEF4F8;
    private static final int COL_SEP_LIGHT    = 0x50304870;
    private static final int COL_BORDER_LIGHT = 0x60203060;
    private static final int COL_ACCENT_LIGHT = 0xA0203060;
    private static final int COL_TITLE_LIGHT  = 0xFF0F1E3C;
    private static final int COL_ITEM_LIGHT   = 0xFF304870;
    private static final int COL_PROFIT_LIGHT = 0xFF1A8040;
    private static final int COL_OTHER_LIGHT  = 0xFF607090;

    private static final int MAX_ITEMS = 4;

    private static final float ITEM_SCALE = 0.85f;

    private static final int ICON_SIZE = 8;
    private static final int ICON_GAP  = 2;
    private static final float MC_ITEM_ICON_SIZE = 16.0f;

    // ── Keystrokes constants (Canelex KeyStrokes Revamp style) ───────────────

    private static final int KS_KEY_SIZE = 11;
    private static final int KS_KEY_GAP  = 1;
    private static final int KS_H_PAD    = 2;
    private static final int KS_V_PAD    = 3;
    private static final int KS_HEIGHT   = KS_V_PAD + 3 * KS_KEY_SIZE + 2 * KS_KEY_GAP;
    private static final int KS_WIDTH    = 2 * KS_H_PAD + 3 * KS_KEY_SIZE + 2 * KS_KEY_GAP;

    private static final float KS_MIN_SCALE = 0.25f;
    private static final float KS_FONT_RATIO = 0.075f;
    private static final float KS_MIN_FONT = 0.3f;

    // Keystroke key arrow symbols (Canelex inverted-T layout)
    private static final String KS_ARROW_W = "▲";
    private static final String KS_ARROW_A = "◀";
    private static final String KS_ARROW_S = "▼";
    private static final String KS_ARROW_D = "▶";

    // Keystroke colours – dark mode
    private static final int KS_BG_PRESSED_DARK    = 0xC0FFFFFF;
    private static final int KS_BG_RELEASED_DARK   = 0x30FFFFFF;
    private static final int KS_TXT_PRESSED_DARK   = 0xFF000000;
    private static final int KS_TXT_RELEASED_DARK  = 0x80FFFFFF;

    // Keystroke colours – light mode
    private static final int KS_BG_PRESSED_LIGHT   = 0xD0203060;
    private static final int KS_BG_RELEASED_LIGHT  = 0x30203060;
    private static final int KS_TXT_PRESSED_LIGHT  = 0xFFEEF4F8;
    private static final int KS_TXT_RELEASED_LIGHT = 0x80203060;


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
     * Returns the panel width in pixels, matching the combined width of the
     * inventory HUD and (when enabled) the paper-doll panel so both overlays
     * appear as one cohesive block above the profit panel.
     */
    private int panelW() {
        int w = InventoryHudRenderer.getOverlayWidth(config.inventoryOverlayScale);
        if (config.inventoryOverlayEnabled && config.paperDollEnabled) {
            w += PaperDollRenderer.getTotalWidth(config.inventoryOverlayScale);
        }
        return w;
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

        // Refresh the throttled display cache (no-op if updated <3s ago).
        tracker.refreshDisplayCache(config.pestProfitEnabled);

        TextRenderer tr = mc.textRenderer;
        // Anchor the profit panel directly below the inventory HUD (and paper-doll
        // panel) so all three widgets appear as one connected block.  The
        // inventoryOverlayX/Y values are used even when inventoryOverlayEnabled is
        // false so the user's configured position is still respected.
        int x = config.inventoryOverlayX;
        int y = config.inventoryOverlayY
                + InventoryHudRenderer.getOverlayHeight(config.inventoryOverlayScale);

        int height = computeHeight(tracker, inGarden);
        int pw = panelW();

        // Background panel.
        context.fill(x, y, x + pw, y + height, COL_BG());
        // Separator line at the top of the profit panel so it is visually
        // separated from the inventory HUD / paper-doll panel above it.
        context.fill(x, y, x + pw, y + 1, COL_SEP());

        // ── Keystrokes widget – right side of the header ──────────────────────
        // Scale keystrokes so KS_HEIGHT fits in the header section height.
        int headerH = PAD_Y + LINE_H + scaledLineH();  // crop + BPS rows
        float ksScale = Math.max(KS_MIN_SCALE,
                (float) headerH / KS_HEIGHT * 0.90f);
        int ksW = Math.round(KS_WIDTH  * ksScale);
        // Right-align within the panel with PAD_X margin, shifted half a key-size
        // to the left so the widget sits slightly inward from the panel edge.
        int ksX = x + pw - PAD_X - ksW - Math.round(KS_KEY_SIZE * ksScale * 0.5f);
        int ksY = y + PAD_Y + Math.round(KS_KEY_SIZE * ksScale * 0.5f)
                - Math.round((KS_KEY_SIZE + KS_KEY_GAP) * ksScale);
        renderKeystrokes(context, tr, ksX, ksY, ksW, ksScale, isDark());

        int curY = y + PAD_Y;

        // ── Crop title: crop name in the crop's colour ────────────────────────
        CropType crop = config.selectedCrop != null ? config.selectedCrop : CropType.COCOA_BEANS;
        int cropColor = crop.getDisplayColor();
        String cropName = Text.translatable(crop.getTranslationKey()).getString();
        context.drawTextWithShadow(tr, cropName, x + PAD_X, curY, cropColor);
        curY += LINE_H;

        // ── BPS row (item scale) ──────────────────────────────────────────────
        double bps = tracker.getAverageBps();
        String bpsLine = "BPS: " + formatBps(bps);
        drawScaledText(context, tr, x + PAD_X, curY, bpsLine, COL_ITEM());
        curY += scaledLineH();
        // Show farming fortune when it has been detected from the tab list.
        double fortune     = tracker.getFarmingFortune();
        double cropFortune = tracker.getCropFortune();
        if (fortune > 0) {
            String fortuneText = cropFortune > 0
                    ? "Fortune: " + (int)(fortune - cropFortune) + " + " + (int)cropFortune + " (crop)"
                    : "Fortune: " + (int) fortune;
            drawScaledText(context, tr, x + PAD_X, curY, fortuneText, COL_ITEM());
            curY += scaledLineH();
        }

        // ── Farming sub-section ───────────────────────────────────────────────
        curY = drawSeparator(context, x, curY);
        context.drawTextWithShadow(tr, "Farming", x + PAD_X, curY, COL_TITLE());
        curY += LINE_H;
        curY = drawItemsAndTotal(context, tr, x, curY,
                tracker.getDisplayFarmingEntries(), tracker.getDisplayFarmingProfit(),
                "Total Farming Profit");

        // ── Pests sub-section (optional, garden-only) ──────────────────────────
        if (config.pestProfitEnabled && inGarden) {
            curY = drawSeparator(context, x, curY);
            context.drawTextWithShadow(tr, "Pests", x + PAD_X, curY, COL_TITLE());
            curY += LINE_H;
            curY = drawItemsAndTotal(context, tr, x, curY,
                    tracker.getDisplayPestEntries(), tracker.getDisplayPestProfit(),
                    "Total Pest Profit");
        }

        // ── Stats sub-section ─────────────────────────────────────────────────
        curY = drawSeparator(context, x, curY);
        context.drawTextWithShadow(tr, "Stats", x + PAD_X, curY, COL_TITLE());
        curY += LINE_H;

        // Time Elapsed – always real-time so the clock ticks normally.
        String timeElapsed = "Time Elapsed: " + formatElapsedTime(tracker.getSessionElapsedMs());
        drawScaledText(context, tr, x + PAD_X, curY, timeElapsed, COL_ITEM());
        curY += scaledLineH();

        // Total Profit (farming + pests) at item scale – throttled to 3s.
        double totalProfit = tracker.getDisplayTotalProfit();
        String tpLabel = "Total Profit:";
        String tpValue = "+" + formatCoins(totalProfit);
        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(x + PAD_X, curY);
        matrices.scale(ITEM_SCALE, ITEM_SCALE);
        int unscaledW = Math.round((panelW() - PAD_X * 2) / ITEM_SCALE);
        int tpRightX = unscaledW - tr.getWidth(tpValue);
        context.drawTextWithShadow(tr, tpLabel, 0, 0, COL_TITLE());
        context.drawTextWithShadow(tr, tpValue, tpRightX, 0, COL_PROFIT());
        matrices.popMatrix();

        // ── Bottom accent stripe ──────────────────────────────────────────────
        context.fill(x, y + height - 1, x + pw, y + height, COL_ACCENT());
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
                        entry.displayName(), entry.count(), entry.profit(), COL_ITEM(), entry.item());
                curY += scaledLineH();
                shown++;
            }
        }
        if (otherProfit > 0) {
            drawItemRow(ctx, tr, x, curY, "Other", -1, otherProfit, COL_OTHER(), null);
            curY += scaledLineH();
        }
        if (entries.isEmpty()) {
            drawScaledText(ctx, tr, x + PAD_X + ICON_SIZE + ICON_GAP, curY, "No items yet", COL_OTHER());
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
     * Draws one item row at reduced scale with an optional item icon on the left:
     * {@code "[icon] 1,234 Sugar Cane   +4,936"}.
     *
     * @param count item count, or {@code -1} to omit the count (for "Other")
     * @param item  the Minecraft item whose texture is shown as a small icon, or
     *              {@code null} to skip icon rendering (e.g. for the "Other" row)
     */
    private void drawItemRow(DrawContext ctx, TextRenderer tr,
                             int panelX, int y,
                             String name, long count, double profit, int textColor, Item item) {
        // Abbreviate "Enchanted " prefix to save space ("Enc, Cocoa Bean" etc.).
        String displayName = name.startsWith("Enchanted ")
                ? "Enc, " + name.substring("Enchanted ".length())
                : name;

        // ── Item icon (optional) ──────────────────────────────────────────────
        if (item != null) {
            var matrices = ctx.getMatrices();
            matrices.pushMatrix();
            // Scale the native MC_ITEM_ICON_SIZE×MC_ITEM_ICON_SIZE item icon down to ICON_SIZE×ICON_SIZE screen pixels.
            float iconScale = ICON_SIZE / MC_ITEM_ICON_SIZE;
            matrices.translate(panelX + PAD_X, y);
            matrices.scale(iconScale, iconScale);
            ItemStack iconStack = new ItemStack(item);
            // Enchanted items show the base item icon with the enchantment glint.
            if (name.startsWith("Enchanted ")) {
                iconStack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
            }
            ctx.drawItem(iconStack, 0, 0);
            matrices.popMatrix();
        }

        // ── Text (count + name left-aligned, profit right-aligned) ────────────
        int iconOffset = ICON_SIZE + ICON_GAP;
        var matrices = ctx.getMatrices();
        matrices.pushMatrix();
        matrices.translate(panelX + PAD_X + iconOffset, y);
        matrices.scale(ITEM_SCALE, ITEM_SCALE);

        int unscaledW    = Math.round((panelW() - PAD_X * 2 - iconOffset) / ITEM_SCALE);
        String countStr  = (count >= 0) ? formatCount(count) + " " : "";
        String profitStr = "+" + formatCoins(profit);
        int rightX       = unscaledW - tr.getWidth(profitStr);

        ctx.drawTextWithShadow(tr, countStr + displayName, 0, 0, textColor);
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

        // Crop title + BPS + optional fortune row
        h += LINE_H;            // crop name
        h += scaledLineH();     // BPS / Crops-per-second row
        if (tracker.getFarmingFortune() > 0) {
            h += scaledLineH(); // Fortune row (only when detected)
        }

        // Separator + Farming subtitle + items + total
        h += separatorH();
        h += LINE_H;            // "Farming" label
        h += sectionItemsH(tracker.getDisplayFarmingEntries());

        // Pests (optional, garden-only)
        if (config.pestProfitEnabled && inGarden) {
            h += separatorH();
            h += LINE_H;        // "Pests" label
            h += sectionItemsH(tracker.getDisplayPestEntries());
        }

        // Separator + Stats + time elapsed + total profit rows
        h += separatorH();
        h += LINE_H;            // "Stats" label
        h += scaledLineH();     // Time Elapsed row
        h += scaledLineH();     // Total Profit row

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
        // Stats section (separator + label + time elapsed + total profit)
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

    // ── Keystrokes rendering ──────────────────────────────────────────────────

    /**
     * Renders the WASD + LMB/RMB keystrokes widget at the given position,
     * using Canelex inverted-T layout:
     * <pre>
     *   [ ▲ ]              ← W  (centred)
     *   [ ◀ ] [ ▼ ] [ ▶ ] ← A S D (centred)
     *   [  L  ] [  R  ]   ← LMB / RMB
     * </pre>
     *
     * @param x     left pixel of the widget
     * @param y     top pixel of the widget
     * @param w     width of the widget in screen pixels
     * @param scale key pixel size multiplier
     * @param dark  whether to use dark-mode colours
     */
    private void renderKeystrokes(DrawContext context, TextRenderer tr,
                                   int x, int y, int w, float scale, boolean dark) {
        KeystrokesTracker tracker = KeystrokesTracker.getInstance();
        long now = System.currentTimeMillis();

        int bgPressed  = dark ? KS_BG_PRESSED_DARK   : KS_BG_PRESSED_LIGHT;
        int bgReleased = dark ? KS_BG_RELEASED_DARK  : KS_BG_RELEASED_LIGHT;
        int txtPressed  = dark ? KS_TXT_PRESSED_DARK  : KS_TXT_PRESSED_LIGHT;
        int txtReleased = dark ? KS_TXT_RELEASED_DARK : KS_TXT_RELEASED_LIGHT;

        int ks     = Math.max(1, Math.round(KS_KEY_SIZE * scale));
        int kg     = Math.max(1, Math.round(KS_KEY_GAP  * scale));
        int stride = ks + kg;
        int hPad   = Math.max(1, Math.round(KS_H_PAD * scale));
        int vPad   = Math.max(1, Math.round(KS_V_PAD * scale));

        // Row 1: W key, centred
        int row1Y = y + vPad;
        int wX    = x + (w - ks) / 2;
        drawKsKey(context, tr, wX, row1Y, ks, KS_ARROW_W,
                KeystrokesTracker.KEY_W, tracker, now, bgPressed, bgReleased, txtPressed, txtReleased);

        // Row 2: A / S / D, centred
        int row2Y     = row1Y + stride;
        int threeW    = 3 * ks + 2 * kg;
        int asdStartX = x + (w - threeW) / 2;
        drawKsKey(context, tr, asdStartX,              row2Y, ks, KS_ARROW_A,
                KeystrokesTracker.KEY_A, tracker, now, bgPressed, bgReleased, txtPressed, txtReleased);
        drawKsKey(context, tr, asdStartX + stride,     row2Y, ks, KS_ARROW_S,
                KeystrokesTracker.KEY_S, tracker, now, bgPressed, bgReleased, txtPressed, txtReleased);
        drawKsKey(context, tr, asdStartX + 2 * stride, row2Y, ks, KS_ARROW_D,
                KeystrokesTracker.KEY_D, tracker, now, bgPressed, bgReleased, txtPressed, txtReleased);

        // Row 3: LMB / RMB, filling inner width
        // Show the CPS count when spamming (packets registered in the last second);
        // fall back to "L" / "R" when just holding or not pressing.
        int row3Y  = row2Y + stride;
        int inner  = w - 2 * hPad;
        int lmbW   = (inner - kg) / 2;
        int rmbW   = inner - lmbW - kg;
        int lmbX   = x + hPad;
        int rmbX   = lmbX + lmbW + kg;
        int lmbCps = tracker.getLmbCps();
        int rmbCps = tracker.getRmbCps();
        String lmbLabel = lmbCps > 0 ? String.valueOf(lmbCps) : "L";
        String rmbLabel = rmbCps > 0 ? String.valueOf(rmbCps) : "R";
        drawKsKey(context, tr, lmbX, row3Y, lmbW, ks, lmbLabel,
                KeystrokesTracker.KEY_LMB, tracker, now, bgPressed, bgReleased, txtPressed, txtReleased);
        drawKsKey(context, tr, rmbX, row3Y, rmbW, ks, rmbLabel,
                KeystrokesTracker.KEY_RMB, tracker, now, bgPressed, bgReleased, txtPressed, txtReleased);
    }

    private static void drawKsKey(DrawContext context, TextRenderer tr,
                                   int x, int y, int w, int h, String label,
                                   int keyIdx, KeystrokesTracker tracker, long now,
                                   int bgPressed, int bgReleased, int txtPressed, int txtReleased) {
        int bg  = tracker.getKeyBgColor  (keyIdx, bgPressed,  bgReleased,  now);
        int txt = tracker.getKeyTextColor(keyIdx, txtPressed, txtReleased, now);
        // Background fill
        context.fill(x, y, x + w, y + h, bg);
        // Centred label
        float fontScale = Math.max(KS_MIN_FONT, h * KS_FONT_RATIO);
        int glyphW = Math.round(tr.getWidth(label) * fontScale);
        int glyphH = Math.round(8 * fontScale);
        int tx = x + (w - glyphW) / 2;
        int ty = y + (h - glyphH) / 2;
        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(tx, ty);
        matrices.scale(fontScale, fontScale);
        context.drawText(tr, label, 0, 0, txt, false);
        matrices.popMatrix();
    }

    private static void drawKsKey(DrawContext context, TextRenderer tr,
                                   int x, int y, int size, String label,
                                   int keyIdx, KeystrokesTracker tracker, long now,
                                   int bgPressed, int bgReleased, int txtPressed, int txtReleased) {
        drawKsKey(context, tr, x, y, size, size, label, keyIdx, tracker, now,
                bgPressed, bgReleased, txtPressed, txtReleased);
    }
}
