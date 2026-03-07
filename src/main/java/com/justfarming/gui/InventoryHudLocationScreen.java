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
 * Fullscreen overlay that lets the player drag the Inventory HUD to any screen
 * position and scroll to resize it.
 *
 * <p>Opening this screen temporarily hides the Just Farming config GUI.
 * Pressing Escape or the on-screen close button saves the new position/scale
 * and re-opens the previous screen.
 */
public class InventoryHudLocationScreen extends Screen {

    private static final int COL_SCREEN_DIM  = 0x40000000;
    private static final int COL_HINT        = 0xB0FFFFFF;
    private static final int COL_BTN_BG      = 0xBF000000;
    private static final int COL_BTN_HOVER   = 0xCC333333;
    private static final int COL_BTN_BORDER  = 0x60FFFFFF;
    private static final int COL_BTN_TEXT    = 0xF2FFFFFF;

    /** Minimum scale the user can set via scroll. */
    private static final float SCALE_MIN  = 0.5f;
    /** Maximum scale the user can set via scroll. */
    private static final float SCALE_MAX  = 3.0f;
    /** Amount added/subtracted per scroll notch. */
    private static final float SCALE_STEP = 0.1f;

    private final Screen parent;
    private final FarmingConfig config;

    /** Current HUD top-left X (pixels). */
    private int hudX;
    /** Current HUD top-left Y (pixels). */
    private int hudY;
    /** Current HUD scale. */
    private float hudScale;

    /** Whether the user is currently dragging the HUD. */
    private boolean dragging = false;
    /** Mouse X at the start of the drag relative to {@link #hudX}. */
    private int dragOffsetX;
    /** Mouse Y at the start of the drag relative to {@link #hudY}. */
    private int dragOffsetY;

    /** Close-button geometry (filled once in init). */
    private int closeBtnX, closeBtnY, closeBtnW, closeBtnH;

    public InventoryHudLocationScreen(Screen parent, FarmingConfig config) {
        super(Text.literal("Inventory HUD Location"));
        this.parent   = parent;
        this.config   = config;
        this.hudX     = config.inventoryOverlayX;
        this.hudY     = config.inventoryOverlayY;
        this.hudScale = Math.max(SCALE_MIN, Math.min(SCALE_MAX, config.inventoryOverlayScale));
    }

    @Override
    protected void init() {
        // Close button: 100 px wide, 20 px tall, centred at the bottom
        closeBtnW = 100;
        closeBtnH = 20;
        closeBtnX = (this.width  - closeBtnW) / 2;
        closeBtnY = this.height - closeBtnH - 10;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Full-screen dim
        context.fill(0, 0, this.width, this.height, COL_SCREEN_DIM);

        // Draw the inventory HUD preview
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player != null) {
            InventoryHudRenderer.renderAt(context, mc, player, hudX, hudY, hudScale);
        }

        // Hint text
        String hint = "Drag to move  \u2022  Scroll to resize  \u2022  Scale: " +
                String.format("%.1f", hudScale);
        int hintW = mc.textRenderer.getWidth(hint);
        context.drawTextWithShadow(mc.textRenderer,
                Text.literal(hint).withColor(COL_HINT),
                (this.width - hintW) / 2, 8, COL_HINT);

        // Close button
        boolean closeBtnHovered = mouseX >= closeBtnX && mouseX < closeBtnX + closeBtnW
                && mouseY >= closeBtnY && mouseY < closeBtnY + closeBtnH;
        int btnBg = closeBtnHovered ? COL_BTN_HOVER : COL_BTN_BG;
        context.fill(closeBtnX, closeBtnY, closeBtnX + closeBtnW, closeBtnY + closeBtnH, btnBg);
        context.fill(closeBtnX,                  closeBtnY,                  closeBtnX + closeBtnW, closeBtnY + 1,                COL_BTN_BORDER);
        context.fill(closeBtnX,                  closeBtnY + closeBtnH - 1,  closeBtnX + closeBtnW, closeBtnY + closeBtnH,        COL_BTN_BORDER);
        context.fill(closeBtnX,                  closeBtnY + 1,              closeBtnX + 1,          closeBtnY + closeBtnH - 1,   COL_BTN_BORDER);
        context.fill(closeBtnX + closeBtnW - 1,  closeBtnY + 1,              closeBtnX + closeBtnW,  closeBtnY + closeBtnH - 1,   COL_BTN_BORDER);
        String btnLabel = "Close";
        int lblW = mc.textRenderer.getWidth(btnLabel);
        context.drawTextWithShadow(mc.textRenderer,
                Text.literal(btnLabel).withColor(COL_BTN_TEXT),
                closeBtnX + (closeBtnW - lblW) / 2,
                closeBtnY + (closeBtnH - 8) / 2,
                COL_BTN_TEXT);

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(Click click, boolean toggle) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button    = click.button();
        if (button == 0) {
            // Close button
            if (mouseX >= closeBtnX && mouseX < closeBtnX + closeBtnW
                    && mouseY >= closeBtnY && mouseY < closeBtnY + closeBtnH) {
                close();
                return true;
            }
            // Start drag if clicking on the HUD
            int overlayW = InventoryHudRenderer.getOverlayWidth(hudScale);
            int overlayH = InventoryHudRenderer.getOverlayHeight(hudScale);
            if (mouseX >= hudX && mouseX < hudX + overlayW
                    && mouseY >= hudY && mouseY < hudY + overlayH) {
                dragging     = true;
                dragOffsetX  = (int) mouseX - hudX;
                dragOffsetY  = (int) mouseY - hudY;
                return true;
            }
        }
        return super.mouseClicked(click, toggle);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (click.button() == 0 && dragging) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (dragging && click.button() == 0) {
            hudX = Math.max(0, Math.min(this.width  - InventoryHudRenderer.getOverlayWidth(hudScale),
                    (int) click.x() - dragOffsetX));
            hudY = Math.max(0, Math.min(this.height - InventoryHudRenderer.getOverlayHeight(hudScale),
                    (int) click.y() - dragOffsetY));
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                  double horizontalAmount, double verticalAmount) {
        if (verticalAmount == 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        // Scroll up = larger, scroll down = smaller
        float delta = (float) (verticalAmount > 0 ? SCALE_STEP : -SCALE_STEP);
        float newScale = Math.round((hudScale + delta) * 10f) / 10f; // round to 1 decimal
        hudScale = Math.max(SCALE_MIN, Math.min(SCALE_MAX, newScale));
        return true;
    }

    @Override
    public void close() {
        // Save new position and scale back to config
        config.inventoryOverlayX     = hudX;
        config.inventoryOverlayY     = hudY;
        config.inventoryOverlayScale = hudScale;
        config.save();
        if (this.client != null) this.client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }
}
