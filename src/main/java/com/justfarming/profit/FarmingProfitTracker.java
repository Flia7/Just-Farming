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
    private long sessionActiveTotalMs    = 0;
    private long sessionActiveStartMs    = -1;
    private boolean wasAnyMacroActive    = false;

    // ── BPS (blocks per second) tracking via per-second counting ─────────────
    private int blocksBrokenThisSecond = 0;
    private final List<Integer> blocksSpeedList = new ArrayList<>();
    private long lastSpeedCheckMs = 0;
    private int secondsStopped = 0;
    private static final int SPEED_RESET_SECONDS = 5;
    private static final int MAX_SPEED_HISTORY_SIZE = 20;
    // AVERAGE_WINDOW_SIZE = 6: take last 6 per-second buckets, average the first 5
    // (dropping the last/current-in-progress second), matching SkyHanni's getRecentBPS().
    private static final int AVERAGE_WINDOW_SIZE = 6;
    private static final double SECONDS_PER_HOUR = 3_600.0;

    // ── Farming fortune (read from the tab list) ──────────────────────────────
    private double farmingFortune = 0.0;
    private double cropFortune = 0.0;
    private static final long FORTUNE_REFRESH_MS = 2_000L;
    private long lastFortuneRefreshMs = 0L;

    private static final Pattern FARMING_FORTUNE_PATTERN =
            Pattern.compile("Farming\\s+Fortune:\\s*([\\d,]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern CROP_FORTUNE_PATTERN =
            Pattern.compile("([A-Za-z][A-Za-z ]+?)\\s+Fortune:\\s*([\\d,]+)", Pattern.CASE_INSENSITIVE);

    // ── Pest cooldown: keep attributing gains to pest for a short time ────────
    private long lastPestActiveMs = -1;
    // ── Cocoa beans formula tracking ─────────────────────────────────────────
    private double pendingCocoaDrops = 0.0;
    private long cocoaFormulaLastCallMs = 0L;
    private static final long COCOA_FORMULA_ACTIVE_WINDOW_MS = 5_000L;
    private static final long PEST_COOLDOWN_MS = 3_000L;

    // ── Vinyl name normalization set ─────────────────────────────────────────
    private static final Set<String> VINYL_NAMES = new HashSet<>(Arrays.asList(
            "pretty fly", "not just a pest", "cricket choir", "cicada symphony",
            "buzzin' beats", "dynamites", "wings of harmony", "rodent revolution",
            "slow and groovy", "earthworm ensemble", "beetle beats", "slug groove",
            "mosquito melody", "locust lullaby", "mite march"
    ));

    // ── Chat-message patterns for pest drop tracking ──────────────────────────
    private static final Pattern CHAT_ITEM_PATTERN =
            Pattern.compile("You received (\\d+)x?\\s+(.+?)(?:\\s+for\\s+|\\.|$)", Pattern.CASE_INSENSITIVE);

    private static final Pattern CHAT_COIN_PATTERN =
            Pattern.compile("You received ([\\d,]+) Coins?\\.?", Pattern.CASE_INSENSITIVE);

    private static final Pattern RARE_DROP_PATTERN =
            Pattern.compile("RARE DROP! (.+?)(?:\\s*\\(\\+[\\d]+\\))?\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern PET_DROP_PATTERN =
            Pattern.compile("PET DROP! (.+?)(?:\\s*\\(\\+[\\d]+\\))?\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern SACK_DEPOSIT_PATTERN =
            Pattern.compile("^\\+(\\d[\\d,]*)\\s+([A-Za-z][A-Za-z0-9 ]*)\\s*(?:[➜→>]|$)",
                    Pattern.CASE_INSENSITIVE);

    private static final String COINS_KEY = "coins";

    // ── Previous inventory snapshot ──────────────────────────────────────────
    private final Map<String, Long> prevSnapshot = new HashMap<>();

    // ── Previous tick state ──────────────────────────────────────────────────
    private boolean wasFarming    = false;
    private boolean wasPestActive = false;

    // ── Cached display names (plain, for HUD rendering) ──────────────────────
    private final Map<String, String> displayNames = new HashMap<>();

    // ── Cached item icons (lower-cased plain name → Minecraft Item) ──────────
    private final Map<String, Item> itemIcons = new HashMap<>();

    // ── Default item icons for known crops and drops ──────────────────────────
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
    public static final long DISPLAY_UPDATE_INTERVAL_MS = 1000L;
    private long lastDisplayUpdateMs = 0L;
    private List<ProfitEntry> displayFarmingEntries = List.of();
    private List<ProfitEntry> displayPestEntries = List.of();
    private double displayFarmingProfit = 0.0;
    private double displayPestProfit = 0.0;
    private double displayCombinedProfitPerHour = 0.0;
    private boolean displayIncludePest = false;

    // ── Number of farming/pest ticks tracked (for "is active" queries) ───────
    private boolean trackerHasData = false;

    // ── Constructor ───────────────────────────────────────────────────────────

    public FarmingProfitTracker() {
        itemIcons.putAll(DEFAULT_ICONS);
    }

    public void onTick(MinecraftClient client,
                       MacroManager macroManager,
                       PestKillerManager pestKillerManager,
                       boolean isVisitorActive) {
        if (client == null || client.player == null) return;
        checkSpeedPerSecond();

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

    private static String plainKey(ItemStack stack) {
        String key = stripColor(stack.getName().getString()).toLowerCase().trim();
        return VINYL_NAMES.contains(key) ? "pest vinyl" : key;
    }

    private static String niceDisplayName(ItemStack stack) {
        return stripColor(stack.getName().getString()).trim();
    }

    public static String stripColor(String s) {
        if (s == null) return "";
        return COLOR_CODE_PATTERN.matcher(s).replaceAll("");
    }

    private static final java.util.regex.Pattern COLOR_CODE_PATTERN =
            java.util.regex.Pattern.compile("§[0-9a-fA-Fk-oK-OrR]");

    // ── Public query API ──────────────────────────────────────────────────────

    public boolean hasData() {
        return trackerHasData;
    }

    public void registerBlockBreak() {
        blocksBrokenThisSecond++;
    }

    private void checkSpeedPerSecond() {
        long now = System.currentTimeMillis();
        if (lastSpeedCheckMs == 0) {
            lastSpeedCheckMs = now;
            return;
        }
        if (now - lastSpeedCheckMs < 1000L) return;
        lastSpeedCheckMs += 1000L;
        int broken = blocksBrokenThisSecond;
        blocksBrokenThisSecond = 0;
        if (broken == 0) {
            if (blocksSpeedList.isEmpty()) return;
            secondsStopped++;
        } else {
            if (secondsStopped >= SPEED_RESET_SECONDS) {
                blocksSpeedList.clear();
                secondsStopped = 0;
            }
            while (secondsStopped > 0) {
                blocksSpeedList.add(0);
                secondsStopped--;
            }
            blocksSpeedList.add(broken);
            while (blocksSpeedList.size() > MAX_SPEED_HISTORY_SIZE) {
                blocksSpeedList.remove(0);
            }
        }
    }

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

    private boolean isCocoaFormulaActive() {
        return cocoaFormulaLastCallMs > 0
                && System.currentTimeMillis() - cocoaFormulaLastCallMs < COCOA_FORMULA_ACTIVE_WINDOW_MS;
    }

    public double getAverageBps() {
        int size = blocksSpeedList.size();
        if (size < 2) return 0.0;
        int startIndex = Math.max(0, size - AVERAGE_WINDOW_SIZE);
        List<Integer> recent = blocksSpeedList.subList(startIndex, size);
        if (recent.size() < 2) return 0.0;
        List<Integer> forAvg = recent.subList(0, recent.size() - 1);
        return forAvg.stream().mapToInt(Integer::intValue).average().orElse(0.0);
    }

    public double calculateCropsPerSecond(CropType crop) {
        double bps = getAverageBps();
        if (bps <= 0 || crop == null) return 0.0;
        return crop.getBaseDrops() * (1.0 + farmingFortune / 100.0) * bps;
    }

    public double getProjectedProfitPerHour(CropType selectedCrop) {
        if (selectedCrop == null) return 0.0;
        double cps = calculateCropsPerSecond(selectedCrop);
        if (cps <= 0) return 0.0;
        double npcPrice = VisitorNpcPrices.getPrice(selectedCrop.getBaseNpcPriceKey());
        return cps * npcPrice * SECONDS_PER_HOUR;
    }

    public double getFarmingFortune() {
        return farmingFortune;
    }

    public double getCropFortune() {
        return cropFortune;
    }

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

    public long getSessionElapsedMs() {
        long total = sessionActiveTotalMs;
        if (wasAnyMacroActive && sessionActiveStartMs >= 0) {
            total += System.currentTimeMillis() - sessionActiveStartMs;
        }
        return total;
    }

    public List<ProfitEntry> getFarmingEntries() {
        return toEntries(farmingItems);
    }

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

    public double getFarmingProfit() {
        return farmingItems.entrySet().stream()
                .mapToDouble(e -> e.getValue() * VisitorNpcPrices.getPrice(e.getKey()))
                .sum();
    }

    public double getPestProfit() {
        return pestItems.entrySet().stream()
                .mapToDouble(e -> e.getValue() * VisitorNpcPrices.getPrice(e.getKey()))
                .sum();
    }

    // ── Throttled display cache ───────────────────────────────────────────────

    public void refreshDisplayCache(boolean includePest, CropType selectedCrop) {
        long now = System.currentTimeMillis();
        if (lastDisplayUpdateMs > 0 && now - lastDisplayUpdateMs < DISPLAY_UPDATE_INTERVAL_MS) return;
        lastDisplayUpdateMs        = now;
        displayFarmingEntries      = toEntries(farmingItems);
        displayPestEntries         = toEntries(pestItems);
        displayFarmingProfit       = getFarmingProfit();
        displayPestProfit          = getPestProfit();
        displayIncludePest         = includePest;
        displayCombinedProfitPerHour = getProjectedProfitPerHour(selectedCrop);
    }

    public List<ProfitEntry> getDisplayFarmingEntries() { return displayFarmingEntries; }

    public List<ProfitEntry> getDisplayPestEntries() { return displayPestEntries; }

    public double getDisplayFarmingProfit() { return displayFarmingProfit; }

    public double getDisplayPestProfit() { return displayPestProfit; }

    public double getDisplayCombinedProfitPerHour() { return displayCombinedProfitPerHour; }

    public double getDisplayTotalProfit() {
        return displayFarmingProfit + (displayIncludePest ? displayPestProfit : 0.0);
    }

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

    private static String normalizeItemName(String rawName) {
        String key = rawName.toLowerCase().trim();
        return VINYL_NAMES.contains(key) ? "pest vinyl" : key;
    }

    private static String normalizePetName(String rawName) {
        return rawName.toLowerCase().trim() + " pet";
    }

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
        blocksSpeedList.clear();
        lastSpeedCheckMs = 0;
        secondsStopped = 0;
        blocksBrokenThisSecond = 0;
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
