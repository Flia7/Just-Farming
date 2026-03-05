package com.justfarming.pest;

import com.justfarming.config.FarmingConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;

/**
 * Manages the auto pest killer routine for Just Farming.
 *
 * <p>When {@link FarmingConfig#autoPestKillerEnabled} is {@code true} and pests
 * are detected, this manager:
 * <ol>
 *   <li>Teleports to the infested plot (via {@code /tptoplot <plot>}) or to the
 *       garden (via {@code /warp garden}), depending on
 *       {@link FarmingConfig#pestKillerWarpToPlot}.</li>
 *   <li>After the teleport delay, scans for pest entities via
 *       {@link PestEntityDetector}.</li>
 *   <li>Flies toward each pest by pointing the camera at it and pressing the
 *       forward key.</li>
 *   <li>Switches to a vacuum item in the hotbar (any item whose name contains
 *       "Vacuum", case-insensitive) and right-clicks while aiming at the pest
 *       for {@link FarmingConfig#pestKillerKillDuration} ms to kill it.</li>
 *   <li>Sends {@code /warp garden} when all detected pests have been killed
 *       and marks itself {@link State#DONE}.</li>
 * </ol>
 */
public class PestKillerManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("just-farming");

    // ── Timing constants ────────────────────────────────────────────────────

    /** Default teleport wait before scanning for pest entities (ms). */
    private static final long TELEPORT_WAIT_DEFAULT_MS = 4000;

    /**
     * Non-configurable random extra added on top of the teleport delay (ms).
     * The actual wait is {@code pestKillerTeleportDelay + random(0, TELEPORT_EXTRA_RANDOM_MS)}.
     */
    private static final long TELEPORT_EXTRA_RANDOM_MS = 200;

    /**
     * How long (ms) to poll for pest entities after teleporting before giving
     * up and returning to the farm.  Allows the server time to render mob data.
     */
    private static final long SCAN_TIMEOUT_MS = 3000;

    /** Fallback kill radius (blocks) used when the config value is not set. */
    private static final double KILL_RADIUS = 5.0;

    /** Distance (blocks) at which the player starts slowing down near the pest. */
    private static final double BRAKE_RADIUS = 10.0;

    /**
     * Camera rotation speed (degrees per second) used when pointing the camera
     * at a pest entity.  Running at render frequency (~60 FPS) produces fine
     * steps of ~1.5° per frame while keeping total angular speed human-like.
     */
    private static final float SMOOTH_LOOK_DEGREES_PER_SECOND = 180.0f;

    /** Initial assumed elapsed time for the first smooth-look step (ms). */
    private static final float SMOOTH_LOOK_INITIAL_DELTA_MS = 50.0f;

    /** Maximum elapsed time used in smooth-look calculations to cap lag spikes (ms). */
    private static final float SMOOTH_LOOK_MAX_DELTA_MS = 250.0f;

    /** Random tremor amplitude (degrees) added to each camera rotation step. */
    private static final float SMOOTH_LOOK_TREMOR_AMPLITUDE = 0.12f;

    /** Scale factor for the pitch component of the camera tremor. */
    private static final float SMOOTH_LOOK_TREMOR_PITCH_SCALE = 0.5f;

    /**
     * Maximum angular error (degrees) the camera can be off-target before
     * the forward key is suppressed to avoid flying in the wrong direction.
     */
    private static final float MAX_FLY_YAW_ERROR_DEGREES = 30f;

    /** Pause after {@code /warp garden} to allow the command to register (ms). */
    private static final long WARP_COMMAND_WAIT_MS = 3000;

    // ── State machine ────────────────────────────────────────────────────────

    /** Internal states of the pest killer routine. */
    public enum State {
        /** Not doing anything. */
        IDLE,
        /** Waiting for the server to teleport the player. */
        TELEPORTING,
        /** Looking for pest entities near the player. */
        SCANNING,
        /** Flying toward the current target pest. */
        FLYING_TO_PEST,
        /** Close enough to pest; right-clicking with the vacuum to kill it. */
        KILLING_PEST,
        /** Sent {@code /warp garden}; brief pause before handing back to the macro. */
        RETURNING,
        /** Routine finished; caller can restart the farming macro. */
        DONE,
    }

    private State state = State.IDLE;
    private long  stateEnteredAt = 0;
    /** Computed teleport wait (base + random extra), set when entering TELEPORTING. */
    private long  teleportWaitMs = TELEPORT_WAIT_DEFAULT_MS;
    /** Timestamp (ms) when /warp garden was sent in RETURNING; 0 = not yet sent. */
    private long  returnWarpSentAt = 0;
    /** Per-state random extra (0–150 ms) added to hardcoded timing constants. */
    private int   randomExtra = 0;

    // Target pest
    private PestEntityDetector.PestEntity currentPest = null;

    // Camera rotation
    private float targetYaw   = 0f;
    private float targetPitch = 0f;
    /** Wall-clock time (ms) of the last smooth-look call; 0 = not yet called. */
    private long  lastSmoothLookTime = 0;

    // Stuck detection (used by flyToward to recover when the player stops making progress)
    private Vec3d lastProgressPos     = null;
    private long  lastProgressCheckTime = 0;
    /** Current strafe direction used during an unstuck manoeuvre: +1 = right, -1 = left, 0 = none. */
    private int   strafeDirection      = 0;
    /** Wall-clock time (ms) at which the current strafe manoeuvre ends; 0 = inactive. */
    private long  strafeEndTime        = 0;

    /** How often (ms) to check whether the player is stuck. */
    private static final long   STUCK_CHECK_INTERVAL_MS      = 1500;
    /** Minimum distance (blocks) the player must travel per check interval to be considered "un-stuck". */
    private static final double MIN_PROGRESS_PER_INTERVAL    = 0.5;
    /** How long (ms) to hold the strafe key when an unstuck manoeuvre is triggered. */
    private static final long   STRAFE_DURATION_MS           = 600;
    /** Minimum distance to the target (blocks) below which stuck detection is skipped. */
    private static final double MIN_STUCK_CHECK_DIST         = 2.0;

    // Hotbar state
    /** Hotbar slot of the vacuum item; -1 if not found. */
    private int vacuumSlot = -1;
    /** Hotbar slot active before the vacuum switch; -1 if unknown. */
    private int preVacuumSlot = -1;

    private final MinecraftClient client;
    private FarmingConfig config;
    private final PestEntityDetector pestEntityDetector;
    private final Random random = new Random();

    // ── Constructor ──────────────────────────────────────────────────────────

    public PestKillerManager(MinecraftClient client, FarmingConfig config,
                             PestEntityDetector pestEntityDetector) {
        this.client = client;
        this.config = config;
        this.pestEntityDetector = pestEntityDetector;
    }

    /** Update the config reference (called after GUI saves). */
    public void setConfig(FarmingConfig config) {
        this.config = config;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /** Returns {@code true} while the pest killer routine is running. */
    public boolean isActive() {
        return state != State.IDLE && state != State.DONE;
    }

    /** Returns {@code true} once the routine finishes. */
    public boolean isDone() {
        return state == State.DONE;
    }

    /** Returns the current internal state (for status display). */
    public State getState() {
        return state;
    }

    /**
     * Resets the manager back to {@link State#IDLE} so it can be triggered again.
     * Call after {@link #isDone()} returns {@code true}.
     */
    public void reset() {
        enterState(State.IDLE);
        restoreHotbarSlot();
        releaseMovementKeys();
    }

    /**
     * Stop the pest killer immediately and release all held keys.
     * Restores the hotbar slot to its pre-vacuum state.
     */
    public void stop() {
        if (state == State.IDLE || state == State.DONE) return;
        LOGGER.info("[JustFarming-PestKiller] Stopped.");
        releaseMovementKeys();
        restoreHotbarSlot();
        enterState(State.IDLE);
    }

    /**
     * Start the pest killer routine.
     *
     * @param pestPlotName the name of a plot that has pests (e.g. {@code "4"}),
     *                     used to build the {@code /tptoplot} command when
     *                     {@link FarmingConfig#pestKillerWarpToPlot} is enabled.
     *                     May be {@code null} if no specific plot name is known.
     */
    public void start(String pestPlotName) {
        LOGGER.info("[JustFarming-PestKiller] Starting pest killer routine.");
        currentPest = null;
        vacuumSlot = -1;
        preVacuumSlot = -1;
        lastSmoothLookTime = 0;
        returnWarpSentAt = 0;

        long base = config != null && config.pestKillerTeleportDelay > 0
                ? config.pestKillerTeleportDelay : TELEPORT_WAIT_DEFAULT_MS;
        int globalRandom = (config != null) ? config.globalRandomizationMs : 0;
        teleportWaitMs = base + random.nextInt((int) TELEPORT_EXTRA_RANDOM_MS + 1)
                + random.nextInt(Math.max(1, globalRandom));

        enterState(State.TELEPORTING);

        if (config.pestKillerWarpToPlot && pestPlotName != null && !pestPlotName.isBlank()) {
            sendCommand("tptoplot " + pestPlotName);
            LOGGER.info("[JustFarming-PestKiller] Sent /tptoplot {} to reach infested plot.", pestPlotName);
        } else {
            sendCommand("warp garden");
            LOGGER.info("[JustFarming-PestKiller] Sent /warp garden to reach garden.");
        }
    }

    /**
     * Called every render frame to apply incremental camera rotation toward
     * {@link #targetYaw}/{@link #targetPitch} while actively flying or killing.
     */
    public void onRenderTick() {
        if (state != State.FLYING_TO_PEST && state != State.KILLING_PEST) return;
        ClientPlayerEntity player = client.player;
        if (player == null) return;
        smoothRotateCamera(player);
    }

    /** Called every client tick from the main tick event. */
    public void onTick() {
        if (state == State.IDLE || state == State.DONE) return;

        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        long now = System.currentTimeMillis();

        switch (state) {

            case TELEPORTING -> {
                if (now - stateEnteredAt >= teleportWaitMs) {
                    LOGGER.info("[JustFarming-PestKiller] Teleport wait elapsed; scanning for pests.");
                    enterState(State.SCANNING);
                }
            }

            case SCANNING -> {
                List<PestEntityDetector.PestEntity> pests = pestEntityDetector.getDetectedPests();
                if (!pests.isEmpty()) {
                    currentPest = pickNearestPest(player, pests);
                    if (currentPest != null) {
                        LOGGER.info("[JustFarming-PestKiller] Found {} pest(s). Targeting: {} at {}.",
                                pests.size(), currentPest.displayName(), currentPest.position());
                        enterState(State.FLYING_TO_PEST);
                    }
                } else if (now - stateEnteredAt >= SCAN_TIMEOUT_MS) {
                    LOGGER.info("[JustFarming-PestKiller] No pests found after scanning; returning to farm.");
                    returnToFarm();
                }
            }

            case FLYING_TO_PEST -> {
                List<PestEntityDetector.PestEntity> pests = pestEntityDetector.getDetectedPests();
                // Re-pick the nearest pest every tick in case the previous one was killed
                currentPest = pickNearestPest(player, pests);
                if (currentPest == null) {
                    // No more pests in detection range; scan for any remaining
                    LOGGER.info("[JustFarming-PestKiller] Target pest gone; scanning for remaining pests.");
                    releaseMovementKeys();
                    enterState(State.SCANNING);
                    return;
                }
                Vec3d pestPos = currentPest.position();
                double dist = player.getEyePos().distanceTo(pestPos);
                if (dist <= getEffectiveKillRadius()) {
                    releaseMovementKeys();
                    // Find vacuum before entering kill state
                    findAndEquipVacuum(player);
                    enterState(State.KILLING_PEST);
                } else {
                    flyToward(player, pestPos);
                }
            }

            case KILLING_PEST -> {
                List<PestEntityDetector.PestEntity> pests = pestEntityDetector.getDetectedPests();
                // Keep targeting the nearest pest
                currentPest = pickNearestPest(player, pests);
                if (currentPest == null) {
                    // This pest was killed; scan for more
                    LOGGER.info("[JustFarming-PestKiller] Pest killed; scanning for remaining pests.");
                    restoreHotbarSlot();
                    releaseMovementKeys();
                    enterState(State.SCANNING);
                    return;
                }

                Vec3d pestPos = currentPest.position();
                double dist = player.getEyePos().distanceTo(pestPos);
                // If the pest moved out of kill range, fly toward it again
                if (dist > getEffectiveKillRadius() * 1.5) {
                    enterState(State.FLYING_TO_PEST);
                    return;
                }

                // Aim camera at pest
                lookAt(player, pestPos);

                // Ensure we're still holding the vacuum
                if (vacuumSlot >= 0 && player.getInventory().getSelectedSlot() != vacuumSlot) {
                    player.getInventory().setSelectedSlot(vacuumSlot);
                }

                // Right-click (use vacuum) while aimed at pest
                if (client.interactionManager != null) {
                    client.interactionManager.interactItem(player, Hand.MAIN_HAND);
                }

                // Check if kill duration elapsed – move to next pest
                long killDuration = config.pestKillerKillDuration > 0
                        ? config.pestKillerKillDuration : 2000;
                if (now - stateEnteredAt >= killDuration) {
                    LOGGER.info("[JustFarming-PestKiller] Kill duration elapsed; moving to next pest.");
                    restoreHotbarSlot();
                    enterState(State.SCANNING);
                }
            }

            case RETURNING -> {
                if (returnWarpSentAt == 0) {
                    sendCommand("warp garden");
                    returnWarpSentAt = now;
                    LOGGER.info("[JustFarming-PestKiller] Sent /warp garden after pest kill.");
                } else if (now - returnWarpSentAt >= WARP_COMMAND_WAIT_MS + randomExtra) {
                    enterState(State.DONE);
                }
            }

            default -> {}
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void enterState(State next) {
        state = next;
        stateEnteredAt = System.currentTimeMillis();
        int globalRandom = (config != null) ? config.globalRandomizationMs : 0;
        randomExtra = random.nextInt(Math.max(1, globalRandom));
        if (next == State.FLYING_TO_PEST) {
            // Reset stuck detection so each new flight attempt starts fresh
            lastProgressPos      = null;
            lastProgressCheckTime = 0;
            strafeEndTime        = 0;
            strafeDirection      = 0;
        }
        if (next != State.IDLE && next != State.DONE) {
            LOGGER.info("[JustFarming-PestKiller] -> {}", next);
        }
    }

    /** Returns the pest entity closest to the player's eye position, or {@code null} if none. */
    private PestEntityDetector.PestEntity pickNearestPest(ClientPlayerEntity player,
                                                           List<PestEntityDetector.PestEntity> pests) {
        if (pests.isEmpty()) return null;
        Vec3d eye = player.getEyePos();
        PestEntityDetector.PestEntity nearest = null;
        double minDist = Double.MAX_VALUE;
        for (PestEntityDetector.PestEntity pest : pests) {
            double d = eye.distanceTo(pest.position());
            if (d < minDist) {
                minDist = d;
                nearest = pest;
            }
        }
        return nearest;
    }

    /**
     * Searches the hotbar for the first item whose display name contains
     * "vacuum" (case-insensitive), records the current slot as {@link #preVacuumSlot},
     * and switches to the vacuum slot.  Does nothing if no vacuum is found.
     */
    private void findAndEquipVacuum(ClientPlayerEntity player) {
        if (vacuumSlot >= 0) return; // already found and equipped

        int found = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                String name = stack.getName().getString();
                // Strip formatting codes before checking
                String cleanName = name.replaceAll("§.", "").trim();
                if (cleanName.toLowerCase().contains("vacuum")) {
                    found = i;
                    break;
                }
            }
        }

        if (found < 0) {
            LOGGER.warn("[JustFarming-PestKiller] No vacuum item found in hotbar.");
            return;
        }

        preVacuumSlot = player.getInventory().getSelectedSlot();
        vacuumSlot = found;
        player.getInventory().setSelectedSlot(vacuumSlot);
        LOGGER.info("[JustFarming-PestKiller] Equipped vacuum from hotbar slot {} (was slot {}).",
                vacuumSlot, preVacuumSlot);
    }

    /**
     * Restores the hotbar slot to {@link #preVacuumSlot} if it was changed.
     */
    private void restoreHotbarSlot() {
        ClientPlayerEntity player = client.player;
        if (player == null) return;
        if (preVacuumSlot >= 0 && vacuumSlot >= 0) {
            player.getInventory().setSelectedSlot(preVacuumSlot);
            LOGGER.info("[JustFarming-PestKiller] Restored hotbar slot {}.", preVacuumSlot);
        }
        vacuumSlot = -1;
        preVacuumSlot = -1;
    }

    /**
     * Fly one tick toward {@code target} by pointing the camera at it (including
     * pitch for vertical movement) and pressing the forward key.  In the Hypixel
     * Skyblock Garden, players have creative-style flight so forward + pitch causes
     * genuine 3D movement.
     *
     * <p>The jump key is held when the target is above the player and the sneak
     * key is held when the target is below – both are standard creative-flight
     * controls for ascending and descending respectively.
     *
     * <p>When the player stops making progress toward the target (stuck detection),
     * a brief left/right strafe manoeuvre is triggered to help navigate around any
     * obstacle that is blocking the direct path.
     */
    private void flyToward(ClientPlayerEntity player, Vec3d target) {
        if (client.options == null) return;

        // Point camera at the target (includes pitch for vertical direction)
        lookAt(player, target);

        Vec3d eye = player.getEyePos();
        double dist = eye.distanceTo(target);

        // Suppress forward movement when camera is still rotating toward the target
        float yawError = targetYaw - player.getYaw();
        while (yawError >  180f) yawError -= 360f;
        while (yawError < -180f) yawError += 360f;

        boolean shouldFly = Math.abs(yawError) <= MAX_FLY_YAW_ERROR_DEGREES;

        // Slow down when close to the pest
        if (shouldFly && dist < BRAKE_RADIUS) {
            // Pulse forward key: move one tick out of every ~(BRAKE_RADIUS/dist) ticks
            long ticks = client.world != null ? client.world.getTime() : 0;
            int pulseStride = Math.max(1, (int) Math.ceil(BRAKE_RADIUS / Math.max(1.0, dist)));
            shouldFly = (ticks % pulseStride == 0);
        }

        // Vertical movement: jump to ascend, sneak to descend (creative-flight controls)
        double dy = target.y - eye.y;
        boolean shouldJump  = dy >  1.5;
        boolean shouldSneak = dy < -1.5;

        // ── Stuck detection ─────────────────────────────────────────────────
        long now = System.currentTimeMillis();
        if (lastProgressPos == null) {
            lastProgressPos      = eye;
            lastProgressCheckTime = now;
        }
        if (now - lastProgressCheckTime >= STUCK_CHECK_INTERVAL_MS) {
            double progress = lastProgressPos.distanceTo(eye);
            if (progress < MIN_PROGRESS_PER_INTERVAL && dist > MIN_STUCK_CHECK_DIST) {
                // Player has not moved enough – alternate strafe direction and hold it briefly
                strafeDirection = (strafeDirection >= 0) ? -1 : 1;
                strafeEndTime   = now + STRAFE_DURATION_MS;
                LOGGER.debug("[JustFarming-PestKiller] Stuck (progress={} blocks); strafing {}.",
                        String.format("%.2f", progress), strafeDirection > 0 ? "right" : "left");
            }
            lastProgressPos       = eye;
            lastProgressCheckTime = now;
        }

        boolean isStrafeActive = now < strafeEndTime && strafeDirection != 0;

        // Allow forward movement alongside strafe so the player still approaches
        // the target while the unstuck manoeuvre is active.
        client.options.forwardKey.setPressed(shouldFly);
        client.options.jumpKey.setPressed(shouldJump);
        client.options.sneakKey.setPressed(shouldSneak);
        client.options.leftKey.setPressed(isStrafeActive && strafeDirection < 0);
        client.options.rightKey.setPressed(isStrafeActive && strafeDirection > 0);
        client.options.backKey.setPressed(false);
    }

    /** Aim the camera toward {@code target} using smooth interpolation. */
    private void lookAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        // Minecraft yaw convention: 0° = south (+Z), 90° = west (-X).
        // atan2(-dx, dz) maps the (dx, dz) direction vector to a Minecraft yaw.
        targetYaw   = (float) Math.toDegrees(Math.atan2(-dx, dz));
        targetPitch = (float) -Math.toDegrees(Math.atan2(dy, distXZ));
        smoothRotateCamera(player);
    }

    /**
     * Applies one incremental camera rotation step toward
     * {@link #targetYaw}/{@link #targetPitch} at a time-based rate.
     */
    private void smoothRotateCamera(ClientPlayerEntity player) {
        long now = System.currentTimeMillis();
        float deltaMs = (lastSmoothLookTime == 0)
                ? SMOOTH_LOOK_INITIAL_DELTA_MS
                : Math.min(SMOOTH_LOOK_MAX_DELTA_MS, (float)(now - lastSmoothLookTime));
        lastSmoothLookTime = now;

        float step = SMOOTH_LOOK_DEGREES_PER_SECOND * deltaMs / 1000.0f;

        float currentYaw   = player.getYaw();
        float currentPitch = player.getPitch();

        float yawDiff   = targetYaw - currentYaw;
        while (yawDiff >  180f) yawDiff -= 360f;
        while (yawDiff < -180f) yawDiff += 360f;
        float pitchDiff = targetPitch - currentPitch;

        if (Math.abs(yawDiff) < 1.0f && Math.abs(pitchDiff) < 1.0f) return;

        float newYaw   = currentYaw   + Math.max(-step, Math.min(step, yawDiff));
        float newPitch = currentPitch + Math.max(-step, Math.min(step, pitchDiff));
        newPitch = Math.max(-90f, Math.min(90f, newPitch));

        float tremorYaw   = (random.nextFloat() * 2f - 1f) * SMOOTH_LOOK_TREMOR_AMPLITUDE;
        float tremorPitch = (random.nextFloat() * 2f - 1f)
                * SMOOTH_LOOK_TREMOR_AMPLITUDE * SMOOTH_LOOK_TREMOR_PITCH_SCALE;

        player.setYaw(newYaw + tremorYaw);
        player.setPitch(newPitch + tremorPitch);
    }

    private void releaseMovementKeys() {
        if (client.options == null) return;
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
    }

    /**
     * Returns the effective kill/vacuum radius in blocks.
     * Uses {@link FarmingConfig#pestKillerVacuumRange} when set, otherwise falls
     * back to the hardcoded {@link #KILL_RADIUS}.
     */
    private double getEffectiveKillRadius() {
        if (config != null && config.pestKillerVacuumRange > 0) {
            return config.pestKillerVacuumRange;
        }
        return KILL_RADIUS;
    }

    private void returnToFarm() {
        returnWarpSentAt = 0;
        enterState(State.RETURNING);
    }

    private void sendCommand(String command) {
        if (client.player != null && client.player.networkHandler != null) {
            client.player.networkHandler.sendChatCommand(command);
        }
    }
}
