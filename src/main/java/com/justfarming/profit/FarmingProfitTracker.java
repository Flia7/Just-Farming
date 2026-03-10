package com.justfarming.profit;

import com.justfarming.CropType;
import com.justfarming.MacroManager;
import com.justfarming.pest.PestKillerManager;
import com.justfarming.visitor.VisitorNpcPrices;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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

    // ── Farming fortune (read from the tab list) ──────────────────────────────
    /**
     * Total farming fortune detected from the player-list (tab) entries.
     * Combines the general "Farming Fortune" stat and the crop-specific
     * "{Crop} Fortune" stat so the formula uses the full effective value.
     * Updated on each game tick via {@link #refreshFarmingFortune(MinecraftClient, CropType)}.
     */
    private double farmingFortune = 0.0;
    /**
     * The crop-specific portion of {@link #farmingFortune} (e.g. "Carrot Fortune").
     * Stored separately so the HUD can display the breakdown.
     * {@code 0.0} when no crop-specific fortune has been detected.
     */
    private double cropFortune = 0.0;
    /** How often (ms) the tab-list farming fortune is re-read. */
    private static final long FORTUNE_REFRESH_MS = 2_000L;
    /** Wall-clock time of the last fortune refresh, or {@code 0} if never. */
    private long lastFortuneRefreshMs = 0L;

    /** Matches "Farming Fortune: 1,234" in stripped tab-list text. */
    private static final Pattern FARMING_FORTUNE_PATTERN =
            Pattern.compile("Farming\\s+Fortune:\\s*([\\d,]+)", Pattern.CASE_INSENSITIVE);

    /**
     * Matches "{word(s)} Fortune: 123" for crop-specific fortune entries.
     * Requires at least one letter followed by " Fortune:" so it won't match
     * "Farming Fortune" (which is handled separately by FARMING_FORTUNE_PATTERN).
     */
    private static final Pattern CROP_FORTUNE_PATTERN =
            Pattern.compile("([A-Za-z][A-Za-z ]+?)\\s+Fortune:\\s*([\\d,]+)", Pattern.CASE_INSENSITIVE);

    // ── Pest cooldown: keep attributing gains to pest for a short time ────────
    /** Timestamp of the last tick where the pest killer was active (-1 if never). */
    private long lastPestActiveMs = -1;
    /** How long after pest activity ends to still attribute gains to pest killing. */

    // ── Cocoa beans formula tracking ─────────────────────────────────────────
    /**
     * Fractional accumulator for formula-calculated cocoa-bean drops.
     * Each block break while farming COCOA_BEANS adds
     * {@code baseDrops × (1 + farmingFortune / 100)} here; once the value
     * reaches ≥ 1 the integer portion is flushed to {@link #farmingItems}.
     */
    private double pendingCocoaDrops = 0.0;
    /**
     * Wall-clock timestamp (ms) of the most recent
     * {@link #registerCropBlockBreak(CropType)} call for COCOA_BEANS.
     * Used to detect whether the formula-tracking path is currently active so
     * that inventory-diff and sack-message paths can suppress the "cocoa beans"
     * key and avoid double-counting.
     */
    private long cocoaFormulaLastCallMs = 0L;
    /**
     * How long (ms) after the last cocoa-bean block break before the formula
     * tracking is considered inactive.  A 5-second window handles typical tick
     * jitter and brief pauses (lane swaps, etc.) without falsely suppressing
     * sack messages during a different crop session.
     */
    private static final long COCOA_FORMULA_ACTIVE_WINDOW_MS = 5_000L;
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

    /**
     * Matches Hypixel SkyBlock rare-drop notifications.
     *
     * <p>Example (colour codes stripped):
     * {@code "RARE DROP! Polished Pumpkin (+206)"}
     *
     * <p>The farming-fortune bonus {@code (+N)} is ignored; the item count is
     * always 1.  The item name is captured up to the optional {@code (+N)} suffix.
     */
    private static final Pattern RARE_DROP_PATTERN =
            Pattern.compile("RARE DROP! (.+?)(?:\\s*\\(\\+[\\d]+\\))?\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * Matches Hypixel SkyBlock pet-drop notifications from pest kills.
     *
     * <p>Example (colour codes stripped):
     * {@code "PET DROP! Rat (+206)"}
     * {@code "PET DROP! Slug (+206)"}
     *
     * <p>The farming-fortune bonus {@code (+N)} is ignored; the item count is
     * always 1.  The pet name is captured up to the optional {@code (+N)} suffix.
     */
    private static final Pattern PET_DROP_PATTERN =
            Pattern.compile("PET DROP! (.+?)(?:\\s*\\(\\+[\\d]+\\))?\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * Matches Hypixel SkyBlock sack-deposit notifications sent both as regular
     * chat messages and as action-bar overlay entries when an item is collected
     * and deposited into a sack rather than the player's inventory.
     *
     * <p>Examples (colour codes stripped):
     * <ul>
     *   <li>{@code "+1 Sugar Cane ➜ Sugar Cane Sack"}</li>
     *   <li>{@code "+5 Red Mushroom → Red Mushroom Sack"}</li>
     *   <li>{@code "+64 Cocoa Beans"}</li>
     * </ul>
     *
     * <p>Group 1 = item count, Group 2 = item name (before any arrow / end-of-line).
     * Used to count crop and mushroom drops that bypass the player's inventory.
     * Arrow characters: U+279C (➜), U+2192 (→), and plain ASCII &gt; as fallback.
     */
    private static final Pattern SACK_DEPOSIT_PATTERN =
            Pattern.compile("^\\+(\\d[\\d,]*)\\s+([A-Za-z][A-Za-z0-9 ]*)\\s*(?:[➜→>]|$)",
                    Pattern.CASE_INSENSITIVE);

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

    // ── Cached item icons (lower-cased plain name → Minecraft Item) ──────────
    /** Maps lower-cased plain name → Minecraft Item for icon rendering in the HUD. */
    private final Map<String, Item> itemIcons = new HashMap<>();

    // ── Default item icons for known crops and drops ──────────────────────────
    /**
     * Well-known item name → Minecraft Item mappings that are pre-populated at
     * construction time (and after each {@link #reset()}).  These ensure that
     * every crop shows an icon in the Profit HUD even when the item has never
     * physically appeared in the player's 36-slot inventory (e.g. items that
     * went straight into a sack).
     */
    private static final Map<String, Item> DEFAULT_ICONS = new HashMap<>();
    static {
        // ── Base crops ──────────────────────────────────────────────────────
        DEFAULT_ICONS.put("wheat",           Items.WHEAT);
        DEFAULT_ICONS.put("carrot",          Items.CARROT);
        DEFAULT_ICONS.put("potato",          Items.POTATO);
        DEFAULT_ICONS.put("pumpkin",         Items.PUMPKIN);
        DEFAULT_ICONS.put("melon",           Items.MELON);
        DEFAULT_ICONS.put("melon slice",     Items.MELON_SLICE);
        DEFAULT_ICONS.put("sugar cane",      Items.SUGAR_CANE);
        DEFAULT_ICONS.put("nether wart",     Items.NETHER_WART);
        DEFAULT_ICONS.put("cactus",          Items.CACTUS);
        DEFAULT_ICONS.put("red mushroom",    Items.RED_MUSHROOM);
        DEFAULT_ICONS.put("brown mushroom",  Items.BROWN_MUSHROOM);
        DEFAULT_ICONS.put("cocoa beans",     Items.COCOA_BEANS);
        DEFAULT_ICONS.put("seeds",           Items.WHEAT_SEEDS);
        DEFAULT_ICONS.put("wheat seeds",     Items.WHEAT_SEEDS);
        DEFAULT_ICONS.put("sunflower",       Items.SUNFLOWER);
        // Hypixel SkyBlock items without a direct vanilla equivalent use the
        // closest visual substitute.
        DEFAULT_ICONS.put("wild rose",       Items.ROSE_BUSH);
        DEFAULT_ICONS.put("moonflower",      Items.LILY_OF_THE_VALLEY);
        // ── Enchanted / processed forms (reuse base icon, renderer adds glint) ─
        DEFAULT_ICONS.put("enchanted wheat",              Items.WHEAT);
        DEFAULT_ICONS.put("enchanted bread",              Items.BREAD);
        DEFAULT_ICONS.put("enchanted carrot",             Items.CARROT);
        DEFAULT_ICONS.put("enchanted golden carrot",      Items.GOLDEN_CARROT);
        DEFAULT_ICONS.put("enchanted potato",             Items.POTATO);
        DEFAULT_ICONS.put("enchanted baked potato",       Items.BAKED_POTATO);
        DEFAULT_ICONS.put("enchanted pumpkin",            Items.PUMPKIN);
        DEFAULT_ICONS.put("enchanted melon",              Items.MELON_SLICE);
        DEFAULT_ICONS.put("enchanted melon slice",        Items.MELON_SLICE);
        DEFAULT_ICONS.put("enchanted sugar",              Items.SUGAR);
        DEFAULT_ICONS.put("enchanted sugar cane",         Items.SUGAR_CANE);
        DEFAULT_ICONS.put("enchanted nether wart",        Items.NETHER_WART);
        DEFAULT_ICONS.put("enchanted cactus green",       Items.CACTUS);
        DEFAULT_ICONS.put("enchanted cactus",             Items.CACTUS);
        DEFAULT_ICONS.put("enchanted red mushroom",       Items.RED_MUSHROOM);
        DEFAULT_ICONS.put("enchanted brown mushroom",     Items.BROWN_MUSHROOM);
        DEFAULT_ICONS.put("enchanted red mushroom block", Items.RED_MUSHROOM_BLOCK);
        DEFAULT_ICONS.put("enchanted brown mushroom block", Items.BROWN_MUSHROOM_BLOCK);
        DEFAULT_ICONS.put("enchanted cocoa beans",        Items.COCOA_BEANS);
        DEFAULT_ICONS.put("enchanted cookie",             Items.COOKIE);
        DEFAULT_ICONS.put("enchanted wild rose",          Items.ROSE_BUSH);
        DEFAULT_ICONS.put("enchanted sunflower",          Items.SUNFLOWER);
        DEFAULT_ICONS.put("enchanted moonflower",         Items.LILY_OF_THE_VALLEY);
        DEFAULT_ICONS.put("enchanted seeds",              Items.WHEAT_SEEDS);
        DEFAULT_ICONS.put("enchanted hay bale",           Items.HAY_BLOCK);
        DEFAULT_ICONS.put("hay bale",                     Items.HAY_BLOCK);
        DEFAULT_ICONS.put("mutant nether wart",           Items.NETHER_WART_BLOCK);
        DEFAULT_ICONS.put("polished pumpkin",             Items.PUMPKIN);
        DEFAULT_ICONS.put("enchanted melon block",        Items.MELON);
        DEFAULT_ICONS.put("compacted wild rose",          Items.ROSE_BUSH);
        // ── Universal pest drops ─────────────────────────────────────────────
        DEFAULT_ICONS.put("compost",         Items.DIRT);
        DEFAULT_ICONS.put("honey jar",       Items.HONEY_BOTTLE);
        DEFAULT_ICONS.put("plant matter",    Items.OAK_LEAVES);
        DEFAULT_ICONS.put("tasty cheese",    Items.YELLOW_DYE);
        DEFAULT_ICONS.put("jelly",           Items.SLIME_BALL);
        DEFAULT_ICONS.put("dung",            Items.BROWN_DYE);
        DEFAULT_ICONS.put("coins",           Items.GOLD_NUGGET);
        // ── Pest vinyls ──────────────────────────────────────────────────────
        DEFAULT_ICONS.put("pest vinyl",      Items.MUSIC_DISC_13);
    }

    // ── Display-data cache (refreshed every second) ───────────────────────────
    /** How often (ms) the Profit HUD display data is refreshed. */
    public static final long DISPLAY_UPDATE_INTERVAL_MS = 1000L;
    /** Wall-clock time of the last display-cache refresh, or {@code 0} if never. */
    private long lastDisplayUpdateMs = 0L;
    /** Cached farming entry list for HUD rendering. */
    private List<ProfitEntry> displayFarmingEntries = List.of();
    /** Cached pest entry list for HUD rendering. */
    private List<ProfitEntry> displayPestEntries = List.of();
    /** Cached total farming profit for HUD rendering. */
    private double displayFarmingProfit = 0.0;
    /** Cached total pest profit for HUD rendering. */
    private double displayPestProfit = 0.0;
    /** Cached combined profit/hour for HUD rendering. */
    private double displayCombinedProfitPerHour = 0.0;
    /** Whether pest profit was included in the last {@link #displayCombinedProfitPerHour} calculation. */
    private boolean displayIncludePest = false;

    // ── Number of farming/pest ticks tracked (for "is active" queries) ───────
    private boolean trackerHasData = false;

    // ── Constructor ───────────────────────────────────────────────────────────

    public FarmingProfitTracker() {
        itemIcons.putAll(DEFAULT_ICONS);
    }

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
            Set<String> allKeys = new HashSet<>(snapshot.keySet());
            allKeys.addAll(prevSnapshot.keySet());
            Map<String, Long> target = isFarming ? farmingItems : pestItems;

            // Record all item gains (positive deltas only).
            for (String key : allKeys) {
                // When farming Cocoa Beans, drops are tracked via the per-break
                // formula in registerCropBlockBreak() and should not be
                // double-counted from the inventory diff.
                if (isFarming && key.equals("cocoa beans") && isCocoaFormulaActive()) continue;
                long current = snapshot.getOrDefault(key, 0L);
                long prev    = prevSnapshot.getOrDefault(key, 0L);
                long delta   = current - prev;
                if (delta > 0) {
                    trackerHasData = true;
                    target.merge(key, delta, Long::sum);
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
            itemIcons.putIfAbsent(key, stack.getItem());
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
     * Records a block break for BPS tracking and, for Cocoa Beans, directly
     * accumulates formula-calculated drops into {@link #farmingItems} so that
     * the profit count works even when beans bypass the player's inventory and
     * go straight to a sack.
     *
     * <p>Formula: {@code avgDrops = baseDrops × (1 + farmingFortune / 100)}.
     * Drops are accumulated fractionally and flushed once the running total
     * reaches 1 or more, preventing rounding errors from accumulating over a
     * long session.
     *
     * <p>For all crops other than Cocoa Beans this method is equivalent to
     * {@link #registerBlockBreak()} – item gains continue to be detected via
     * the normal inventory-diff path in {@link #onTick}.
     *
     * @param crop the crop type currently being farmed (may be {@code null})
     */
    public void registerCropBlockBreak(CropType crop) {
        registerBlockBreak(); // Always register for BPS tracking
        if (crop != CropType.COCOA_BEANS) return;
        // Cocoa beans: add expected drops directly to farmingItems so that
        // beans going to sacks (which skip the inventory) are counted correctly.
        // When farmingFortune has not yet been detected (= 0) the formula falls
        // back to the base drop rate, which is still a better estimate than
        // waiting for sack/inventory messages that may never arrive.
        double avgDrops = crop.getBaseDrops() * (1.0 + farmingFortune / 100.0);
        pendingCocoaDrops += avgDrops;
        if (pendingCocoaDrops >= 1.0) {
            long drops = (long) Math.floor(pendingCocoaDrops);
            pendingCocoaDrops -= drops;
            trackerHasData = true;
            farmingItems.merge("cocoa beans", drops, Long::sum);
            displayNames.putIfAbsent("cocoa beans", "Cocoa Beans");
        }
        cocoaFormulaLastCallMs = System.currentTimeMillis();
    }

    /**
     * Returns {@code true} when cocoa-bean formula tracking is currently active
     * (i.e. {@link #registerCropBlockBreak} was called with COCOA_BEANS within
     * the last {@value #COCOA_FORMULA_ACTIVE_WINDOW_MS} ms).  Used to suppress
     * inventory-diff and sack-message tracking for "cocoa beans" to prevent
     * double-counting.
     */
    private boolean isCocoaFormulaActive() {
        return cocoaFormulaLastCallMs > 0
                && System.currentTimeMillis() - cocoaFormulaLastCallMs < COCOA_FORMULA_ACTIVE_WINDOW_MS;
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
     * Calculates the estimated crops harvested per second using the full
     * Hypixel SkyBlock farming fortune formula:
     * <pre>
     *   Crops/s = baseDrops × (1 + farmingFortune / 100) × BPS
     * </pre>
     * Returns {@code 0.0} when the BPS counter is zero (macro not running).
     *
     * @param crop the currently selected crop (provides baseDrops)
     */
    public double calculateCropsPerSecond(CropType crop) {
        double bps = getAverageBps();
        if (bps <= 0 || crop == null) return 0.0;
        return crop.getBaseDrops() * (1.0 + farmingFortune / 100.0) * bps;
    }

    /**
     * Returns the last detected total farming fortune (general + crop-specific),
     * as read from the player-list (tab) entries.  Returns {@code 0.0} if the
     * fortune has not yet been detected or if the value could not be parsed.
     */
    public double getFarmingFortune() {
        return farmingFortune;
    }

    /**
     * Returns the last detected crop-specific fortune (e.g. "Carrot Fortune"),
     * as read from the player-list (tab) entries.  Returns {@code 0.0} if no
     * crop-specific fortune has been detected for the currently selected crop.
     * This value is already included in {@link #getFarmingFortune()}.
     */
    public double getCropFortune() {
        return cropFortune;
    }

    /**
     * Reads the player-list (tab list) entries to detect the current farming
     * fortune.  Combines both the general {@code "Farming Fortune: N"} entry and
     * any crop-specific {@code "{Crop} Fortune: N"} entry so the full effective
     * fortune is available for the crops-per-second formula.
     *
     * <p>Refreshes at most every {@value #FORTUNE_REFRESH_MS} ms.  Crop-specific
     * fortune uses the name of the currently selected crop (e.g. "Carrot Fortune"
     * when the selected crop is CARROT).
     *
     * @param client      the Minecraft client (must not be null)
     * @param selectedCrop the currently configured crop (used to find crop-specific fortune)
     */
    public void refreshFarmingFortune(MinecraftClient client, CropType selectedCrop) {
        long now = System.currentTimeMillis();
        if (lastFortuneRefreshMs > 0 && now - lastFortuneRefreshMs < FORTUNE_REFRESH_MS) return;
        lastFortuneRefreshMs = now;

        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        if (handler == null) return;

        Collection<PlayerListEntry> entries = handler.getPlayerList();
        if (entries == null || entries.isEmpty()) return;

        // Crop-specific fortune key e.g. "carrot", "nether wart" (via CropType helper).
        String cropKey = selectedCrop != null ? selectedCrop.getCropFortuneKey() : "";

        double baseFortune = 0.0;
        double cropFortune = 0.0;

        for (PlayerListEntry entry : entries) {
            Text displayName = entry.getDisplayName();
            if (displayName == null) continue;
            String text = stripColor(displayName.getString());

            // Check for general "Farming Fortune: N" first.
            Matcher fm = FARMING_FORTUNE_PATTERN.matcher(text);
            if (fm.find()) {
                try {
                    baseFortune = Double.parseDouble(fm.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {}
                continue; // don't also check the crop pattern on the same entry
            }

            // Check for crop-specific fortune only when a crop is selected.
            if (!cropKey.isEmpty()) {
                Matcher cm = CROP_FORTUNE_PATTERN.matcher(text);
                if (cm.find()) {
                    String label = cm.group(1).trim().toLowerCase();
                    if (label.equals(cropKey)) {
                        try {
                            cropFortune = Double.parseDouble(cm.group(2).replace(",", ""));
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        this.cropFortune  = cropFortune;
        farmingFortune    = baseFortune + cropFortune;
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
            // If the enchanted form of this item has already been recorded (meaning
            // compaction is active), suppress the base-item row.  This mirrors
            // SkyHanni behaviour: basic items are shown until the sacks fill and
            // compaction begins; once enchanted items appear only the enchanted row
            // is displayed, eliminating the flickery appear/disappear cycle.
            if (!key.startsWith("enchanted ") && items.containsKey("enchanted " + key)) {
                continue;
            }
            double price = VisitorNpcPrices.getPrice(key);
            double profit = count * price;
            String nice  = displayNames.getOrDefault(key, capitalize(key));
            list.add(new ProfitEntry(nice, count, profit, itemIcons.get(key)));
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

    // ── Throttled display cache ───────────────────────────────────────────────

    /**
     * Refreshes the cached display data if {@link #DISPLAY_UPDATE_INTERVAL_MS}
     * milliseconds have elapsed since the last refresh.  Cheap no-op otherwise.
     * Call this once per HUD render frame, then read values via the
     * {@code getDisplay*} accessors to get smoothly throttled, flicker-free
     * profit data.
     *
     * @param includePest whether pest profit is included in the combined P/h
     */
    public void refreshDisplayCache(boolean includePest) {
        long now = System.currentTimeMillis();
        if (lastDisplayUpdateMs > 0 && now - lastDisplayUpdateMs < DISPLAY_UPDATE_INTERVAL_MS) return;
        lastDisplayUpdateMs        = now;
        displayFarmingEntries      = toEntries(farmingItems);
        displayPestEntries         = toEntries(pestItems);
        displayFarmingProfit       = getFarmingProfit();
        displayPestProfit          = getPestProfit();
        displayIncludePest         = includePest;
        displayCombinedProfitPerHour = getCombinedProfitPerHour(includePest);
    }

    /**
     * Returns the throttled farming entry list.
     * Call {@link #refreshDisplayCache} once per render frame before using this.
     */
    public List<ProfitEntry> getDisplayFarmingEntries() { return displayFarmingEntries; }

    /**
     * Returns the throttled pest entry list.
     * Call {@link #refreshDisplayCache} once per render frame before using this.
     */
    public List<ProfitEntry> getDisplayPestEntries() { return displayPestEntries; }

    /** Returns the throttled total farming profit. */
    public double getDisplayFarmingProfit() { return displayFarmingProfit; }

    /** Returns the throttled total pest profit. */
    public double getDisplayPestProfit() { return displayPestProfit; }

    /**
     * Returns the throttled combined profit/hour.
     * Includes pest profit only if {@code includePest} was {@code true} in the
     * most recent {@link #refreshDisplayCache} call.
     */
    public double getDisplayCombinedProfitPerHour() { return displayCombinedProfitPerHour; }

    /**
     * Returns the total display profit (farming + optional pest) using the
     * throttled cached values.
     */
    public double getDisplayTotalProfit() {
        return displayFarmingProfit + (displayIncludePest ? displayPestProfit : 0.0);
    }

    /**
     * Parses one game chat message and attributes item/coin gains to the pest
     * profit section when the pest killer is active (or within its cooldown
     * window), or to the farming profit section when the farming macro is active.
     *
     * <p>Hypixel SkyBlock sends a "You received Nx Item" message when a pest is
     * killed and its loot is teleported directly to the player's collection
     * storage, so it never passes through the inventory.  This method ensures
     * those gains are still captured by the profit tracker.
     *
     * <p>Also handles "RARE DROP! Item (+N)" and "PET DROP! Item (+N)" messages
     * produced when a rare item or pet drops from a pest kill.
     *
     * @param rawMessage        the plain-text chat line (colour codes already stripped
     *                          by {@link net.minecraft.text.Text#getString()})
     * @param pestKillerManager the pest killer manager used to determine context
     * @param macroManager      the farming macro manager used to determine farming context
     */
    public void onChatMessage(String rawMessage, PestKillerManager pestKillerManager, MacroManager macroManager) {
        if (rawMessage == null || rawMessage.isBlank()) return;

        // Strip Minecraft colour codes in case the caller didn't.
        String plain = stripColor(rawMessage).trim();

        long nowMs = System.currentTimeMillis();
        boolean isPestActive = pestKillerManager != null && pestKillerManager.isActive();
        boolean inPestCooldown = lastPestActiveMs >= 0
                && (nowMs - lastPestActiveMs) < PEST_COOLDOWN_MS;
        boolean trackAsPest = isPestActive || inPestCooldown;
        boolean isFarming   = !trackAsPest && macroManager != null && macroManager.isRunning();

        if (!trackAsPest && !isFarming) return;

        Map<String, Long> target = trackAsPest ? pestItems : farmingItems;

        // Try to match coin reward first (e.g. "You received 450 Coins.") – pest only.
        if (trackAsPest) {
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

            // Try to match RARE DROP! notification (e.g. "RARE DROP! Polished Pumpkin (+206)").
            Matcher rareDropMatcher = RARE_DROP_PATTERN.matcher(plain);
            if (rareDropMatcher.find()) {
                String itemName = rareDropMatcher.group(1).trim();
                if (!itemName.isEmpty()) {
                    String key = normalizeItemName(itemName);
                    trackerHasData = true;
                    pestItems.merge(key, 1L, Long::sum);
                    displayNames.putIfAbsent(key, itemName);
                }
                return;
            }

            // Try to match PET DROP! notification (e.g. "PET DROP! Rat (+206)").
            Matcher petDropMatcher = PET_DROP_PATTERN.matcher(plain);
            if (petDropMatcher.find()) {
                String petName = petDropMatcher.group(1).trim();
                if (!petName.isEmpty()) {
                    String key = normalizePetName(petName);
                    trackerHasData = true;
                    pestItems.merge(key, 1L, Long::sum);
                    displayNames.putIfAbsent(key, petName + " Pet");
                }
                return;
            }
        }

        // Try to match item reward (e.g. "You received 163x Enchanted Nether Wart.").
        Matcher itemMatcher = CHAT_ITEM_PATTERN.matcher(plain);
        if (itemMatcher.find()) {
            try {
                long amount = Long.parseLong(itemMatcher.group(1));
                String itemName = itemMatcher.group(2).trim();
                if (amount > 0 && !itemName.isEmpty()) {
                    String key = normalizeItemName(itemName);
                    // Cocoa beans are tracked via per-break formula; skip to avoid double-count.
                    if (isFarming && key.equals("cocoa beans") && isCocoaFormulaActive()) return;
                    trackerHasData = true;
                    target.merge(key, amount, Long::sum);
                    displayNames.putIfAbsent(key, itemName);
                }
            } catch (NumberFormatException ignored) {}
            return; // matched – don't also try the sack pattern
        }

        // Try to match Hypixel sack-deposit messages: "+5 Red Mushroom ➜ Sack"
        // These are sent when items are deposited into a sack rather than the
        // player's inventory (e.g. mooshroom-cow mushroom drops during farming).
        Matcher sackMatcher = SACK_DEPOSIT_PATTERN.matcher(plain);
        if (sackMatcher.find()) {
            try {
                long amount = Long.parseLong(sackMatcher.group(1).replace(",", ""));
                String itemName = sackMatcher.group(2).trim();
                if (amount > 0 && !itemName.isEmpty()) {
                    String key = normalizeItemName(itemName);
                    // Cocoa beans are tracked via per-break formula; skip to avoid double-count.
                    if (isFarming && key.equals("cocoa beans") && isCocoaFormulaActive()) return;
                    // Only count items that are in the price table or the icon map
                    // to avoid accumulating random "+N" strings from unrelated text.
                    if (VisitorNpcPrices.getPrice(key) > 0 || DEFAULT_ICONS.containsKey(key)) {
                        trackerHasData = true;
                        target.merge(key, amount, Long::sum);
                        displayNames.putIfAbsent(key, itemName);
                    }
                }
            } catch (NumberFormatException ignored) {}
        }
    }

    /**
     * Called for every action-bar (overlay) message received while the farming
     * macro is running.  Parses Hypixel SkyBlock sack-deposit notifications in
     * the form {@code "+N ItemName"} and attributes them to the farming profit
     * so that drops going directly to sacks (e.g. mooshroom-cow mushroom drops)
     * are counted even though they never appear in the player's inventory.
     *
     * @param rawMessage    the raw action-bar text (may contain colour codes)
     * @param macroManager  the farming macro manager used to determine context
     */
    public void onActionBarMessage(String rawMessage, MacroManager macroManager) {
        if (rawMessage == null || rawMessage.isBlank()) return;

        String plain = stripColor(rawMessage).trim();

        long nowMs = System.currentTimeMillis();
        boolean isPestActive = lastPestActiveMs >= 0 && (nowMs - lastPestActiveMs) < PEST_COOLDOWN_MS;
        boolean isFarming = !isPestActive && macroManager != null && macroManager.isRunning();
        if (!isFarming) return;

        // The action bar may contain multiple "+N Item" tokens separated by spaces.
        // Scan across the whole string for every sack-deposit pattern match.
        java.util.regex.Matcher m = SACK_DEPOSIT_PATTERN.matcher(plain);
        while (m.find()) {
            try {
                long amount = Long.parseLong(m.group(1).replace(",", ""));
                String itemName = m.group(2).trim();
                if (amount > 0 && !itemName.isEmpty()) {
                    String key = normalizeItemName(itemName);
                    // Cocoa beans are tracked via per-break formula; skip to avoid double-count.
                    if (key.equals("cocoa beans") && isCocoaFormulaActive()) continue;
                    if (VisitorNpcPrices.getPrice(key) > 0 || DEFAULT_ICONS.containsKey(key)) {
                        trackerHasData = true;
                        farmingItems.merge(key, amount, Long::sum);
                        displayNames.putIfAbsent(key, itemName);
                    }
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
     * Normalizes a raw pet name from a "PET DROP!" chat message to the
     * price-table key used in {@link VisitorNpcPrices}.
     *
     * <p>Hypixel reports the pet name without the "Pet" suffix, e.g. "Rat" or
     * "Slug".  This method appends " pet" so the key matches the price table.
     */
    private static String normalizePetName(String rawName) {
        return rawName.toLowerCase().trim() + " pet";
    }

    /**
     * Resets all accumulated data and time counters, ready for a fresh
     * tracking session.
     */
    public void reset() {
        farmingItems.clear();
        pestItems.clear();
        displayNames.clear();
        itemIcons.clear();
        itemIcons.putAll(DEFAULT_ICONS);  // restore pre-populated defaults
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
        farmingFortune        = 0.0;
        cropFortune           = 0.0;
        lastFortuneRefreshMs  = 0L;
        pendingCocoaDrops     = 0.0;
        cocoaFormulaLastCallMs = 0L;
        // Invalidate display cache so the cleared state is shown immediately.
        lastDisplayUpdateMs = 0L;
        displayFarmingEntries = List.of();
        displayPestEntries    = List.of();
        displayFarmingProfit  = 0.0;
        displayPestProfit     = 0.0;
        displayCombinedProfitPerHour = 0.0;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    /**
     * A single row in the profit display: item display name, total count
     * collected, total NPC profit value, and the Minecraft item for icon rendering.
     * {@code item} may be {@code null} when the item type is not known.
     */
    public record ProfitEntry(String displayName, long count, double profit, Item item) {}
}
