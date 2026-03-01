package com.justfarming.gui;

import com.justfarming.config.FarmingConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * A modal screen that lists every known garden visitor.
 * Clicking a visitor's name toggles their blacklist status: blacklisted visitors
 * are automatically skipped by the visitor routine regardless of their
 * required items.  Red-tinted entries are currently blacklisted.
 */
public class VisitorBlacklistScreen extends Screen {

    // ── Visitor list ──────────────────────────────────────────────────────────
    // Sorted alphabetically for easy browsing.
    private static final String[] ALL_VISITORS = {
            "Adventurer", "Alchemage", "Alchemist", "An", "Andrew", "Anita",
            "Archaeologist", "Arthur", "Baker", "Banker Broadjaw", "Bartender",
            "Bednom", "Beth", "Bruuh", "Carpenter", "Chantelle", "Chief Scorn",
            "Chunk", "Clerk Seraphine", "Cold Enjoyer", "Dalbrek", "Dante Goon",
            "Duke", "Dulin", "Duncan", "Dusk", "Elle", "Emissary Carlton",
            "Emissary Ceanna", "Emissary Fraiser", "Emissary Sisko", "Emissary Wilson",
            "Erihann", "Fann", "Farm Merchant", "Farmer Jon", "Farmhand",
            "Fear Mongerer", "Felix", "Fisherman Gerald", "Fragilis", "Friendly Hiker",
            "Frozen Alex", "Gary", "Gemma", "Geonathan Greatforge", "Gimley",
            "Gold Forger", "Grandma Wolf", "Guy", "Gwendolyn", "Hendrik", "Hoppity",
            "Hornum", "Hungry Hiker", "Iron Forger", "Jack", "Jacob", "Jacobus",
            "Jamie", "Jerry", "Jotraeline Greatforge", "Lazy Miner", "Leo", "Liam",
            "Librarian", "Lift Operator", "Ludleth", "Lumber Jack", "Lumina", "Lynn",
            "Madame Eleanor Q. Goldsworth III", "Maeve", "Marco", "Marigold", "Mason",
            "Master Tactician Funk", "Mayor Aatrox", "Mayor Cole", "Mayor Diana",
            "Mayor Diaz", "Mayor Finnegan", "Mayor Foxy", "Mayor Marina", "Mayor Paul",
            "Moby", "Odawa", "Old Man Garry", "Old Shaman Nyko", "Ophelia", "Oringo",
            "Pearl Dealer", "Pest Wrangler", "Pest Wrangler?", "Pete", "Plumber Joe",
            "Puzzler", "Queen Mismyla", "Queen Nyx", "Ravenous Rhino",
            "Resident Neighbor", "Resident Snooty", "Rhys", "Romero", "Royal Resident",
            "Rusty", "Ryan", "Ryu", "Sargwyn", "Scout Scardius", "Seymour", "Shaggy",
            "Sherry", "Shifty", "Sirius", "Spaceman", "Spider Tamer", "St. Jerry",
            "Stella", "Tammy", "Tarwen", "Terry", "The Trapper", "Tia the Fairy",
            "Tom", "Tomioka", "Trevor", "Trinity", "Tyashoi Alchemist", "Tyzzo",
            "Vargul", "Vex", "Vincent", "Vinyl Collector", "Weaponsmith", "Wizard",
            "Xalx", "Zog"
    };

    // ── Colour palette (matches FarmingConfigScreen / CropSelectScreen) ───────
    private static final int COL_SCREEN_DIM   = 0x60000000;
    private static final int COL_WIN_BG       = 0xBF000000;
    private static final int COL_BORDER       = 0x28FFFFFF;
    private static final int COL_SEP          = 0x14FFFFFF;
    private static final int COL_TEXT         = 0xF2FFFFFF;
    private static final int COL_ACCENT       = 0xFF7C4DFF;
    private static final int COL_SHADOW       = 0x60000000;
    /** Red tint applied behind a blacklisted visitor's button. */
    private static final int COL_BLACKLISTED_HIGHLIGHT = 0x30FF5060;
    private static final int COL_TEXT_MUTED   = 0x66FFFFFF;

    // ── Natural panel dimensions ───────────────────────────────────────────────
    private static final int PANEL_WIDTH   = 300;
    private static final int HEADER_HEIGHT = 28;
    private static final int BUTTON_HEIGHT = 20;
    private static final int PADDING       = 5;

    private final Screen        parent;
    private final FarmingConfig config;

    private int panelX, panelY, panelW, panelH;
    private final FlatButtonWidget[] visitorButtons = new FlatButtonWidget[ALL_VISITORS.length];

    /** How many visitor rows have been scrolled past. */
    private int scrollOffset    = 0;
    /** How many visitor rows can be displayed at once. */
    private int maxVisibleRows  = ALL_VISITORS.length;

    public VisitorBlacklistScreen(Screen parent, FarmingConfig config) {
        super(Text.literal("Blacklist Visitors"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        int naturalH = HEADER_HEIGHT + PADDING
                + ALL_VISITORS.length * (BUTTON_HEIGHT + PADDING)
                + BUTTON_HEIGHT + PADDING;

        panelW = Math.min(PANEL_WIDTH, this.width  - 10);
        panelH = Math.min(naturalH,    this.height - 10);
        panelX = (this.width  - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        // How many visitor rows fit between the header and the Cancel/Close button
        int contentH = panelH - HEADER_HEIGHT - PADDING - (BUTTON_HEIGHT + PADDING);
        maxVisibleRows = Math.max(1, contentH / (BUTTON_HEIGHT + PADDING));

        int maxScroll = Math.max(0, ALL_VISITORS.length - maxVisibleRows);
        scrollOffset = Math.min(scrollOffset, maxScroll);

        int bw = panelW - 2 * PADDING - 4;
        int bh = BUTTON_HEIGHT;
        int wx = panelX + PADDING + 2;
        int y  = panelY + HEADER_HEIGHT + PADDING;

        if (config.visitorBlacklist == null) {
            config.visitorBlacklist = new ArrayList<>();
        }

        for (int i = 0; i < ALL_VISITORS.length; i++) {
            final String visitor = ALL_VISITORS[i];
            int visibleIndex = i - scrollOffset;
            visitorButtons[i] = new FlatButtonWidget(
                    wx, y + visibleIndex * (bh + PADDING), bw, bh,
                    getButtonText(visitor),
                    btn -> {
                        // Toggle blacklist membership
                        if (config.visitorBlacklist.contains(visitor)) {
                            config.visitorBlacklist.remove(visitor);
                        } else {
                            config.visitorBlacklist.add(visitor);
                        }
                        config.save();
                        btn.setMessage(getButtonText(visitor));
                    });
            if (visibleIndex >= 0 && visibleIndex < maxVisibleRows) {
                this.addDrawableChild(visitorButtons[i]);
            }
        }

        // Close/Done button pinned to the bottom of the panel
        this.addDrawableChild(new FlatButtonWidget(
                wx, panelY + panelH - bh - PADDING, bw, bh,
                Text.literal("Done"),
                btn -> { if (this.client != null) this.client.setScreen(parent); }));
    }

    private Text getButtonText(String visitor) {
        boolean blacklisted = config.visitorBlacklist != null
                && config.visitorBlacklist.contains(visitor);
        String suffix = blacklisted ? "  \u25CF Blacklisted" : "";
        return Text.literal(visitor + suffix);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                  double horizontalAmount, double verticalAmount) {
        int maxScroll = Math.max(0, ALL_VISITORS.length - maxVisibleRows);
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
        // Header accent bar
        context.fill(panelX, panelY, panelX + 3, panelY + HEADER_HEIGHT, COL_ACCENT);
        // Header separator
        context.fill(panelX + 3, panelY + HEADER_HEIGHT - 1,
                panelR, panelY + HEADER_HEIGHT, COL_SEP);
        // Title
        context.drawTextWithShadow(this.textRenderer,
                this.title.copy().withColor(COL_TEXT),
                panelX + 10, panelY + (HEADER_HEIGHT - 8) / 2, COL_TEXT);

        // Highlight blacklisted visitors
        if (config.visitorBlacklist != null) {
            for (int i = 0; i < ALL_VISITORS.length; i++) {
                int visibleIndex = i - scrollOffset;
                if (visibleIndex < 0 || visibleIndex >= maxVisibleRows) continue;
                if (config.visitorBlacklist.contains(ALL_VISITORS[i])) {
                    FlatButtonWidget b = visitorButtons[i];
                    context.fill(b.getX() - 1, b.getY() - 1,
                            b.getX() + b.getWidth() + 1, b.getY() + b.getHeight() + 1,
                            COL_BLACKLISTED_HIGHLIGHT);
                }
            }
        }

        super.render(context, mouseX, mouseY, delta);

        // Scroll indicators
        int maxScroll = Math.max(0, ALL_VISITORS.length - maxVisibleRows);
        int indicatorX = panelX + panelW - PADDING - 2;
        int contentTopY = panelY + HEADER_HEIGHT + PADDING;
        if (scrollOffset > 0) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("▲"), indicatorX, contentTopY, COL_TEXT_MUTED);
        }
        if (scrollOffset < maxScroll) {
            int bottomY = panelY + panelH - BUTTON_HEIGHT - PADDING - 8;
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("▼"), indicatorX, bottomY, COL_TEXT_MUTED);
        }
    }

    @Override
    public boolean shouldPause() { return false; }
}
