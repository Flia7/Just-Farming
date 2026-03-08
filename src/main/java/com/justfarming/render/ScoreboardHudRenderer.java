package com.justfarming.render;

import com.justfarming.config.FarmingConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Renders a custom "Just Farming" branded scoreboard sidebar, replacing the
 * vanilla Minecraft scoreboard overlay.
 *
 * <p>Layout:
 * <pre>
 *  ┌──────────────────────────┐
 *  │  ★ JUST FARMING ★        │  ← header (gold/green gradient feel)
 *  ├──────────────────────────┤
 *  │  &lt;scoreboard lines&gt;      │  ← vanilla sidebar entries, coloured
 *  └──────────────────────────┘
 * </pre>
 *
 * <p>The panel is anchored to the right side of the screen and positioned
 * just below the top, matching the vanilla scoreboard position.
 */
public class ScoreboardHudRenderer {

    // ── Colours ───────────────────────────────────────────────────────────────

    // Dark mode
    private static final int COL_BG_DARK     = 0xA8000000;
    private static final int COL_ACCENT_DARK = 0xFF3AFF8A;
    private static final int COL_HEADER_DARK = 0xFF3AFF8A;
    private static final int COL_STAR_DARK   = 0xFFFFD700;
    private static final int COL_TEXT_DARK   = 0xFFFFFFFF;

    // Light mode
    private static final int COL_BG_LIGHT     = 0xD0EEF4F8;
    private static final int COL_ACCENT_LIGHT = 0xFF1A6040;
    private static final int COL_HEADER_LIGHT = 0xFF1A6040;
    private static final int COL_STAR_LIGHT   = 0xFF9060A0;
    private static final int COL_TEXT_LIGHT   = 0xFF0F1E3C;

    // ── Layout ────────────────────────────────────────────────────────────────

    private static final int PAD_X        = 5;
    private static final int PAD_Y        = 4;
    private static final int LINE_H       = 10;
    private static final int HEADER_H     = 12;
    private static final int ACCENT_H     = 1;
    private static final int MARGIN_RIGHT = 2;

    /** Maximum scoreboard entries to show (matches vanilla's limit of 15). */
    private static final int MAX_ENTRIES  = 15;

    private final FarmingConfig config;

    public ScoreboardHudRenderer(FarmingConfig config) {
        this.config = config;
    }

    /**
     * Renders the custom scoreboard sidebar.  Does nothing when
     * {@link FarmingConfig#customScoreboardEnabled} is {@code false} or when
     * there is no scoreboard objective assigned to the sidebar slot.
     *
     * @param context   the draw context
     */
    public void render(DrawContext context) {
        if (!config.customScoreboardEnabled) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.world == null) return;

        Scoreboard sb = mc.world.getScoreboard();
        ScoreboardObjective objective = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (objective == null) return;

        TextRenderer tr = mc.textRenderer;
        int screenW = mc.getWindow().getScaledWidth();

        // ── Collect entries ───────────────────────────────────────────────────
        List<ScoreboardEntry> entries = new ArrayList<>(sb.getScoreboardEntries(objective));
        // Sort by score descending (highest score shown at top, like vanilla).
        entries.sort(Comparator.comparingInt(ScoreboardEntry::value).reversed());
        if (entries.size() > MAX_ENTRIES) {
            entries = entries.subList(0, MAX_ENTRIES);
        }

        // ── Determine panel dimensions ────────────────────────────────────────
        // Header text
        String headerText = "★ JUST FARMING ★";
        int headerW = tr.getWidth(headerText);

        // Find widest content line (entry text only, no score numbers)
        int maxLineW = headerW;
        List<String> lineTexts = new ArrayList<>();
        for (ScoreboardEntry entry : entries) {
            String displayLine = getEntryDisplayLine(sb, entry);
            maxLineW = Math.max(maxLineW, tr.getWidth(displayLine));
            lineTexts.add(displayLine);
        }

        int panelW = maxLineW + PAD_X * 2 + 2;
        int panelH = PAD_Y                   // top padding
                + HEADER_H                   // header title
                + ACCENT_H + 2              // accent line + gap
                + entries.size() * LINE_H   // entry rows
                + PAD_Y;                    // bottom padding

        // ── Position: right side, centered vertically ──────────────────────────
        int screenH = mc.getWindow().getScaledHeight();
        int panelX = screenW - panelW - MARGIN_RIGHT;
        int panelY = (screenH - panelH) / 2;

        boolean dark = config.darkMode;
        int colBg     = dark ? COL_BG_DARK     : COL_BG_LIGHT;
        int colAccent = dark ? COL_ACCENT_DARK : COL_ACCENT_LIGHT;
        int colStar   = dark ? COL_STAR_DARK   : COL_STAR_LIGHT;
        int colHeader = dark ? COL_HEADER_DARK : COL_HEADER_LIGHT;
        int colText   = dark ? COL_TEXT_DARK   : COL_TEXT_LIGHT;

        // ── Background ────────────────────────────────────────────────────────
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, colBg);

        int curY = panelY + PAD_Y;

        // ── Header ────────────────────────────────────────────────────────────
        // Draw the two stars in gold and the "JUST FARMING" text in green
        int headerX = panelX + PAD_X;
        drawHeaderLine(context, tr, headerX, curY, panelW - PAD_X * 2, colStar, colHeader);
        curY += HEADER_H;

        // Accent line (full width minus padding)
        context.fill(panelX + PAD_X, curY,
                panelX + panelW - PAD_X, curY + ACCENT_H, colAccent);
        curY += ACCENT_H + 2;

        // ── Scoreboard lines (centered, no score numbers) ─────────────────────
        int innerW = panelW - PAD_X * 2;
        for (String text : lineTexts) {
            int textW = tr.getWidth(text);
            int textX = panelX + PAD_X + (innerW - textW) / 2;
            context.drawTextWithShadow(tr, text, textX, curY, colText);
            curY += LINE_H;
        }
    }

    /**
     * Draws the "★ JUST FARMING ★" header with star decorations in gold and
     * the centre text in the accent green, all centred in the available width.
     */
    private void drawHeaderLine(DrawContext ctx, TextRenderer tr,
                                int x, int y, int availW,
                                int colStar, int colHeader) {
        String leftStar  = "★ ";
        String title     = "JUST FARMING";
        String rightStar = " ★";
        int totalW = tr.getWidth(leftStar) + tr.getWidth(title) + tr.getWidth(rightStar);
        int startX = x + Math.max(0, (availW - totalW) / 2);
        ctx.drawTextWithShadow(tr, leftStar,  startX,                                  y, colStar);
        ctx.drawTextWithShadow(tr, title,     startX + tr.getWidth(leftStar),          y, colHeader);
        ctx.drawTextWithShadow(tr, rightStar, startX + tr.getWidth(leftStar + title),  y, colStar);
    }

    /**
     * Assembles the display text for one scoreboard entry by combining the
     * score-holder's team prefix and suffix (SkyHanni approach), falling back
     * to the raw owner string if no team is assigned.
     */
    private static String getEntryDisplayLine(Scoreboard sb, ScoreboardEntry entry) {
        Team team = sb.getScoreHolderTeam(entry.owner());
        if (team != null) {
            String prefix = team.getPrefix() != null ? team.getPrefix().getString() : "";
            String suffix = team.getSuffix() != null ? team.getSuffix().getString() : "";
            String combined = prefix + suffix;
            if (!combined.isBlank()) return combined;
        }
        // Fallback: use the entry's display name if available
        if (entry.name() != null) {
            String n = entry.name().getString();
            if (!n.isBlank()) return n;
        }
        return entry.owner();
    }

}
