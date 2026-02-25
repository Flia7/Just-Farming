package com.justfarming.pest;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the Hypixel Skyblock tab list to detect which garden plots currently
 * contain pests.
 *
 * <p>Hypixel encodes pest information in tab list entries using lines such as:
 * <pre>
 *   Plot ①: 2   Plot ③: 1
 * </pre>
 * where ①–㉔ are Unicode CIRCLED NUMBER characters (U+2460–U+3251) representing
 * plot numbers 1–24, followed by a pest count.  Plain "Plot 1:", "Plot 2:" etc.
 * are also matched for robustness.
 */
public class PestDetector {

    // Unicode circled numbers ①–⑳ (U+2460–U+2473) cover plots 1–20
    // ㉑–㉔ (U+3251–U+3254) cover plots 21–24
    private static final char[] CIRCLED = buildCircledArray();

    // Matches "Plot <symbol|number>: <count>" (case-insensitive, optional spaces)
    private static final Pattern PEST_PATTERN =
            Pattern.compile("Plot\\s*([①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳㉑㉒㉓㉔\\d]+)\\s*[:\\-]?\\s*(\\d+)?", Pattern.CASE_INSENSITIVE);

    // Also match compact formats: "①2" or "①:2"
    private static final Pattern COMPACT_PATTERN =
            Pattern.compile("([①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳㉑㉒㉓㉔])\\s*[:\\-]?\\s*(\\d+)?");

    private final Set<Integer> pestPlots = new HashSet<>();

    // -----------------------------------------------------------------------

    /** Called every tick to refresh the pest plot list from the tab list. */
    public void update(MinecraftClient client) {
        pestPlots.clear();
        if (client.player == null || client.player.networkHandler == null) return;

        for (PlayerListEntry entry : client.player.networkHandler.getPlayerList()) {
            if (entry.getDisplayName() == null) continue;
            String text = entry.getDisplayName().getString();
            parseEntry(text);
        }
    }

    /** Returns an unmodifiable view of plot numbers (1–24) that currently have pests. */
    public Set<Integer> getPestPlots() {
        return Collections.unmodifiableSet(pestPlots);
    }

    // -----------------------------------------------------------------------

    private void parseEntry(String text) {
        if (text == null || text.isBlank()) return;
        // Strip Minecraft colour codes (§X)
        String clean = text.replaceAll("§[0-9a-fklmnorA-FKLMNOR]", "").trim();

        // Try "Plot <sym/num>" pattern
        Matcher m = PEST_PATTERN.matcher(clean);
        while (m.find()) {
            String token = m.group(1);
            int plotNum = parseToken(token);
            if (plotNum >= 1 && plotNum <= 24) {
                pestPlots.add(plotNum);
            }
        }

        // Try compact circled-number pattern "①2"
        Matcher cm = COMPACT_PATTERN.matcher(clean);
        while (cm.find()) {
            String sym = cm.group(1);
            int plotNum = circledToInt(sym.charAt(0));
            if (plotNum >= 1 && plotNum <= 24) {
                pestPlots.add(plotNum);
            }
        }
    }

    /** Parse a token that is either a digit string or a circled-number character. */
    private int parseToken(String token) {
        if (token == null || token.isEmpty()) return -1;
        char first = token.charAt(0);
        int fromCircled = circledToInt(first);
        if (fromCircled > 0) return fromCircled;
        try {
            return Integer.parseInt(token.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Converts a Unicode circled-number character to its integer value (1–24), or 0 if not a circled number. */
    private static int circledToInt(char c) {
        if (c >= '\u2460' && c <= '\u2473') return c - '\u2460' + 1; // ①–⑳ → 1–20
        if (c >= '\u3251' && c <= '\u3254') return c - '\u3251' + 21; // ㉑–㉔ → 21–24
        return 0;
    }

    private static char[] buildCircledArray() {
        // ①–⑳ U+2460–U+2473, ㉑–㉔ U+3251–U+3254
        char[] arr = new char[24];
        for (int i = 0; i < 20; i++) arr[i] = (char) ('\u2460' + i);
        for (int i = 0; i < 4; i++) arr[20 + i] = (char) ('\u3251' + i);
        return arr;
    }
}
