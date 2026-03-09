package com.justfarming;

/**
 * Enum representing all farmable crop types in Hypixel Skyblock.
 *
 * <p>Each crop carries its recommended farming speed (BPS), default yaw
 * (horizontal rotation) and default pitch (vertical look angle) so the macro
 * can lock the camera to the correct position automatically.
 */
public enum CropType {
    //                                        translationKey                         maxAge  yaw      pitch  speed  baseDrops
    WHEAT              ("crop.just-farming.wheat",              7,    0.00f,   2.8f, 308, 1.0),
    CARROT             ("crop.just-farming.carrot",             7,    0.00f,   2.8f, 308, 3.0),
    POTATO             ("crop.just-farming.potato",             7,    0.00f,   2.8f, 308, 3.0),
    MELON              ("crop.just-farming.melon",              0,    0.00f, -58.5f, 368, 5.0),   // mean of 3-7 slices
    PUMPKIN            ("crop.just-farming.pumpkin",            0,    0.00f, -58.5f, 368, 1.0),
    SUGAR_CANE         ("crop.just-farming.sugar_cane",         0, -135.00f,   0.0f, 328, 2.0),   // breaks 2 blocks
    CACTUS             ("crop.just-farming.cactus",             0,    0.00f,   0.0f, 464, 2.0),   // breaks 2 blocks
    MUSHROOM           ("crop.just-farming.mushroom",           0,  -26.57f,   0.0f, 259, 1.0),
    COCOA_BEANS        ("crop.just-farming.cocoa_beans",        2,   90.00f, -45.0f, 368, 3.0),   // base for fortune formula (2-3 range)
    NETHER_WART        ("crop.just-farming.nether_wart",        3,    0.00f,   0.0f, 308, 2.5),   // mean of 2-3 drops
    POTATO_S_SHAPE     ("crop.just-farming.potato_s_shape",     7,    0.00f,   2.8f, 308, 3.0),
    NETHER_WART_S_SHAPE("crop.just-farming.nether_wart_s_shape",3,    0.00f,   0.0f, 308, 2.5),
    CARROT_S_SHAPE     ("crop.just-farming.carrot_s_shape",     7,    0.00f,   2.8f, 308, 3.0),
    WHEAT_S_SHAPE      ("crop.just-farming.wheat_s_shape",      7,    0.00f,   2.8f, 308, 1.0),
    PUMPKIN_S_SHAPE    ("crop.just-farming.pumpkin_s_shape",    0,    0.00f, -58.5f, 368, 1.0),
    MELON_S_SHAPE      ("crop.just-farming.melon_s_shape",      0,    0.00f, -58.5f, 368, 5.0),
    SUGAR_CANE_S_SHAPE ("crop.just-farming.sugar_cane_s_shape", 0, -135.00f,   0.0f, 328, 2.0),
    MOONFLOWER_S_SHAPE ("crop.just-farming.moonflower_s_shape", 0, -135.00f,   0.0f, 328, 1.0),
    SUNFLOWER_S_SHAPE  ("crop.just-farming.sunflower_s_shape",  0, -135.00f,   0.0f, 328, 1.0),
    WILD_ROSE_S_SHAPE  ("crop.just-farming.wild_rose_s_shape",  0, -135.00f,   0.0f, 328, 1.0);

    /** Translation key for display name */
    private final String translationKey;
    /** The maximum age (growth stage) at which the crop is fully grown, 0 if not age-based */
    private final int maxAge;
    /** Default yaw (horizontal rotation, degrees) while farming this crop. */
    private final float defaultYaw;
    /** Default pitch (vertical look angle, degrees) while farming this crop. */
    private final float defaultPitch;
    /** Recommended farming speed in BPS for this crop. */
    private final int recommendedSpeed;
    /**
     * Base crop drops per block break (before farming fortune is applied).
     * Used in the formula: Average Drops = baseDrops × (1 + farmingFortune / 100).
     * Values are sourced from the Hypixel SkyBlock farming fortune formula for
     * Java Edition (not Bedrock).
     */
    private final double baseDrops;

    CropType(String translationKey, int maxAge, float defaultYaw, float defaultPitch, int recommendedSpeed, double baseDrops) {
        this.translationKey   = translationKey;
        this.maxAge           = maxAge;
        this.defaultYaw       = defaultYaw;
        this.defaultPitch     = defaultPitch;
        this.recommendedSpeed = recommendedSpeed;
        this.baseDrops        = baseDrops;
    }

    public String getTranslationKey() {
        return translationKey;
    }

    public int getMaxAge() {
        return maxAge;
    }

    /** Returns the default yaw angle (degrees) for locking the camera while farming this crop. */
    public float getDefaultYaw() {
        return defaultYaw;
    }

    /** Returns the default pitch angle (degrees) for locking the camera while farming this crop. */
    public float getDefaultPitch() {
        return defaultPitch;
    }

    /** Returns the recommended farming speed in BPS for this crop. */
    public int getRecommendedSpeed() {
        return recommendedSpeed;
    }

    /**
     * Returns the base crop drops per block break before farming fortune is applied.
     * Use in the formula: {@code avgDrops = baseDrops × (1 + farmingFortune / 100)}.
     */
    public double getBaseDrops() {
        return baseDrops;
    }

    /**
     * Returns the crop-specific fortune label used in the Hypixel SkyBlock tab list,
     * e.g. {@code "carrot"} for Carrot/Carrot_S_Shape (matched against "{name} Fortune").
     * The returned string is lower-cased and has the "_S_SHAPE" suffix stripped.
     */
    public String getCropFortuneKey() {
        return name().replace("_S_SHAPE", "").replace('_', ' ').toLowerCase();
    }

    public boolean isAgeBased() {
        return maxAge > 0;
    }

    public boolean isSShape() {
        return this == POTATO || this == POTATO_S_SHAPE
                || this == NETHER_WART || this == NETHER_WART_S_SHAPE
                || this == CARROT || this == CARROT_S_SHAPE
                || this == WHEAT || this == WHEAT_S_SHAPE
                || this == PUMPKIN || this == PUMPKIN_S_SHAPE
                || this == MELON || this == MELON_S_SHAPE;
    }

    public boolean isLeftBack() {
        return this == SUGAR_CANE || this == SUGAR_CANE_S_SHAPE
                || this == MOONFLOWER_S_SHAPE
                || this == SUNFLOWER_S_SHAPE || this == WILD_ROSE_S_SHAPE;
    }

    public boolean isForwardBack() {
        return this == MUSHROOM;
    }

    /** Returns {@code true} for the Cactus crop, which uses a left-then-right strafe pattern. */
    public boolean isCactus() {
        return this == CACTUS;
    }

    /**
     * Returns the ARGB display colour associated with this crop type, used in
     * the Profit HUD title to tint the crop name with its characteristic colour.
     */
    public int getDisplayColor() {
        return switch (this) {
            case WHEAT, WHEAT_S_SHAPE                       -> 0xFFFFCC55; // golden yellow
            case CARROT, CARROT_S_SHAPE                     -> 0xFFFF8800; // orange
            case POTATO, POTATO_S_SHAPE                     -> 0xFFDDC060; // tan/gold
            case MELON, MELON_S_SHAPE                       -> 0xFF55FF55; // green
            case PUMPKIN, PUMPKIN_S_SHAPE                   -> 0xFFFF7700; // orange
            case SUGAR_CANE, SUGAR_CANE_S_SHAPE             -> 0xFF66DD44; // lime green
            case CACTUS                                     -> 0xFF22AA22; // dark green
            case MUSHROOM                                   -> 0xFFBB8855; // brown
            case COCOA_BEANS                                -> 0xFFAA6633; // cocoa brown
            case NETHER_WART, NETHER_WART_S_SHAPE           -> 0xFFFF5555; // red
            case MOONFLOWER_S_SHAPE                         -> 0xFFBBBBFF; // pale blue/white
            case SUNFLOWER_S_SHAPE                          -> 0xFFFFEE22; // yellow
            case WILD_ROSE_S_SHAPE                          -> 0xFFFF6699; // pink
        };
    }

    @Override
    public String toString() {
        return translationKey;
    }
}
