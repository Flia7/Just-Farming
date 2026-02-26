package com.justfarming;

import com.justfarming.config.FarmingConfig;
import com.justfarming.gui.FarmingConfigScreen;
import com.justfarming.pest.PestDetector;
import com.justfarming.render.OverlayRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;
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
 *   <li>Auto tool switching to the best hoe in the hotbar</li>
 *   <li>Back-and-forth row pattern with automatic end-of-row detection</li>
 *   <li>{@code /jf rewarp} command to send {@code /warp garden} to the server</li>
 * </ul>
 */
public class JustFarming implements ClientModInitializer {

    public static final String MOD_ID = "just-farming";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Singleton references shared across the mod
    private static FarmingConfig config;
    private static MacroManager macroManager;
    private static PestDetector pestDetector;

    // Keybindings
    private static KeyBinding toggleMacroKey;
    private static KeyBinding openGuiKey;
    private static KeyBinding freelookKey;
    private static final KeyBinding.Category KEY_CATEGORY = KeyBinding.Category.create(Identifier.of("just-farming", "categories"));

    /** Saved perspective before freelook was enabled, used to restore on disable. */
    private static Perspective lastPerspective = null;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[JustFarming] Initialising...");

        // Load config
        config = FarmingConfig.load();

        // Create macro manager and pest detector
        macroManager = new MacroManager(net.minecraft.client.MinecraftClient.getInstance(), config);
        pestDetector = new PestDetector();
        final OverlayRenderer overlayRenderer = new OverlayRenderer(config, pestDetector);

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
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(
                        literal("jf")
                                .then(literal("rewarp")
                                        .executes(ctx -> {
                                            macroManager.setRewarpHere();
                                            if (ctx.getSource().getPlayer() != null) {
                                                ctx.getSource().getPlayer().sendMessage(
                                                        net.minecraft.text.Text.literal("§a[JustFarming] Rewarp position set here."), true);
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
                if (macroManager.isFreelookEnabled()) {
                    // Save current perspective only once (in case it was not cleared properly)
                    if (lastPerspective == null) {
                        lastPerspective = client.options.getPerspective();
                    }
                    // Switch to third-person-back when entering freelook from first person
                    if (lastPerspective == Perspective.FIRST_PERSON) {
                        client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                    }
                    if (client.player != null) {
                        client.player.sendMessage(
                                net.minecraft.text.Text.literal("§e[JustFarming] Freelook enabled."), true);
                    }
                } else {
                    // Restore the saved perspective
                    if (lastPerspective != null) {
                        client.options.setPerspective(lastPerspective);
                        lastPerspective = null;
                    }
                    if (client.player != null) {
                        client.player.sendMessage(
                                net.minecraft.text.Text.literal("§e[JustFarming] Freelook disabled."), true);
                    }
                }
            }

            // Run macro tick
            macroManager.onTick();

            // Update pest detection every tick
            pestDetector.update(client);
        });

        // Register world render event for pest plot overlay
        WorldRenderEvents.AFTER_ENTITIES.register(overlayRenderer::render);

        LOGGER.info("[JustFarming] Ready. Toggle macro: R | Open GUI: I | Freelook: L | Command: /jf rewarp");
    }

    /** Returns the shared config instance. */
    public static FarmingConfig getConfig() {
        return config;
    }

    /** Returns the shared macro manager instance. */
    public static MacroManager getMacroManager() {
        return macroManager;
    }

    /** Returns the shared pest detector instance. */
    public static PestDetector getPestDetector() {
        return pestDetector;
    }
}
