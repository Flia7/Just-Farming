package com.justfarming;

import com.justfarming.config.FarmingConfig;
import com.justfarming.gui.FarmingConfigScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Just Farming – Hypixel Skyblock farming macro mod for Fabric 1.21.10.
 *
 * <p>Features:
 * <ul>
 *   <li>GUI to select crop, speed, pitch, row length, and toggle options</li>
 *   <li>Keybind to start/stop the macro (default: R)</li>
 *   <li>Keybind to open the config GUI (default: I)</li>
 *   <li>Auto tool switching to the best hoe in the hotbar</li>
 *   <li>Back-and-forth row pattern with configurable row length</li>
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
                "key.categories.just-farming"
        ));

        openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.just-farming.open_gui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_I,
                "key.categories.just-farming"
        ));

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

            // Run macro tick
            macroManager.onTick();
        });

        LOGGER.info("[JustFarming] Ready. Toggle macro: R | Open GUI: I");
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
