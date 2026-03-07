package com.justfarming.input;

import net.minecraft.client.MinecraftClient;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tracks keystroke events (WASD, left-click) for the keystrokes HUD display.
 *
 * <p>Maintains a 1-second sliding window of attack-click events so that CPS can
 * be displayed in real time.  Events arrive from two sources:
 * <ul>
 *   <li>{@link #update(MinecraftClient)} – called every client tick to detect
 *       key-press transitions on the standard Minecraft key bindings.</li>
 *   <li>{@link #registerAttack()} – called explicitly by macro code that sends
 *       attack packets directly rather than through the key-binding system
 *       (e.g. {@code MacroManager.directBreakBlock()} and the vacuum-shot fire
 *       in {@code PestKillerManager}).</li>
 * </ul>
 */
public class KeystrokesTracker {

    private static final KeystrokesTracker INSTANCE = new KeystrokesTracker();

    /** Duration (ms) of the sliding window used to compute CPS. */
    private static final long CPS_WINDOW_MS = 1000L;

    /** Timestamps (ms) of recent left-click / attack events. */
    private final Deque<Long> attackTimestamps = new ArrayDeque<>();

    /** Previous tick's attackKey pressed state, for press-transition detection. */
    private boolean wasAttackPressed = false;

    private KeystrokesTracker() {}

    /** Returns the global singleton instance. */
    public static KeystrokesTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Called every client tick to update key-transition detection and prune
     * expired click timestamps.
     *
     * @param client the Minecraft client instance
     */
    public void update(MinecraftClient client) {
        if (client.options == null) return;
        long now = System.currentTimeMillis();

        // Detect attackKey press transition (released → pressed).
        boolean isAttackPressed = client.options.attackKey.isPressed();
        if (isAttackPressed && !wasAttackPressed) {
            attackTimestamps.add(now);
        }
        wasAttackPressed = isAttackPressed;

        pruneOldEntries(now);
    }

    /**
     * Registers a single left-click (attack) event.  Call this when the macro
     * sends an attack/break packet directly (e.g. from {@code directBreakBlock}
     * or the vacuum-shot fire) so that those clicks are counted in the CPS.
     */
    public void registerAttack() {
        long now = System.currentTimeMillis();
        attackTimestamps.add(now);
        pruneOldEntries(now);
    }

    /**
     * Returns the current left-click CPS (number of attack events in the last
     * {@link #CPS_WINDOW_MS} ms).
     */
    public int getAttackCps() {
        pruneOldEntries(System.currentTimeMillis());
        return attackTimestamps.size();
    }

    /** Returns {@code true} if the W (forward) key is currently pressed. */
    public boolean isForwardPressed(MinecraftClient client) {
        return client.options != null && client.options.forwardKey.isPressed();
    }

    /** Returns {@code true} if the A (left-strafe) key is currently pressed. */
    public boolean isLeftPressed(MinecraftClient client) {
        return client.options != null && client.options.leftKey.isPressed();
    }

    /** Returns {@code true} if the S (back) key is currently pressed. */
    public boolean isBackPressed(MinecraftClient client) {
        return client.options != null && client.options.backKey.isPressed();
    }

    /** Returns {@code true} if the D (right-strafe) key is currently pressed. */
    public boolean isRightPressed(MinecraftClient client) {
        return client.options != null && client.options.rightKey.isPressed();
    }

    /** Returns {@code true} if the attack (left-click) key is currently pressed. */
    public boolean isAttackPressed(MinecraftClient client) {
        return client.options != null && client.options.attackKey.isPressed();
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void pruneOldEntries(long now) {
        long cutoff = now - CPS_WINDOW_MS;
        while (!attackTimestamps.isEmpty() && attackTimestamps.peek() < cutoff) {
            attackTimestamps.poll();
        }
    }
}
