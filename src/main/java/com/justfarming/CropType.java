package com.justfarming;

/**
 * Enum representing all farmable crop types in Hypixel Skyblock.
 *
 * <p>Each crop carries its recommended farming speed (BPS), default yaw
 * (horizontal rotation) and default pitch (vertical look angle) so the macro
 * can lock the camera to the correct position automatically.
 */
public enum CropType {
    //                                        translationKey                         maxAge  yaw      pitch  speed
    WHEAT              ("crop.just-farming.wheat",              7,    0.00f,   2.8f, 308),
    CARROT             ("crop.just-farming.carrot",             7,    0.00f,   2.8f, 308),
    POTATO             ("crop.just-farming.potato",             7,    0.00f,   2.8f, 308),
    MELON              ("crop.just-farming.melon",              0,    0.00f, -58.5f, 368),
    PUMPKIN            ("crop.just-farming.pumpkin",            0,    0.00f, -58.5f, 368),
    SUGAR_CANE         ("crop.just-farming.sugar_cane",         0, -135.00f,   0.0f, 328),
    CACTUS             ("crop.just-farming.cactus",             0,    0.00f,   0.0f, 464),
    MUSHROOM           ("crop.just-farming.mushroom",           0,  -26.57f,   0.0f, 259),
    COCOA_BEANS        ("crop.just-farming.cocoa_beans",        2,   90.00f, -45.0f, 368),
    NETHER_WART        ("crop.just-farming.nether_wart",        3,    0.00f,   0.0f, 308),
    POTATO_S_SHAPE     ("crop.just-farming.potato_s_shape",     7,    0.00f,   2.8f, 308),
    NETHER_WART_S_SHAPE("crop.just-farming.nether_wart_s_shape",3,    0.00f,   0.0f, 308),
    CARROT_S_SHAPE     ("crop.just-farming.carrot_s_shape",     7,    0.00f,   2.8f, 308),
    WHEAT_S_SHAPE      ("crop.just-farming.wheat_s_shape",      7,    0.00f,   2.8f, 308),
    PUMPKIN_S_SHAPE    ("crop.just-farming.pumpkin_s_shape",    0,    0.00f, -58.5f, 368),
    MELON_S_SHAPE      ("crop.just-farming.melon_s_shape",      0,    0.00f, -58.5f, 368),
    SUGAR_CANE_S_SHAPE ("crop.just-farming.sugar_cane_s_shape", 0, -135.00f,   0.0f, 328),
    MOONFLOWER_S_SHAPE ("crop.just-farming.moonflower_s_shape", 0, -135.00f,   0.0f, 328),
    SUNFLOWER_S_SHAPE  ("crop.just-farming.sunflower_s_shape",  0, -135.00f,   0.0f, 328),
    WILD_ROSE_S_SHAPE  ("crop.just-farming.wild_rose_s_shape",  0, -135.00f,   0.0f, 328);

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

    CropType(String translationKey, int maxAge, float defaultYaw, float defaultPitch, int recommendedSpeed) {
        this.translationKey   = translationKey;
        this.maxAge           = maxAge;
        this.defaultYaw       = defaultYaw;
        this.defaultPitch     = defaultPitch;
        this.recommendedSpeed = recommendedSpeed;
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

    @Override
    public String toString() {
        return translationKey;
    }
}
