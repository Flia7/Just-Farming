package com.justfarming;

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

    private final String translationKey;
    private final int maxAge;
    private final float defaultYaw;
    private final float defaultPitch;
    private final int recommendedSpeed;
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

    public float getDefaultYaw() {
        return defaultYaw;
    }

    public float getDefaultPitch() {
        return defaultPitch;
    }

    public int getRecommendedSpeed() {
        return recommendedSpeed;
    }

    public double getBaseDrops() {
        return baseDrops;
    }

    public String getCropFortuneKey() {
        if (this == COCOA_BEANS) return "cocoa bean";
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

    public boolean isCactus() {
        return this == CACTUS;
    }

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

    public String getBaseNpcPriceKey() {
        return switch (this) {
            case WHEAT, WHEAT_S_SHAPE             -> "wheat";
            case CARROT, CARROT_S_SHAPE           -> "carrot";
            case POTATO, POTATO_S_SHAPE           -> "potato";
            case PUMPKIN, PUMPKIN_S_SHAPE         -> "pumpkin";
            case MELON, MELON_S_SHAPE             -> "melon slice";
            case SUGAR_CANE, SUGAR_CANE_S_SHAPE   -> "sugar cane";
            case CACTUS                           -> "cactus";
            case MUSHROOM                         -> "red mushroom";
            case COCOA_BEANS                      -> "cocoa beans";
            case NETHER_WART, NETHER_WART_S_SHAPE -> "nether wart";
            case MOONFLOWER_S_SHAPE               -> "moonflower";
            case SUNFLOWER_S_SHAPE                -> "sunflower";
            case WILD_ROSE_S_SHAPE                -> "wild rose";
        };
    }

    @Override
    public String toString() {
        return translationKey;
    }
}
