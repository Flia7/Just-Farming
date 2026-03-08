package com.justfarming.profit;

import com.justfarming.MacroManager;
import com.justfarming.pest.PestKillerManager;
import com.justfarming.visitor.VisitorNpcPrices;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks items collected and profit earned during farming and pest killing.
 *
 * <p>Item gains are detected by comparing the player's full inventory
 * (hotbar + main slots 0–35) each tick against the previous snapshot.
 * Gains recorded while the farming macro is active (but not the pest killer)
 * are attributed to <em>farming</em>; gains while the pest killer is active
 * are attributed to <em>pest killing</em>.
 *
 * <p>All prices are taken from {@link VisitorNpcPrices} so no external API
 * calls are ever made.  Item display names are stripped of colour codes and
 * lowercased before lookup so they match the price-table keys.
 *
 * <p>Call {@link #onTick} once per game tick (in {@code END_CLIENT_TICK}).
 * Call {@link #reset} to start a fresh session.
 */
public class FarmingProfitTracker {

    // ── Accumulated item gains (lower-cased plain name → total count) ────────
    private final Map<String, Long> farmingItems = new LinkedHashMap<>();
    private final Map<String, Long> pestItems    = new LinkedHashMap<>();

    // ── Time tracking ────────────────────────────────────────────────────────
    private long farmingSessionStartMs = -1;
    private long farmingTotalMs        = 0;
    private long pestSessionStartMs    = -1;
    private long pestTotalMs           = 0;

    // ── Previous inventory snapshot ──────────────────────────────────────────
    private final Map<String, Long> prevSnapshot = new HashMap<>();

    // ── Previous tick state ──────────────────────────────────────────────────
    private boolean wasFarming    = false;
    private boolean wasPestActive = false;

    // ── Cached display names (plain, for HUD rendering) ──────────────────────
    /** Maps lower-cased plain name → prettified (original case) display name. */
    private final Map<String, String> displayNames = new HashMap<>();

    // ── Number of farming/pest ticks tracked (for "is active" queries) ───────
    private boolean trackerHasData = false;

    // -------------------------------------------------------------------------

    /**
     * Must be called once per game tick.  Snapshots the player's inventory,
     * detects item gains, and updates time counters.
     *
     * @param client          the current Minecraft client (may be {@code null}-safe)
     * @param macroManager    the farming macro manager
     * @param pestKillerManager the pest killer manager
     */
    public void onTick(MinecraftClient client,
                       MacroManager macroManager,
                       PestKillerManager pestKillerManager) {
        if (client == null || client.player == null) return;

        boolean isPestActive = pestKillerManager != null && pestKillerManager.isActive();
        boolean isFarming    = !isPestActive
                && macroManager != null && macroManager.isRunning();

        long nowMs = System.currentTimeMillis();

        // ── Update time counters ─────────────────────────────────────────────
        if (isFarming) {
            if (!wasFarming) farmingSessionStartMs = nowMs;
        } else if (wasFarming && farmingSessionStartMs >= 0) {
            farmingTotalMs += nowMs - farmingSessionStartMs;
            farmingSessionStartMs = -1;
        }

        if (isPestActive) {
            if (!wasPestActive) pestSessionStartMs = nowMs;
        } else if (wasPestActive && pestSessionStartMs >= 0) {
            pestTotalMs += nowMs - pestSessionStartMs;
            pestSessionStartMs = -1;
        }

        wasFarming    = isFarming;
        wasPestActive = isPestActive;

        // ── Snapshot current inventory ───────────────────────────────────────
        Map<String, Long> snapshot = takeSnapshot(client.player);

        // ── Attribute item gains ─────────────────────────────────────────────
        if (isFarming || isPestActive) {
            for (Map.Entry<String, Long> entry : snapshot.entrySet()) {
                String key     = entry.getKey();
                long current   = entry.getValue();
                long prev      = prevSnapshot.getOrDefault(key, 0L);
                long delta     = current - prev;
                if (delta > 0) {
                    trackerHasData = true;
                    if (isFarming) {
                        farmingItems.merge(key, delta, Long::sum);
                    } else {
                        pestItems.merge(key, delta, Long::sum);
                    }
                }
            }
        }

        prevSnapshot.clear();
        prevSnapshot.putAll(snapshot);
    }

    // ── Snapshot helpers ──────────────────────────────────────────────────────

    private Map<String, Long> takeSnapshot(ClientPlayerEntity player) {
        Map<String, Long> map = new HashMap<>();
        for (int i = 0; i < 36; i++) {           // hotbar (0–8) + main (9–35)
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            String key = plainKey(stack);
            if (key.isEmpty()) continue;
            map.merge(key, (long) stack.getCount(), Long::sum);
            displayNames.putIfAbsent(key, niceDisplayName(stack));
        }
        return map;
    }

    /** Lower-cased, colour-stripped item name used as map key. */
    private static String plainKey(ItemStack stack) {
        return stripColor(stack.getName().getString()).toLowerCase().trim();
    }

    /**
     * Best-effort "nice" version of the display name: colour codes stripped,
     * original capitalisation kept as returned by the name component.
     */
    private static String niceDisplayName(ItemStack stack) {
        return stripColor(stack.getName().getString()).trim();
    }

    /** Removes Minecraft colour/formatting codes (§x) from a string. */
    public static String stripColor(String s) {
        if (s == null) return "";
        return COLOR_CODE_PATTERN.matcher(s).replaceAll("");
    }

    /** Pre-compiled pattern for Minecraft colour/formatting codes. */
    private static final java.util.regex.Pattern COLOR_CODE_PATTERN =
            java.util.regex.Pattern.compile("§[0-9a-fA-Fk-oK-OrR]");

    // ── Public query API ──────────────────────────────────────────────────────

    /** Returns {@code true} once any item gain has been recorded this session. */
    public boolean hasData() {
        return trackerHasData;
    }

    /**
     * Returns a snapshot list of {@link ProfitEntry} for farming items,
     * sorted by profit descending.
     */
    public List<ProfitEntry> getFarmingEntries() {
        return toEntries(farmingItems);
    }

    /**
     * Returns a snapshot list of {@link ProfitEntry} for pest-kill items,
     * sorted by profit descending.
     */
    public List<ProfitEntry> getPestEntries() {
        return toEntries(pestItems);
    }

    private List<ProfitEntry> toEntries(Map<String, Long> items) {
        List<ProfitEntry> list = new ArrayList<>();
        for (Map.Entry<String, Long> e : items.entrySet()) {
            String key   = e.getKey();
            long   count = e.getValue();
            double price = VisitorNpcPrices.getPrice(key);
            double profit = count * price;
            String nice  = displayNames.getOrDefault(key, capitalize(key));
            list.add(new ProfitEntry(nice, count, profit));
        }
        list.sort(Comparator.comparingDouble(ProfitEntry::profit).reversed());
        return list;
    }

    /** Total farming NPC profit accumulated this session. */
    public double getFarmingProfit() {
        return farmingItems.entrySet().stream()
                .mapToDouble(e -> e.getValue() * VisitorNpcPrices.getPrice(e.getKey()))
                .sum();
    }

    /** Total pest-kill NPC profit accumulated this session. */
    public double getPestProfit() {
        return pestItems.entrySet().stream()
                .mapToDouble(e -> e.getValue() * VisitorNpcPrices.getPrice(e.getKey()))
                .sum();
    }

    /** Farming profit per hour (coins/hour) based on total farming time. */
    public double getFarmingProfitPerHour() {
        long totalMs = farmingTotalMs;
        if (wasFarming && farmingSessionStartMs >= 0) {
            totalMs += System.currentTimeMillis() - farmingSessionStartMs;
        }
        if (totalMs <= 0) return 0.0;
        return getFarmingProfit() / (totalMs / 3_600_000.0);
    }

    /** Pest-kill profit per hour (coins/hour) based on total pest-killing time. */
    public double getPestProfitPerHour() {
        long totalMs = pestTotalMs;
        if (wasPestActive && pestSessionStartMs >= 0) {
            totalMs += System.currentTimeMillis() - pestSessionStartMs;
        }
        if (totalMs <= 0) return 0.0;
        return getPestProfit() / (totalMs / 3_600_000.0);
    }

    /**
     * Resets all accumulated data and time counters, ready for a fresh
     * tracking session.
     */
    public void reset() {
        farmingItems.clear();
        pestItems.clear();
        displayNames.clear();
        prevSnapshot.clear();
        farmingTotalMs     = 0;
        pestTotalMs        = 0;
        farmingSessionStartMs = -1;
        pestSessionStartMs    = -1;
        wasFarming    = false;
        wasPestActive = false;
        trackerHasData = false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    /**
     * A single row in the profit display: item display name, total count
     * collected, and total NPC profit value.
     */
    public record ProfitEntry(String displayName, long count, double profit) {}
}
