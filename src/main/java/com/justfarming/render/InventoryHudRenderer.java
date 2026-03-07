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
 * Scale is controlled by {@link FarmingConfig#inventoryOverlayScale}.
 */
public class InventoryHudRenderer {

    /** Pixel size of one slot square (item icon) at scale 1.0. */
    private static final int SLOT_SIZE = 16;

    /** Pixel gap between adjacent slot icons at scale 1.0. */
    private static final int GAP = 2;

    /** Combined stride (slot icon + gap) used for both X and Y spacing at scale 1.0. */
    private static final int SLOT_SPACING = SLOT_SIZE + GAP;

    /** Number of item columns in the inventory grid. */
    public static final int COLS = 9;

    /** Number of item rows in the inventory grid (one row per inventory row). */
    public static final int ROWS = 3;

    /** Total rendered width of the overlay grid in pixels at scale 1.0. */
    public static final int GRID_W = COLS * SLOT_SPACING - GAP;

    /** Total rendered height of the overlay grid in pixels at scale 1.0. */
    public static final int GRID_H = ROWS * SLOT_SPACING - GAP;

    /** Background padding around the item grid at scale 1.0. */
    private static final int BG_PAD = 3;

    /** Background colour (semi-transparent black) – also used by the paper-doll panel. */
    public static final int BG_COLOR = 0xA0000000;

    /**
     * Slot background colour drawn behind each inventory slot to create a
     * visible grid of empty-slot squares.  Slightly lighter than
     * {@link #BG_COLOR} so individual slots are distinguishable even when empty.
     */
    private static final int SLOT_BG_COLOR = 0x50000000;

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

        renderAt(context, mc, player, config.inventoryOverlayX, config.inventoryOverlayY,
                config.inventoryOverlayScale);
    }

    /**
     * Renders the inventory overlay at the given position and scale.
     * Used both by {@link #render} and by {@link com.justfarming.gui.InventoryHudLocationScreen}.
     */
    public static void renderAt(DrawContext context, MinecraftClient mc,
                                 ClientPlayerEntity player, int bgX, int bgY, float scaleIn) {
        float scale = Math.max(0.25f, scaleIn);

        int scaledGridW  = Math.round(GRID_W  * scale);
        int scaledGridH  = Math.round(GRID_H  * scale);
        int scaledBgPad  = Math.max(1, Math.round(BG_PAD * scale));

        // Draw a semi-transparent background behind the grid.
        context.fill(bgX, bgY,
                bgX + scaledGridW + 2 * scaledBgPad,
                bgY + scaledGridH + 2 * scaledBgPad,
                BG_COLOR);

        // Items are rendered inside the background, offset by scaledBgPad.
        int startX = bgX + scaledBgPad;
        int startY = bgY + scaledBgPad;

        int scaledSlotSpacing = Math.round(SLOT_SPACING * scale);

        // Render the 27 inventory slots (rows 1–3, Minecraft slot indices 9–35).
        // Apply scale via the Matrix3x2fStack so item icons and count labels scale correctly.
        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(startX, startY);
        matrices.scale(scale, scale);
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int slotIndex = 9 + (row * COLS) + col;
                // Positions are in unscaled slot space (scale is applied via matrix)
                int itemX = col * SLOT_SPACING;
                int itemY = row * SLOT_SPACING;
                // Draw a dark slot background behind every slot (even empty ones)
                // so the inventory grid is always visible.
                context.fill(itemX, itemY, itemX + SLOT_SIZE, itemY + SLOT_SIZE, SLOT_BG_COLOR);
                ItemStack stack = player.getInventory().getStack(slotIndex);
                if (!stack.isEmpty()) {
                    context.drawItem(stack, itemX, itemY);
                    context.drawStackOverlay(mc.textRenderer, stack, itemX, itemY);
                }
            }
        }
        matrices.popMatrix();
    }

    /**
     * Returns the total rendered width of the inventory overlay (background included)
     * at the given scale, in screen pixels.
     */
    public static int getOverlayWidth(float scale) {
        float s = Math.max(0.25f, scale);
        int scaledBgPad = Math.max(1, Math.round(BG_PAD * s));
        return Math.round(GRID_W * s) + 2 * scaledBgPad;
    }

    /**
     * Returns the total rendered height of the inventory overlay (background included)
     * at the given scale, in screen pixels.
     */
    public static int getOverlayHeight(float scale) {
        float s = Math.max(0.25f, scale);
        int scaledBgPad = Math.max(1, Math.round(BG_PAD * s));
        return Math.round(GRID_H * s) + 2 * scaledBgPad;
    }
}
