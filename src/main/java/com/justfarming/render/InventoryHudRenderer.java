package com.justfarming.render;

import com.justfarming.config.FarmingConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;

/**
 * Renders the player's main inventory (rows 1–3, slots 9–35) as a HUD overlay.
 *
 * <p>The overlay displays a 9×3 grid of item slots at a configurable screen
 * position, using the same item and count rendering as Minecraft's standard GUI.
 * It is always rendered on top of all other HUD elements but remains behind
 * full-screen GUIs (chat, inventory, etc.).
 *
 * <p>Position is controlled by {@link FarmingConfig#inventoryOverlayX} and
 * {@link FarmingConfig#inventoryOverlayY}, which specify the top-left corner
 * of the overlay in screen pixels (origin at screen top-left).
 */
public class InventoryHudRenderer {

    /** Pixel size of one slot square (item icon). */
    private static final int SLOT_SIZE = 16;

    /** Pixel gap between adjacent slot icons. */
    private static final int GAP = 2;

    /** Combined stride (slot icon + gap) used for both X and Y spacing. */
    private static final int SLOT_SPACING = SLOT_SIZE + GAP;

    /** Number of item columns in the inventory grid. */
    private static final int COLS = 9;

    /** Number of item rows in the inventory grid (one row per inventory row). */
    private static final int ROWS = 3;

    /** Total rendered width of the overlay grid in pixels. */
    private static final int GRID_W = COLS * SLOT_SPACING - GAP;

    /** Total rendered height of the overlay grid in pixels. */
    private static final int GRID_H = ROWS * SLOT_SPACING - GAP;

    /** Background padding around the item grid. */
    private static final int BG_PAD = 3;

    /** Background colour (semi-transparent black). */
    private static final int BG_COLOR = 0xA0000000;

    private final FarmingConfig config;

    public InventoryHudRenderer(FarmingConfig config) {
        this.config = config;
    }

    /**
     * Called by the HUD render callback every frame.  Renders the inventory
     * overlay when {@link FarmingConfig#inventoryOverlayEnabled} is {@code true}
     * and the player is present.
     *
     * @param context the current draw context
     */
    public void render(DrawContext context) {
        if (!config.inventoryOverlayEnabled) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null) return;

        // inventoryOverlayX / inventoryOverlayY specify the top-left corner of
        // the entire overlay (including the background padding rectangle).
        int bgX = config.inventoryOverlayX;
        int bgY = config.inventoryOverlayY;

        // Draw a semi-transparent background behind the grid.
        context.fill(bgX, bgY,
                bgX + GRID_W + 2 * BG_PAD,
                bgY + GRID_H + 2 * BG_PAD,
                BG_COLOR);

        // Items are rendered inside the background, offset by BG_PAD.
        int startX = bgX + BG_PAD;
        int startY = bgY + BG_PAD;

        // Render the 27 inventory slots (rows 1–3, Minecraft slot indices 9–35).
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int slotIndex = 9 + (row * COLS) + col;
                ItemStack stack = player.getInventory().getStack(slotIndex);
                if (!stack.isEmpty()) {
                    int itemX = startX + col * SLOT_SPACING;
                    int itemY = startY + row * SLOT_SPACING;
                    context.drawItem(stack, itemX, itemY);
                    context.drawStackOverlay(mc.textRenderer, stack, itemX, itemY);
                }
            }
        }
    }
}
