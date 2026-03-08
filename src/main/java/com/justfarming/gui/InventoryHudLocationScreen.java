package com.justfarming.gui;

import com.justfarming.JustFarming;
import com.justfarming.config.FarmingConfig;
import com.justfarming.render.InventoryHudRenderer;
import com.justfarming.render.ProfitHudRenderer;
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
 *   <li><b>Profit HUD</b>    – farming/pest profit panel.</li>
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

    // ── Which HUD is being dragged (0 = none, 1 = Inventory, 2 = Profit) ─────
    private static final int DRAG_NONE    = 0;
    private static final int DRAG_INV     = 1;
    private static final int DRAG_PROFIT  = 2;

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
    private int draggingHud  = DRAG_NONE;
    private int dragOffsetX;
    private int dragOffsetY;

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
        context.fill(0, 0, this.width, this.height, COL_SCREEN_DIM);

        MinecraftClient mc  = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;

        // ── Inventory HUD ──────────────────────────────────────────────────────
        if (player != null) {
            InventoryHudRenderer.renderAt(context, mc, player, invHudX, invHudY, invHudScale);
        }
        int invW = InventoryHudRenderer.getOverlayWidth(invHudScale);
        int invH = InventoryHudRenderer.getOverlayHeight(invHudScale);
        highlightHud(context, invHudX, invHudY, invW, invH, mouseX, mouseY, DRAG_INV);

        // ── Profit HUD (placeholder if no data yet) ────────────────────────────
        int profW = ProfitHudRenderer.getPanelWidth();
        int profH = ProfitHudRenderer.getApproxHeight(config.pestProfitEnabled);
        var tracker = JustFarming.getProfitTracker();
        if (tracker != null && tracker.hasData()) {
            new ProfitHudRenderer(config).render(context, tracker);
        } else {
            drawProfitHudPlaceholder(context, mc, profitHudX, profitHudY, profW, profH);
        }
        highlightHud(context, profitHudX, profitHudY, profW, profH, mouseX, mouseY, DRAG_PROFIT);

        // ── HUD labels on hover/drag ───────────────────────────────────────────
        drawHudLabel(context, mc, invHudX, invHudY, invW,
                "Inventory HUD  \u2022  Scroll to resize",
                mouseX, mouseY, DRAG_INV);
        drawHudLabel(context, mc, profitHudX, profitHudY, profW,
                "Profit HUD",
                mouseX, mouseY, DRAG_PROFIT);

        // ── Hint text at the top ───────────────────────────────────────────────
        String hint = "\u2022  Drag any HUD to reposition    \u2022  Scroll on Inventory HUD to resize  \u2022  Scale: "
                + String.format("%.1f", invHudScale);
        int hintW = mc.textRenderer.getWidth(hint);
        context.drawTextWithShadow(mc.textRenderer,
                Text.literal(hint).withColor(COL_HINT),
                (this.width - hintW) / 2, 6, COL_HINT);

        // ── Close button ───────────────────────────────────────────────────────
        boolean closeBtnHovered = mouseX >= closeBtnX && mouseX < closeBtnX + closeBtnW
                && mouseY >= closeBtnY && mouseY < closeBtnY + closeBtnH;
        int btnBg = closeBtnHovered ? COL_BTN_HOVER : COL_BTN_BG;
        context.fill(closeBtnX, closeBtnY, closeBtnX + closeBtnW, closeBtnY + closeBtnH, btnBg);
        drawBtnBorder(context, closeBtnX, closeBtnY, closeBtnW, closeBtnH);
        String btnLabel = "Done";
        int lblW = mc.textRenderer.getWidth(btnLabel);
        context.drawTextWithShadow(mc.textRenderer,
                Text.literal(btnLabel).withColor(COL_BTN_TEXT),
                closeBtnX + (closeBtnW - lblW) / 2,
                closeBtnY + (closeBtnH - 8) / 2,
                COL_BTN_TEXT);

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
                               int x, int y, int w, String label,
                               int mouseX, int mouseY, int hudId) {
        boolean hovered  = mouseX >= x && mouseX < x + w && mouseY >= y
                && mouseY < y + InventoryHudRenderer.getOverlayHeight(invHudScale);
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

    /** Draws a placeholder box representing the Profit HUD when no tracker data exists. */
    private void drawProfitHudPlaceholder(DrawContext context, MinecraftClient mc,
                                           int x, int y, int w, int h) {
        context.fill(x, y, x + w, y + h, 0xA0000000);
        int border = 0x28FFFFFF;
        context.fill(x,         y,         x + w, y + 1,     border);
        context.fill(x,         y + h - 1, x + w, y + h,     border);
        context.fill(x,         y + 1,     x + 1, y + h - 1, border);
        context.fill(x + w - 1, y + 1,     x + w, y + h - 1, border);
        String label = "Profit HUD";
        int lw  = mc.textRenderer.getWidth(label);
        int ly  = y + (h - 8) / 2;
        context.drawTextWithShadow(mc.textRenderer,
                Text.literal(label).withColor(0x88FFFFFF), x + (w - lw) / 2, ly, 0x88FFFFFF);
    }

    /** Draws a 1-pixel border around a rectangle using the button-border colour. */
    private void drawBtnBorder(DrawContext context, int x, int y, int w, int h) {
        context.fill(x,             y,             x + w, y + 1,         COL_BTN_BORDER);
        context.fill(x,             y + h - 1,     x + w, y + h,         COL_BTN_BORDER);
        context.fill(x,             y + 1,         x + 1, y + h - 1,     COL_BTN_BORDER);
        context.fill(x + w - 1,     y + 1,         x + w, y + h - 1,     COL_BTN_BORDER);
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
                return true;
            }
            // Profit HUD drag
            int profW = ProfitHudRenderer.getPanelWidth();
            int profH = ProfitHudRenderer.getApproxHeight(config.pestProfitEnabled);
            if (mx >= profitHudX && mx < profitHudX + profW
                    && my >= profitHudY && my < profitHudY + profH) {
                draggingHud = DRAG_PROFIT;
                dragOffsetX = (int) mx - profitHudX;
                dragOffsetY = (int) my - profitHudY;
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
        if (click.button() == 0) {
            if (draggingHud == DRAG_INV) {
                invHudX = Math.max(0, Math.min(this.width  - InventoryHudRenderer.getOverlayWidth(invHudScale),
                        (int) click.x() - dragOffsetX));
                invHudY = Math.max(0, Math.min(this.height - InventoryHudRenderer.getOverlayHeight(invHudScale),
                        (int) click.y() - dragOffsetY));
                return true;
            }
            if (draggingHud == DRAG_PROFIT) {
                int profW = ProfitHudRenderer.getPanelWidth();
                int profH = ProfitHudRenderer.getApproxHeight(config.pestProfitEnabled);
                profitHudX = Math.max(0, Math.min(this.width  - profW, (int) click.x() - dragOffsetX));
                profitHudY = Math.max(0, Math.min(this.height - profH, (int) click.y() - dragOffsetY));
                return true;
            }
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                  double horizontalAmount, double verticalAmount) {
        if (verticalAmount == 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        // Only resize inventory HUD when hovering over it
        int invW = InventoryHudRenderer.getOverlayWidth(invHudScale);
        int invH = InventoryHudRenderer.getOverlayHeight(invHudScale);
        if (mouseX >= invHudX && mouseX < invHudX + invW
                && mouseY >= invHudY && mouseY < invHudY + invH) {
            float d = (float) (verticalAmount > 0 ? SCALE_STEP : -SCALE_STEP);
            float newScale = Math.round((invHudScale + d) * 10f) / 10f;
            invHudScale = Math.max(SCALE_MIN, Math.min(SCALE_MAX, newScale));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
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

