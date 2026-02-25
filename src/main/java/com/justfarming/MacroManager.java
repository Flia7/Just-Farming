package com.justfarming;

import com.justfarming.config.FarmingConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the farming macro state and tick logic.
 *
 * <p>The macro runs on the client tick thread. Each tick it:
 * <ol>
 *   <li>Optionally switches to the best farming tool in the hotbar.</li>
 *   <li>Sets the player's pitch to the configured farming angle.</li>
 *   <li>Moves the player forward until the configured row length is reached,
 *       then reverses direction (back-and-forth pattern).</li>
 * </ol>
 *
 * <p>Actual block breaking is handled by holding the attack key, which Minecraft
 * processes natively each tick.
 */
public class MacroManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("just-farming");

    private final MinecraftClient client;
    private FarmingConfig config;

    private boolean running = false;

    /** Number of ticks travelled in the current direction */
    private int ticksInDirection = 0;
    /** +1 = forward, -1 = backward */
    private int direction = 1;
    /** Tick counter used to throttle macro speed */
    private int tickCounter = 0;

    public MacroManager(MinecraftClient client, FarmingConfig config) {
        this.client = client;
        this.config = config;
    }

    /** Update the config reference (called after GUI saves). */
    public void setConfig(FarmingConfig config) {
        this.config = config;
    }

    /** Returns {@code true} if the macro is currently active. */
    public boolean isRunning() {
        return running;
    }

    /** Start the macro. */
    public void start() {
        if (running) return;
        running = true;
        ticksInDirection = 0;
        direction = 1;
        tickCounter = 0;
        LOGGER.info("[JustFarming] Macro started. Crop: {}", config.selectedCrop);
    }

    /** Stop the macro and release held keys. */
    public void stop() {
        if (!running) return;
        running = false;
        releaseKeys();
        LOGGER.info("[JustFarming] Macro stopped.");
    }

    /** Toggle start/stop. */
    public void toggle() {
        if (running) {
            stop();
        } else {
            start();
        }
    }

    /**
     * Called every client tick. Executes one step of the macro if active.
     */
    public void onTick() {
        if (!running) return;

        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            stop();
            return;
        }

        // Throttle by speed setting
        tickCounter++;
        int speedDivisor = switch (config.macroSpeed) {
            case 1 -> 5;   // slow: act every 5 ticks
            case 3 -> 1;   // fast: act every tick
            default -> 2;  // normal: act every 2 ticks
        };
        if (tickCounter % speedDivisor != 0) return;

        // Optionally switch to best hoe in hotbar
        if (config.autoToolSwitch) {
            switchToBestFarmingTool(player);
        }

        // Set pitch (look angle)
        player.setPitch(config.farmingPitch);

        // Hold attack key to break blocks in view
        client.options.attackKey.setPressed(true);

        // Move in current direction
        if (direction == 1) {
            client.options.forwardKey.setPressed(true);
            client.options.backKey.setPressed(false);
        } else {
            client.options.backKey.setPressed(true);
            client.options.forwardKey.setPressed(false);
        }

        ticksInDirection++;

        // Reverse direction at the end of the row
        int rowLengthTicks = config.rowLength * speedDivisor;
        if (ticksInDirection >= rowLengthTicks) {
            direction = -direction;
            ticksInDirection = 0;
            // Turn 180 degrees
            float currentYaw = player.getYaw();
            player.setYaw(currentYaw + 180.0f);
        }
    }

    /** Release all held movement/attack keys. */
    private void releaseKeys() {
        if (client.options == null) return;
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.attackKey.setPressed(false);
    }

    /**
     * Switch the player's selected hotbar slot to the first hoe found,
     * prioritising higher-tier hoes (diamond/netherite).
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
        if (name.contains("diamond")) return 4;
        if (name.contains("gold")) return 3;
        if (name.contains("iron")) return 2;
        if (name.contains("stone")) return 1;
        return 0;
    }
}
