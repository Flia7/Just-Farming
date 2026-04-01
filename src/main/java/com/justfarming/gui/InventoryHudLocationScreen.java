package com.justfarming.gui;

import com.justfarming.config.FarmingConfig;
import com.justfarming.render.InventoryHudRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;

/**
 * Fullscreen overlay that lets the player drag all Just Farming HUDs to any
 * screen position. The Inventory HUD can also be resized via the scroll wheel.
 *
 * <p>Draggable HUDs:
 * <ul>
 *   <li><b>Inventory HUD</b> – 9×3 inventory grid; scroll to resize.</li>
 * </ul>
 *
 * <p>Opening this screen temporarily hides the Just Farming config GUI.
 * Pressing Escape or the on-screen close button saves every position/scale
 * change and re-opens the previous screen.
 */
public class InventoryHudLocationScreen extends Screen {

    // ── Colours ───────────────────────────────────────────────────────────────
    private static final int COL_SCREEN_DIM    = 0x40000000;
    private static final int COL_HINT          = 0xB0FFFFFF;
    private static final int COL_BTN_BG        = 0xBF000000;
    private static final int COL_BTN_HOVER     = 0xCC333333;
    private static final int COL_BTN_BORDER    = 0x60FFFFFF;
    private static final int COL_BTN_TEXT      = 0xF2FFFFFF;
    /** Highlight outline drawn while hovering over a draggable HUD. */
    private static final int COL_HUD_HOVER     = 0x4050A8E0;  // translucent sky-blue
    /** Outline drawn while actively dragging a HUD. */
    private static final int COL_HUD_DRAG      = 0x6050A8E0;  // stronger blue

    // ── Inventory HUD scale limits ────────────────────────────────────────────
    private static final float SCALE_MIN  = 0.5f;
    private static final float SCALE_MAX  = 3.0f;
    private static final float SCALE_STEP = 0.1f;

    // ── Which HUD is being dragged (0 = none, 1 = inventory HUD) ──────────────
    private static final int DRAG_NONE    = 0;
    private static final int DRAG_INV     = 1;

    private final Screen parent;
    private final FarmingConfig config;

    // ── Inventory HUD state ───────────────────────────────────────────────────
    private int   invHudX;
    private int   invHudY;
    private float invHudScale;

    // ── Profit HUD state ──────────────────────────────────────────────────────
    private int profitHudX;
    private int profitHudY;

    // ── Drag state ────────────────────────────────────────────────────────────
    /** Which HUD was originally clicked to start the drag; used for the visual highlight only. */
    private int draggingHud  = DRAG_NONE;
    /** Mouse X at the drag start, relative to the HUD that was clicked. */
    private int dragOffsetX;
    private int dragOffsetY;
    /**
     * Positions of all HUDs at drag start.  When dragging, every HUD moves
     * by the same delta so they all stay in their relative positions.
     */
    private int dragStartInvX, dragStartInvY;
    private int dragStartProfitX, dragStartProfitY;
    private int dragStartMouseX, dragStartMouseY;

    // ── Close-button geometry ─────────────────────────────────────────────────
    private int closeBtnX, closeBtnY, closeBtnW, closeBtnH;

    public InventoryHudLocationScreen(Screen parent, FarmingConfig config) {
        super(Text.literal("Edit HUD"));
        this.parent      = parent;
        this.config      = config;
        this.invHudX     = config.inventoryOverlayX;
        this.invHudY     = config.inventoryOverlayY;
        this.invHudScale = Math.max(SCALE_MIN, Math.min(SCALE_MAX, config.inventoryOverlayScale));
        this.profitHudX  = config.profitHudX;
        this.profitHudY  = config.profitHudY;
        GuiTheme.activate(config);
    }

    @Override
    protected void init() {
        closeBtnW = 120;
        closeBtnH = 20;
        closeBtnX = (this.width  - closeBtnW) / 2;
        closeBtnY = this.height - closeBtnH - 10;
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        GuiTheme t = GuiTheme.current;
        context.fill(0, 0, this.width, this.height, t.SCREEN_DIM);

        MinecraftClient mc  = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;

        // ── Inventory HUD ──────────────────────────────────────────────────────
        if (player != null) {
            InventoryHudRenderer.renderAt(context, mc, player, invHudX, invHudY, invHudScale);
        }
        int invW = InventoryHudRenderer.getOverlayWidth(invHudScale);
        int invH = InventoryHudRenderer.getOverlayHeight(invHudScale);
        highlightHud(context, invHudX, invHudY, invW, invH, mouseX, mouseY, DRAG_INV);

        // ── HUD labels on hover/drag ───────────────────────────────────────────
        drawHudLabel(context, mc, invHudX, invHudY, invW, invH,
                "Inventory HUD  \u2022  Drag to move all",
                mouseX, mouseY, DRAG_INV);

        // ── Hint text at the top ───────────────────────────────────────────────
        String hint = "\u2022  Drag any HUD to move all    \u2022  Scroll over a HUD to resize  \u2022  Scale: "
                + String.format("%.1f", invHudScale);
        int hintW = mc.textRenderer.getWidth(hint);
        context.drawTextWithShadow(mc.textRenderer,
                Text.literal(hint).withColor(t.TEXT),
                (this.width - hintW) / 2, 6, t.TEXT);

        // ── Close button ───────────────────────────────────────────────────────
        boolean closeBtnHovered = mouseX >= closeBtnX && mouseX < closeBtnX + closeBtnW
                && mouseY >= closeBtnY && mouseY < closeBtnY + closeBtnH;
        int btnBg = closeBtnHovered ? t.BTN_BG_HOVER : t.WIN_BG;
        context.fill(closeBtnX, closeBtnY, closeBtnX + closeBtnW, closeBtnY + closeBtnH, btnBg);
        drawBtnBorder(context, closeBtnX, closeBtnY, closeBtnW, closeBtnH, t.BORDER);
        String btnLabel = "Done";
        int lblW = mc.textRenderer.getWidth(btnLabel);
        context.drawTextWithShadow(mc.textRenderer,
                Text.literal(btnLabel).withColor(t.TEXT),
                closeBtnX + (closeBtnW - lblW) / 2,
                closeBtnY + (closeBtnH - 8) / 2,
                t.TEXT);

        super.render(context, mouseX, mouseY, delta);
    }

    /** Draws a highlight border over a HUD when it is hovered or being dragged. */
    private void highlightHud(DrawContext context, int x, int y, int w, int h,
                               int mouseX, int mouseY, int hudId) {
        boolean hovered  = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        boolean dragging = draggingHud == hudId;
        if (!hovered && !dragging) return;
        int col = dragging ? COL_HUD_DRAG : COL_HUD_HOVER;
        // Fill interior
        context.fill(x, y, x + w, y + h, col);
        // 1-px white border
        int border = 0x80FFFFFF;
        context.fill(x,         y,         x + w, y + 1,     border);
        context.fill(x,         y + h - 1, x + w, y + h,     border);
        context.fill(x,         y + 1,     x + 1, y + h - 1, border);
        context.fill(x + w - 1, y + 1,     x + w, y + h - 1, border);
    }

    /** Draws a small label above a HUD when it is hovered or being dragged. */
    private void drawHudLabel(DrawContext context, MinecraftClient mc,
                               int x, int y, int w, int h, String label,
                               int mouseX, int mouseY, int hudId) {
        boolean hovered  = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        boolean dragging = draggingHud == hudId;
        if (!hovered && !dragging) return;
        int lw = mc.textRenderer.getWidth(label);
        int lx = x + (w - lw) / 2;
        int ly = Math.max(0, y - 10);
        // shadow background
        context.fill(lx - 2, ly - 1, lx + lw + 2, ly + 9, 0xA0000000);
        context.drawTextWithShadow(mc.textRenderer,
                Text.literal(label).withColor(0xFFFFFFFF), lx, ly, 0xFFFFFFFF);
    }

    /** Draws a 1-pixel border around a rectangle using the given colour. */
    private void drawBtnBorder(DrawContext context, int x, int y, int w, int h, int col) {
        context.fill(x,             y,             x + w, y + 1,         col);
        context.fill(x,             y + h - 1,     x + w, y + h,         col);
        context.fill(x,             y + 1,         x + 1, y + h - 1,     col);
        context.fill(x + w - 1,     y + 1,         x + w, y + h - 1,     col);
    }

    // ── Input handling ────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(Click click, boolean toggle) {
        double mx = click.x();
        double my = click.y();
        if (click.button() == 0) {
            // Close button
            if (mx >= closeBtnX && mx < closeBtnX + closeBtnW
                    && my >= closeBtnY && my < closeBtnY + closeBtnH) {
                close();
                return true;
            }
            // Inventory HUD drag – check first (higher priority if HUDs overlap)
            int invW = InventoryHudRenderer.getOverlayWidth(invHudScale);
            int invH = InventoryHudRenderer.getOverlayHeight(invHudScale);
            if (mx >= invHudX && mx < invHudX + invW
                    && my >= invHudY && my < invHudY + invH) {
                draggingHud = DRAG_INV;
                dragOffsetX = (int) mx - invHudX;
                dragOffsetY = (int) my - invHudY;
                // Record all HUD positions at drag start for group movement.
                dragStartInvX    = invHudX;    dragStartInvY    = invHudY;
                dragStartProfitX = profitHudX; dragStartProfitY = profitHudY;
                dragStartMouseX  = (int) mx;   dragStartMouseY  = (int) my;
                return true;
            }
        }
        return super.mouseClicked(click, toggle);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (click.button() == 0 && draggingHud != DRAG_NONE) {
            draggingHud = DRAG_NONE;
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (click.button() == 0 && draggingHud != DRAG_NONE) {
            // Compute the delta from the drag-start mouse position.
            int dx = (int) click.x() - dragStartMouseX;
            int dy = (int) click.y() - dragStartMouseY;

            // Move ALL HUDs by the same delta so they stay together as a group.
            int invW  = InventoryHudRenderer.getOverlayWidth(invHudScale);
            int invH  = InventoryHudRenderer.getOverlayHeight(invHudScale);
            invHudX    = Math.max(0, Math.min(this.width  - invW,  dragStartInvX    + dx));
            invHudY    = Math.max(0, Math.min(this.height - invH,  dragStartInvY    + dy));
            profitHudX = dragStartProfitX + dx;
            profitHudY = dragStartProfitY + dy;
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                  double horizontalAmount, double verticalAmount) {
        if (verticalAmount == 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        // Only rescale when the cursor is over a draggable HUD element.
        int invW  = InventoryHudRenderer.getOverlayWidth(invHudScale);
        int invH  = InventoryHudRenderer.getOverlayHeight(invHudScale);
        boolean overInv    = mouseX >= invHudX    && mouseX < invHudX    + invW
                          && mouseY >= invHudY    && mouseY < invHudY    + invH;
        if (!overInv) return false;
        float d = (float) (verticalAmount > 0 ? SCALE_STEP : -SCALE_STEP);
        float newScale = Math.round((invHudScale + d) * 10f) / 10f;
        invHudScale = Math.max(SCALE_MIN, Math.min(SCALE_MAX, newScale));
        return true;
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    @Override
    public void close() {
        config.inventoryOverlayX     = invHudX;
        config.inventoryOverlayY     = invHudY;
        config.inventoryOverlayScale = invHudScale;
        config.profitHudX            = profitHudX;
        config.profitHudY            = profitHudY;
        config.save();
        if (this.client != null) this.client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }
}
