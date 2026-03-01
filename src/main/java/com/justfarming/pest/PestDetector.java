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
 * Skyblock tab list and scoreboard.
 *
 * <p>Detection is modelled after SkyHanni's approach:
 * <ul>
 *   <li><b>Tab list (Pests Widget)</b>: lines like {@code " Plots: 4, 12, 13, 18, 20"}</li>
 *   <li><b>Scoreboard</b>: lines like {@code "Plot - 4 ൠ x1"} or
 *       {@code "§7⏣ §aThe Garden §4§lൠ§7 x3"}</li>
 * </ul>
 */
public class PestDetector {

    // Tab list: " Plots: 4, 12, 13, 18, 20"
    private static final Pattern INFESTED_PLOTS_PATTERN =
            Pattern.compile("\\s*Plots:\\s*(.+)");

    // Scoreboard: "Plot - 4 ൠ x1" (after colour-code stripping)
    private static final Pattern PLOT_PEST_SCOREBOARD_PATTERN =
            Pattern.compile("\\s*Plot\\s*-\\s*(\\S+)\\s*ൠ\\s*x(\\d+)");

    // Scoreboard: "The Garden ൠ x3" (total pest count, after stripping)
    private static final Pattern TOTAL_PEST_PATTERN =
            Pattern.compile("The Garden\\s*ൠ\\s*x(\\d+)");

    // Scoreboard/tab list: Hypixel location indicator "⏣ The Garden …"
    // The ⏣ symbol is Hypixel's location prefix; matching it prevents false
    // positives from chat messages or player names containing "The Garden".
    private static final Pattern GARDEN_LOCATION_PATTERN =
            Pattern.compile("⏣\\s+The\\s+Garden.*");

    /** Plot names (e.g. "4", "12") that currently have pests. */
    private final Set<String> pestPlots = new HashSet<>();

    /** Per-plot pest counts (plot name → pest count) from the scoreboard. */
    private final Map<String, Integer> pestCounts = new HashMap<>();

    /** Plots that newly gained pests in the most recent {@link #update} call. */
    private Set<String> newlyInfestedPlots = new HashSet<>();

    /** Total pest count as reported by the scoreboard. */
    private int totalPests = 0;

    /** Whether the player is currently in the Hypixel Skyblock Garden. */
    private boolean inGarden = false;

    // -----------------------------------------------------------------------

    /**
     * Called every tick to refresh the pest plot list from the tab list.
     */
    public void update(MinecraftClient client) {
        Set<String> previousPlots = new HashSet<>(pestPlots);
        pestPlots.clear();
        pestCounts.clear();
        totalPests = 0;
        inGarden = false;
        if (client.player == null || client.player.networkHandler == null) return;

        for (PlayerListEntry entry : client.player.networkHandler.getPlayerList()) {
            if (entry.getDisplayName() == null) continue;
            String text = entry.getDisplayName().getString();
            parseEntry(text);
        }

        // Read scoreboard sidebar (per-plot pest counts: "Plot - N ൠ xM")
        if (client.world != null) {
            Scoreboard sb = client.world.getScoreboard();
            ScoreboardObjective sidebar = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
            if (sidebar != null) {
                for (ScoreboardEntry e : sb.getScoreboardEntries(sidebar)) {
                    // Try the entry's own display text first
                    parseEntry(e.name().getString());
                    // Also try the team-decorated text (prefix + owner + suffix)
                    Team team = sb.getScoreHolderTeam(e.owner());
                    if (team != null) {
                        parseEntry(team.getPrefix().getString() + e.owner() + team.getSuffix().getString());
                    }
                }
            }
        }

        // Detect plots that have newly gained pests since the last tick
        newlyInfestedPlots = new HashSet<>(pestPlots);
        newlyInfestedPlots.removeAll(previousPlots);
    }

    /**
     * Returns an unmodifiable view of plot names that currently have pests.
     */
    public Set<String> getPestPlots() {
        return Collections.unmodifiableSet(pestPlots);
    }

    /**
     * Returns an unmodifiable map of plot name → pest count for plots whose
     * per-plot count was reported by the scoreboard this tick.
     */
    public Map<String, Integer> getPestCounts() {
        return Collections.unmodifiableMap(pestCounts);
    }

    /**
     * Returns the set of plots that newly gained pests in the most recent
     * {@link #update} call (i.e. plots not present in the previous tick).
     */
    public Set<String> getNewlyInfestedPlots() {
        return Collections.unmodifiableSet(newlyInfestedPlots);
    }

    /**
     * Returns the total number of pests detected from the scoreboard.
     */
    public int getTotalPests() {
        return totalPests;
    }

    /**
     * Returns {@code true} when the current scoreboard or tab list indicates
     * the player is in the Hypixel Skyblock Garden.
     */
    public boolean isInGarden() {
        return inGarden;
    }

    /**
     * Formats a pest count as e.g. {@code "Pests: 1"} or {@code "Pests: 3"}.
     */
    public static String formatPestCount(int count) {
        return "Pests: " + count;
    }

    // -----------------------------------------------------------------------

    private void parseEntry(String text) {
        if (text == null || text.isBlank()) return;
        // Strip Minecraft colour codes (§X)
        String clean = text.replaceAll("§[0-9a-fklmnorA-FKLMNOR]", "").trim();

        // Detect Garden location
        if (GARDEN_LOCATION_PATTERN.matcher(clean).matches()) {
            inGarden = true;
        }

        // Try "Plots: X, Y, Z" format (Pests Widget in tab list)
        Matcher plotsMatch = INFESTED_PLOTS_PATTERN.matcher(clean);
        if (plotsMatch.matches()) {
            String[] names = plotsMatch.group(1).split(",");
            for (String name : names) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) {
                    pestPlots.add(trimmed);
                }
            }
            return;
        }

        // Try per-plot scoreboard: "Plot - NAME ൠ xN"
        Matcher plotPest = PLOT_PEST_SCOREBOARD_PATTERN.matcher(clean);
        if (plotPest.find()) {
            String plotName = plotPest.group(1);
            pestPlots.add(plotName);
            String countStr = plotPest.group(2);
            if (countStr != null) {
                try {
                    pestCounts.put(plotName, Integer.parseInt(countStr));
                } catch (NumberFormatException ignored) {
                }
            }
            return;
        }

        // Try total pest count: "The Garden ൠ xN"
        Matcher totalMatch = TOTAL_PEST_PATTERN.matcher(clean);
        if (totalMatch.find()) {
            try {
                totalPests = Integer.parseInt(totalMatch.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
    }
}
