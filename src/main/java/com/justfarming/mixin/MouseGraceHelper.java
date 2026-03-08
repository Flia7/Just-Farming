package com.justfarming.mixin;

/**
 * Holds the cursor-ungrab grace-period state that is shared between
 * {@link MouseMixin} and {@link MinecraftClientMixin}.
 *
 * <p>This state cannot live as a {@code public static} method directly inside
 * {@link MouseMixin} because the Mixin framework disallows non-private static
 * methods on Mixin classes (it would throw an
 * {@code InvalidMixinException} at startup).  Placing the state here—in a
 * plain, non-Mixin class—sidesteps that restriction while keeping the logic
 * in the same package.
 */
final class MouseGraceHelper {

    /** Duration (ms) of the cursor-ungrab grace period after a GUI is closed. */
    static final long GUI_CLOSE_GRACE_PERIOD_MS = 1000L;

    /**
     * Wall-clock time (ms) until which cursor re-locking is suppressed after
     * any GUI screen is closed.
     */
    static volatile long guiCloseGraceUntilMs = 0L;

    private MouseGraceHelper() {}

    /**
     * Called by {@link MinecraftClientMixin} whenever a screen is closed
     * (i.e. {@code setScreen(null)} is called).  Starts the 1-second ungrab
     * grace period.
     */
    static void notifyGuiClosed() {
        guiCloseGraceUntilMs = System.currentTimeMillis() + GUI_CLOSE_GRACE_PERIOD_MS;
    }
}
