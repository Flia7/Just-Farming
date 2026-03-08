package com.justfarming.input;

import net.minecraft.client.MinecraftClient;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tracks keystroke state (WASD, LMB, RMB) for the keystrokes HUD display.
 *
 * <p>Inspired by the
 * <a href="https://github.com/Polyfrost/Canelex-KeyStrokes-Revamp">Canelex
 * KeyStrokes Revamp</a> mod, this tracker provides:
 * <ul>
 *   <li><b>Per-key pressed state</b> – combining physical key-binding state
 *       with virtual-press events fired by macro packets.</li>
 *   <li><b>Per-key fade progress</b> – a 0–1 value tracking how far along
 *       the smooth transition between pressed and released colours is, mirroring
 *       Canelex's {@code percentFaded} animation.</li>
 *   <li><b>LMB CPS counter</b> – a 1-second sliding window that counts both
 *       physical click events and direct attack packets sent by the macro (via
 *       {@link #registerAttack()}).</li>
 * </ul>
 *
 * <p>State is updated every client tick via {@link #update(MinecraftClient)}.
 * Direct macro packets (that bypass the key-binding system) are registered via
 * {@link #registerAttack()} (for block-break/attack) and {@link #registerUse()}
 * (for right-click/use packets).
 */
public class KeystrokesTracker {

    private static final KeystrokesTracker INSTANCE = new KeystrokesTracker();

    // ── Key index constants ────────────────────────────────────────────────────

    /** Index for the W (forward) key. */
    public static final int KEY_W   = 0;
    /** Index for the A (left-strafe) key. */
    public static final int KEY_A   = 1;
    /** Index for the S (backward) key. */
    public static final int KEY_S   = 2;
    /** Index for the D (right-strafe) key. */
    public static final int KEY_D   = 3;
    /** Index for the left mouse button (attack). */
    public static final int KEY_LMB = 4;
    /** Index for the right mouse button (use). */
    public static final int KEY_RMB = 5;

    private static final int NUM_KEYS = 6;

    // ── Timing constants ───────────────────────────────────────────────────────

    /** Duration (ms) of the sliding window used for the LMB CPS counter. */
    private static final long CPS_WINDOW_MS = 1000L;

    /**
     * Duration (ms) over which a key's colour fades between pressed and
     * unpressed states.  Matches the Canelex KeyStrokes Revamp default
     * {@code fadingTime} of 100 ms.
     */
    public static final long FADING_DURATION_MS = 100L;

    /**
     * How long (ms) a virtual key-press from a macro packet keeps the button
     * illuminated even when the physical key binding is not held.
     * Slightly longer than one Minecraft tick (50 ms) to survive tick-rate jitter.
     */
    private static final long VIRTUAL_PRESS_DURATION_MS = 65L;

    // ── Per-key state ──────────────────────────────────────────────────────────

    /** Whether each key is currently considered down (physical OR virtual). */
    private final boolean[] keyDown       = new boolean[NUM_KEYS];
    /** Wall-clock time (ms) of the last pressed↔released transition per key. */
    private final long[]    keyLastChange = new long[NUM_KEYS];

    // ── Virtual-press timestamps (from macro packets) ──────────────────────────

    /**
     * Timestamp of the most recent LMB packet registered via
     * {@link #registerAttack()}.  A value within
     * {@link #VIRTUAL_PRESS_DURATION_MS} of the current time makes LMB
     * appear pressed even if the physical attack key is not held.
     */
    private volatile long lmbLastPacketTime = 0L;

    /**
     * Timestamp of the most recent RMB packet registered via
     * {@link #registerUse()}.  Same virtual-press logic as LMB.
     */
    private volatile long rmbLastPacketTime = 0L;

    // ── LMB CPS sliding window ─────────────────────────────────────────────────

    /** Timestamps of LMB events within the {@link #CPS_WINDOW_MS} window. */
    private final Deque<Long> lmbTimestamps = new ArrayDeque<>();

    // ── RMB CPS sliding window ─────────────────────────────────────────────────

    /** Timestamps of RMB events within the {@link #CPS_WINDOW_MS} window. */
    private final Deque<Long> rmbTimestamps = new ArrayDeque<>();

    // ── Constructor ────────────────────────────────────────────────────────────

    private KeystrokesTracker() {
        long t = System.currentTimeMillis();
        for (int i = 0; i < NUM_KEYS; i++) {
            keyLastChange[i] = t;
        }
    }

    /** Returns the global singleton instance. */
    public static KeystrokesTracker getInstance() {
        return INSTANCE;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Called every client tick.  Reads the current physical key-binding states,
     * combines them with active virtual-press events, and records the time of
     * any state transitions for smooth fading.
     *
     * @param client the current Minecraft client instance
     */
    public void update(MinecraftClient client) {
        if (client.options == null) return;
        long now = System.currentTimeMillis();

        boolean[] physical = {
            client.options.forwardKey.isPressed(),
            client.options.leftKey.isPressed(),
            client.options.backKey.isPressed(),
            client.options.rightKey.isPressed(),
            client.options.attackKey.isPressed() || (now - lmbLastPacketTime < VIRTUAL_PRESS_DURATION_MS),
            client.options.useKey.isPressed()    || (now - rmbLastPacketTime < VIRTUAL_PRESS_DURATION_MS)
        };

        for (int i = 0; i < NUM_KEYS; i++) {
            if (physical[i] != keyDown[i]) {
                keyDown[i]       = physical[i];
                keyLastChange[i] = now;
            }
        }

        pruneLmbWindow(now);
        pruneRmbWindow(now);
    }

    /**
     * Registers a left-click (attack) packet sent by the macro.
     *
     * <p>This both lights up the LMB button briefly (even if the physical attack
     * key is not held) and adds an event to the LMB CPS sliding window.
     * Call this from {@code MacroManager.directBreakBlock()} and anywhere the
     * macro sends an attack packet directly.
     */
    public void registerAttack() {
        long now = System.currentTimeMillis();
        lmbLastPacketTime = now;
        lmbTimestamps.add(now);
        pruneLmbWindow(now);
    }

    /**
     * Registers a right-click (use) packet sent by the macro.
     *
     * <p>This lights up the RMB button briefly even if the physical use key
     * is not held, and adds an event to the RMB CPS sliding window.
     * Useful when AOTV/AOTE clicks or other use-item actions are
     * sent as direct packets rather than through the key-binding system.
     */
    public void registerUse() {
        long now = System.currentTimeMillis();
        rmbLastPacketTime = now;
        rmbTimestamps.add(now);
        pruneRmbWindow(now);
    }

    /**
     * Returns {@code true} if the given key is currently considered pressed
     * (physical binding OR active virtual-press event).
     *
     * @param key one of {@link #KEY_W}, {@link #KEY_A}, {@link #KEY_S},
     *            {@link #KEY_D}, {@link #KEY_LMB}, {@link #KEY_RMB}
     */
    public boolean isKeyDown(int key) {
        if (key < 0 || key >= NUM_KEYS) return false;
        return keyDown[key];
    }

    /**
     * Returns the fade-in progress (0–1) toward the current state of the key.
     *
     * <p>0 means the key <em>just</em> changed state; 1 means the transition
     * has fully completed.  The renderer uses this to interpolate between the
     * pressed and unpressed colours, mirroring Canelex's {@code percentFaded}.
     *
     * @param key one of the {@code KEY_*} constants
     * @param now current wall-clock time in ms (pass {@code System.currentTimeMillis()})
     * @return progress in [0, 1]
     */
    public float getFadeProgress(int key, long now) {
        if (key < 0 || key >= NUM_KEYS) return 1.0f;
        return Math.min(1.0f, (float) (now - keyLastChange[key]) / FADING_DURATION_MS);
    }

    /**
     * Returns the interpolated ARGB background colour for a key, blending
     * between the pressed and unpressed colours based on the current fade
     * progress (Canelex {@code getBackgroundColor()} style).
     *
     * @param key           key index
     * @param pressedColor   ARGB colour when fully pressed
     * @param unpressedColor ARGB colour when fully unpressed
     * @param now           current wall-clock ms
     */
    public int getKeyBgColor(int key, int pressedColor, int unpressedColor, long now) {
        if (key < 0 || key >= NUM_KEYS) return unpressedColor;
        boolean down   = keyDown[key];
        float   pct    = getFadeProgress(key, now);
        int     target = down ? pressedColor : unpressedColor;
        if (pct >= 1.0f) return target;
        int origin = down ? unpressedColor : pressedColor;
        return blendArgb(target, origin, pct);
    }

    /**
     * Returns the interpolated ARGB text colour for a key (same blending
     * logic as {@link #getKeyBgColor}).
     *
     * @param key                 key index
     * @param pressedTextColor    ARGB text colour when fully pressed
     * @param unpressedTextColor  ARGB text colour when fully unpressed
     * @param now                 current wall-clock ms
     */
    public int getKeyTextColor(int key, int pressedTextColor, int unpressedTextColor, long now) {
        if (key < 0 || key >= NUM_KEYS) return unpressedTextColor;
        boolean down   = keyDown[key];
        float   pct    = getFadeProgress(key, now);
        int     target = down ? pressedTextColor : unpressedTextColor;
        if (pct >= 1.0f) return target;
        int origin = down ? unpressedTextColor : pressedTextColor;
        return blendArgb(target, origin, pct);
    }

    /**
     * Returns the current LMB CPS (number of {@link #registerAttack()} calls
     * within the past 1 second).
     */
    public int getLmbCps() {
        pruneLmbWindow(System.currentTimeMillis());
        return lmbTimestamps.size();
    }

    /**
     * Returns the current RMB CPS (number of {@link #registerUse()} calls
     * within the past 1 second).
     */
    public int getRmbCps() {
        pruneRmbWindow(System.currentTimeMillis());
        return rmbTimestamps.size();
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private void pruneLmbWindow(long now) {
        long cutoff = now - CPS_WINDOW_MS;
        while (!lmbTimestamps.isEmpty() && lmbTimestamps.peek() < cutoff) {
            lmbTimestamps.poll();
        }
    }

    private void pruneRmbWindow(long now) {
        long cutoff = now - CPS_WINDOW_MS;
        while (!rmbTimestamps.isEmpty() && rmbTimestamps.peek() < cutoff) {
            rmbTimestamps.poll();
        }
    }

    /**
     * Linearly blends two ARGB colours.
     * {@code pct = 0} → fully {@code b}; {@code pct = 1} → fully {@code a}.
     * Mirrors the {@code getIntermediateColor} method in the Canelex
     * KeyStrokes Revamp {@code GuiKey} class.
     */
    private static int blendArgb(int a, int b, float pct) {
        float q  = 1.0f - pct;
        int   aa = (a >>> 24) & 0xFF,  ba = (b >>> 24) & 0xFF;
        int   ar = (a >> 16)  & 0xFF,  br = (b >> 16)  & 0xFF;
        int   ag = (a >>  8)  & 0xFF,  bg = (b >>  8)  & 0xFF;
        int   ab =  a         & 0xFF,  bb =  b          & 0xFF;
        return ((int)(aa * pct + ba * q) << 24)
             | ((int)(ar * pct + br * q) << 16)
             | ((int)(ag * pct + bg * q) <<  8)
             |  (int)(ab * pct + bb * q);
    }
}
