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
    NETHER_WART("crop.just-farming.nether_wart", 3);

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

    @Override
    public String toString() {
        return translationKey;
    }
}
