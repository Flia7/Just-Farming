package com.justfarming.pest;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;

import java.util.Collections;
import java.util.HashSet;
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

    /** Plot names (e.g. "4", "12") that currently have pests. */
    private final Set<String> pestPlots = new HashSet<>();

    /** Total pest count as reported by the scoreboard. */
    private int totalPests = 0;

    // -----------------------------------------------------------------------

    /**
     * Called every tick to refresh the pest plot list from the tab list.
     */
    public void update(MinecraftClient client) {
        pestPlots.clear();
        totalPests = 0;
        if (client.player == null || client.player.networkHandler == null) return;

        for (PlayerListEntry entry : client.player.networkHandler.getPlayerList()) {
            if (entry.getDisplayName() == null) continue;
            String text = entry.getDisplayName().getString();
            parseEntry(text);
        }
    }

    /**
     * Returns an unmodifiable view of plot names that currently have pests.
     */
    public Set<String> getPestPlots() {
        return Collections.unmodifiableSet(pestPlots);
    }

    /**
     * Returns the total number of pests detected from the scoreboard.
     */
    public int getTotalPests() {
        return totalPests;
    }

    // -----------------------------------------------------------------------

    private void parseEntry(String text) {
        if (text == null || text.isBlank()) return;
        // Strip Minecraft colour codes (§X)
        String clean = text.replaceAll("§[0-9a-fklmnorA-FKLMNOR]", "").trim();

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
