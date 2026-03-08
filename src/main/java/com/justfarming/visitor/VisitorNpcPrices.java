package com.justfarming.visitor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NPC sell prices (coins per item) for Hypixel SkyBlock items that garden
 * visitors may request.
 *
 * <p>Prices are based on the current Hypixel SkyBlock NPC sell menu values.
 * All lookup is case-insensitive so that the display names returned by visitor
 * GUI parsing are matched regardless of capitalisation differences.
 */
public final class VisitorNpcPrices {

    private VisitorNpcPrices() {}

    // ── NPC sell prices (coins per item) ────────────────────────────────────
    private static final Map<String, Double> NPC_PRICES = new HashMap<>();

    static {
        // ── Base crops ───────────────────────────────────────────────────────
        NPC_PRICES.put("wheat",              6.0);
        NPC_PRICES.put("carrot",             3.0);
        NPC_PRICES.put("potato",             3.0);
        NPC_PRICES.put("pumpkin",           10.0);
        NPC_PRICES.put("melon",              18.0);
        NPC_PRICES.put("melon slice",        2.0);
        NPC_PRICES.put("sugar cane",         4.0);
        NPC_PRICES.put("nether wart",        4.0);
        NPC_PRICES.put("cactus",             4.0);
        NPC_PRICES.put("red mushroom",      10.0);
        NPC_PRICES.put("brown mushroom",    10.0);
        NPC_PRICES.put("cocoa beans",        3.0);
        NPC_PRICES.put("wild rose",          4.0);
        NPC_PRICES.put("sunflower",          4.0);
        NPC_PRICES.put("moonflower",         4.0);
        NPC_PRICES.put("seeds",              3.0);
        NPC_PRICES.put("wheat seeds",        3.0);

        // ── Enchanted crops ──────────────────────────────────────────────────
        NPC_PRICES.put("enchanted wheat",           960.0);
        NPC_PRICES.put("enchanted bread",            60.0);
        NPC_PRICES.put("enchanted carrot",          480.0);
        NPC_PRICES.put("enchanted golden carrot",  76800.0);
        NPC_PRICES.put("enchanted potato",          480.0);
        NPC_PRICES.put("enchanted baked potato",  76800.0);
        NPC_PRICES.put("enchanted pumpkin",        1600.0);
        NPC_PRICES.put("enchanted melon slice",     320.0);
        NPC_PRICES.put("enchanted melon",         51200.0);
        NPC_PRICES.put("enchanted sugar",           640.0);
        NPC_PRICES.put("enchanted nether wart",     640.0);
        NPC_PRICES.put("enchanted cactus green",    640.0);
        NPC_PRICES.put("enchanted red mushroom",   1600.0);
        NPC_PRICES.put("enchanted brown mushroom", 1600.0);
        NPC_PRICES.put("enchanted cocoa beans",     480.0);
        NPC_PRICES.put("enchanted cookie",        76800.0);
        NPC_PRICES.put("enchanted wild rose",       640.0);
        NPC_PRICES.put("enchanted sunflower",       640.0);
        NPC_PRICES.put("enchanted moonflower",      640.0);
        NPC_PRICES.put("enchanted seeds",           480.0);

        // ── Super-enchanted / block-tier crops ───────────────────────────────
        NPC_PRICES.put("hay bale",                   54.0);
        NPC_PRICES.put("enchanted hay bale",      153600.0);
        NPC_PRICES.put("mutant nether wart",      102400.0);
        NPC_PRICES.put("enchanted red mushroom block",   51200.0);
        NPC_PRICES.put("enchanted brown mushroom block", 51200.0);
        NPC_PRICES.put("enchanted cactus",        102400.0);
        NPC_PRICES.put("enchanted sugar cane",    102400.0);
        NPC_PRICES.put("polished pumpkin",        256000.0);

        // ── Compacted / bulk variants ────────────────────────────────────────
        NPC_PRICES.put("compacted wild rose",     102400.0);

        // ── Pest-kill drops ──────────────────────────────────────────────────
        // Compost is a common pest-kill reward in the Garden.
        NPC_PRICES.put("compost",                   4.0);
        // Ink sac is occasionally dropped by slugs / flies.
        NPC_PRICES.put("ink sac",                   5.6);
        // Pumpkin seeds and melon seeds (dropped by various pests).
        NPC_PRICES.put("pumpkin seeds",             3.0);
        NPC_PRICES.put("melon seeds",               3.0);
        NPC_PRICES.put("beetroot seeds",            3.0);
    }

    /**
     * Returns the NPC sell price (coins per item) for the given item name, or
     * {@code 0.0} if the item is not known.  Lookup is case-insensitive.
     *
     * @param itemName display name of the item as reported by the visitor GUI
     * @return NPC sell price in coins, or {@code 0.0} if unknown
     */
    public static double getPrice(String itemName) {
        if (itemName == null) return 0.0;
        return NPC_PRICES.getOrDefault(itemName.toLowerCase().trim(), 0.0);
    }

    /**
     * Calculates the total NPC sell value for all items in the given
     * requirement list.
     *
     * <p>Each requirement's contribution is {@code amount × NPC price per item}.
     * Requirements for unknown items contribute {@code 0} so the total is a
     * lower-bound estimate when some items are not in the price table.
     *
     * @param requirements the list of item requirements extracted from a visitor
     * @return total NPC sell value in coins
     */
    public static double getTotalNpcValue(List<VisitorRequirement> requirements) {
        double total = 0.0;
        for (VisitorRequirement req : requirements) {
            total += req.amount * getPrice(req.itemName);
        }
        return total;
    }
}
