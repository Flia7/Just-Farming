package com.justfarming.gui;

import com.justfarming.CropType;
import com.justfarming.config.FarmingConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * A modal screen that presents every available crop as a clickable button.
 * Selecting a crop saves it to {@link FarmingConfig#selectedCrop} and
 * returns to the parent screen.
 */
public class CropSelectScreen extends Screen {

    // ── Crop list ─────────────────────────────────────────────────────────────
    private static final CropType[] CROPS = {
        CropType.COCOA_BEANS,      CropType.MUSHROOM,         CropType.CACTUS,
        CropType.POTATO_S_SHAPE,   CropType.NETHER_WART_S_SHAPE, CropType.CARROT_S_SHAPE,
        CropType.WHEAT_S_SHAPE,    CropType.PUMPKIN_S_SHAPE,
        CropType.MELON_S_SHAPE,    CropType.SUGAR_CANE_S_SHAPE,
        CropType.MOONFLOWER_S_SHAPE, CropType.SUNFLOWER_S_SHAPE,
        CropType.WILD_ROSE_S_SHAPE
    };

    // ── Colour palette (matches FarmingConfigScreen new style) ────────────────
    private static final int COL_SCREEN_DIM  = 0x60000000;
    private static final int COL_WIN_BG      = 0xBF000000;
    private static final int COL_BORDER      = 0x28FFFFFF;
    private static final int COL_SEP         = 0x14FFFFFF;
    private static final int COL_TEXT        = 0xF2FFFFFF;
    private static final int COL_ACCENT      = 0xFF7C4DFF;
    private static final int COL_SHADOW      = 0x60000000;
    private static final int COL_SELECTED_HL = 0x3080FF80;

    // ── Natural panel dimensions ───────────────────────────────────────────────
    private static final int PANEL_WIDTH   = 280;
    private static final int HEADER_HEIGHT = 28;
    private static final int BUTTON_HEIGHT = 20;
    private static final int PADDING       = 5;

    private final Screen        parent;
    private final FarmingConfig config;

    private int panelX, panelY, panelW, panelH;
    private final ButtonWidget[] cropButtons = new ButtonWidget[CROPS.length];

    public CropSelectScreen(Screen parent, FarmingConfig config) {
        super(Text.literal("Select Crop"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        int naturalH = HEADER_HEIGHT + PADDING
                + CROPS.length * (BUTTON_HEIGHT + PADDING)
                + BUTTON_HEIGHT + PADDING;

        panelW = Math.min(PANEL_WIDTH, this.width  - 10);
        panelH = Math.min(naturalH,    this.height - 10);
        panelX = (this.width  - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        int bw = panelW - 2 * PADDING - 4;
        int bh = BUTTON_HEIGHT;
        int wx = panelX + PADDING + 2;
        int y  = panelY + HEADER_HEIGHT + PADDING;

        for (int i = 0; i < CROPS.length; i++) {
            final CropType crop = CROPS[i];
            String label = Text.translatable(crop.getTranslationKey()).getString()
                    + "  (Speed: " + crop.getRecommendedSpeed() + ")";
            cropButtons[i] = ButtonWidget.builder(
                    Text.literal(label),
                    btn -> {
                        config.selectedCrop = crop;
                        config.save();
                        if (this.client != null) this.client.setScreen(parent);
                    })
                    .dimensions(wx, y, bw, bh)
                    .build();
            this.addDrawableChild(cropButtons[i]);
            y += bh + PADDING;
        }

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Cancel"),
                btn -> { if (this.client != null) this.client.setScreen(parent); })
                .dimensions(wx, panelY + panelH - bh - PADDING, bw, bh)
                .build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int panelR = panelX + panelW;
        int panelB = panelY + panelH;

        // Full-screen dim
        context.fill(0, 0, this.width, this.height, COL_SCREEN_DIM);
        // Drop shadow
        context.fill(panelX + 4, panelY + 4, panelR + 4, panelB + 4, COL_SHADOW);
        // Border
        context.fill(panelX - 1, panelY - 1, panelR + 1, panelB + 1, COL_BORDER);
        // Panel body
        context.fill(panelX, panelY, panelR, panelB, COL_WIN_BG);
        // Header accent bar (left edge of header)
        context.fill(panelX, panelY, panelX + 3, panelY + HEADER_HEIGHT, COL_ACCENT);
        // Header separator
        context.fill(panelX + 3, panelY + HEADER_HEIGHT - 1,
                panelR, panelY + HEADER_HEIGHT, COL_SEP);
        // Title
        context.drawTextWithShadow(this.textRenderer,
                this.title.copy().withColor(COL_TEXT),
                panelX + 10, panelY + (HEADER_HEIGHT - 8) / 2, COL_TEXT);

        // Highlight selected crop
        for (int i = 0; i < CROPS.length; i++) {
            if (CROPS[i] == config.selectedCrop) {
                ButtonWidget b = cropButtons[i];
                context.fill(b.getX() - 1, b.getY() - 1,
                        b.getX() + b.getWidth() + 1, b.getY() + b.getHeight() + 1,
                        COL_SELECTED_HL);
            }
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() { return false; }
}
