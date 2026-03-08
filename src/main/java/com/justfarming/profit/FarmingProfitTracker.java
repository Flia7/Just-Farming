package com.justfarming.profit;

import com.justfarming.MacroManager;
import com.justfarming.pest.PestKillerManager;
import com.justfarming.visitor.VisitorNpcPrices;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // ── Active-session elapsed time (only counts while a macro is running) ───
    /** Accumulated milliseconds during which at least one macro was active. */
    private long sessionActiveTotalMs    = 0;
    /** Wall-clock start of the current active segment; -1 when no macro is running. */
    private long sessionActiveStartMs    = -1;
    /** Whether any macro was active in the previous tick. */
    private boolean wasAnyMacroActive    = false;

    // ── BPS (blocks per second) tracking via a sliding-window buffer ─────────
    /** Circular timestamp buffer for recent block breaks. */
    private final long[] breakTimestamps = new long[1000];
    private int  breakHead  = 0;  // next write index
    private int  breakCount = 0;  // number of valid entries
    /** Sliding window length for BPS calculation (milliseconds). */
    private static final long BPS_WINDOW_MS = 5_000L;
    /** Milliseconds in one hour, used for profit/hour calculations. */
    private static final double MS_PER_HOUR = 3_600_000.0;

    // ── Pest cooldown: keep attributing gains to pest for a short time ────────
    /** Timestamp of the last tick where the pest killer was active (-1 if never). */
    private long lastPestActiveMs = -1;
    /** How long after pest activity ends to still attribute gains to pest killing. */
    private static final long PEST_COOLDOWN_MS = 3_000L;

    // ── Vinyl name normalization set ─────────────────────────────────────────
    /**
     * All individual pest-vinyl item names that should be grouped under the
     * "Pest Vinyl" display entry in the profit HUD.
     */
    private static final Set<String> VINYL_NAMES = new HashSet<>(Arrays.asList(
            "pretty fly", "not just a pest", "cricket choir", "cicada symphony",
            "buzzin' beats", "dynamites", "wings of harmony", "rodent revolution",
            "slow and groovy", "earthworm ensemble", "beetle beats", "slug groove",
            "mosquito melody", "locust lullaby", "mite march"
    ));

    // ── Chat-message patterns for pest drop tracking ──────────────────────────
    /**
     * Matches Hypixel SkyBlock "You received" item-drop messages produced when
     * a pest is killed and its loot goes directly to the player's collection
     * (bypassing the inventory).
     *
     * <p>Example (stripped of colour codes):
     * {@code "You received 163x Enchanted Nether Wart."}
     * or
     * {@code "You received 12 Nether Wart."}
     * or
     * {@code "You received 187x Enchanted Potato for killing a Locust!"}
     *
     * <p>The item name is captured until a period, " for " (e.g. "for killing"),
     * or end-of-string – whichever comes first.
     */
    private static final Pattern CHAT_ITEM_PATTERN =
            Pattern.compile("You received (\\d+)x?\\s+(.+?)(?:\\s+for\\s+|\\.|$)", Pattern.CASE_INSENSITIVE);

    /**
     * Matches the coin reward line sent when a pest is killed.
     *
     * <p>Example: {@code "You received 450 Coins."} or {@code "You received 1,200 Coins."}
     */
    private static final Pattern CHAT_COIN_PATTERN =
            Pattern.compile("You received ([\\d,]+) Coins?\\.?", Pattern.CASE_INSENSITIVE);

    /** Internal key used for coin entries in the pest items map. */
    private static final String COINS_KEY = "coins";

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
     * @param isVisitorActive whether the visitor routine is currently running
     */
    public void onTick(MinecraftClient client,
                       MacroManager macroManager,
                       PestKillerManager pestKillerManager,
                       boolean isVisitorActive) {
        if (client == null || client.player == null) return;

        boolean isPestActive = pestKillerManager != null && pestKillerManager.isActive();
        boolean isFarming    = !isPestActive
                && macroManager != null && macroManager.isRunning();
        boolean isAnyMacroActive = isFarming || isPestActive || isVisitorActive;

        long nowMs = System.currentTimeMillis();

        // ── Track active-session elapsed time (only when any macro is running) ──
        if (isAnyMacroActive) {
            if (!wasAnyMacroActive) {
                // Macro just started: begin accumulating
                sessionActiveStartMs = nowMs;
            }
        } else {
            if (wasAnyMacroActive && sessionActiveStartMs >= 0) {
                // Macro just stopped: commit the elapsed segment
                sessionActiveTotalMs += nowMs - sessionActiveStartMs;
                sessionActiveStartMs = -1;
            }
        }
        wasAnyMacroActive = isAnyMacroActive;

        // ── Track pest cooldown window ────────────────────────────────────────
        if (isPestActive) {
            lastPestActiveMs = nowMs;
        }
        // Attribute to pest if currently active OR within the cooldown window
        boolean inPestCooldown = lastPestActiveMs >= 0
                && (nowMs - lastPestActiveMs) < PEST_COOLDOWN_MS;
        boolean trackAsPest = isPestActive || inPestCooldown;

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
        // Farming takes priority over the pest cooldown: if the macro is actively
        // farming, attribute gains to farming even if a recent pest kill is still
        // in the cooldown window.
        if (isFarming || trackAsPest) {
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

    /** Lower-cased, colour-stripped item name used as map key. Vinyl names are normalised to "pest vinyl". */
    private static String plainKey(ItemStack stack) {
        String key = stripColor(stack.getName().getString()).toLowerCase().trim();
        return VINYL_NAMES.contains(key) ? "pest vinyl" : key;
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
     * Records a single block break event for BPS calculation.
     * Should be called each time the macro actually attacks/breaks a block.
     */
    public void registerBlockBreak() {
        long now = System.currentTimeMillis();
        breakTimestamps[breakHead] = now;
        breakHead = (breakHead + 1) % breakTimestamps.length;
        if (breakCount < breakTimestamps.length) breakCount++;
    }

    /**
     * Returns the average blocks-per-second broken over the recent sliding
     * window ({@value #BPS_WINDOW_MS} ms).
     */
    public double getAverageBps() {
        if (breakCount == 0) return 0.0;
        long now = System.currentTimeMillis();
        long windowStart = now - BPS_WINDOW_MS;
        int recent = 0;
        for (int i = 0; i < breakCount; i++) {
            int idx = ((breakHead - 1 - i) + breakTimestamps.length) % breakTimestamps.length;
            if (breakTimestamps[idx] >= windowStart) recent++;
            else break;
        }
        return recent / (BPS_WINDOW_MS / 1000.0);
    }

    /**
     * Returns the total elapsed milliseconds during which at least one macro
     * (farming, pest killer, or visitor) was running this session.
     * Pauses automatically when no macro is active, so the timer only advances
     * while farming, killing pests, or running the visitor routine.
     * Returns {@code 0} before any activity.
     */
    public long getSessionElapsedMs() {
        long total = sessionActiveTotalMs;
        if (wasAnyMacroActive && sessionActiveStartMs >= 0) {
            total += System.currentTimeMillis() - sessionActiveStartMs;
        }
        return total;
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
        return getFarmingProfit() / (totalMs / MS_PER_HOUR);
    }

    /** Pest-kill profit per hour (coins/hour) based on total pest-killing time. */
    public double getPestProfitPerHour() {
        long totalMs = pestTotalMs;
        if (wasPestActive && pestSessionStartMs >= 0) {
            totalMs += System.currentTimeMillis() - pestSessionStartMs;
        }
        if (totalMs <= 0) return 0.0;
        return getPestProfit() / (totalMs / MS_PER_HOUR);
    }

    /**
     * Combined (farming + pest) profit per hour based on total active session
     * elapsed time.
     *
     * <p>Calculated as:
     * (total farming profit + total pest profit) ÷ session elapsed seconds × 3600.
     *
     * @param includePest whether to include pest profit in the total
     */
    public double getCombinedProfitPerHour(boolean includePest) {
        long elapsedMs = getSessionElapsedMs();
        if (elapsedMs <= 0) return 0.0;
        double totalProfit = getFarmingProfit() + (includePest ? getPestProfit() : 0.0);
        return totalProfit / (elapsedMs / MS_PER_HOUR);
    }

    /**
     * Parses one game chat message and attributes item/coin gains to the pest
     * profit section when the pest killer is active (or within its cooldown
     * window).
     *
     * <p>Hypixel SkyBlock sends a "You received Nx Item" message when a pest is
     * killed and its loot is teleported directly to the player's collection
     * storage, so it never passes through the inventory.  This method ensures
     * those gains are still captured by the profit tracker.
     *
     * @param rawMessage      the plain-text chat line (colour codes already stripped
     *                        by {@link net.minecraft.text.Text#getString()})
     * @param pestKillerManager the pest killer manager used to determine context
     */
    public void onChatMessage(String rawMessage, PestKillerManager pestKillerManager) {
        if (rawMessage == null || rawMessage.isBlank()) return;

        // Strip Minecraft colour codes in case the caller didn't.
        String plain = stripColor(rawMessage).trim();

        long nowMs = System.currentTimeMillis();
        boolean isPestActive = pestKillerManager != null && pestKillerManager.isActive();
        boolean inPestCooldown = lastPestActiveMs >= 0
                && (nowMs - lastPestActiveMs) < PEST_COOLDOWN_MS;
        boolean trackAsPest = isPestActive || inPestCooldown;

        if (!trackAsPest) return;

        // Try to match coin reward first (e.g. "You received 450 Coins.")
        Matcher coinMatcher = CHAT_COIN_PATTERN.matcher(plain);
        if (coinMatcher.find()) {
            try {
                long amount = Long.parseLong(coinMatcher.group(1).replace(",", ""));
                if (amount > 0) {
                    trackerHasData = true;
                    pestItems.merge(COINS_KEY, amount, Long::sum);
                    displayNames.putIfAbsent(COINS_KEY, "Coins");
                }
            } catch (NumberFormatException ignored) {}
            return;
        }

        // Try to match item reward (e.g. "You received 163x Enchanted Nether Wart.")
        Matcher itemMatcher = CHAT_ITEM_PATTERN.matcher(plain);
        if (itemMatcher.find()) {
            try {
                long amount = Long.parseLong(itemMatcher.group(1));
                String itemName = itemMatcher.group(2).trim();
                if (amount > 0 && !itemName.isEmpty()) {
                    String key = normalizeItemName(itemName);
                    trackerHasData = true;
                    pestItems.merge(key, amount, Long::sum);
                    displayNames.putIfAbsent(key, itemName);
                }
            } catch (NumberFormatException ignored) {}
        }
    }

    /**
     * Normalizes a raw item name from the chat message to the same lower-cased,
     * vinyl-grouped key format used for inventory-tracked items.
     */
    private static String normalizeItemName(String rawName) {
        String key = rawName.toLowerCase().trim();
        return VINYL_NAMES.contains(key) ? "pest vinyl" : key;
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
        sessionActiveTotalMs  = 0;
        sessionActiveStartMs  = -1;
        wasAnyMacroActive     = false;
        lastPestActiveMs      = -1;
        wasFarming    = false;
        wasPestActive = false;
        trackerHasData = false;
        breakHead  = 0;
        breakCount = 0;
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
