package com.justfarming.render;

import com.justfarming.config.FarmingConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * Renders a paper-doll player model directly to the right of the
 * {@link InventoryHudRenderer} overlay.
 *
 * <p>The player model occupies the full height of the panel (5/5), matching
 * the height of the inventory HUD.  Keystrokes are rendered separately inside
 * the {@link ProfitHudRenderer} header area.
 *
 * <pre>
 *   ┌──────────────────────────────────────┐  ┌──────────────────┐
 *   │  [slot]…[slot]  (3-row inv grid)     │  │                  │  ↑
 *   │  [slot]…[slot]                       │  │   player model   │  5/5
 *   │  [slot]…[slot]                       │  │                  │  ↓
 *   └──────────────────────────────────────┘  └──────────────────┘
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
    private static final int PANEL_GAP = 0;

    /**
     * Returns the total width (panel + gap) in screen pixels of the paper-doll panel
     * at the given HUD scale, used by {@link ProfitHudRenderer} to compute its combined width.
     *
     * @param scale the current {@link FarmingConfig#inventoryOverlayScale}
     */
    public static int getTotalWidth(float scale) {
        float s = Math.max(0.25f, scale);
        return Math.round((PANEL_W + PANEL_GAP) * s);
    }

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

        int panelW = Math.round(PANEL_W * scale);
        int panelH = invH;   // same height as the inventory HUD

        int panelX = invHudX + invW + Math.round(PANEL_GAP * scale);
        int panelY = invHudY;

        // ── Background ────────────────────────────────────────────────────────
        int bgColor     = config.darkMode ? InventoryHudRenderer.BG_COLOR_DARK   : InventoryHudRenderer.BG_COLOR_LIGHT;
        int accentColor = config.darkMode ? InventoryHudRenderer.ACCENT_COLOR_DARK : InventoryHudRenderer.ACCENT_COLOR_LIGHT;
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, bgColor);

        // Top accent stripe (1px) – matching the inventory HUD accent.
        context.fill(panelX, panelY, panelX + panelW, panelY + 1, accentColor);

        // Bottom accent stripe – only when profit HUD is not rendered below.
        if (!config.profitTrackerEnabled) {
            context.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, accentColor);
        }

        // ── Player model (full panel height) ──────────────────────────────────
        // Entity display size: 65% of 65% (≈ 42.25%) of the full panel height, minimum 4 px.
        int entitySize = Math.max(4, (int) (panelH * 0.65f * 0.65f));

        // mouseX=0 → face toward viewer; small negative mouseY → slight upward
        // tilt so the face is visible rather than the top of the head.
        float mouseX = 0.0f;
        float mouseY = -(entitySize * 0.30f);

        InventoryScreen.drawEntity(
                context,
                panelX, panelY, panelX + panelW, panelY + panelH,
                entitySize,
                mouseX, mouseY,
                0.0f,   // tickDelta = 0 freezes entity animations (static pose)
                player);
    }
}

