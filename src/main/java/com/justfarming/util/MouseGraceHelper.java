package com.justfarming.util;

/**
 * Holds the cursor-ungrab grace-period state that is shared between
 * {@link com.justfarming.mixin.MouseMixin} and
 * {@link com.justfarming.mixin.MinecraftClientMixin}.
 *
 * <p>This class intentionally lives outside the {@code com.justfarming.mixin}
 * package.  Placing a non-{@link org.spongepowered.asm.mixin.Mixin @Mixin}
 * helper class inside the mixin package causes Fabric Loader's Knot
 * class-loader to run the class through
 * {@code KnotClassDelegate.getPostMixinClassByteArray}, which can fail at
 * runtime when the class is first accessed (e.g. on the Render thread when a
 * screen is closed).  Keeping helper state here—in a plain package—avoids that
 * processing entirely.
 */
public final class MouseGraceHelper {

    /** Duration (ms) of the cursor-ungrab grace period after a GUI is closed. */
    public static final long GUI_CLOSE_GRACE_PERIOD_MS = 1000L;

    /**
     * Wall-clock time (ms) until which cursor re-locking is suppressed after
     * any GUI screen is closed.
     */
    public static volatile long guiCloseGraceUntilMs = 0L;

    private MouseGraceHelper() {}

    /**
     * Called by {@code MinecraftClientMixin} whenever a screen is closed
     * (i.e. {@code setScreen(null)} is called).  Starts the 1-second ungrab
     * grace period.
     */
    public static void notifyGuiClosed() {
        guiCloseGraceUntilMs = System.currentTimeMillis() + GUI_CLOSE_GRACE_PERIOD_MS;
    }
}
