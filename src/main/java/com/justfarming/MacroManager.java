package com.justfarming;

import com.justfarming.config.FarmingConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the cocoa-beans farming macro state and tick logic.
 *
 * <p>State machine:
 * <ol>
 *   <li>IDLE – macro is not running.</li>
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

    private final MinecraftClient client;
    private FarmingConfig config;

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private enum MacroState { IDLE, MOUSEMAT_SNAP, DETECTING, BACKWARD_LEFT, FORWARD_LEFT, FORWARD_RIGHT,
            STRAFE_LEFT_ONLY, STRAFE_RIGHT_ONLY, BACK_ONLY, FORWARD_ONLY, WARPING,
            LANE_SWAP_WAITING }

    private MacroState state = MacroState.IDLE;
    private boolean running = false;
    private boolean freelookEnabled = false;
    private double freelookZoom = DEFAULT_ZOOM;

    /**
     * When a crop has a custom key configuration this flag tracks which
     * "half" of the direction cycle we are in.  {@code false} = primary keys,
     * {@code true} = flipped keys (forward↔back, left↔right swapped).
     */
    private boolean customFlipped = false;

    /** Position recorded at the start of the DETECTING phase. */
    private Vec3d detectStartPos = null;
    private int detectTicks = 0;

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
     * Tracks which phase of the MOUSEMAT_SNAP state we are in.
     * {@code 0} = slot not yet switched;
     * {@code 1} = slot switched, waiting 200 ms before clicking;
     * {@code 2} = click sent, waiting for camera to snap to desired angle.
     */
    private int mousematSnapPhase = 0;

    /** Hotbar slot that was active before the Squeaky Mousemat switch; -1 if unknown. */
    private int preMousematSlot = -1;

    /** System-time (ms) when the last Squeaky Mousemat action (slot switch or click) occurred. */
    private long mousematActionTime = 0;

    // -----------------------------------------------------------------------
    // Constructor / config
    // -----------------------------------------------------------------------

    public MacroManager(MinecraftClient client, FarmingConfig config) {
        this.client = client;
        this.config = config;
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
     * block-breaking even when a GUI screen is open.
     */
    public boolean shouldBreak() {
        return running && (state == MacroState.BACKWARD_LEFT || state == MacroState.FORWARD_LEFT
                || state == MacroState.FORWARD_RIGHT || state == MacroState.STRAFE_LEFT_ONLY
                || state == MacroState.STRAFE_RIGHT_ONLY
                || state == MacroState.BACK_ONLY || state == MacroState.FORWARD_ONLY);
    }

    /**
     * Returns {@code true} when the macro is in the FORWARD_LEFT state,
     * {@code false} when in BACKWARD_LEFT.
     *
     * <p>Used by input-layer mixins to determine which direction to apply when
     * re-asserting movement inputs while a GUI screen is open.
     */
    public boolean isMovingForward() {
        if (!running) return false;
        com.justfarming.config.FarmingConfig.CropCustomSettings cs =
                config.getCropSettings(config.selectedCrop);
        if (cs != null && shouldBreak()) {
            return customFlipped ? cs.back : cs.forward;
        }
        return state == MacroState.FORWARD_LEFT || state == MacroState.FORWARD_RIGHT
                || state == MacroState.FORWARD_ONLY;
    }

    /** Returns {@code true} when the back key should be held (BACKWARD_LEFT or BACK_ONLY). */
    public boolean isMovingBack() {
        if (!running) return false;
        com.justfarming.config.FarmingConfig.CropCustomSettings cs =
                config.getCropSettings(config.selectedCrop);
        if (cs != null && shouldBreak()) {
            return customFlipped ? cs.forward : cs.back;
        }
        return state == MacroState.BACKWARD_LEFT || state == MacroState.BACK_ONLY;
    }

    /** Returns {@code true} when the left-strafe key should be held. */
    public boolean isStrafeLeft() {
        if (!running) return false;
        com.justfarming.config.FarmingConfig.CropCustomSettings cs =
                config.getCropSettings(config.selectedCrop);
        if (cs != null && shouldBreak()) {
            return customFlipped && shouldFlipStrafe(cs) ? cs.right : cs.left;
        }
        return state == MacroState.BACKWARD_LEFT || state == MacroState.FORWARD_LEFT
                || state == MacroState.STRAFE_LEFT_ONLY;
    }

    /** Returns {@code true} when the right-strafe key should be held (FORWARD_RIGHT or STRAFE_RIGHT_ONLY). */
    public boolean isStrafeRight() {
        if (!running) return false;
        com.justfarming.config.FarmingConfig.CropCustomSettings cs =
                config.getCropSettings(config.selectedCrop);
        if (cs != null && shouldBreak()) {
            return customFlipped && shouldFlipStrafe(cs) ? cs.left : cs.right;
        }
        return state == MacroState.FORWARD_RIGHT || state == MacroState.STRAFE_RIGHT_ONLY;
    }

    /**
     * Re-apply the programmatic key states that the macro holds during a
     * movement+breaking phase.  Called from {@code KeyBindingMixin} immediately
     * after {@link net.minecraft.client.option.KeyBinding#unpressAll()} runs so
     * that only the keys the macro explicitly controls are kept pressed while
     * all other key states are reset normally.
     */
    public void reapplyMovementKeys() {
        if (!running || client.options == null) return;
        if (state == MacroState.BACKWARD_LEFT || state == MacroState.FORWARD_LEFT
                || state == MacroState.FORWARD_RIGHT || state == MacroState.STRAFE_LEFT_ONLY
                || state == MacroState.STRAFE_RIGHT_ONLY
                || state == MacroState.BACK_ONLY || state == MacroState.FORWARD_ONLY) {

            com.justfarming.config.FarmingConfig.CropCustomSettings cs =
                    config.getCropSettings(config.selectedCrop);
            if (cs != null) {
                client.options.attackKey.setPressed(cs.attack);
                client.options.forwardKey.setPressed(customFlipped ? cs.back    : cs.forward);
                client.options.backKey.setPressed(   customFlipped ? cs.forward : cs.back);
                client.options.leftKey.setPressed(   customFlipped && shouldFlipStrafe(cs) ? cs.right : cs.left);
                client.options.rightKey.setPressed(  customFlipped && shouldFlipStrafe(cs) ? cs.left  : cs.right);
                return;
            }

            client.options.attackKey.setPressed(true);
            boolean goForward  = (state == MacroState.FORWARD_LEFT || state == MacroState.FORWARD_RIGHT || state == MacroState.FORWARD_ONLY);
            boolean goBack     = (state == MacroState.BACKWARD_LEFT || state == MacroState.BACK_ONLY);
            boolean goLeft     = (state == MacroState.BACKWARD_LEFT || state == MacroState.FORWARD_LEFT || state == MacroState.STRAFE_LEFT_ONLY);
            boolean goRight    = (state == MacroState.FORWARD_RIGHT || state == MacroState.STRAFE_RIGHT_ONLY);
            client.options.forwardKey.setPressed(goForward);
            client.options.backKey.setPressed(goBack);
            client.options.leftKey.setPressed(goLeft);
            client.options.rightKey.setPressed(goRight);
        }
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
        running = true;
        state = config.squeakyMousematEnabled ? MacroState.MOUSEMAT_SNAP : MacroState.DETECTING;
        detectTicks = 0;
        detectStartPos = null;
        lastPos = null;
        stuckTicks = 0;
        customFlipped = false;
        mousematSnapPhase = 0;
        preMousematSlot = -1;
        mousematActionTime = 0;
        if (config.unlockedMouseEnabled) {
            client.mouse.unlockCursor();
        }
        LOGGER.info("[JustFarming] Macro started. Crop: {}", config.selectedCrop);
    }

    /** Stop the macro and release all held keys. */
    public void stop() {
        if (!running) return;
        running = false;
        state = MacroState.IDLE;
        releaseKeys();
        if (config.unlockedMouseEnabled && client.currentScreen == null) {
            client.mouse.lockCursor();
        }
        LOGGER.info("[JustFarming] Macro stopped.");
    }

    /** Toggle start / stop. */
    public void toggle() {
        if (running) stop();
        else start();
    }

    /**
     * Save the player's current position as the rewarp waypoint.
     * Every time the macro reaches this position it will automatically
     * send {@code /warp garden}.  This is called by the {@code /jf rewarp}
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
        if (!running) return;

        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            stop();
            return;
        }

        // Lock pitch and yaw to crop-specific defaults every active tick.
        // Skipped during MOUSEMAT_SNAP so the mousemat's camera snap can take effect.
        if (state != MacroState.MOUSEMAT_SNAP) {
            player.setPitch(config.getEffectivePitch(config.selectedCrop));
            player.setYaw(config.getEffectiveYaw(config.selectedCrop));
        }

        switch (state) {
            case MOUSEMAT_SNAP      -> tickMousematSnap(player);
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
                triggerRewarp();
                // Begin a fresh detection cycle after warping back to garden
                state = MacroState.DETECTING;
                detectTicks = 0;
                detectStartPos = new Vec3d(player.getX(), player.getY(), player.getZ());
                lastPos = null;
                stuckTicks = 0;
            }
            case LANE_SWAP_WAITING -> {
                releaseKeys();
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

        // If a custom key configuration is active for this crop, override the
        // keys that the state machine just set so the player presses exactly the
        // user-configured keys (swapped when customFlipped is true).
        applyCustomKeyOverrides();
    }

    // -----------------------------------------------------------------------
    // State handlers
    // -----------------------------------------------------------------------

    /**
     * Mousemat snap phase: searches the player's hotbar for a Squeaky Mousemat,
     * switches to that slot, waits 200 ms, performs a left-click (arm swing) so
     * that Hypixel Skyblock's plugin snaps the camera to the mousemat's configured
     * yaw and pitch, then verifies the snap succeeded before restoring the
     * previously held farming tool.
     *
     * <p>Phase sequence:
     * <ol>
     *   <li>Phase 0 – find the Squeaky Mousemat in the hotbar.  If the player is
     *       already holding the mousemat, find the farming tool in the hotbar and
     *       save that slot as {@code preMousematSlot} so it can be restored later.
     *       Switch to the mousemat slot and record the action time.</li>
     *   <li>Phase 1 – wait 200 ms after the slot switch, then send the left-click
     *       (arm swing).  {@link ClientPlayerEntity#swingHand} sends only
     *       {@code HandSwingC2SPacket} — no block interaction, no breaking
     *       animation — which is sufficient for Hypixel Skyblock's Squeaky
     *       Mousemat ability to fire regardless of what the crosshair is
     *       targeting.</li>
     *   <li>Phase 2 – wait 200 ms after the click, then check whether the player's
     *       yaw/pitch match the desired crop angles (within 2°).  If the snap
     *       succeeded, restore the farming tool slot and advance to phase 3.
     *       If not, resend the click and wait another 200 ms (stay in phase 2).</li>
     *   <li>Phase 3 – wait 200 ms after the farming tool slot is restored to ensure
     *       the player is holding the farming tool before farming begins, then
     *       transition to DETECTING.</li>
     * </ol>
     * If the Squeaky Mousemat is not found in phase 0, the state transitions to
     * DETECTING immediately.
     */
    private void tickMousematSnap(ClientPlayerEntity player) {
        releaseKeys();

        if (mousematSnapPhase == 0) {
            // Phase 0: find the mousemat and switch to it
            int mousematSlot = -1;
            for (int i = 0; i < 9; i++) {
                ItemStack stack = player.getInventory().getStack(i);
                if (!stack.isEmpty() && stack.getName().getString().equals("Squeaky Mousemat")) {
                    mousematSlot = i;
                    break;
                }
            }

            if (mousematSlot >= 0) {
                preMousematSlot = player.getInventory().getSelectedSlot();
                // If the player was already holding the mousemat, find the farming tool.
                if (preMousematSlot == mousematSlot) {
                    for (int i = 0; i < 9; i++) {
                        if (i == mousematSlot) continue;
                        if (!player.getInventory().getStack(i).isEmpty()) {
                            preMousematSlot = i;
                            LOGGER.info("[JustFarming] Player was holding Squeaky Mousemat; "
                                    + "farming tool detected in hotbar slot {}.", preMousematSlot);
                            break;
                        }
                    }
                    if (preMousematSlot == mousematSlot) {
                        LOGGER.warn("[JustFarming] Could not find farming tool in hotbar.");
                    }
                }
                player.getInventory().setSelectedSlot(mousematSlot);
                LOGGER.info("[JustFarming] Switching to Squeaky Mousemat in hotbar slot {} (was slot {}).",
                        mousematSlot, preMousematSlot);
                mousematActionTime = System.currentTimeMillis();
                mousematSnapPhase = 1;
            } else {
                LOGGER.info("[JustFarming] Squeaky Mousemat not found in hotbar, skipping mousemat snap.");
                mousematSnapPhase = 0;
                state = MacroState.DETECTING;
                detectTicks = 0;
                detectStartPos = null;
            }
        } else if (mousematSnapPhase == 1) {
            // Phase 1: wait 200 ms after slot switch, then left-click.
            // swingHand sends only HandSwingC2SPacket — no block interaction,
            // no breaking animation — which is all Hypixel Skyblock needs to
            // trigger the Squeaky Mousemat ability regardless of crosshair target.
            if (System.currentTimeMillis() - mousematActionTime >= 200) {
                player.swingHand(Hand.MAIN_HAND);
                LOGGER.info("[JustFarming] Left-clicking Squeaky Mousemat.");
                mousematActionTime = System.currentTimeMillis();
                mousematSnapPhase = 2;
            }
        } else if (mousematSnapPhase == 2) {
            // Phase 2: wait 200 ms after the click, then check if camera snapped
            if (System.currentTimeMillis() - mousematActionTime < 200) return;

            float desiredYaw   = config.getEffectiveYaw(config.selectedCrop);
            float desiredPitch = config.getEffectivePitch(config.selectedCrop);
            float yawDiff   = Math.abs(normalizeAngleDiff(player.getYaw()   - desiredYaw));
            float pitchDiff = Math.abs(player.getPitch() - desiredPitch);

            if (yawDiff <= 2.0f && pitchDiff <= 2.0f) {
                // Camera snapped successfully – restore the farming tool slot
                LOGGER.info("[JustFarming] Camera snapped to desired angle (yaw={}, pitch={}). Restoring slot {}.",
                        player.getYaw(), player.getPitch(), preMousematSlot);
                if (preMousematSlot >= 0) {
                    player.getInventory().setSelectedSlot(preMousematSlot);
                }
                // Phase 3: wait for the farming tool to be in hand before farming starts
                mousematActionTime = System.currentTimeMillis();
                mousematSnapPhase = 3;
            } else {
                // Snap not confirmed yet – retry the left-click
                LOGGER.info("[JustFarming] Camera not snapped yet (yaw={}, pitch={}), retrying click.",
                        player.getYaw(), player.getPitch());
                player.swingHand(Hand.MAIN_HAND);
                mousematActionTime = System.currentTimeMillis();
            }
        } else {
            // Phase 3: wait 200 ms after restoring the farming tool slot to ensure
            // the player is holding the tool before farming begins.
            if (System.currentTimeMillis() - mousematActionTime >= 200) {
                mousematSnapPhase = 0;
                state = MacroState.DETECTING;
                detectTicks = 0;
                detectStartPos = null;
                LOGGER.info("[JustFarming] Farming tool equipped. Starting DETECTING phase.");
            }
        }
    }

    /** Normalizes an angle difference to the range [-180, 180]. */
    private static float normalizeAngleDiff(float diff) {
        diff = diff % 360.0f;
        if (diff > 180.0f)  diff -= 360.0f;
        if (diff < -180.0f) diff += 360.0f;
        return diff;
    }

    /**
     * Observation phase: no keys are pressed; we watch the player move (or not)
     * to decide whether to start going backward or forward.
     */
    private void tickDetecting(ClientPlayerEntity player) {
        releaseKeys();

        // If a custom key configuration is active, skip the movement-detection
        // heuristic and jump straight into the user-configured key state.
        com.justfarming.config.FarmingConfig.CropCustomSettings cs =
                config.getCropSettings(config.selectedCrop);
        if (cs != null) {
            customFlipped = false;
            state = deriveStateFromCustomKeys(cs);
            lastPos = new Vec3d(player.getX(), player.getY(), player.getZ());
            stuckTicks = 0;
            LOGGER.info("[JustFarming] Custom key config – starting {}.", state);
            return;
        }

        if (detectStartPos == null) {
            detectStartPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        }

        detectTicks++;
        if (detectTicks < DETECT_TICKS) return;

        // Decide initial direction from observed movement
        Vec3d currentXYZ = new Vec3d(player.getX(), player.getY(), player.getZ());
        Vec3d delta = currentXYZ.subtract(detectStartPos);
        double moved = Math.sqrt(delta.x * delta.x + delta.z * delta.z);

        if (moved > 0.2) {
            if (config.selectedCrop.isSShape()) {
                // S-Shape crops always move forward
                state = MacroState.FORWARD_LEFT;
                LOGGER.info("[JustFarming] Detected movement – S-Shape crop, starting FORWARD_LEFT.");
            } else if (config.selectedCrop.isLeftBack()) {
                state = MacroState.STRAFE_LEFT_ONLY;
                LOGGER.info("[JustFarming] Detected movement – left-back crop, starting STRAFE_LEFT_ONLY.");
            } else if (config.selectedCrop.isCactus()) {
                state = MacroState.STRAFE_LEFT_ONLY;
                LOGGER.info("[JustFarming] Detected movement – cactus, starting STRAFE_LEFT_ONLY.");
            } else {
                // Project delta onto the player's forward axis
                double yawRad = Math.toRadians(config.selectedCrop.getDefaultYaw());
                double fwdX = -Math.sin(yawRad);
                double fwdZ =  Math.cos(yawRad);
                double forwardComponent = delta.x * fwdX + delta.z * fwdZ;

                if (config.selectedCrop.isForwardBack()) {
                    state = (forwardComponent > 0) ? MacroState.FORWARD_ONLY : MacroState.BACK_ONLY;
                } else {
                    state = (forwardComponent > 0) ? MacroState.FORWARD_LEFT : MacroState.BACKWARD_LEFT;
                }
                LOGGER.info("[JustFarming] Detected movement – starting {}.", state);
            }
        } else {
            // Player is stationary
            if (config.selectedCrop.isSShape()) {
                state = MacroState.FORWARD_LEFT;
            } else if (config.selectedCrop.isLeftBack() || config.selectedCrop.isCactus()) {
                state = MacroState.STRAFE_LEFT_ONLY;
            } else if (config.selectedCrop.isForwardBack()) {
                state = MacroState.FORWARD_ONLY;
            } else {
                state = MacroState.BACKWARD_LEFT;
            }
            LOGGER.info("[JustFarming] No movement detected – defaulting to {}.", state);
        }

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

        // ---- Block breaking ----
        // When no screen is open, Minecraft's handleInputEvents() calls
        // handleBlockBreaking(true) naturally (attackKey.isPressed() + isCursorLocked()),
        // which in turn calls updateBlockBreakingProgress(). Duplicating that call here
        // would cause double packets per tick and spurious START_DESTROY_BLOCK packets
        // for already-broken (air) blocks – both detectable by server-side anti-cheat.
        //
        // When a screen IS open, handleInputEvents() is skipped entirely by Minecraft,
        // so we drive breaking directly in that case only.
        if (client.currentScreen != null
                && client.interactionManager != null
                && client.crosshairTarget instanceof BlockHitResult blockHit
                && !client.world.getBlockState(blockHit.getBlockPos()).isAir()) {
            client.interactionManager.updateBlockBreakingProgress(
                    blockHit.getBlockPos(), blockHit.getSide());
        }

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
                // End of row reached – flip direction (with optional lane-swap delay)
                stuckTicks = 0;
                com.justfarming.config.FarmingConfig.CropCustomSettings cs =
                        config.getCropSettings(config.selectedCrop);
                long swapDelay = config.laneSwapDelayMin
                        + (long) (Math.random() * (config.laneSwapDelayRandom + 1));
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
            double distSq = Math.pow(currentPos.x - config.rewarpX, 2)
                          + Math.pow(currentPos.z - config.rewarpZ, 2);
            if (distSq <= config.rewarpRange * config.rewarpRange) {
                LOGGER.info("[JustFarming] Reached rewarp position – warping.");
                warpStartTime   = System.currentTimeMillis();
                warpTargetDelay = config.rewarpDelayMin
                        + (long) (Math.random() * (config.rewarpDelayRandom + 1));
                state = MacroState.WARPING;
            }
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
