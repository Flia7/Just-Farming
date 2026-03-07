package com.flia.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

/**
 * A flat, custom-rendered button that matches the dark GUI theme instead of
 * using the default Minecraft stone-button texture.
 */
public class FlatButtonWidget extends ClickableWidget {

    private static final int COL_BG_NORMAL = 0x1AFFFFFF;
    private static final int COL_BG_HOVER  = 0x33FFFFFF;
    private static final int COL_BORDER    = 0x28FFFFFF;
    private static final int COL_ACCENT    = 0xFF7C4DFF;
    private static final int COL_TEXT      = 0xF2FFFFFF;

    @FunctionalInterface
    public interface PressAction {
        void onPress(FlatButtonWidget button);
    }

    private final PressAction onPress;

    public FlatButtonWidget(int x, int y, int width, int height,
                            Text message, PressAction onPress) {
        super(x, y, width, height, message);
        this.onPress = onPress;
    }

    @Override
    public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean hov = isHovered();
        int bg = hov ? COL_BG_HOVER : COL_BG_NORMAL;
        // Background
        context.fill(x, y, x + w, y + h, bg);
        // Subtle inner bottom shadow for depth
        context.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0x18000000);
        // 1-px border
        context.fill(x,         y,         x + w,     y + 1,     COL_BORDER);
        context.fill(x,         y + h - 1, x + w,     y + h,     COL_BORDER);
        context.fill(x,         y + 1,     x + 1,     y + h - 1, COL_BORDER);
        context.fill(x + w - 1, y + 1,     x + w,     y + h - 1, COL_BORDER);
        // Left accent bar: 3 px wide when hovered, 2 px otherwise
        int accentW = hov ? 3 : 2;
        context.fill(x, y, x + accentW, y + h, COL_ACCENT);
        // Centred message
        context.drawCenteredTextWithShadow(
                MinecraftClient.getInstance().textRenderer,
                getMessage(),
                x + w / 2,
                y + (h - 8) / 2,
                COL_TEXT);
    }

    @Override
    public void onClick(net.minecraft.client.gui.Click click, boolean toggle) {
        if (onPress != null) {
            onPress.onPress(this);
        }
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
    }

    /**
     * Shared helper: draws a flat custom slider track, thumb, and centered text.
     * Used by slider inner classes in all GUI screens.
     */
    public static void renderFlatSlider(DrawContext context, int x, int y, int w, int h,
                                         double value, Text message) {
        // Background
        context.fill(x, y, x + w, y + h, 0x14FFFFFF);
        // Filled portion
        int fillW = (int) Math.round(value * w);
        if (fillW > 0) context.fill(x, y, x + fillW, y + h, 0x28FFFFFF);
        // Left accent bar (always visible, 2 px)
        context.fill(x, y, x + 2, y + h, 0xFF7C4DFF);
        // Thumb indicator (2 px wide, full height)
        int thumbX = x + Math.max(2, Math.min(fillW, w - 2));
        context.fill(thumbX - 1, y, thumbX + 1, y + h, 0xF2FFFFFF);
        // Border (top, bottom, right)
        context.fill(x,         y,         x + w, y + 1, 0x28FFFFFF);
        context.fill(x,         y + h - 1, x + w, y + h, 0x28FFFFFF);
        context.fill(x + w - 1, y,         x + w, y + h, 0x28FFFFFF);
        // Centered text
        context.drawCenteredTextWithShadow(
                MinecraftClient.getInstance().textRenderer,
                message, x + w / 2, y + (h - 8) / 2, 0xF2FFFFFF);
    }
}
