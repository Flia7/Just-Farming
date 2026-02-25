package com.justfarming;

import com.justfarming.config.FarmingConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
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
 *   <li>BACKWARD_LEFT – holds back + strafe-left + attack.</li>
 *   <li>FORWARD_LEFT – holds forward + strafe-left + attack.</li>
 *   <li>WARPING – releases keys and sends {@code /warp garden}.</li>
 * </ol>
 *
 * <p>Direction switches when the player stops moving (end of row detected by
 * the player's position not changing for {@value #STUCK_THRESHOLD} ticks).
 * The rewarp trigger fires when the player enters the configured rewarp radius.
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

    private final MinecraftClient client;
    private FarmingConfig config;

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private enum MacroState { IDLE, DETECTING, BACKWARD_LEFT, FORWARD_LEFT, WARPING }

    private MacroState state = MacroState.IDLE;
    private boolean running = false;
    private boolean freelookEnabled = false;

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

    /** Returns {@code true} if freelook mode is enabled. */
    public boolean isFreelookEnabled() {
        return freelookEnabled;
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
        state = MacroState.DETECTING;
        detectTicks = 0;
        detectStartPos = null;
        lastPos = null;
        stuckTicks = 0;
        LOGGER.info("[JustFarming] Macro started. Crop: {}", config.selectedCrop);
    }

    /** Stop the macro and release all held keys. */
    public void stop() {
        if (!running) return;
        running = false;
        state = MacroState.IDLE;
        releaseKeys();
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

        // Lock pitch and yaw every active tick (unless freelook is enabled)
        if (!freelookEnabled) {
            player.setPitch(config.farmingPitch);
            player.setYaw(config.farmingYaw);
        }

        switch (state) {
            case DETECTING      -> tickDetecting(player);
            case BACKWARD_LEFT  -> tickMoving(player, false);
            case FORWARD_LEFT   -> tickMoving(player, true);
            case WARPING        -> {
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
            default -> {}
        }
    }

    // -----------------------------------------------------------------------
    // State handlers
    // -----------------------------------------------------------------------

    /**
     * Observation phase: no keys are pressed; we watch the player move (or not)
     * to decide whether to start going backward or forward.
     */
    private void tickDetecting(ClientPlayerEntity player) {
        releaseKeys();

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
            // Project delta onto the player's forward axis
            double yawRad = Math.toRadians(config.farmingYaw);
            double fwdX = -Math.sin(yawRad);
            double fwdZ =  Math.cos(yawRad);
            double forwardComponent = delta.x * fwdX + delta.z * fwdZ;

            state = (forwardComponent > 0) ? MacroState.FORWARD_LEFT : MacroState.BACKWARD_LEFT;
            LOGGER.info("[JustFarming] Detected movement – starting {}.", state);
        } else {
            // Player is stationary – default to backward
            state = MacroState.BACKWARD_LEFT;
            LOGGER.info("[JustFarming] No movement detected – defaulting to BACKWARD_LEFT.");
        }

        lastPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        stuckTicks = 0;
    }

    /**
     * Movement phase: presses the appropriate directional keys and monitors the
     * player's position for end-of-row or rewarp-trigger events.
     *
     * @param forward {@code true} = moving forward; {@code false} = moving backward.
     */
    private void tickMoving(ClientPlayerEntity player, boolean forward) {
        // Optionally switch to best farming tool
        if (config.autoToolSwitch) {
            switchToBestFarmingTool(player);
        }

        // Hold attack (breaks cocoa beans in view)
        client.options.attackKey.setPressed(true);

        // Always strafe left
        client.options.leftKey.setPressed(true);

        // Set forward / backward
        if (forward) {
            client.options.forwardKey.setPressed(true);
            client.options.backKey.setPressed(false);
        } else {
            client.options.backKey.setPressed(true);
            client.options.forwardKey.setPressed(false);
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
                // End of row reached – flip direction
                stuckTicks = 0;
                state = forward ? MacroState.BACKWARD_LEFT : MacroState.FORWARD_LEFT;
                LOGGER.info("[JustFarming] End of row – switching to {}.", state);
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

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Release all held movement / attack keys. */
    private void releaseKeys() {
        if (client.options == null) return;
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.attackKey.setPressed(false);
    }

    /**
     * Switch the player's selected hotbar slot to the best hoe found,
     * prioritising higher-tier hoes (netherite > diamond > gold > iron > stone).
     */
    private void switchToBestFarmingTool(ClientPlayerEntity player) {
        int bestSlot = -1;
        int bestTier = -1;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (item instanceof HoeItem) {
                int tier = getToolTier(item);
                if (tier > bestTier) {
                    bestTier = tier;
                    bestSlot = i;
                }
            }
        }

        if (bestSlot >= 0) {
            player.getInventory().setSelectedSlot(bestSlot);
        }
    }

    /** Simple tier ranking for hoe items. */
    private int getToolTier(Item item) {
        String name = item.getClass().getSimpleName().toLowerCase();
        if (name.contains("netherite")) return 5;
        if (name.contains("diamond"))   return 4;
        if (name.contains("gold"))      return 3;
        if (name.contains("iron"))      return 2;
        if (name.contains("stone"))     return 1;
        return 0;
    }
}
