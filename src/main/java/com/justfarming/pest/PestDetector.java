package com.justfarming.pest;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects which garden plots currently contain pests by parsing the Hypixel
 * Skyblock scoreboard and tab list.
 *
 * <p>Detection follows SkyHanni's approach:
 * <ul>
 *   <li><b>Tab list (Pests Widget)</b>: lines like {@code "  Plots: 4, 12, 13"}</li>
 *   <li><b>Scoreboard sidebar</b>: area lines such as
 *       {@code "§7⏣ §aThe Garden"} (garden with no pests),
 *       {@code "§7⏣ §aThe Garden §4§lൠ§7 x3"} (total pest count), or
 *       {@code "§aPlot §7- §b4 §4§lൠ§7 x1"} (per-plot count).</li>
 * </ul>
 *
 * <p>Scoreboard lines are assembled as {@code teamPrefix + teamSuffix}
 * (the score-holder name/owner is a random placeholder that Hypixel uses
 * internally and must be excluded, mirroring SkyHanni's
 * {@code getPlayerNames} helper).
 */
public class PestDetector {

    // ── Garden location ──────────────────────────────────────────────────────

    /**
     * Raw (§-coded) scoreboard area line indicating the player is in the Garden.
     * Matches lines like {@code "§7⏣ §aThe Garden"} (no pests, §a = green) or
     * {@code "§7⏣ §cThe Garden §4§lൠ§7 x3"} (pests present, §c = red).
     * Both green (§a) and red (§c) variants are used by Hypixel depending on
     * whether pests are present.
     */
    private static final Pattern GARDEN_RAW =
            Pattern.compile("§7⏣ §[ac](?:The Garden|Plot)");

    /**
     * Stripped (no §) version of the garden area detector.
     * Matches {@code "⏣ The Garden"} or {@code "⏣ Plot"} etc.
     */
    private static final Pattern GARDEN_STRIPPED =
            Pattern.compile("⏣\\s+(?:The\\s+Garden|Plot)");

    // ── Pest data ─────────────────────────────────────────────────────────────

    /** Tab list "Visitors" widget: {@code "  Visitors: 3"} */
    private static final Pattern VISITOR_COUNT_PATTERN =
            Pattern.compile("\\s*Visitors:\\s*(\\d+)");

    /** Tab list "Pests" widget: {@code "  Plots: 4, 12, 13, 18, 20"} */
    private static final Pattern INFESTED_PLOTS_PATTERN =
            Pattern.compile("\\s*Plots:\\s*(.+)");

    /** Scoreboard per-plot pest count (stripped): {@code "Plot - 4 ൠ x1"} */
    private static final Pattern PLOT_PEST_PATTERN =
            Pattern.compile("\\s*Plot\\s*-\\s*(\\S+)\\s*ൠ\\s*x(\\d+)");

    /** Scoreboard total pest count (stripped): {@code "The Garden ൠ x3"} */
    private static final Pattern TOTAL_PEST_PATTERN =
            Pattern.compile("The Garden\\s*ൠ\\s*x(\\d+)");

    // ── State ─────────────────────────────────────────────────────────────────

    /** Plot names (e.g. "4", "12") that currently have pests. */
    private final Set<String> pestPlots = new HashSet<>();

    /** Per-plot pest counts (plot name → pest count) from the scoreboard. */
    private final Map<String, Integer> pestCounts = new HashMap<>();

    /** Plots that newly gained pests in the most recent {@link #update} call. */
    private Set<String> newlyInfestedPlots = new HashSet<>();

    /** Total pest count as reported by the scoreboard. */
    private int totalPests = 0;

    /** Number of visitors currently at the barn, as reported by the tab list. */
    private int visitorCount = 0;

    /** Whether a "Visitors: N" line was found in the tab list during the most recent update. */
    private boolean visitorCountDetected = false;

    /** Whether the player is currently in the Hypixel Skyblock Garden. */
    private boolean inGarden = false;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called every tick to refresh pest state from the scoreboard and tab list.
     */
    public void update(MinecraftClient client) {
        Set<String> previousPlots = new HashSet<>(pestPlots);
        pestPlots.clear();
        pestCounts.clear();
        totalPests = 0;
        visitorCount = 0;
        visitorCountDetected = false;
        inGarden = false;
        if (client.player == null || client.player.networkHandler == null) return;

        // ── Tab list ─────────────────────────────────────────────────────────
        for (PlayerListEntry entry : client.player.networkHandler.getPlayerList()) {
            if (entry.getDisplayName() == null) continue;
            parseLine(entry.getDisplayName().getString());
        }

        // ── Scoreboard sidebar ───────────────────────────────────────────────
        if (client.world != null) {
            Scoreboard sb = client.world.getScoreboard();
            ScoreboardObjective sidebar = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
            if (sidebar != null) {
                for (ScoreboardEntry e : sb.getScoreboardEntries(sidebar)) {
                    // Check the entry's own display text (if present).
                    if (e.name() != null) {
                        parseLine(e.name().getString());
                    }
                    // Rebuild the display line following SkyHanni's approach:
                    // use teamPrefix + teamSuffix and ignore the score-holder
                    // owner name (which is a random placeholder on Hypixel).
                    // Team.getPrefix() and Team.getSuffix() always return a
                    // non-null Text (empty literal if unset), so no null check
                    // is required here.
                    Team team = sb.getScoreHolderTeam(e.owner());
                    if (team != null) {
                        String line = team.getPrefix().getString()
                                + team.getSuffix().getString();
                        parseLine(line);
                    }
                }
            }
        }

        newlyInfestedPlots = new HashSet<>(pestPlots);
        newlyInfestedPlots.removeAll(previousPlots);
    }

    /** Clears all cached state (call when leaving the Garden). */
    public void clear() {
        pestPlots.clear();
        pestCounts.clear();
        newlyInfestedPlots.clear();
        totalPests = 0;
        visitorCount = 0;
        visitorCountDetected = false;
        inGarden = false;
    }

    /** Returns the set of plot names that currently have pests. */
    public Set<String> getPestPlots() {
        return Collections.unmodifiableSet(pestPlots);
    }

    /** Returns the per-plot pest counts reported by the scoreboard this tick. */
    public Map<String, Integer> getPestCounts() {
        return Collections.unmodifiableMap(pestCounts);
    }

    /** Returns plots that newly gained pests in the most recent tick. */
    public Set<String> getNewlyInfestedPlots() {
        return Collections.unmodifiableSet(newlyInfestedPlots);
    }

    /** Returns the total pest count from the scoreboard. */
    public int getTotalPests() {
        return totalPests;
    }

    /** Returns the visitor count from the tab list (0 if not detected). */
    public int getVisitorCount() {
        return visitorCount;
    }

    /** Returns {@code true} if a "Visitors: N" entry was found in the tab list this tick. */
    public boolean isVisitorCountDetected() {
        return visitorCountDetected;
    }

    /** Returns {@code true} when the scoreboard/tab list confirms the player is in the Garden. */
    public boolean isInGarden() {
        return inGarden;
    }

    /** Formats a pest count as e.g. {@code "Pests: 3"}. */
    public static String formatPestCount(int count) {
        return "Pests: " + count;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Parses one scoreboard or tab-list line.  Garden detection runs on both
     * the raw (§-coded) string and the stripped version so that the code works
     * regardless of whether Hypixel sends legacy {@code §}-formatted text or
     * modern JSON components (where {@link net.minecraft.text.Text#getString()}
     * already returns plain text without § codes).
     */
    private void parseLine(String raw) {
        if (raw == null || raw.isBlank()) return;

        // Garden detection on raw text (handles legacy §-formatted strings).
        if (GARDEN_RAW.matcher(raw).find()) {
            inGarden = true;
        }

        // Strip § colour codes and trim for pattern matching.
        String clean = raw.replaceAll("§[0-9a-fklmnorA-FKLMNOR]", "").trim();
        if (clean.isEmpty()) return;

        // Garden detection on stripped text (handles modern component text).
        if (GARDEN_STRIPPED.matcher(clean).find()) {
            inGarden = true;
        }

        // Tab-list "Visitors: N" widget.
        Matcher visitorMatcher = VISITOR_COUNT_PATTERN.matcher(clean);
        if (visitorMatcher.matches()) {
            visitorCountDetected = true;
            try {
                visitorCount = Integer.parseInt(visitorMatcher.group(1));
            } catch (NumberFormatException ignored) {
            }
            return;
        }

        // Tab-list "Plots: X, Y, Z" widget.
        Matcher plotsMatcher = INFESTED_PLOTS_PATTERN.matcher(clean);
        if (plotsMatcher.matches()) {
            for (String part : plotsMatcher.group(1).split(",")) {
                String name = part.trim();
                if (!name.isEmpty()) pestPlots.add(name);
            }
            return;
        }

        // Scoreboard per-plot: "Plot - NAME ൠ xN".
        Matcher plotPest = PLOT_PEST_PATTERN.matcher(clean);
        if (plotPest.find()) {
            String plotName = plotPest.group(1);
            pestPlots.add(plotName);
            try {
                pestCounts.put(plotName, Integer.parseInt(plotPest.group(2)));
            } catch (NumberFormatException ignored) {
            }
            return;
        }

        // Scoreboard total: "The Garden ൠ xN".
        Matcher total = TOTAL_PEST_PATTERN.matcher(clean);
        if (total.find()) {
            try {
                totalPests = Integer.parseInt(total.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
    }
}
