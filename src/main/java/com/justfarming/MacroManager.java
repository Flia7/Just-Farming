package com.justfarming;

import com.justfarming.config.FarmingConfig;
import com.justfarming.pest.PestDetector;
import com.justfarming.pest.PestKillerManager;
import com.justfarming.visitor.VisitorManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
import java.util.Set;

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
     * Camera rotation speed (degrees per second) used when aligning to the
     * crop-specific yaw/pitch.  Running at render frequency (~60 FPS) produces
     * steps of ~3°/frame, giving a total travel time of ~0.5 s for a 90° turn –
     * smooth and natural without being sluggish.
     */
    private static final float ROTATION_DEGREES_PER_SECOND = 180.0f;

    /**
     * Maximum delta-time (ms) used for the rotation interpolation.
     * Caps the step during a severe lag spike so the camera does not
     * teleport to the target after a multi-second freeze.
     */
    private static final float ROTATION_MAX_DELTA_MS = 250.0f;

    /**
     * Amplitude (degrees) of the random tremor added to each camera rotation
     * step, replicating the micro-vibration of a real mouse player.
     */
    private static final float ROTATION_TREMOR_AMPLITUDE = 0.12f;

    /**
     * Scale factor for the pitch component of the camera tremor.
     * Humans produce less vertical hand shake than horizontal.
     */
    private static final float ROTATION_TREMOR_PITCH_SCALE = 0.5f;

    /** Duration (ms) for each space-key press in the disable-flight sequence. */
    private static final long DISABLE_FLIGHT_PRESS_MS = 100L;
    /** Gap (ms) between the two presses in the disable-flight sequence. */
    private static final long DISABLE_FLIGHT_GAP_MS   = 60L;
    /** Extra wait (ms) after the second press before declaring the sequence complete. */
    private static final long DISABLE_FLIGHT_DONE_MS  = 200L;

    private final MinecraftClient client;
    private FarmingConfig config;
    private VisitorManager visitorManager;
    private PestKillerManager pestKillerManager;
    private PestDetector pestDetector;
    private final Random random = new Random();

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private enum MacroState { IDLE, DISABLING_FLIGHT, MOUSEMAT_CLICK, ALIGNING_ROTATION, DETECTING, BACKWARD_LEFT, FORWARD_LEFT, FORWARD_RIGHT,
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
    /**
     * {@code true} when the pest killer routine is running (triggered at the
     * rewarp block) and the farming macro has been paused.  The macro will
     * automatically restart (or hand off to the visitor routine) once the pest
     * killer completes.
     */
    private boolean waitingForPestKiller = false;
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

    /**
     * System-time (ms) when the DETECTING phase started; used to track the
     * one-tick movement observation window.
     */
    private long startDetectTime = 0;

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
     * Whether to keep the strafe-left key pressed during {@link MacroState#LANE_SWAP_WAITING}.
     * Set when the left-strafe direction is common to both the outgoing and incoming movement
     * state, so the key is never released and no lateral micro-movement occurs.
     */
    private boolean laneSwapKeepLeft = false;

    /**
     * Whether to keep the strafe-right key pressed during {@link MacroState#LANE_SWAP_WAITING}.
     * Set when the right-strafe direction is common to both the outgoing and incoming movement
     * state, so the key is never released and no lateral micro-movement occurs.
     */
    private boolean laneSwapKeepRight = false;

    /** Wall-clock time (ms) when the disable-flight sequence started; 0 = not active. */
    private long disableFlightStartTime = 0;
    /** The state to enter once the disable-flight sequence finishes. */
    private MacroState nextStateAfterFlightDisable = MacroState.DETECTING;

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

    /**
     * Inject the {@link PestKillerManager} and {@link PestDetector} so the
     * macro can trigger pest killing at the rewarp block before handling visitors.
     */
    public void setPestKillerManager(PestKillerManager pestKillerManager, PestDetector pestDetector) {
        this.pestKillerManager = pestKillerManager;
        this.pestDetector = pestDetector;
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

    /** Returns {@code true} when the macro is paused waiting for the visitor routine to finish. */
    public boolean isWaitingForVisitors() {
        return waitingForVisitors;
    }

    /** Returns {@code true} when the macro is paused waiting for the pest killer routine to finish. */
    public boolean isWaitingForPestKiller() {
        return waitingForPestKiller;
    }

    /**
     * Returns {@code true} when the macro should pause movement and block-breaking
     * because a GUI screen is open while {@link FarmingConfig#macroEnabledInGui}
     * is disabled.
     *
     * <p>When this returns {@code true}, {@link #tickMoving} releases all keys and
     * returns immediately, and {@link #shouldBreak()} returns {@code false}.
     */
    private boolean isGuiBlocking() {
        return !config.macroEnabledInGui && client.currentScreen != null;
    }

    /**
     * Returns {@code true} when the macro is actively in a movement+breaking
     * phase (BACKWARD_LEFT, FORWARD_LEFT, etc.).
     *
     * <p>Returns {@code false} while a GUI is open and
     * {@link FarmingConfig#macroEnabledInGui} is disabled, matching the user's
     * expectation that the macro pauses when a screen is open in that mode.
     */
    public boolean shouldBreak() {
        if (isGuiBlocking()) return false;
        return running && (state == MacroState.BACKWARD_LEFT || state == MacroState.FORWARD_LEFT
                || state == MacroState.FORWARD_RIGHT || state == MacroState.STRAFE_LEFT_ONLY
                || state == MacroState.STRAFE_RIGHT_ONLY
                || state == MacroState.BACK_ONLY || state == MacroState.FORWARD_ONLY);
    }
    /** Returns {@code true} when the macro is in the VISITING state (visitor routine active). */
    public boolean isVisiting() {
        return state == MacroState.VISITING;
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
        // Switch to the configured farming tool slot if one is set, or auto-detect
        // a farming tool in the hotbar when in Auto mode (farmingToolHotbarSlot == -1).
        if (client.player != null) {
            if (config.farmingToolHotbarSlot >= 0 && config.farmingToolHotbarSlot <= 8
                    && !client.player.getInventory().getStack(config.farmingToolHotbarSlot).isEmpty()) {
                client.player.getInventory().setSelectedSlot(config.farmingToolHotbarSlot);
                LOGGER.info("[JustFarming] Switched to farming tool slot {}.", config.farmingToolHotbarSlot);
            } else if (config.farmingToolHotbarSlot < 0) {
                // Auto-detect: find the first hotbar slot that holds a known farming tool.
                for (int i = 0; i < 9; i++) {
                    ItemStack stack = client.player.getInventory().getStack(i);
                    if (PestKillerManager.isFarmingTool(stack)) {
                        client.player.getInventory().setSelectedSlot(i);
                        LOGGER.info("[JustFarming] Auto-detected farming tool '{}' at slot {}; switching.",
                                PestKillerManager.getCleanItemName(stack), i);
                        break;
                    }
                }
            }
        }
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
        MacroState normalFirstState = (config.squeakyMousematEnabled && !skipMousemat)
                ? MacroState.MOUSEMAT_CLICK : MacroState.ALIGNING_ROTATION;
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
        disableFlightStartTime = 0;
        if (config.unlockedMouseEnabled) {
            client.mouse.unlockCursor();
        }
        // If the player is currently flying, disable flight before starting the macro.
        if (client.player != null && client.player.getAbilities().flying) {
            LOGGER.info("[JustFarming] Player is flying; disabling flight before starting macro.");
            nextStateAfterFlightDisable = normalFirstState;
            state = MacroState.DISABLING_FLIGHT;
        } else {
            state = normalFirstState;
        }
        LOGGER.info("[JustFarming] Macro started. Crop: {}", config.selectedCrop);
    }

    /** Stop the macro and release all held keys. */
    public void stop() {
        if (!running && !waitingForVisitors && !waitingForPestKiller
                && (visitorManager == null || !visitorManager.isActive())
                && (pestKillerManager == null || !pestKillerManager.isActive())) return;
        running = false;
        waitingForVisitors = false;
        waitingForPestKiller = false;
        state = MacroState.IDLE;
        lastRotationTime = 0;
        disableFlightStartTime = 0;
        releaseKeys();
        if (visitorManager != null && visitorManager.isActive()) {
            visitorManager.stop();
        }
        if (pestKillerManager != null && pestKillerManager.isActive()) {
            pestKillerManager.stop();
        }
        if (config.unlockedMouseEnabled && client.currentScreen == null) {
            client.mouse.lockCursor();
        }
        LOGGER.info("[JustFarming] Macro stopped.");
    }

    /** Toggle start / stop. */
    public void toggle() {
        if (running || waitingForVisitors || waitingForPestKiller
                || (visitorManager != null && visitorManager.isActive())
                || (pestKillerManager != null && pestKillerManager.isActive())) stop();
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

    /**
     * Called every render frame to smoothly rotate the camera toward the
     * crop-specific yaw/pitch target.
     *
     * <p>Running at render frequency (typically 60+ FPS) produces steps of
     * roughly 3° per frame at 180°/s, well within the range of genuine mouse
     * input.  This avoids the discrete 18°-per-tick jumps that occur when
     * rotation is applied only on game ticks (20 TPS).
     *
     * <p>Skipped during {@link MacroState#MOUSEMAT_CLICK} so the Squeaky
     * Mousemat ability's server-side camera adjustment is not immediately
     * overridden.
     */
    public void onRenderTick() {
        if (!running || state == MacroState.MOUSEMAT_CLICK) return;
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        long now = System.currentTimeMillis();
        float deltaMs = (lastRotationTime == 0)
                ? 50.0f
                : Math.min(ROTATION_MAX_DELTA_MS, (float)(now - lastRotationTime));
        lastRotationTime = now;

        float step = ROTATION_DEGREES_PER_SECOND * deltaMs / 1000.0f;

        float targetPitch = config.getEffectivePitch(config.selectedCrop);
        float targetYaw   = config.getEffectiveYaw(config.selectedCrop);

        float currentYaw   = player.getYaw();
        float currentPitch = player.getPitch();

        float yawDiff   = normalizeAngleDiff(targetYaw   - currentYaw);
        float pitchDiff = normalizeAngleDiff(targetPitch - currentPitch);

        if (Math.abs(yawDiff) < 0.1f && Math.abs(pitchDiff) < 0.1f) return;

        float newYaw   = currentYaw   + Math.max(-step, Math.min(step, yawDiff));
        float newPitch = currentPitch + Math.max(-step, Math.min(step, pitchDiff));
        newPitch = Math.max(-90f, Math.min(90f, newPitch));

        float tremorYaw   = (random.nextFloat() * 2f - 1f) * ROTATION_TREMOR_AMPLITUDE;
        float tremorPitch = (random.nextFloat() * 2f - 1f) * ROTATION_TREMOR_AMPLITUDE * ROTATION_TREMOR_PITCH_SCALE;

        player.setYaw(newYaw + tremorYaw);
        player.setPitch(newPitch + tremorPitch);
    }

    /** Called every client tick. Executes one step of the macro if active. */
    public void onTick() {
        // If paused waiting for the pest killer routine to finish (triggered at rewarp),
        // start the visitor routine or restart farming once the pest killer is done.
        if (waitingForPestKiller) {
            if (pestKillerManager != null && pestKillerManager.isDone()) {
                waitingForPestKiller = false;
                pestKillerManager.reset();
                if (config.visitorsEnabled && visitorManager != null) {
                    // Pest killer already sent /warp garden; visitors will /tptoplot barn.
                    waitingForVisitors = true;
                    if (config.unlockedMouseEnabled && client.currentScreen == null) {
                        client.mouse.lockCursor();
                    }
                    visitorManager.start();
                    LOGGER.info("[JustFarming] Pest killer done – visitor mode enabled, starting visitor routine.");
                } else {
                    // Pest killer already sent /warp garden; restart farming.
                    start();
                    LOGGER.info("[JustFarming] Pest killer done – resuming farming macro.");
                }
            }
            return;
        }

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

        // Camera rotation is handled by onRenderTick() at render frequency
        // (60+ FPS) for smooth, linear steps.  Nothing to do here.

        switch (state) {
            case DISABLING_FLIGHT  -> tickDisablingFlight(player);
            case MOUSEMAT_CLICK     -> tickMousematClick(player);
            case ALIGNING_ROTATION -> tickAligningRotation(player);
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
                Set<String> pestPlots = (pestDetector != null)
                        ? pestDetector.getPestPlots() : Collections.emptySet();
                if (config.autoPestKillerEnabled && pestKillerManager != null
                        && !pestPlots.isEmpty()) {
                    // Pest killer has priority over visitors at the rewarp block.
                    // Stop the farming macro and start the pest killer for all infested plots.
                    running = false;
                    state = MacroState.IDLE;
                    lastRotationTime = 0;
                    waitingForPestKiller = true;
                    if (config.unlockedMouseEnabled && client.currentScreen == null) {
                        client.mouse.lockCursor();
                    }
                    pestKillerManager.start(new ArrayList<>(pestPlots));
                    LOGGER.info("[JustFarming] Pest killer enabled – stopping farming macro, starting pest killer routine.");
                } else if (config.visitorsEnabled && visitorManager != null) {
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
                    startDetectTime = 0;
                    detectStartPos = new Vec3d(player.getX(), player.getY(), player.getZ());
                    lastPos = null;
                    stuckTicks = 0;
                }
            }
            case LANE_SWAP_WAITING -> {
                if (laneSwapPendingCustomFlip) {
                    // Custom-key flip: release all movement keys (existing behaviour).
                    releaseMovementKeys();
                } else {
                    // Standard flip: keep only the strafe key(s) that are common to both
                    // the outgoing and incoming state so there is no lateral micro-movement.
                    if (client.options != null) {
                        client.options.forwardKey.setPressed(false);
                        client.options.backKey.setPressed(false);
                        client.options.leftKey.setPressed(laneSwapKeepLeft);
                        client.options.rightKey.setPressed(laneSwapKeepRight);
                    }
                }
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
     * Rotation-alignment phase: releases all movement keys and waits until the
     * player's yaw and pitch have settled within {@value #ROTATION_ALIGN_THRESHOLD}
     * degrees of the crop-specific target.  Only entered when the Squeaky
     * Mousemat is disabled; it ensures the camera is aimed correctly before the
     * macro starts moving or breaking any blocks.
     *
     * <p>Camera rotation is driven by {@link #onRenderTick()} which runs at
     * render frequency and is not affected by this state.
     */
    private static final float ROTATION_ALIGN_THRESHOLD = 2.0f;

    private void tickAligningRotation(ClientPlayerEntity player) {
        releaseKeys();
        float targetYaw   = config.getEffectiveYaw(config.selectedCrop);
        float targetPitch = config.getEffectivePitch(config.selectedCrop);
        float yawDiff     = Math.abs(normalizeAngleDiff(player.getYaw()   - targetYaw));
        float pitchDiff   = Math.abs(normalizeAngleDiff(player.getPitch() - targetPitch));
        if (yawDiff <= ROTATION_ALIGN_THRESHOLD && pitchDiff <= ROTATION_ALIGN_THRESHOLD) {
            state = MacroState.DETECTING;
            startDetectTime  = 0;
            detectStartPos   = null;
            LOGGER.info("[JustFarming] Rotation aligned (yaw diff={}, pitch diff={}) – starting detection.", yawDiff, pitchDiff);
        }
    }

    /**
     * Executes a double-press space sequence to disable creative-style flight.
     * Once the sequence completes the macro transitions to {@link #nextStateAfterFlightDisable}.
     */
    private void tickDisablingFlight(ClientPlayerEntity player) {
        if (client.options == null) return;
        long now = System.currentTimeMillis();
        if (disableFlightStartTime == 0) disableFlightStartTime = now;
        long elapsed   = now - disableFlightStartTime;
        long phase1End = DISABLE_FLIGHT_PRESS_MS;
        long phase2End = DISABLE_FLIGHT_PRESS_MS + DISABLE_FLIGHT_GAP_MS;
        long phase3End = DISABLE_FLIGHT_PRESS_MS * 2 + DISABLE_FLIGHT_GAP_MS;

        if (elapsed >= phase3End + DISABLE_FLIGHT_DONE_MS) {
            disableFlightStartTime = 0;
            client.options.jumpKey.setPressed(false);
            LOGGER.info("[JustFarming] Disable-flight sequence complete; continuing startup.");
            state = nextStateAfterFlightDisable;
        } else {
            boolean jumpPressed = elapsed < phase1End
                    || (elapsed >= phase2End && elapsed < phase3End);
            client.options.jumpKey.setPressed(jumpPressed);
            client.options.forwardKey.setPressed(false);
        }
    }

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
     * Observation phase: the macro releases all movement keys for one tick to
     * observe the player's current movement direction, then immediately starts
     * in the detected direction (or the built-in default if the player is
     * stationary).  Direction can be changed at any time using the alternate-
     * direction keybind (default: N).
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

        // Wait one tick so we have two positions to compute a movement delta.
        if (detectStartPos == null) {
            detectStartPos = new Vec3d(player.getX(), player.getY(), player.getZ());
            startDetectTime = System.currentTimeMillis();
            return;
        }

        CropType crop = config.selectedCrop;
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

        detectStartPos = null;
        startDetectTime = 0;
        lastPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        stuckTicks = 0;
    }

    /**
     * Movement phase: presses the appropriate directional keys and monitors the
     * player's position for end-of-row or rewarp-trigger events.
     *
     * <p>When a GUI screen is open and {@link FarmingConfig#macroEnabledInGui} is
     * disabled, all keys are released and the method returns early so the player
     * stops moving and breaking.  Position tracking is also suspended so that
     * being blocked by the GUI is not misidentified as an end-of-row event.
     *
     * @param forward    {@code true} = press the forward key.
     * @param back       {@code true} = press the back key.
     * @param strafeLeft {@code true} = press the left-strafe key.
     * @param strafeRight {@code true} = press the right-strafe key.
     */
    private void tickMoving(ClientPlayerEntity player, boolean forward, boolean back,
                            boolean strafeLeft, boolean strafeRight) {
        // Pause all movement and breaking when the GUI is blocking
        // (macroEnabledInGui = false while a screen is open).
        if (isGuiBlocking()) {
            releaseKeys();
            lastPos = null; // prevent stationary ticks from triggering stuck detection
            return;
        }

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
                stuckTicks = 0;
                com.justfarming.config.FarmingConfig.CropCustomSettings cs =
                        config.getCropSettings(config.selectedCrop);
                long swapDelay = config.laneSwapDelayMin
                        + random.nextLong(config.laneSwapDelayRandom + 1)
                        + randomJitter();
                if (cs != null) {
                    // Custom key mode: release movement keys and toggle the flip flag.
                    releaseMovementKeys();
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
                // Determine which strafe key(s) are shared by both the outgoing and the
                // incoming state.  A shared strafe key is never released, which prevents
                // a one-tick lateral micro-movement during the direction flip.
                boolean nextStrafeLeft  = (nextState == MacroState.BACKWARD_LEFT
                        || nextState == MacroState.FORWARD_LEFT
                        || nextState == MacroState.STRAFE_LEFT_ONLY);
                boolean nextStrafeRight = (nextState == MacroState.FORWARD_RIGHT
                        || nextState == MacroState.STRAFE_RIGHT_ONLY);
                laneSwapKeepLeft  = strafeLeft  && nextStrafeLeft;
                laneSwapKeepRight = strafeRight && nextStrafeRight;
                if (swapDelay > 0) {
                    // Release only the directional (forward/back) keys; keep the preserved
                    // strafe key held so there is no lateral micro-movement during the wait.
                    client.options.forwardKey.setPressed(false);
                    client.options.backKey.setPressed(false);
                    client.options.leftKey.setPressed(laneSwapKeepLeft);
                    client.options.rightKey.setPressed(laneSwapKeepRight);
                    laneSwapStartTime = System.currentTimeMillis();
                    laneSwapTargetDelay = swapDelay;
                    laneSwapPendingCustomFlip = false;
                    laneSwapNextState = nextState;
                    state = MacroState.LANE_SWAP_WAITING;
                } else {
                    // Immediate transition: the current-tick keys remain set until the
                    // next tick when the new state's tickMoving overwrites them.  Because
                    // the strafe key is still held there is no lateral micro-movement.
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

        // Always drive block breaking directly so the macro works identically
        // regardless of whether a GUI screen is open or closed.  Vanilla's
        // handleBlockBreaking() is suppressed by MinecraftClientMixin when the
        // macro is active, so this is the sole break driver for all states.
        directBreakBlock();
    }

    /**
     * Directly attacks the block at the crosshair target via the interaction
     * manager.
     *
     * <p>This is the sole block-break driver when the macro is active.
     * {@code MinecraftClientMixin} suppresses vanilla's
     * {@code handleBlockBreaking()} call entirely while {@link #shouldBreak()}
     * is {@code true}, so there is no risk of a double-call resetting break
     * progress.  Calling {@link net.minecraft.client.network.ClientPlayerInteractionManager#attackBlock}
     * on the same block position every tick is idempotent: the first call sends
     * {@code START_DESTROY_BLOCK}; subsequent calls for the same block are
     * no-ops until the block changes.  {@code swingHand} is only called when
     * {@code attackBlock} confirms the action was processed.</p>
     */
    public void directBreakBlock() {
        if (client.interactionManager == null || client.world == null) return;
        if (!(client.crosshairTarget instanceof BlockHitResult blockHit)) return;
        BlockPos pos = blockHit.getBlockPos();
        if (!client.world.getBlockState(pos).isAir()) {
            boolean attacked = client.interactionManager.attackBlock(pos, blockHit.getSide());
            if (attacked && client.player != null) {
                client.player.swingHand(Hand.MAIN_HAND);
            }
        }
    }

    /**
     * Instantly alternates the macro's movement direction when the player presses
     * the configurable alternate-direction keybind (default: N, reassignable in
     * the Controls screen).
     *
     * <p>This replaces the old per-crop GLFW polling approach.  A single key now
     * works uniformly for every crop type:
     * <ul>
     *   <li>S-shape crops (wheat, carrot, potato, melon, pumpkin, nether wart):
     *       toggles between {@link MacroState#FORWARD_LEFT} and
     *       {@link MacroState#FORWARD_RIGHT}.</li>
     *   <li>Cactus: toggles between {@link MacroState#STRAFE_LEFT_ONLY} and
     *       {@link MacroState#STRAFE_RIGHT_ONLY}.</li>
     *   <li>Left-Back (sugar cane, moonflower, sunflower, wild rose): toggles
     *       between {@link MacroState#STRAFE_LEFT_ONLY} and
     *       {@link MacroState#BACK_ONLY}.</li>
     *   <li>Forward-Back (mushroom): toggles between {@link MacroState#FORWARD_ONLY}
     *       and {@link MacroState#BACK_ONLY}.</li>
     *   <li>Cocoa Beans / other back-left crops: toggles between
     *       {@link MacroState#BACKWARD_LEFT} and {@link MacroState#FORWARD_LEFT}.</li>
     *   <li>Custom-key crops: toggles {@link #customFlipped} and immediately
     *       applies the new key combination.</li>
     * </ul>
     *
     * <p>Any pending {@link MacroState#LANE_SWAP_WAITING} delay is cancelled so
     * the switch is truly instantaneous.
     *
     * <p>Called by {@link com.justfarming.JustFarming}'s tick handler whenever
     * the {@code alternate_direction} keybind reports a fresh press
     * ({@link net.minecraft.client.option.KeyBinding#wasPressed()}).
     */
    public void triggerInstantAlternate() {
        if (!running) return;
        CropType crop = config.selectedCrop;
        com.justfarming.config.FarmingConfig.CropCustomSettings cs =
                config.getCropSettings(crop);

        if (cs != null) {
            // Custom key mode: flip the customFlipped flag immediately.
            customFlipped = !customFlipped;
            // Cancel any in-progress lane-swap delay.
            if (state == MacroState.LANE_SWAP_WAITING) {
                state = deriveStateFromCustomKeys(cs);
            }
            stuckTicks = 0;
            lastPos    = null;
            LOGGER.info("[JustFarming] Alternate key – custom flip {} (crop: {}).",
                    customFlipped ? "on" : "off", crop);
        } else {
            // Built-in pattern: compute and jump directly to the opposite state.
            MacroState nextState = computeAlternateState(crop);
            if (nextState != null && nextState != state) {
                // Cancel any in-progress lane-swap delay.
                state      = nextState;
                stuckTicks = 0;
                lastPos    = null;
                LOGGER.info("[JustFarming] Alternate key – switching to {} (crop: {}).",
                        nextState, crop);
            }
        }
    }

    /**
     * Returns the alternate {@link MacroState} for the given built-in crop type,
     * toggling between the two halves of its row pattern.
     */
    private MacroState computeAlternateState(CropType crop) {
        if (crop.isSShape()) {
            return (state == MacroState.FORWARD_LEFT) ? MacroState.FORWARD_RIGHT : MacroState.FORWARD_LEFT;
        } else if (crop.isCactus()) {
            return (state == MacroState.STRAFE_LEFT_ONLY) ? MacroState.STRAFE_RIGHT_ONLY : MacroState.STRAFE_LEFT_ONLY;
        } else if (crop.isLeftBack()) {
            return (state == MacroState.STRAFE_LEFT_ONLY) ? MacroState.BACK_ONLY : MacroState.STRAFE_LEFT_ONLY;
        } else if (crop.isForwardBack()) {
            return (state == MacroState.FORWARD_ONLY) ? MacroState.BACK_ONLY : MacroState.FORWARD_ONLY;
        } else {
            // Cocoa Beans (and other back-left crops)
            return (state == MacroState.BACKWARD_LEFT) ? MacroState.FORWARD_LEFT : MacroState.BACKWARD_LEFT;
        }
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
     *
     * <p>When {@link #customFlipped} is {@code true} the macro is in its
     * "alternate" direction pass.  The flip axis depends on the crop:
     * <ul>
     *   <li><b>S-shape / pure-strafe</b> ({@link #shouldFlipStrafe} = {@code true}):
     *       forward/back keys stay the same; left↔right are swapped.</li>
     *   <li><b>Cocoa-Beans style</b> ({@link #shouldFlipStrafe} = {@code false}):
     *       forward↔back are swapped; left/right keys stay the same.</li>
     * </ul>
     */
    private void applyCustomKeyOverrides() {
        if (!shouldBreak()) return;
        com.justfarming.config.FarmingConfig.CropCustomSettings cs =
                config.getCropSettings(config.selectedCrop);
        if (cs == null || client.options == null) return;
        boolean flipStrafe = shouldFlipStrafe(cs);
        client.options.attackKey.setPressed(cs.attack);
        client.options.forwardKey.setPressed((!flipStrafe && customFlipped) ? cs.back    : cs.forward);
        client.options.backKey.setPressed(   (!flipStrafe && customFlipped) ? cs.forward : cs.back);
        client.options.leftKey.setPressed(   (flipStrafe  && customFlipped) ? cs.right   : cs.left);
        client.options.rightKey.setPressed(  (flipStrafe  && customFlipped) ? cs.left    : cs.right);
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
     * <p>S-shape crops (wheat, carrot, potato, melon, pumpkin, nether wart) always
     * move <em>forward</em> and alternate only the strafe direction (left↔right),
     * so {@code shouldFlipStrafe} must return {@code true} for them.  Pure-strafe
     * crops (Cactus, no forward/back) also swap strafe.  Only crops that
     * alternate forward↔back (e.g. Cocoa Beans where {@code cs.back = true}, or
     * Mushroom where neither left nor right is pressed) must return {@code false}
     * so that the forward/back keys are swapped instead.
     *
     * <p>Decision rule: flip strafe only when there is actually a strafe key to
     * flip ({@code cs.left || cs.right}) AND the crop does not use back movement.
     * Mushroom (forward-only with no strafe) falls through to the forward↔back
     * axis so the direction alternates correctly.
     */
    private static boolean shouldFlipStrafe(
            com.justfarming.config.FarmingConfig.CropCustomSettings cs) {
        return !cs.back && (cs.left || cs.right);
    }
}
