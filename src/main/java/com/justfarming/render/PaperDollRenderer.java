package com.justfarming.render;

import com.justfarming.config.FarmingConfig;
import com.justfarming.input.KeystrokesTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * Renders a paper-doll player model and a compact WASD + CPS keystrokes display
 * directly to the right of the {@link InventoryHudRenderer} overlay.
 *
 * <p>The panel shares the same background colour and exact height as the
 * inventory HUD so the two widgets appear as a single cohesive unit.  The upper
 * ~65 % of the panel shows the player model (via
 * {@link InventoryScreen#drawEntity}) and the lower ~35 % shows the keystrokes
 * display:
 *
 * <pre>
 *   ┌─────────────────────────────────────────┐ ┌────────────┐
 *   │  [slot][slot]…[slot]  (inventory grid)  │ │  (player)  │
 *   │  [slot][slot]…[slot]                    │ │  [↑][CPS]  │
 *   │  [slot][slot]…[slot]                    │ │ [←][↓][→]  │
 *   └─────────────────────────────────────────┘ └────────────┘
 * </pre>
 *
 * <p>Active WASD keys are drawn with a bright background; inactive keys use a
 * dim background.  The CPS counter shows the number of left-click events in the
 * last second as reported by {@link KeystrokesTracker}.
 */
public class PaperDollRenderer {

    // ── Panel dimensions (at scale 1.0) ─────────────────────────────────────

    /**
     * Width (px) of the paper-doll panel at scale 1.0.
     * Large enough to accommodate a readable player model and three key buttons
     * side-by-side in the keystrokes row.
     */
    private static final int PANEL_W = 46;

    /**
     * Gap (px) between the right edge of the inventory HUD and the left edge of
     * the paper-doll panel at scale 1.0.
     */
    private static final int PANEL_GAP = 2;

    // ── Key-button dimensions (at scale 1.0) ─────────────────────────────────

    /** Width and height (px) of each key button square at scale 1.0. */
    private static final int KEY_SIZE = 7;

    /** Horizontal gap (px) between adjacent key buttons at scale 1.0. */
    private static final int KEY_GAP = 2;

    /** Height (px) of the full keystrokes section at the bottom of the panel. */
    private static final int KS_HEIGHT = 20;

    // ── Colours ──────────────────────────────────────────────────────────────

    private static final int KEY_BG_ACTIVE   = 0xC0FFFFFF; // pressed key – light
    private static final int KEY_BG_INACTIVE = 0x30FFFFFF; // released key – dim
    private static final int KEY_TXT_ACTIVE  = 0xFF000000; // black text on light bg
    private static final int KEY_TXT_INACTIVE= 0x80FFFFFF; // dim white text on dark bg
    private static final int CPS_COLOR       = 0xFFFFFFFF; // CPS number – white

    // ── Font / text scaling constants ────────────────────────────────────────

    /** Minimum font scale for the CPS counter text (prevents unreadably tiny text). */
    private static final float MIN_CPS_TEXT_SCALE   = 0.4f;
    /** CPS text scale as a fraction of the HUD scale (makes text proportional to panel size). */
    private static final float CPS_TEXT_SCALE_RATIO = 0.55f;

    /** Minimum font scale for arrow labels inside key buttons. */
    private static final float MIN_FONT_SCALE   = 0.3f;
    /**
     * Font scale as a fraction of the key-button size so the arrow character
     * fills roughly 80 % of the button area at any HUD scale.
     * (Minecraft's default glyph is ~5–6 px at scale 1; a ratio of 0.14 × key
     * size gives approximately that size for the default 7-px button.)
     */
    private static final float FONT_SCALE_RATIO = 0.14f;

    private static final String ARROW_UP    = "↑";
    private static final String ARROW_LEFT  = "←";
    private static final String ARROW_DOWN  = "↓";
    private static final String ARROW_RIGHT = "→";

    // ── State ────────────────────────────────────────────────────────────────

    private final FarmingConfig config;

    public PaperDollRenderer(FarmingConfig config) {
        this.config = config;
    }

    /**
     * Called by the HUD render callback.  Renders the paper-doll panel to the
     * right of the inventory HUD when {@link FarmingConfig#paperDollEnabled}
     * and {@link FarmingConfig#inventoryOverlayEnabled} are both {@code true}.
     *
     * @param context     the current draw context
     * @param invHudX     X pixel position of the inventory HUD's top-left corner
     * @param invHudY     Y pixel position of the inventory HUD's top-left corner
     * @param invScale    the scale multiplier in use for the inventory HUD
     */
    public void render(DrawContext context, int invHudX, int invHudY, float invScale) {
        if (!config.inventoryOverlayEnabled || !config.paperDollEnabled) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null) return;

        float scale = Math.max(0.25f, invScale);

        int invW  = InventoryHudRenderer.getOverlayWidth(scale);
        int invH  = InventoryHudRenderer.getOverlayHeight(scale);

        // Pixel dimensions of the panel at current scale.
        int panelW = Math.round(PANEL_W * scale);
        int panelH = invH; // same height as inventory HUD

        int panelX = invHudX + invW + Math.round(PANEL_GAP * scale);
        int panelY = invHudY;

        // ── Background ────────────────────────────────────────────────────────
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH,
                InventoryHudRenderer.BG_COLOR);

        // ── Player model ─────────────────────────────────────────────────────
        int ksH     = Math.round(KS_HEIGHT * scale);
        int modelH  = panelH - ksH;

        // Entity size is chosen so the model fits within the model area with a
        // small vertical margin.  The InventoryScreen clipping ensures it never
        // bleeds outside the model area.
        int entitySize = Math.max(4, (int) (modelH * 0.70f));

        // Slight upward look angle so the face is visible rather than the top
        // of the head.  mouseX=0 → face directly toward viewer; mouseY=-N → head
        // tilted slightly backward (standard paper-doll convention).
        float mouseX = 0.0f;
        float mouseY = -(entitySize * 0.25f);

        InventoryScreen.drawEntity(context,
                panelX, panelY,
                panelX + panelW, panelY + modelH,
                entitySize,
                mouseX, mouseY,
                0.0f,   // tickDelta – 0 freezes entity animations for a static display
                player);

        // ── Keystrokes section ────────────────────────────────────────────────
        int ksY = panelY + modelH;
        renderKeystrokes(context, mc, player, panelX, ksY, panelW, ksH, scale);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Renders the WASD arrows and CPS counter inside the keystrokes area of the
     * paper-doll panel.
     *
     * <p>Layout (at scale 1.0, KS_HEIGHT=20px):
     * <pre>
     *   y+2  [ ↑ ]  &lt;cps&gt;
     *   y+11 [←][↓][→]
     * </pre>
     */
    private void renderKeystrokes(DrawContext context, MinecraftClient mc,
                                   ClientPlayerEntity player,
                                   int x, int y, int w, int h, float scale) {
        KeystrokesTracker tracker = KeystrokesTracker.getInstance();
        TextRenderer tr = mc.textRenderer;

        int ks   = Math.max(1, Math.round(KEY_SIZE * scale)); // key button size
        int kg   = Math.max(1, Math.round(KEY_GAP  * scale)); // key gap

        int pad  = Math.max(1, Math.round(2 * scale)); // padding around keys

        // ── Row 1: W(↑) key + CPS counter ────────────────────────────────────
        // W key centred horizontally in the left half of the panel.
        int row1Y = y + pad;

        int wKeyX = x + pad;
        boolean wPressed = tracker.isForwardPressed(mc);
        drawKey(context, tr, wKeyX, row1Y, ks, ARROW_UP, wPressed, scale);

        // CPS counter to the right of the W key.
        int cps = tracker.getAttackCps();
        String cpsStr = String.valueOf(cps);
        int cpsX = wKeyX + ks + Math.round(3 * scale);
        int cpsY = row1Y + (ks - 6) / 2; // vertically centred within key height (font height ≈ 6)
        context.getMatrices().pushMatrix();
        float txtScale = Math.max(MIN_CPS_TEXT_SCALE, scale * CPS_TEXT_SCALE_RATIO);
        context.getMatrices().translate(cpsX, cpsY);
        context.getMatrices().scale(txtScale, txtScale);
        context.drawText(tr, cpsStr, 0, 0, CPS_COLOR, true);
        context.getMatrices().popMatrix();

        // ── Row 2: A(←) S(↓) D(→) ────────────────────────────────────────────
        int row2Y = row1Y + ks + kg;

        // Three keys centred horizontally inside the panel.
        int rowW  = 3 * ks + 2 * kg;
        int row2X = x + (w - rowW) / 2;

        boolean aPressed = tracker.isLeftPressed(mc);
        boolean sPressed = tracker.isBackPressed(mc);
        boolean dPressed = tracker.isRightPressed(mc);

        drawKey(context, tr, row2X,              row2Y, ks, ARROW_LEFT,  aPressed, scale);
        drawKey(context, tr, row2X + ks + kg,    row2Y, ks, ARROW_DOWN,  sPressed, scale);
        drawKey(context, tr, row2X + 2*(ks + kg),row2Y, ks, ARROW_RIGHT, dPressed, scale);
    }

    /**
     * Draws a single key button square with an arrow symbol centred inside it.
     *
     * @param context  draw context
     * @param tr       text renderer
     * @param x        left pixel of the key
     * @param y        top pixel of the key
     * @param size     side length of the key square in pixels
     * @param label    arrow character to draw (↑ ← ↓ →)
     * @param active   {@code true} if the key is currently pressed
     * @param scale    current HUD scale (for font scaling)
     */
    private void drawKey(DrawContext context, TextRenderer tr,
                         int x, int y, int size,
                         String label, boolean active, float scale) {
        int bgColor  = active ? KEY_BG_ACTIVE  : KEY_BG_INACTIVE;
        int txtColor = active ? KEY_TXT_ACTIVE : KEY_TXT_INACTIVE;

        // Key background
        context.fill(x, y, x + size, y + size, bgColor);

        // Arrow symbol – scale font so it fits within the key square.
        // A Minecraft glyph is ~5-6 px tall at scale 1.0; we target ~80 % of key size.
        float fontScale = Math.max(MIN_FONT_SCALE, size * FONT_SCALE_RATIO);
        int glyphW = Math.round(tr.getWidth(label) * fontScale);
        int glyphH = Math.round(6 * fontScale);
        int txtX = x + (size - glyphW) / 2;
        int txtY = y + (size - glyphH) / 2;

        context.getMatrices().pushMatrix();
        context.getMatrices().translate(txtX, txtY);
        context.getMatrices().scale(fontScale, fontScale);
        context.drawText(tr, label, 0, 0, txtColor, false);
        context.getMatrices().popMatrix();
    }
}
