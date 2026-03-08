package com.justfarming.render;

import com.justfarming.config.FarmingConfig;
import com.justfarming.input.KeystrokesTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * Renders a paper-doll player model and a compact WASD + LMB/RMB keystrokes
 * display directly to the right of the {@link InventoryHudRenderer} overlay.
 *
 * <p>Visual design is inspired by the
 * <a href="https://github.com/Polyfrost/Canelex-KeyStrokes-Revamp">Canelex
 * KeyStrokes Revamp</a> mod:
 * <ul>
 *   <li>Inverted-T WASD layout: W centred on the top row; A, S, D on the row
 *       below (matching Canelex's key arrangement).</li>
 *   <li>Filled triangle arrows ▲ ▼ ◀ ▶ for WASD labels.</li>
 *   <li>Smooth per-key colour fading when pressed/released, using the same
 *       linear interpolation ({@code percentFaded}) approach as Canelex.</li>
 *   <li>LMB / RMB mouse buttons in a third row; LMB shows "L" when not
 *       clicking and the live CPS count (packets sent, not just physical
 *       clicks) while actively clicking.</li>
 * </ul>
 *
 * <pre>
 *   ┌──────────────────────────────────────┐  ┌──────────────────┐
 *   │  [slot]…[slot]  (3-row inv grid)     │  │                  │  ↑
 *   │  [slot]…[slot]                       │  │   player model   │  3/5
 *   │  [slot]…[slot]                       │  │                  │  ↓
 *   └──────────────────────────────────────┘  ├──────────────────┤
 *                                             │      [ ▲ ]       │  ↑
 *                                             │  [ ◀ ][ ▼ ][ ▶ ]│  2/5
 *                                             │  [ L 7]  [ R ]   │  ↓
 *                                             └──────────────────┘
 * </pre>
 *
 * <p>The panel shares the same background colour and exact height as the
 * inventory HUD so the two widgets appear as a single cohesive unit.
 */
public class PaperDollRenderer {

    // ── Panel dimensions (unscaled, at inventoryOverlayScale = 1.0) ──────────

    /** Unscaled width (px) of the paper-doll panel. */
    private static final int PANEL_W = 40;

    /** Unscaled gap (px) between the right edge of the inventory HUD and this panel. */
    private static final int PANEL_GAP = 2;

    // ── Key layout (unscaled) ─────────────────────────────────────────────────

    /** Unscaled side length (px) of each WASD key square. */
    private static final int KEY_SIZE = 11;

    /** Unscaled gap (px) between adjacent keys (horizontal and vertical). */
    private static final int KEY_GAP = 1;

    /** Unscaled horizontal padding inside the panel on each side. */
    private static final int H_PAD = 2;

    /** Unscaled vertical padding above the first key row. */
    private static final int V_PAD = 3;

    /**
     * Unscaled height (px) of the keystrokes section at the bottom of the panel.
     * Contains three rows (W, ASD, LMB/RMB) with two gaps between them, plus
     * top padding.
     */
    private static final int KS_HEIGHT = V_PAD + 3 * KEY_SIZE + 2 * KEY_GAP;

    // ── Key symbols – Canelex KeyStrokes Revamp triangle arrows ──────────────

    private static final String ARROW_W = "▲";
    private static final String ARROW_A = "◀";
    private static final String ARROW_S = "▼";
    private static final String ARROW_D = "▶";

    // ── Colours – adaptive per theme ─────────────────────────────────────────

    /** Background colour when a key is fully pressed (dark mode). */
    private static final int BG_PRESSED_DARK    = 0xC0FFFFFF;
    /** Background colour when a key is fully released (dark mode). */
    private static final int BG_RELEASED_DARK   = 0x30FFFFFF;
    /** Text/icon colour when a key is fully pressed (dark mode). */
    private static final int TXT_PRESSED_DARK   = 0xFF000000;
    /** Text/icon colour when a key is fully released (dark mode). */
    private static final int TXT_RELEASED_DARK  = 0x80FFFFFF;

    /** Background colour when a key is fully pressed (light mode). */
    private static final int BG_PRESSED_LIGHT   = 0xD0203060;
    /** Background colour when a key is fully released (light mode). */
    private static final int BG_RELEASED_LIGHT  = 0x30203060;
    /** Text/icon colour when a key is fully pressed (light mode). */
    private static final int TXT_PRESSED_LIGHT  = 0xFFEEF4F8;
    /** Text/icon colour when a key is fully released (light mode). */
    private static final int TXT_RELEASED_LIGHT = 0x80203060;

    // ── Font scaling ──────────────────────────────────────────────────────────

    /**
     * Ratio of font scale to key pixel size.  Targets approximately 65 % of
     * the key height as the glyph cap height.  Minecraft's default font cap
     * height is roughly 8 px at scale 1.0, so the formula is
     * {@code keyPx * KEY_FONT_SCALE_RATIO ≈ keyPx * 0.65 / 8}.
     */
    private static final float KEY_FONT_SCALE_RATIO = 0.075f;

    /** Minimum font scale so labels remain readable at very small HUD scales. */
    private static final float MIN_FONT_SCALE = 0.3f;

    /**
     * Minimum keystroke-section scale factor.  Prevents individual key boxes
     * from becoming too small to render legibly when the inventory HUD is
     * displayed at very low scale values.
     */
    private static final float MIN_KEYSTROKE_SCALE = 0.2f;

    // ── State ─────────────────────────────────────────────────────────────────

    private final FarmingConfig config;

    public PaperDollRenderer(FarmingConfig config) {
        this.config = config;
    }

    // ── Public render entry point ─────────────────────────────────────────────

    /**
     * Called by the HUD render callback every frame.  Renders the paper-doll
     * panel to the right of the inventory HUD when both
     * {@link FarmingConfig#inventoryOverlayEnabled} and
     * {@link FarmingConfig#paperDollEnabled} are {@code true}.
     *
     * @param context   draw context
     * @param invHudX   screen X of the inventory HUD's top-left corner
     * @param invHudY   screen Y of the inventory HUD's top-left corner
     * @param invScale  the active inventory HUD scale multiplier
     */
    public void render(DrawContext context, int invHudX, int invHudY, float invScale) {
        if (!config.inventoryOverlayEnabled || !config.paperDollEnabled) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null) return;

        float scale = Math.max(0.25f, invScale);

        int invW  = InventoryHudRenderer.getOverlayWidth(scale);
        int invH  = InventoryHudRenderer.getOverlayHeight(scale);

        int panelW = Math.round(PANEL_W   * scale);
        int panelH = invH;   // same height as the inventory HUD

        // Player model gets 3/5 of the panel height; keystrokes get the remaining 2/5.
        int modelH = (panelH * 3) / 5;
        int ksH    = panelH - modelH;

        // Compute a keystroke-section scale so the 3 key rows fit in the 2/5 height,
        // then shrink to 85 % so the keys sit comfortably inside the panel bounds.
        float ksScale = Math.max(MIN_KEYSTROKE_SCALE, (float) ksH / KS_HEIGHT) * 0.85f;

        int panelX = invHudX + invW + Math.round(PANEL_GAP * scale);
        int panelY = invHudY;

        // ── Background ────────────────────────────────────────────────────────
        int bgColor = config.darkMode ? InventoryHudRenderer.BG_COLOR_DARK : InventoryHudRenderer.BG_COLOR_LIGHT;
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, bgColor);

        // ── Player model ──────────────────────────────────────────────────────
        // Entity display size: 45.5% (70% × 65%) of the model area height; minimum 4 px.
        int entitySize = Math.max(4, (int) (modelH * 0.455f));

        // Mouse offsets control the entity's look direction within drawEntity.
        // mouseX=0 → face toward viewer; small negative mouseY → slight upward
        // tilt so the face is visible rather than the top of the head.
        float mouseX = 0.0f;
        float mouseY = -(entitySize * 0.30f);

        InventoryScreen.drawEntity(
                context,
                panelX, panelY, panelX + panelW, panelY + modelH,
                entitySize,
                mouseX, mouseY,
                0.0f,   // tickDelta = 0 freezes entity animations (static pose)
                player);

        // ── Separator line between model area and keystrokes area ─────────────
        int sepY = panelY + modelH;
        int sepColor = config.darkMode ? 0x20FFFFFF : 0x30203060;
        context.fill(panelX, sepY, panelX + panelW, sepY + 1, sepColor);

        // ── Keystrokes section ────────────────────────────────────────────────
        renderKeystrokes(context, mc.textRenderer, panelX, sepY + 1, panelW, ksH - 1, ksScale,
                config.darkMode);
    }

    // ── Private rendering helpers ─────────────────────────────────────────────

    /**
     * Renders the WASD keys (W top / A-S-D bottom) and the LMB / RMB mouse
     * buttons inside the keystrokes area.
     *
     * <p>Layout (unscaled, KS_HEIGHT ≈ 38 px):
     * <pre>
     *   y + V_PAD            :  [ ▲ ]                 ← W  (centred)
     *   y + V_PAD + stride   :  [ ◀ ] [ ▼ ] [ ▶ ]   ← A S D (centred)
     *   y + V_PAD + 2*stride :  [ LMB cps ] [ RMB ]  ← mouse buttons
     * </pre>
     *
     * @param x       left pixel of the keystrokes section
     * @param y       top pixel of the keystrokes section
     * @param w       width of the section in screen pixels
     * @param h       height of the section in screen pixels (unused directly)
     * @param scale   current HUD scale multiplier
     */
    private void renderKeystrokes(DrawContext context, TextRenderer tr,
                                   int x, int y, int w, int h, float scale, boolean dark) {
        KeystrokesTracker tracker = KeystrokesTracker.getInstance();
        long now = System.currentTimeMillis();

        int bgPressedColor  = dark ? BG_PRESSED_DARK  : BG_PRESSED_LIGHT;
        int bgReleasedColor = dark ? BG_RELEASED_DARK : BG_RELEASED_LIGHT;
        int txtPressedColor  = dark ? TXT_PRESSED_DARK  : TXT_PRESSED_LIGHT;
        int txtReleasedColor = dark ? TXT_RELEASED_DARK : TXT_RELEASED_LIGHT;

        int ks     = Math.max(1, Math.round(KEY_SIZE * scale));
        int kg     = Math.max(1, Math.round(KEY_GAP  * scale));
        int stride = ks + kg;
        int hPad   = Math.max(1, Math.round(H_PAD * scale));
        int vPad   = Math.max(1, Math.round(V_PAD * scale));

        // ── Row 1: W key, centred horizontally ───────────────────────────────
        int row1Y = y + vPad;
        int wX    = x + (w - ks) / 2;
        drawKey(context, tr, wX, row1Y, ks, ARROW_W,
                KeystrokesTracker.KEY_W, tracker, now,
                bgPressedColor, bgReleasedColor, txtPressedColor, txtReleasedColor);

        // ── Row 2: A / S / D, centred horizontally ───────────────────────────
        int row2Y     = row1Y + stride;
        int threeW    = 3 * ks + 2 * kg;
        int asdStartX = x + (w - threeW) / 2;
        drawKey(context, tr, asdStartX,          row2Y, ks, ARROW_A,
                KeystrokesTracker.KEY_A, tracker, now,
                bgPressedColor, bgReleasedColor, txtPressedColor, txtReleasedColor);
        drawKey(context, tr, asdStartX + stride, row2Y, ks, ARROW_S,
                KeystrokesTracker.KEY_S, tracker, now,
                bgPressedColor, bgReleasedColor, txtPressedColor, txtReleasedColor);
        drawKey(context, tr, asdStartX + 2 * stride, row2Y, ks, ARROW_D,
                KeystrokesTracker.KEY_D, tracker, now,
                bgPressedColor, bgReleasedColor, txtPressedColor, txtReleasedColor);

        // ── Row 3: LMB (with CPS) and RMB, filling the inner width ───────────
        int row3Y  = row2Y + stride;
        int inner  = w - 2 * hPad;
        int lmbW   = (inner - kg) / 2;
        int rmbW   = inner - lmbW - kg;
        int lmbX   = x + hPad;
        int rmbX   = lmbX + lmbW + kg;

        drawKey(context, tr, lmbX, row3Y, lmbW, ks, "L",
                KeystrokesTracker.KEY_LMB, tracker, now,
                bgPressedColor, bgReleasedColor, txtPressedColor, txtReleasedColor);
        drawKey(context, tr, rmbX, row3Y, rmbW, ks, "R",
                KeystrokesTracker.KEY_RMB, tracker, now,
                bgPressedColor, bgReleasedColor, txtPressedColor, txtReleasedColor);
    }

    private void drawKey(DrawContext context, TextRenderer tr,
                         int x, int y, int w, int h, String label,
                         int keyIdx, KeystrokesTracker tracker, long now,
                         int bgPressed, int bgReleased, int txtPressed, int txtReleased) {
        int bg  = tracker.getKeyBgColor  (keyIdx, bgPressed,  bgReleased,  now);
        int txt = tracker.getKeyTextColor(keyIdx, txtPressed, txtReleased, now);
        drawKeyBox(context, tr, x, y, w, h, label, bg, txt);
    }

    private void drawKey(DrawContext context, TextRenderer tr,
                         int x, int y, int size, String label,
                         int keyIdx, KeystrokesTracker tracker, long now,
                         int bgPressed, int bgReleased, int txtPressed, int txtReleased) {
        drawKey(context, tr, x, y, size, size, label, keyIdx, tracker, now,
                bgPressed, bgReleased, txtPressed, txtReleased);
    }

    /**
     * Core drawing primitive: fills a rectangular key button with {@code bg},
     * then centres {@code label} text inside it at the appropriate font scale.
     *
     * @param x, y       top-left corner of the button
     * @param w, h       dimensions of the button in screen pixels
     * @param label      text to centre inside the button
     * @param bg         background colour (ARGB)
     * @param txt        text colour (ARGB)
     */
    private static void drawKeyBox(DrawContext context, TextRenderer tr,
                                   int x, int y, int w, int h,
                                   String label, int bg, int txt) {
        // Background fill
        context.fill(x, y, x + w, y + h, bg);

        // Scale font so the glyph occupies ~65% of the button height.
        // Minecraft's default font cap height is ≈8 px at fontScale 1.0.
        float fontScale = Math.max(MIN_FONT_SCALE, h * KEY_FONT_SCALE_RATIO);
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
}
