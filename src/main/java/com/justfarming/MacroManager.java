package com.justfarming;

import com.justfarming.config.FarmingConfig;
import com.justfarming.visitor.VisitorManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * Manages the cocoa-beans farming macro state and tick logic.
 *
 * <p>State machine:
 * <ol>
 *   <li>IDLE – macro is not running.</li>
 *   <li>MOUSEMAT_CLICK – switches to the Squeaky Mousemat slot, sends an
 *       attackBlock packet (when aimed at a block) plus a hand-swing to trigger
 *       the item ability on the server, then restores the original slot before
 *       transitioning to DETECTING.</li>
 *   <li>DETECTING – observes the player for a few ticks to decide the initial
 *       direction (backward or forward).</li>
 *   <li>BACKWARD_LEFT – holds back + strafe-left + attack (Cocoa Beans).</li>
 *   <li>FORWARD_LEFT – holds forward + strafe-left + attack.</li>
 *   <li>FORWARD_RIGHT – holds forward + strafe-right + attack (S-Shape crops).</li>
 *   <li>STRAFE_LEFT_ONLY – holds strafe-left only + attack (Sugar Cane / Moonflower / Sunflower / Wild Rose).</li>
 *   <li>STRAFE_RIGHT_ONLY – holds strafe-right only + attack (Cactus, second half of each row pair).</li>
 *   <li>BACK_ONLY – holds back only + attack (end-of-row for left-back and forward-back crops).</li>
 *   <li>FORWARD_ONLY – holds forward only + attack (Mushroom).</li>
 *   <li>WARPING – releases keys and sends {@code /warp garden}.</li>
 * </ol>
 *
 * <p>Direction switches when the player stops moving (end of row detected by
 * the player's position not changing for {@value #STUCK_THRESHOLD} ticks).
 * The rewarp trigger fires when the player enters the configured rewarp radius.
 *
 * <p>Freelook mode offsets the camera behind the player at a configurable zoom
 * distance ({@value #DEFAULT_ZOOM} blocks by default). Scroll wheel adjusts the
 * distance between {@value #MIN_ZOOM} and {@value #MAX_ZOOM} blocks. While
 * freelook is active, the F5 perspective toggle and hotbar scroll are suppressed.
 */
public class MacroManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("just-farming");

    /** Ticks to observe before choosing initial direction. */
    private static final int DETECT_TICKS = 5;

    /**
     * How long (ms) the macro waits in the DETECTING phase for the player to
     * press a direction key (W / S / D) before falling back to the default.
     */
    private static final long START_DIRECTION_WAIT_MS = 1000L;

    /**
     * Consecutive ticks the player must stay still (while a movement key is held)
     * before we consider the row to be finished and flip direction.
     */
    private static final int STUCK_THRESHOLD = 8;

    /** Default freelook camera distance from the player's eye (blocks). */
    private static final double DEFAULT_ZOOM = 4.0;

    /** Minimum freelook camera distance (blocks). */
    private static final double MIN_ZOOM = 1.5;

    /** Maximum freelook camera distance (blocks). */
    private static final double MAX_ZOOM = 20.0;

    /**
     * Convergence speed for smooth camera rotation (per second).
     * With exponential decay the formula is {@code t = 1 - e^(-speed * dt)}.
     * A value of 10 means ~99 % of the remaining angle is covered within 0.5 s
     * at normal 20 TPS; larger lag spikes catch up automatically because the
     * interpolation is time-based.
     */
    private static final float ROTATION_SPEED = 10.0f;

    /**
     * Maximum delta-time (ms) used for the rotation interpolation.
     * Caps the "catch-up" during a severe lag spike so the camera does not
     * teleport to the target after a multi-second freeze.
     */
    private static final float ROTATION_MAX_DELTA_MS = 250.0f;

    private final MinecraftClient client;
    private FarmingConfig config;
    private VisitorManager visitorManager;
    private final Random random = new Random();

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private enum MacroState { IDLE, MOUSEMAT_CLICK, DETECTING, BACKWARD_LEFT, FORWARD_LEFT, FORWARD_RIGHT,
            STRAFE_LEFT_ONLY, STRAFE_RIGHT_ONLY, BACK_ONLY, FORWARD_ONLY, WARPING,
            LANE_SWAP_WAITING, VISITING }

    private MacroState state = MacroState.IDLE;
    private boolean running = false;
    /**
     * {@code true} when the visitor routine is running and the farming macro
     * has been paused.  The macro will automatically restart once the visitor
     * routine completes (reaches {@link VisitorManager.State#DONE}).
     */
    private boolean waitingForVisitors = false;
    private boolean freelookEnabled = false;
    private double freelookZoom = DEFAULT_ZOOM;

    /**
     * Wall-clock time (ms) of the previous tick used for lag-safe rotation
     * interpolation.  Reset to 0 whenever the macro starts or stops so the
     * first interpolation step uses the default tick duration (50 ms).
     */
    private long lastRotationTime = 0;

    /**
     * When a crop has a custom key configuration this flag tracks which
     * "half" of the direction cycle we are in.  {@code false} = primary keys,
     * {@code true} = flipped keys (forward↔back, left↔right swapped).
     */
    private boolean customFlipped = false;

    /** Position recorded at the start of the DETECTING phase. */
    private Vec3d detectStartPos = null;
    private int detectTicks = 0;

    /**
     * System-time (ms) when the DETECTING phase started; {@code 0} if not yet
     * initialised.  Used for the 1-second direction-choice window.
     */
    private long startDetectTime = 0;

    /**
     * Tracks whether W was pressed at any point since the current detection
     * window started.  Reset when {@link #startDetectTime} is initialised so
     * that even a sub-tick key-press (< 50 ms) is reliably registered.
     */
    private boolean detectionWEverPressed = false;
    /** Same as {@link #detectionWEverPressed} but for the S key. */
    private boolean detectionSEverPressed = false;
    /** Same as {@link #detectionWEverPressed} but for the D key. */
    private boolean detectionDEverPressed = false;

    /** Position recorded at the previous movement tick (for stuck detection). */
    private Vec3d lastPos = null;
    private int stuckTicks = 0;

    /** System-time (ms) when the WARPING state was entered, used for the swap delay. */
    private long warpStartTime = 0;

    /** Total delay (ms) to wait in WARPING state before sending /warp garden. */
    private long warpTargetDelay = 0;

    /** System-time (ms) when the LANE_SWAP_WAITING state was entered. */
    private long laneSwapStartTime = 0;

    /** Total delay (ms) to wait in LANE_SWAP_WAITING state before flipping direction. */
    private long laneSwapTargetDelay = 0;

    /**
     * The state to transition to after the lane-swap delay expires.
     * Only valid when {@code state == LANE_SWAP_WAITING} and {@link #laneSwapPendingCustomFlip} is false.
     */
    private MacroState laneSwapNextState = null;

    /**
     * When {@code true}, the lane-swap delay will toggle {@link #customFlipped} instead
     * of switching to {@link #laneSwapNextState}.
     */
    private boolean laneSwapPendingCustomFlip = false;

    /**
     * Tracks which phase of the MOUSEMAT_CLICK state we are in.
     * {@code 0} = waiting for swap-to delay (or detecting slot on first tick);
     * {@code 1} = on mousemat slot, waiting {@code mousematPreDelay} ms before clicking;
     * {@code 2} = click sent, waiting {@code mousematPostDelay} ms before restoring slot;
     * {@code 3} = slot restored, waiting {@code mousematResumeDelay} ms before farming.
     */
    private int mousematPhase = 0;

    /** Hotbar slot that was active before the Squeaky Mousemat switch; -1 if unknown. */
    private int preMousematSlot = -1;

    /** Hotbar slot of the Squeaky Mousemat discovered in phase 0; -1 if unknown. */
    private int mousematTargetSlot = -1;

    /** System-time (ms) when the last mousemat phase transition occurred. */
    private long mousematActionTime = 0;

    /**
     * Extra random delay (0–150 ms) added to the configured mousemat delay for
     * the current phase.  Re-rolled each time {@link #mousematActionTime} is set.
     */
    private int mousematPhaseRandomExtra = 0;


    // -----------------------------------------------------------------------
    // Constructor / config
    // -----------------------------------------------------------------------

    public MacroManager(MinecraftClient client, FarmingConfig config) {
        this.client = client;
        this.config = config;
    }

    /** Inject the {@link VisitorManager} so the macro can hand off to it at rewarp. */
    public void setVisitorManager(VisitorManager visitorManager) {
        this.visitorManager = visitorManager;
    }

    /** Update the config reference (called after GUI saves). */
    public void setConfig(FarmingConfig config) {
        this.config = config;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Returns {@code true} if the macro is currently active. */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns {@code true} when the macro is actively in a movement+breaking
     * phase (BACKWARD_LEFT or FORWARD_LEFT).
     *
     * <p>Used by {@code MinecraftClientMixin} to decide whether to force
     * block-breaking even when a GUI screen is open, and to skip
     * {@link net.minecraft.client.option.KeyBinding#unpressAll()} so that
     * movement and attack keys stay pressed when a GUI is opened.
     */
    public boolean shouldBreak() {
        return running && (state == MacroState.BACKWARD_LEFT || state == MacroState.FORWARD_LEFT
                || state == MacroState.FORWARD_RIGHT || state == MacroState.STRAFE_LEFT_ONLY
                || state == MacroState.STRAFE_RIGHT_ONLY
                || state == MacroState.BACK_ONLY || state == MacroState.FORWARD_ONLY);
    }
    /** Returns {@code true} when the macro is in the VISITING state (visitor routine active). */
    public boolean isVisiting() {
        return state == MacroState.VISITING;
    }

    /** Returns {@code true} if freelook mode is enabled. */
    public boolean isFreelookEnabled() {
        return freelookEnabled;
    }

    /** Returns {@code true} if freelook is active (enabled regardless of macro state). */
    public boolean isFreelookActive() {
        return freelookEnabled;
    }

    /** Returns the current freelook camera distance from the player's eye (blocks). */
    public double getFreelookZoom() {
        return freelookZoom;
    }

    /**
     * Adjust the freelook zoom distance by {@code delta} blocks.
     * The distance is clamped to [{@value #MIN_ZOOM}, {@value #MAX_ZOOM}].
     *
     * @param delta positive = farther, negative = closer
     */
    public void adjustFreelookZoom(double delta) {
        freelookZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, freelookZoom + delta));
    }

    /** Toggle freelook on/off. */
    public void toggleFreelook() {
        freelookEnabled = !freelookEnabled;
        LOGGER.info("[JustFarming] Freelook {}.", freelookEnabled ? "enabled" : "disabled");
    }

    /** Start the macro. */
    public void start() {
        if (running) return;
        // Do not start the farming macro while the visitor routine is active.
        if (visitorManager != null && visitorManager.isActive()) {
            LOGGER.info("[JustFarming] Cannot start farming macro while visitor routine is active.");
            return;
        }
        waitingForVisitors = false;
        running = true;
        boolean skipMousemat = false;
        if (config.squeakyMousematEnabled && client.player != null) {
            float desiredYaw   = config.getEffectiveYaw(config.selectedCrop);
            float desiredPitch = config.getEffectivePitch(config.selectedCrop);
            float yawDiff   = Math.abs(normalizeAngleDiff(client.player.getYaw()   - desiredYaw));
            float pitchDiff = Math.abs(normalizeAngleDiff(client.player.getPitch() - desiredPitch));
            if (yawDiff <= 1.0f && pitchDiff <= 1.0f) {
                skipMousemat = true;
                LOGGER.info("[JustFarming] Already aimed at target yaw/pitch – skipping Squeaky Mousemat.");
            }
        }
        state = (config.squeakyMousematEnabled && !skipMousemat) ? MacroState.MOUSEMAT_CLICK : MacroState.DETECTING;
        detectTicks = 0;
        detectStartPos = null;
        startDetectTime = 0;
        lastPos = null;
        stuckTicks = 0;
        customFlipped = false;
        mousematPhase = 0;
        preMousematSlot = -1;
        mousematTargetSlot = -1;
        mousematActionTime = 0;
        mousematPhaseRandomExtra = 0;
        lastRotationTime = 0;
        if (config.unlockedMouseEnabled) {
            client.mouse.unlockCursor();
        }
        LOGGER.info("[JustFarming] Macro started. Crop: {}", config.selectedCrop);
    }

    /** Stop the macro and release all held keys. */
    public void stop() {
        if (!running && !waitingForVisitors) return;
        running = false;
        waitingForVisitors = false;
        state = MacroState.IDLE;
        lastRotationTime = 0;
        releaseKeys();
        if (visitorManager != null && visitorManager.isActive()) {
            visitorManager.stop();
        }
        if (config.unlockedMouseEnabled && client.currentScreen == null) {
            client.mouse.lockCursor();
        }
        LOGGER.info("[JustFarming] Macro stopped.");
    }

    /** Toggle start / stop. */
    public void toggle() {
        if (running || waitingForVisitors) stop();
        else start();
    }

    /**
     * Save the player's current position as the rewarp waypoint.
     * Every time the macro reaches this position it will automatically
     * send {@code /warp garden}.  This is called by the {@code /just rewarp}
     * command.
     */
    public void setRewarpHere() {
        if (client.player != null) {
            config.rewarpX   = client.player.getX();
            config.rewarpY   = client.player.getY();
            config.rewarpZ   = client.player.getZ();
            config.rewarpSet = true;
            config.save();
            LOGGER.info("[JustFarming] Rewarp position set to {}, {}, {}.",
                    config.rewarpX, config.rewarpY, config.rewarpZ);
        }
    }

    /**
     * Clear all rewarp positions.
     * Called by the {@code /just rewarp clear} command.
     */
    public void clearRewarps() {
        config.rewarpSet = false;
        config.save();
        LOGGER.info("[JustFarming] Rewarp positions cleared.");
    }

    /**
     * Send {@code /warp garden} to the server.
     * Called automatically by the macro when the player reaches the rewarp position.
     */
    public void triggerRewarp() {
        if (client.player != null && client.player.networkHandler != null) {
            client.player.networkHandler.sendChatCommand("warp garden");
            LOGGER.info("[JustFarming] Rewarp triggered – sent /warp garden.");
        }
    }

    // -----------------------------------------------------------------------
    // Tick logic
    // -----------------------------------------------------------------------

    /** Called every client tick. Executes one step of the macro if active. */
    public void onTick() {
        // If paused waiting for the visitor routine to finish, restart farming when done.
        if (waitingForVisitors) {
            if (visitorManager != null && visitorManager.isDone()) {
                waitingForVisitors = false;
                start();
            }
            return;
        }

        if (!running) return;

        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            stop();
            return;
        }

        // Smoothly interpolate pitch and yaw toward the crop-specific target
        // every active tick.  Uses time-based exponential decay so the camera
        // glides to the correct angle at a consistent real-time rate even when
        // the server or client is lagging (lag-safe).
        // Skipped during MOUSEMAT_CLICK so the ability's server-side camera
        // adjustment (if any) is not immediately overridden.
        if (state != MacroState.MOUSEMAT_CLICK) {
            long now = System.currentTimeMillis();
            float deltaMs = (lastRotationTime == 0)
                    ? 50.0f
                    : Math.min(ROTATION_MAX_DELTA_MS, (float)(now - lastRotationTime));
            lastRotationTime = now;

            // Exponential-decay blend factor – scales with actual elapsed time
            // so lag spikes do not cause the camera to stutter or jump.
            float t = 1.0f - (float) Math.exp(-ROTATION_SPEED * deltaMs / 1000.0f);

            float targetPitch = config.getEffectivePitch(config.selectedCrop);
            float targetYaw   = config.getEffectiveYaw(config.selectedCrop);
            // normalizeAngleDiff wraps the raw difference into [-180, 180] so the
            // camera always takes the shortest arc (e.g. -170° instead of +190°).
            float pitchDiff   = normalizeAngleDiff(targetPitch - player.getPitch());
            float yawDiff     = normalizeAngleDiff(targetYaw   - player.getYaw());

            // Snap to the exact target when within half a degree to prevent
            // infinite floating-point drift near the destination.
            player.setPitch(Math.abs(pitchDiff) < 0.5f ? targetPitch
                    : player.getPitch() + pitchDiff * t);
            player.setYaw(Math.abs(yawDiff) < 0.5f ? targetYaw
                    : player.getYaw() + yawDiff * t);
        }

        switch (state) {
            case MOUSEMAT_CLICK     -> tickMousematClick(player);
            case DETECTING         -> tickDetecting(player);
            case BACKWARD_LEFT     -> tickMoving(player, false, true,  true,  false);
            case FORWARD_LEFT      -> tickMoving(player, true,  false, true,  false);
            case FORWARD_RIGHT     -> tickMoving(player, true,  false, false, true);
            case STRAFE_LEFT_ONLY  -> tickMoving(player, false, false, true,  false);
            case STRAFE_RIGHT_ONLY -> tickMoving(player, false, false, false, true);
            case BACK_ONLY         -> tickMoving(player, false, true,  false, false);
            case FORWARD_ONLY      -> tickMoving(player, true,  false, false, false);
            case WARPING           -> {
                releaseKeys();
                if (System.currentTimeMillis() - warpStartTime < warpTargetDelay) {
                    // Still waiting for the configured delay – keep keys released
                    break;
                }
                if (config.visitorsEnabled && visitorManager != null) {
                    // Stop the farming macro and hand off to the visitor routine.
                    // The macro will automatically restart once visitors are done.
                    running = false;
                    state = MacroState.IDLE;
                    lastRotationTime = 0;
                    waitingForVisitors = true;
                    if (config.unlockedMouseEnabled && client.currentScreen == null) {
                        client.mouse.lockCursor();
                    }
                    visitorManager.start();
                    LOGGER.info("[JustFarming] Visitor mode enabled – stopping farming macro, starting visitor routine.");
                } else {
                    triggerRewarp();
                    // Begin a fresh detection cycle after warping back to garden
                    state = MacroState.DETECTING;
                    detectTicks = 0;
                    startDetectTime = 0;
                    detectStartPos = new Vec3d(player.getX(), player.getY(), player.getZ());
                    lastPos = null;
                    stuckTicks = 0;
                }
            }
            case LANE_SWAP_WAITING -> {
                releaseMovementKeys();
                if (System.currentTimeMillis() - laneSwapStartTime >= laneSwapTargetDelay) {
                    if (laneSwapPendingCustomFlip) {
                        customFlipped = !customFlipped;
                        LOGGER.info("[JustFarming] Custom key flip (delayed) – {}.",
                                customFlipped ? "flipped" : "normal");
                    } else {
                        LOGGER.info("[JustFarming] End of row (delayed) – switching to {}.", laneSwapNextState);
                    }
                    state = laneSwapPendingCustomFlip
                            ? deriveStateFromCustomKeys(config.getCropSettings(config.selectedCrop))
                            : laneSwapNextState;
                }
            }
            default -> {}
        }
    }

    // -----------------------------------------------------------------------
    // State handlers
    // -----------------------------------------------------------------------

    /**
     * Mousemat click phases:
     * <ol>
     *   <li>Phase 0 – discover the Squeaky Mousemat slot.  If the player is not
     *       already holding it, wait {@code mousematSwapToDelay} ms then switch
     *       to its slot.  If already holding, skip the delay and go to phase 1.</li>
     *   <li>Phase 1 – wait {@code mousematPreDelay} ms, then activate the ability.</li>
     *   <li>Phase 2 – wait {@code mousematPostDelay} ms, then restore the original slot.</li>
     *   <li>Phase 3 – wait {@code mousematResumeDelay} ms, then start DETECTING.</li>
     * </ol>
     */
    private void tickMousematClick(ClientPlayerEntity player) {
        releaseKeys();
        long now = System.currentTimeMillis();

        switch (mousematPhase) {
            case 0 -> {
                if (mousematActionTime == 0) {
                    // First tick in this state: find the Squeaky Mousemat.
                    int mousematSlot = -1;
                    for (int i = 0; i < 9; i++) {
                        net.minecraft.item.ItemStack stack = player.getInventory().getStack(i);
                        if (!stack.isEmpty() && stack.getName().getString().equals("Squeaky Mousemat")) {
                            mousematSlot = i;
                            break;
                        }
                    }
                    if (mousematSlot < 0) {
                        LOGGER.info("[JustFarming] Squeaky Mousemat not found in hotbar – skipping.");
                        state = MacroState.DETECTING;
                        detectTicks = 0;
                        startDetectTime = 0;
                        detectStartPos = null;
                        return;
                    }
                    mousematTargetSlot = mousematSlot;
                    int currentSlot = player.getInventory().getSelectedSlot();
                    if (currentSlot == mousematSlot) {
                        // Already holding mousemat – find the farming tool for later restore.
                        preMousematSlot = -1;
                        for (int i = 0; i < 9; i++) {
                            if (i != mousematSlot && !player.getInventory().getStack(i).isEmpty()) {
                                preMousematSlot = i;
                                break;
                            }
                        }
                        // No swap needed – jump straight to pre-click delay.
                        mousematActionTime = now;
                        mousematPhaseRandomExtra = randomJitter();
                        mousematPhase = 1;
                    } else {
                        // Need to switch – record farming slot and start swap-to delay.
                        preMousematSlot = currentSlot;
                        mousematActionTime = now;
                        mousematPhaseRandomExtra = randomJitter();
                        // Stay in phase 0 until delay elapses.
                    }
                } else if (now - mousematActionTime >= config.mousematSwapToDelay + mousematPhaseRandomExtra) {
                    // Swap-to delay elapsed – switch to mousemat slot.
                    player.getInventory().setSelectedSlot(mousematTargetSlot);
                    LOGGER.info("[JustFarming] Switched to Squeaky Mousemat slot {} (was slot {}).",
                            mousematTargetSlot, preMousematSlot);
                    mousematActionTime = now;
                    mousematPhaseRandomExtra = randomJitter();
                    mousematPhase = 1;
                }
            }
            case 1 -> {
                // Wait pre-click delay, then activate ability.
                if (now - mousematActionTime >= config.mousematPreDelay + mousematPhaseRandomExtra) {
                    performMousematClick(player);
                    LOGGER.info("[JustFarming] Squeaky Mousemat left-click sent.");
                    mousematActionTime = now;
                    mousematPhaseRandomExtra = randomJitter();
                    mousematPhase = 2;
                }
            }
            case 2 -> {
                // Wait post-click delay, then restore slot.
                if (now - mousematActionTime >= config.mousematPostDelay + mousematPhaseRandomExtra) {
                    if (preMousematSlot >= 0) {
                        player.getInventory().setSelectedSlot(preMousematSlot);
                        LOGGER.info("[JustFarming] Restored hotbar slot {}.", preMousematSlot);
                    }
                    mousematActionTime = now;
                    mousematPhaseRandomExtra = randomJitter();
                    mousematPhase = 3;
                }
            }
            case 3 -> {
                // Wait resume delay, then begin farming.
                if (now - mousematActionTime >= config.mousematResumeDelay + mousematPhaseRandomExtra) {
                    mousematPhase = 0;
                    mousematTargetSlot = -1;
                    state = MacroState.DETECTING;
                    detectTicks = 0;
                    startDetectTime = 0;
                    detectStartPos = null;
                }
            }
            default -> {}
        }
    }

    /**
     * Performs the Squeaky Mousemat left-click.
     *
     * <p>Two branches:
     * <ul>
     *   <li>If the crosshair is aimed at a block, calls
     *       {@code client.interactionManager.attackBlock(blockPos, side)}, which
     *       sends a {@code PlayerActionC2SPacket} (START_DESTROY_BLOCK) to trigger
     *       the item ability server-side, then swings the hand.</li>
     *   <li>Otherwise, sends only the hand-swing packet.</li>
     * </ul>
     */
    private void performMousematClick(ClientPlayerEntity player) {
        if (client.interactionManager != null
                && client.crosshairTarget instanceof BlockHitResult blockHit) {
            client.interactionManager.attackBlock(blockHit.getBlockPos(), blockHit.getSide());
        }
        player.swingHand(Hand.MAIN_HAND);
    }

    /** Normalizes an angle difference to the range [-180, 180]. */
    private static float normalizeAngleDiff(float diff) {
        diff = diff % 360.0f;
        if (diff > 180.0f)  diff -= 360.0f;
        if (diff < -180.0f) diff += 360.0f;
        return diff;
    }

    /** Returns the block coordinate (floor) of the given world coordinate. */
    private static int blockCoord(double coord) {
        return (int) Math.floor(coord);
    }

    /**
     * Observation phase: the macro releases all movement keys and gives the
     * player up to {@value #START_DIRECTION_WAIT_MS} ms to press a direction
     * key (W for Cocoa Beans, D for S-Shape / left-back / Cactus, S for
     * Mushroom) to choose which side to start from.  If no key is pressed the
     * macro falls back to the movement-observation heuristic that was used
     * before.
     */
    private void tickDetecting(ClientPlayerEntity player) {
        releaseKeys();

        // If a custom key configuration is active, skip the detection heuristic
        // and jump straight into the user-configured key state.
        com.justfarming.config.FarmingConfig.CropCustomSettings cs =
                config.getCropSettings(config.selectedCrop);
        if (cs != null) {
            customFlipped = false;
            state = deriveStateFromCustomKeys(cs);
            lastPos = new Vec3d(player.getX(), player.getY(), player.getZ());
            stuckTicks = 0;
            startDetectTime = 0;
            LOGGER.info("[JustFarming] Custom key config – starting {}.", state);
            return;
        }

        long now = System.currentTimeMillis();
        if (startDetectTime == 0) {
            startDetectTime = now;
            detectStartPos = new Vec3d(player.getX(), player.getY(), player.getZ());
            detectionWEverPressed = false;
            detectionSEverPressed = false;
            detectionDEverPressed = false;
        }

        // Check raw GLFW key state so we can read the physical key even while the
        // macro itself has setPressed(false) on all movement bindings.
        // If the window is unavailable the booleans stay false and the macro falls
        // back to the default direction after the wait expires.
        CropType crop = config.selectedCrop;
        boolean wPressed = false, sPressed = false, dPressed = false;
        if (client.getWindow() != null) {
            wPressed = InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_W);
            sPressed = InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_S);
            dPressed = InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_D);
        }

        // Accumulate ever-pressed flags so a sub-tick press (< 50 ms) is detected.
        if (wPressed) detectionWEverPressed = true;
        if (sPressed) detectionSEverPressed = true;
        if (dPressed) detectionDEverPressed = true;

        // Determine whether the player has indicated a starting direction.
        // Use ever-pressed flags so even a very brief key press registers.
        boolean choiceForward = (crop == CropType.COCOA_BEANS) && (wPressed || detectionWEverPressed);
        boolean choiceRight   = (crop.isSShape() || crop.isLeftBack() || crop.isCactus()) && (dPressed || detectionDEverPressed);
        boolean choiceBack    = crop.isForwardBack() && (sPressed || detectionSEverPressed);
        boolean hasMadeChoice = choiceForward || choiceRight || choiceBack;

        // Wait up to START_DIRECTION_WAIT_MS for the player's input.
        long elapsed = now - startDetectTime;
        if (!hasMadeChoice && elapsed < START_DIRECTION_WAIT_MS) {
            return;
        }

        // Apply the chosen or default direction.
        if (hasMadeChoice) {
            if (choiceForward) {
                // Cocoa Beans + W: start going forward instead of backward.
                state = MacroState.FORWARD_LEFT;
                LOGGER.info("[JustFarming] Direction choice (W) – starting FORWARD_LEFT.");
            } else if (choiceRight) {
                if (crop.isSShape()) {
                    // S-Shape crops + D: start going right instead of left.
                    state = MacroState.FORWARD_RIGHT;
                    LOGGER.info("[JustFarming] Direction choice (D) – starting FORWARD_RIGHT.");
                } else if (crop.isLeftBack()) {
                    // Left-Back crops (Cane/Moonflower/Wild Rose/Sunflower) + D: start going back.
                    state = MacroState.BACK_ONLY;
                    LOGGER.info("[JustFarming] Direction choice (D) – starting BACK_ONLY.");
                } else {
                    // Cactus + D: start strafing right instead of left.
                    state = MacroState.STRAFE_RIGHT_ONLY;
                    LOGGER.info("[JustFarming] Direction choice (D) – starting STRAFE_RIGHT_ONLY.");
                }
            } else {
                // Mushroom + S: start going backward instead of forward.
                state = MacroState.BACK_ONLY;
                LOGGER.info("[JustFarming] Direction choice (S) – starting BACK_ONLY.");
            }
        } else {
            // No direction key pressed – fall back to movement heuristic, then
            // the built-in per-crop default.
            Vec3d currentXYZ = new Vec3d(player.getX(), player.getY(), player.getZ());
            Vec3d delta = currentXYZ.subtract(detectStartPos);
            double moved = Math.sqrt(delta.x * delta.x + delta.z * delta.z);

            if (moved > 0.2) {
                if (crop.isSShape()) {
                    state = MacroState.FORWARD_LEFT;
                    LOGGER.info("[JustFarming] Detected movement – S-Shape crop, starting FORWARD_LEFT.");
                } else if (crop.isLeftBack()) {
                    state = MacroState.STRAFE_LEFT_ONLY;
                    LOGGER.info("[JustFarming] Detected movement – left-back crop, starting STRAFE_LEFT_ONLY.");
                } else if (crop.isCactus()) {
                    state = MacroState.STRAFE_LEFT_ONLY;
                    LOGGER.info("[JustFarming] Detected movement – cactus, starting STRAFE_LEFT_ONLY.");
                } else {
                    // Project delta onto the crop's forward axis.
                    double yawRad = Math.toRadians(crop.getDefaultYaw());
                    double fwdX = -Math.sin(yawRad);
                    double fwdZ =  Math.cos(yawRad);
                    double forwardComponent = delta.x * fwdX + delta.z * fwdZ;
                    if (crop.isForwardBack()) {
                        state = (forwardComponent > 0) ? MacroState.FORWARD_ONLY : MacroState.BACK_ONLY;
                    } else {
                        state = (forwardComponent > 0) ? MacroState.FORWARD_LEFT : MacroState.BACKWARD_LEFT;
                    }
                    LOGGER.info("[JustFarming] Detected movement – starting {}.", state);
                }
            } else {
                // Player is stationary – fall back to the per-crop built-in default.
                state = defaultStartState(crop);
                LOGGER.info("[JustFarming] No movement detected – defaulting to {}.", state);
            }
        }

        startDetectTime = 0;
        detectionWEverPressed = false;
        detectionSEverPressed = false;
        detectionDEverPressed = false;
        lastPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        stuckTicks = 0;
    }

    /**
     * Movement phase: presses the appropriate directional keys and monitors the
     * player's position for end-of-row or rewarp-trigger events.
     *
     * @param forward    {@code true} = press the forward key.
     * @param back       {@code true} = press the back key.
     * @param strafeLeft {@code true} = press the left-strafe key.
     * @param strafeRight {@code true} = press the right-strafe key.
     */
    private void tickMoving(ClientPlayerEntity player, boolean forward, boolean back,
                            boolean strafeLeft, boolean strafeRight) {
        // Hold attack (breaks crops in view)
        client.options.attackKey.setPressed(true);

        // Directional keys
        client.options.forwardKey.setPressed(forward);
        client.options.backKey.setPressed(back);
        client.options.leftKey.setPressed(strafeLeft);
        client.options.rightKey.setPressed(strafeRight);

        // ---- Stuck detection (end of row) ----
        Vec3d currentPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        if (lastPos != null) {
            double dx = currentPos.x - lastPos.x;
            double dz = currentPos.z - lastPos.z;
            double moved = Math.sqrt(dx * dx + dz * dz);

            if (moved < 0.05) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
            }

            if (stuckTicks >= STUCK_THRESHOLD) {
                // End of row reached – flip direction (with optional lane-swap delay).
                // Release movement keys immediately so the player stops before the delay,
                // but keep the attack key held so block breaking continues.
                stuckTicks = 0;
                releaseMovementKeys();
                com.justfarming.config.FarmingConfig.CropCustomSettings cs =
                        config.getCropSettings(config.selectedCrop);
                long swapDelay = config.laneSwapDelayMin
                        + random.nextLong(config.laneSwapDelayRandom + 1)
                        + randomJitter();
                if (cs != null) {
                    // Custom key mode: toggle the flip flag (forward↔back, left↔right)
                    if (swapDelay > 0) {
                        laneSwapStartTime = System.currentTimeMillis();
                        laneSwapTargetDelay = swapDelay;
                        laneSwapPendingCustomFlip = true;
                        state = MacroState.LANE_SWAP_WAITING;
                    } else {
                        customFlipped = !customFlipped;
                        LOGGER.info("[JustFarming] Custom key flip – {}.",
                                customFlipped ? "flipped" : "normal");
                    }
                    return;
                }
                MacroState nextState;
                if (config.selectedCrop.isSShape()) {
                    // S-Shape: keep moving forward, flip strafe direction
                    nextState = (state == MacroState.FORWARD_LEFT)
                            ? MacroState.FORWARD_RIGHT : MacroState.FORWARD_LEFT;
                } else if (config.selectedCrop.isLeftBack()) {
                    // Left-Back: alternate between strafe-left and back-only
                    nextState = (state == MacroState.STRAFE_LEFT_ONLY)
                            ? MacroState.BACK_ONLY : MacroState.STRAFE_LEFT_ONLY;
                } else if (config.selectedCrop.isCactus()) {
                    // Cactus: alternate between strafe-left and strafe-right
                    nextState = (state == MacroState.STRAFE_LEFT_ONLY)
                            ? MacroState.STRAFE_RIGHT_ONLY : MacroState.STRAFE_LEFT_ONLY;
                } else if (config.selectedCrop.isForwardBack()) {
                    // Forward-Back (Mushroom): alternate between forward-only and back-only
                    nextState = (state == MacroState.FORWARD_ONLY)
                            ? MacroState.BACK_ONLY : MacroState.FORWARD_ONLY;
                } else {
                    // Cocoa Beans: flip forward/backward, always strafe left
                    nextState = (state == MacroState.FORWARD_LEFT)
                            ? MacroState.BACKWARD_LEFT : MacroState.FORWARD_LEFT;
                }
                if (swapDelay > 0) {
                    laneSwapStartTime = System.currentTimeMillis();
                    laneSwapTargetDelay = swapDelay;
                    laneSwapPendingCustomFlip = false;
                    laneSwapNextState = nextState;
                    state = MacroState.LANE_SWAP_WAITING;
                } else {
                    state = nextState;
                    LOGGER.info("[JustFarming] End of row – switching to {}.", state);
                }
                return;
            }
        }
        lastPos = currentPos;

        // ---- Rewarp trigger ----
        if (config.rewarpSet) {
            if (blockCoord(currentPos.x) == blockCoord(config.rewarpX)
                    && blockCoord(currentPos.z) == blockCoord(config.rewarpZ)) {
                LOGGER.info("[JustFarming] Reached rewarp position – warping.");
                warpStartTime   = System.currentTimeMillis();
                warpTargetDelay = config.rewarpDelayMin
                        + random.nextLong(config.rewarpDelayRandom + 1)
                        + randomJitter();
                state = MacroState.WARPING;
            }
        }

        // If a custom key configuration is active for this crop, override the
        // keys that the state machine just set so the player presses exactly the
        // user-configured keys (swapped when customFlipped is true).
        applyCustomKeyOverrides();
    }

    /** Release all held movement / attack keys. */
    private void releaseKeys() {
        if (client.options == null) return;
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.attackKey.setPressed(false);
    }

    /** Release movement keys only, keeping the attack/break key held. */
    private void releaseMovementKeys() {
        if (client.options == null) return;
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
    }

    /**
     * Returns a non-negative random extra delay (ms) within the configured
     * global jitter range ({@code 0} to {@code globalRandomizationMs - 1}).
     */
    private int randomJitter() {
        int range = Math.max(1, config.globalRandomizationMs);
        return random.nextInt(range);
    }

    /** Returns the hard-coded default starting state for a crop when no other signal is available. */
    private static MacroState defaultStartState(CropType crop) {
        if (crop.isSShape())            return MacroState.FORWARD_LEFT;
        if (crop.isLeftBack() || crop.isCactus()) return MacroState.STRAFE_LEFT_ONLY;
        if (crop.isForwardBack())       return MacroState.FORWARD_ONLY;
        return MacroState.BACKWARD_LEFT;
    }

    /**
     * After every tick where the macro is actively moving, override the
     * state-machine-set keys with the user's custom configuration (if any).
     */
    private void applyCustomKeyOverrides() {
        if (!shouldBreak()) return;
        com.justfarming.config.FarmingConfig.CropCustomSettings cs =
                config.getCropSettings(config.selectedCrop);
        if (cs == null || client.options == null) return;
        client.options.attackKey.setPressed(cs.attack);
        client.options.forwardKey.setPressed(customFlipped ? cs.back    : cs.forward);
        client.options.backKey.setPressed(   customFlipped ? cs.forward : cs.back);
        client.options.leftKey.setPressed(   customFlipped && shouldFlipStrafe(cs) ? cs.right : cs.left);
        client.options.rightKey.setPressed(  customFlipped && shouldFlipStrafe(cs) ? cs.left  : cs.right);
    }

    /**
     * Map a {@link com.justfarming.config.FarmingConfig.CropCustomSettings}
     * key combination to the closest {@link MacroState} so that the stuck
     * detection logic keeps working normally.
     */
    private MacroState deriveStateFromCustomKeys(
            com.justfarming.config.FarmingConfig.CropCustomSettings cs) {
        if (cs.forward && cs.left)  return MacroState.FORWARD_LEFT;
        if (cs.forward && cs.right) return MacroState.FORWARD_RIGHT;
        if (cs.forward)             return MacroState.FORWARD_ONLY;
        if (cs.back    && cs.left)  return MacroState.BACKWARD_LEFT;
        if (cs.back)                return MacroState.BACK_ONLY;
        if (cs.left)                return MacroState.STRAFE_LEFT_ONLY;
        if (cs.right)               return MacroState.STRAFE_RIGHT_ONLY;
        return MacroState.FORWARD_ONLY; // fallback
    }

    /**
     * Returns {@code true} when the strafe keys (left↔right) should be swapped
     * on a direction flip.
     *
     * <p>Pure-strafe crops like Cactus (no forward/back movement) alternate
     * their lateral direction on each row, so left↔right must be swapped.
     * Crops that alternate forward/back (e.g. Cocoa Beans) keep the same
     * strafe direction on both passes, so left↔right must NOT be swapped.
     */
    private static boolean shouldFlipStrafe(
            com.justfarming.config.FarmingConfig.CropCustomSettings cs) {
        return !(cs.forward || cs.back);
    }
}
