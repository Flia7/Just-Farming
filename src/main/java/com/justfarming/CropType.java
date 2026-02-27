package com.justfarming;

/**
 * Enum representing all farmable crop types in Hypixel Skyblock.
 */
public enum CropType {
    WHEAT("crop.just-farming.wheat", 7),
    CARROT("crop.just-farming.carrot", 7),
    POTATO("crop.just-farming.potato", 7),
    MELON("crop.just-farming.melon", 0),
    PUMPKIN("crop.just-farming.pumpkin", 0),
    SUGAR_CANE("crop.just-farming.sugar_cane", 0),
    CACTUS("crop.just-farming.cactus", 0),
    MUSHROOM("crop.just-farming.mushroom", 0),
    COCOA_BEANS("crop.just-farming.cocoa_beans", 2),
    NETHER_WART("crop.just-farming.nether_wart", 3),
    POTATO_S_SHAPE("crop.just-farming.potato_s_shape", 7),
    NETHER_WART_S_SHAPE("crop.just-farming.nether_wart_s_shape", 3),
    CARROT_S_SHAPE("crop.just-farming.carrot_s_shape", 7),
    WHEAT_S_SHAPE("crop.just-farming.wheat_s_shape", 7),
    PUMPKIN_S_SHAPE("crop.just-farming.pumpkin_s_shape", 0),
    MELON_S_SHAPE("crop.just-farming.melon_s_shape", 0),
    SUGAR_CANE_S_SHAPE("crop.just-farming.sugar_cane_s_shape", 0),
    MOONFLOWER_S_SHAPE("crop.just-farming.moonflower_s_shape", 0),
    SUNFLOWER_S_SHAPE("crop.just-farming.sunflower_s_shape", 0),
    WILD_ROSE_S_SHAPE("crop.just-farming.wild_rose_s_shape", 0);

    /** Translation key for display name */
    private final String translationKey;
    /** The maximum age (growth stage) at which the crop is fully grown, 0 if not age-based */
    private final int maxAge;

    CropType(String translationKey, int maxAge) {
        this.translationKey = translationKey;
        this.maxAge = maxAge;
    }

    public String getTranslationKey() {
        return translationKey;
    }

    public int getMaxAge() {
        return maxAge;
    }

    public boolean isAgeBased() {
        return maxAge > 0;
    }

    public boolean isSShape() {
        return this == POTATO_S_SHAPE || this == NETHER_WART_S_SHAPE
                || this == CARROT_S_SHAPE || this == WHEAT_S_SHAPE
                || this == PUMPKIN_S_SHAPE || this == MELON_S_SHAPE
                || this == SUGAR_CANE_S_SHAPE || this == MOONFLOWER_S_SHAPE
                || this == SUNFLOWER_S_SHAPE || this == WILD_ROSE_S_SHAPE;
    }

    @Override
    public String toString() {
        return translationKey;
    }
}
