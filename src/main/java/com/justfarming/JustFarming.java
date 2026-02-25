package com.justfarming;

import com.justfarming.config.FarmingConfig;
import com.justfarming.gui.FarmingConfigScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Just Farming – Hypixel Skyblock cocoa-beans farming macro mod for Fabric 1.21.10.
 *
 * <p>Features:
 * <ul>
 *   <li>GUI to select crop (Cocoa Beans), speed, pitch, yaw, and rewarp position</li>
 *   <li>Keybind to start/stop the macro (default: R)</li>
 *   <li>Keybind to open the config GUI (default: I)</li>
 *   <li>Keybind to toggle freelook – look freely without macro locking view (default: L)</li>
 *   <li>Auto tool switching to the best hoe in the hotbar</li>
 *   <li>Back-and-forth row pattern with automatic end-of-row detection</li>
 *   <li>{@code /jf rewarp} command to mark the player's current position as the
 *       rewarp trigger (identical to clicking "Set Rewarp Here" in the GUI)</li>
 * </ul>
 */
public class JustFarming implements ClientModInitializer {

    public static final String MOD_ID = "just-farming";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Singleton references shared across the mod
    private static FarmingConfig config;
    private static MacroManager macroManager;

    // Keybindings
    private static KeyBinding toggleMacroKey;
    private static KeyBinding openGuiKey;
    private static KeyBinding freelookKey;
    private static final KeyBinding.Category KEY_CATEGORY = KeyBinding.Category.create(Identifier.of("just-farming", "categories"));

    @Override
    public void onInitializeClient() {
        LOGGER.info("[JustFarming] Initialising...");

        // Load config
        config = FarmingConfig.load();

        // Create macro manager
        macroManager = new MacroManager(net.minecraft.client.MinecraftClient.getInstance(), config);

        // Register keybindings
        toggleMacroKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.just-farming.toggle_macro",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                KEY_CATEGORY
        ));

        openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.just-farming.open_gui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_I,
                KEY_CATEGORY
        ));

        freelookKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.just-farming.freelook",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_L,
                KEY_CATEGORY
        ));

        // Register /jf rewarp client command
        // Sets (or updates) the rewarp trigger position to the player's current
        // location.  The macro will automatically send /warp garden every time
        // it reaches this position while running.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(
                        literal("jf")
                                .then(literal("rewarp")
                                        .executes(ctx -> {
                                            net.minecraft.client.network.ClientPlayerEntity player =
                                                    ctx.getSource().getPlayer();
                                            if (player != null) {
                                                config.rewarpX   = player.getX();
                                                config.rewarpY   = player.getY();
                                                config.rewarpZ   = player.getZ();
                                                config.rewarpSet = true;
                                                config.save();
                                                macroManager.setConfig(config);
                                                player.sendMessage(
                                                        net.minecraft.text.Text.literal(
                                                                String.format("§a[JustFarming] Rewarp position set at %.1f, %.1f, %.1f – /warp garden will trigger here.",
                                                                        config.rewarpX, config.rewarpY, config.rewarpZ)),
                                                        false);
                                                LOGGER.info("[JustFarming] Rewarp position set to ({}, {}, {}).",
                                                        config.rewarpX, config.rewarpY, config.rewarpZ);
                                            }
                                            return 1;
                                        }))));

        // Register client tick callback
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Process toggle macro keybind
            while (toggleMacroKey.wasPressed()) {
                macroManager.toggle();
                if (client.player != null) {
                    if (macroManager.isRunning()) {
                        client.player.sendMessage(
                                net.minecraft.text.Text.literal("§a[JustFarming] Macro started."), true);
                    } else {
                        client.player.sendMessage(
                                net.minecraft.text.Text.literal("§c[JustFarming] Macro stopped."), true);
                    }
                }
            }

            // Process open GUI keybind
            while (openGuiKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new FarmingConfigScreen(null, config, macroManager));
                }
            }

            // Process freelook keybind
            while (freelookKey.wasPressed()) {
                macroManager.toggleFreelook();
                if (client.player != null) {
                    client.player.sendMessage(
                            net.minecraft.text.Text.literal(
                                    macroManager.isFreelookEnabled()
                                            ? "§e[JustFarming] Freelook ON"
                                            : "§e[JustFarming] Freelook OFF"),
                            true);
                }
            }

            // Run macro tick
            macroManager.onTick();
        });

        LOGGER.info("[JustFarming] Ready. Toggle: R | GUI: I | Freelook: L | Command: /jf rewarp");
    }

    /** Returns the shared config instance. */
    public static FarmingConfig getConfig() {
        return config;
    }

    /** Returns the shared macro manager instance. */
    public static MacroManager getMacroManager() {
        return macroManager;
    }
}
