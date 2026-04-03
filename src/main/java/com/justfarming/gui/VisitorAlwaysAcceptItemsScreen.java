package com.justfarming.gui;

import com.justfarming.config.FarmingConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * A modal screen that lists known visitor reward items.
 * Clicking an item toggles whether it should always be accepted by the
 * visitor routine even when the visitor exceeds the configured max price.
 */
public class VisitorAlwaysAcceptItemsScreen extends Screen {

    // ── Visitor reward item list ───────────────────────────────────────────────
    // User-provided reward list (kept exactly as requested).
    private static final String[] ALL_ITEMS = {
            "Overgrown Grass",
            "Green Bandana",
            "Music Rune I",
            "Space Helmet",
            "Fairy Soul",
            "Copper Dye",
            "Flowering Bouquet",
            "Fruit Bowl",
            "Turbo-Cacti I",
            "Turbo-Cane I",
            "Turbo-Carrot I",
            "Turbo-Mushrooms I",
            "Turbo-Potato I",
            "Turbo-Warts I",
            "Turbo-Wheat I",
            "Turbo-Cocoa I",
            "Turbo-Melon I",
            "Turbo-Pumpkin I",
            "Cultivating I",
            "Replenish I",
            "Delicate V",
            "Dedication IV",
            "Jungle Key",
            "Pet Cake",
            "Fine Flour",
            "Arachne Fragment",
            "Dead Bush",
            "Mysterious Crop",
            "Velvet Top Hat",
            "Cashmere Jacket",
            "Satin Trousers",
            "Oxford Shoes",
            "Harvest Harbinger Potion"
    };

    // ── Layout/style constants (mirrors VisitorBlacklistScreen) ──────────────
    private static final int COLUMNS       = 3;
    private static final int PANEL_WIDTH   = 460;
    private static final int HEADER_HEIGHT = 28;
    private static final int SEARCH_HEIGHT = 20;
    private static final int BUTTON_HEIGHT = 22;
    private static final int PADDING       = 6;
    private static final String SELECTED_INDICATOR = "\u25CF";
    /** Mint/cyan tint applied behind selected item buttons. */
    private static final int COL_SELECTED_HIGHLIGHT = 0x3080FF80;

    private final Screen parent;
    private final FarmingConfig config;

    private int panelX, panelY, panelW, panelH;
    private FlatButtonWidget[] filteredButtons = new FlatButtonWidget[0];
    private String searchQuery = "";
    private int scrollOffset = 0;
    private int maxVisibleRows = 1;
    private String[] filteredItems = ALL_ITEMS;

    public VisitorAlwaysAcceptItemsScreen(Screen parent, FarmingConfig config) {
        super(Text.translatable("gui.just-farming.visitors_always_accept_items_title"));
        this.parent = parent;
        this.config = config;
        GuiTheme.activate(config);
    }

    private void applyFilter() {
        if (searchQuery == null || searchQuery.isEmpty()) {
            filteredItems = ALL_ITEMS;
        } else {
            String lower = searchQuery.toLowerCase();
            List<String> result = new ArrayList<>();
            for (String item : ALL_ITEMS) {
                if (item.toLowerCase().contains(lower)) result.add(item);
            }
            filteredItems = result.toArray(new String[0]);
        }
    }

    @Override
    protected void init() {
        applyFilter();

        int numRows = (filteredItems.length + COLUMNS - 1) / COLUMNS;
        int numRowsForH = (ALL_ITEMS.length + COLUMNS - 1) / COLUMNS;
        int naturalH = HEADER_HEIGHT + PADDING + SEARCH_HEIGHT + PADDING
                + numRowsForH * (BUTTON_HEIGHT + PADDING)
                + BUTTON_HEIGHT + PADDING;

        panelW = Math.min(PANEL_WIDTH, this.width - 10);
        panelH = Math.min(naturalH, this.height - 10);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        int contentH = panelH - HEADER_HEIGHT - PADDING - SEARCH_HEIGHT - PADDING
                - (BUTTON_HEIGHT + PADDING);
        maxVisibleRows = Math.max(1, contentH / (BUTTON_HEIGHT + PADDING));

        int maxScroll = Math.max(0, numRows - maxVisibleRows);
        scrollOffset = Math.min(scrollOffset, maxScroll);

        int colGap = PADDING;
        int totalBW = panelW - 2 * PADDING - 4;
        int bw = (totalBW - (COLUMNS - 1) * colGap) / COLUMNS;
        int bh = BUTTON_HEIGHT;
        int wx = panelX + PADDING + 2;

        int searchY = panelY + HEADER_HEIGHT + PADDING;
        TextFieldWidget searchField = new TextFieldWidget(
                this.textRenderer, wx, searchY, totalBW, SEARCH_HEIGHT, Text.empty());
        searchField.setMaxLength(64);
        searchField.setText(searchQuery);
        searchField.setPlaceholder(Text.literal("Search items...").withColor(GuiTheme.current.TEXT_MUTED));
        searchField.setChangedListener(text -> {
            searchQuery = text;
            scrollOffset = 0;
            this.clearAndInit();
        });
        this.addDrawableChild(searchField);
        this.setFocused(searchField);

        int y = panelY + HEADER_HEIGHT + PADDING + SEARCH_HEIGHT + PADDING;

        if (config.visitorAlwaysAcceptItems == null) {
            config.visitorAlwaysAcceptItems = new ArrayList<>();
        }

        filteredButtons = new FlatButtonWidget[filteredItems.length];

        for (int i = 0; i < filteredItems.length; i++) {
            final String item = filteredItems[i];
            int row = i / COLUMNS;
            int col = i % COLUMNS;
            int visibleRow = row - scrollOffset;
            int bx = wx + col * (bw + colGap);
            FlatButtonWidget btn = new FlatButtonWidget(
                    bx, y + visibleRow * (bh + PADDING), bw, bh,
                    getButtonText(item),
                    b -> {
                        if (config.visitorAlwaysAcceptItems.contains(item)) {
                            config.visitorAlwaysAcceptItems.remove(item);
                        } else {
                            config.visitorAlwaysAcceptItems.add(item);
                        }
                        config.save();
                        b.setMessage(getButtonText(item));
                    });
            filteredButtons[i] = btn;
            if (visibleRow >= 0 && visibleRow < maxVisibleRows) {
                this.addDrawableChild(btn);
            }
        }

        this.addDrawableChild(new FlatButtonWidget(
                wx, panelY + panelH - bh - PADDING, totalBW, bh,
                Text.translatable("gui.just-farming.close"),
                btn -> { if (this.client != null) this.client.setScreen(parent); }));
    }

    private Text getButtonText(String item) {
        boolean selected = config.visitorAlwaysAcceptItems != null
                && config.visitorAlwaysAcceptItems.contains(item);
        String suffix = selected ? "  " + SELECTED_INDICATOR + " Selected" : "";
        return Text.literal(item + suffix);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontalAmount, double verticalAmount) {
        int numRows = (filteredItems.length + COLUMNS - 1) / COLUMNS;
        int maxScroll = Math.max(0, numRows - maxVisibleRows);
        int delta = verticalAmount > 0 ? -1 : 1;
        int newOffset = Math.max(0, Math.min(maxScroll, scrollOffset + delta));
        if (newOffset != scrollOffset) {
            scrollOffset = newOffset;
            this.clearAndInit();
        }
        return true;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        GuiTheme t = GuiTheme.current;
        int panelR = panelX + panelW;
        int panelB = panelY + panelH;

        context.fill(0, 0, this.width, this.height, t.SCREEN_DIM);
        context.fill(panelX + 4, panelY + 4, panelR + 4, panelB + 4, t.SHADOW);
        context.fill(panelX - 1, panelY - 1, panelR + 1, panelB + 1, t.BORDER);
        context.fill(panelX, panelY, panelR, panelB, t.WIN_BG);
        context.fill(panelX, panelY, panelX + 3, panelY + HEADER_HEIGHT, t.ACCENT);
        context.fill(panelX + 3, panelY + HEADER_HEIGHT - 1, panelR, panelY + HEADER_HEIGHT, t.SEP);
        context.drawTextWithShadow(this.textRenderer,
                this.title.copy().withColor(t.TEXT),
                panelX + 10, panelY + (HEADER_HEIGHT - 8) / 2, t.TEXT);

        if (config.visitorAlwaysAcceptItems != null) {
            for (int i = 0; i < filteredItems.length; i++) {
                int visibleRow = (i / COLUMNS) - scrollOffset;
                if (visibleRow < 0 || visibleRow >= maxVisibleRows) continue;
                if (config.visitorAlwaysAcceptItems.contains(filteredItems[i])
                        && i < filteredButtons.length && filteredButtons[i] != null) {
                    FlatButtonWidget b = filteredButtons[i];
                    context.fill(b.getX() - 1, b.getY() - 1,
                            b.getX() + b.getWidth() + 1, b.getY() + b.getHeight() + 1,
                            COL_SELECTED_HIGHLIGHT);
                }
            }
        }

        super.render(context, mouseX, mouseY, delta);

        int numRows = (filteredItems.length + COLUMNS - 1) / COLUMNS;
        int maxScroll = Math.max(0, numRows - maxVisibleRows);
        int indicatorX = panelX + panelW - PADDING - 2;
        int contentTopY = panelY + HEADER_HEIGHT + PADDING + SEARCH_HEIGHT + PADDING;
        if (scrollOffset > 0) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("▲"), indicatorX, contentTopY, t.TEXT_MUTED);
        }
        if (scrollOffset < maxScroll) {
            int bottomY = panelY + panelH - BUTTON_HEIGHT - PADDING - 8;
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("▼"), indicatorX, bottomY, t.TEXT_MUTED);
        }
    }

    @Override
    public boolean shouldPause() { return false; }
}
