package com.justfarming;

import com.justfarming.config.FarmingConfig;
import com.justfarming.gui.FarmingConfigScreen;
import com.justfarming.pest.PestDetector;
import com.justfarming.pest.PestEntityDetector;
import com.justfarming.render.OverlayRenderer;
import com.justfarming.visitor.VisitorManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
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
 *   <li>Auto tool switching to the best hoe in the hotbar</li>
 *   <li>Back-and-forth row pattern with automatic end-of-row detection</li>
 *   <li>{@code /just rewarp} command to send {@code /warp garden} to the server</li>
 * </ul>
 */
public class JustFarming implements ClientModInitializer {

    public static final String MOD_ID = "just-farming";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Singleton references shared across the mod
    private static FarmingConfig config;
    private static MacroManager macroManager;
    private static PestDetector pestDetector;
    private static PestEntityDetector pestEntityDetector;
    private static VisitorManager visitorManager;

    // Keybindings
    private static KeyBinding toggleMacroKey;
    private static KeyBinding openGuiKey;
    private static KeyBinding freelookKey;
    private static KeyBinding alternateDirectionKey;
    private static final KeyBinding.Category KEY_CATEGORY = KeyBinding.Category.create(Identifier.of("just-farming", "categories"));

    @Override
    public void onInitializeClient() {
        LOGGER.info("[JustFarming] Initialising...");

        // Load config
        config = FarmingConfig.load();

        // Create macro manager and pest detector
        macroManager = new MacroManager(net.minecraft.client.MinecraftClient.getInstance(), config);
        pestDetector = new PestDetector();
        pestEntityDetector = new PestEntityDetector();
        visitorManager = new VisitorManager(net.minecraft.client.MinecraftClient.getInstance(), config);
        macroManager.setVisitorManager(visitorManager);

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

        alternateDirectionKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.just-farming.alternate_direction",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_N,
                KEY_CATEGORY
        ));

        // Register /just rewarp client commands
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(
                        literal("just")
                                .then(literal("rewarp")
                                        .executes(ctx -> {
                                            macroManager.setRewarpHere();
                                            if (ctx.getSource().getPlayer() != null) {
                                                ctx.getSource().getPlayer().sendMessage(
                                                        net.minecraft.text.Text.literal("§a[JustFarming] Rewarp position set here."), true);
                                            }
                                            return 1;
                                        })
                                        .then(literal("clear")
                                                .executes(ctx -> {
                                                    macroManager.clearRewarps();
                                                    if (ctx.getSource().getPlayer() != null) {
                                                        ctx.getSource().getPlayer().sendMessage(
                                                                net.minecraft.text.Text.literal("§a[JustFarming] All rewarp positions cleared."), true);
                                                    }
                                                    return 1;
                                                })))
                                .then(literal("visitor")
                                        .executes(ctx -> {
                                            visitorManager.start();
                                            if (ctx.getSource().getPlayer() != null) {
                                                ctx.getSource().getPlayer().sendMessage(
                                                        net.minecraft.text.Text.literal("§a[JustFarming] Visitor routine started. Teleporting to barn..."), true);
                                            }
                                            return 1;
                                        }))));
        // Block the F5 perspective toggle while freelook is active.
        // START_CLIENT_TICK fires before handleKeyBindings(), so consuming
        // the key press here prevents Minecraft from acting on it.
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (macroManager.isFreelookActive() && client.options != null) {
                // Consume all queued perspective-toggle key presses before Minecraft's
                // handleKeyBindings() processes them, so F5 has no effect during freelook.
                while (client.options.togglePerspectiveKey.wasPressed()) { /* suppress */ }
            }
        });

        // Register client tick callback
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleMacroKey.wasPressed()) {
                // macroManager.toggle() handles all states including visitor-wait
                boolean wasActive = macroManager.isRunning() || visitorManager.isActive();
                if (!wasActive && config.gardenOnlyEnabled && !pestDetector.isInGarden()) {
                    // Prevent starting the macro outside the Garden.
                    if (client.player != null) {
                        client.player.sendMessage(net.minecraft.text.Text.literal(
                                "§c[JustFarming] You must be in the Garden to use this macro."), true);
                    }
                    continue;
                }
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
                            net.minecraft.text.Text.literal(macroManager.isFreelookEnabled()
                                    ? "§e[JustFarming] Freelook enabled."
                                    : "§e[JustFarming] Freelook disabled."), true);
                }
            }

            // Process alternate-direction keybind: instantly swap movement direction
            while (alternateDirectionKey.wasPressed()) {
                macroManager.triggerInstantAlternate();
            }

            // Run macro tick
            macroManager.onTick();

            // Run visitor manager tick (active only when visitor routine is running)
            visitorManager.onTick();

            // Update pest detection every tick
            pestDetector.update(client);
            pestEntityDetector.update(client);

            // Stop macro if Garden-only mode is enabled and the player left the Garden.
            // Do not stop while the visitor routine is active or while the macro is in
            // the VISITING state (returning from visitors to the garden).
            if (!pestDetector.isInGarden() && config.gardenOnlyEnabled && macroManager.isRunning()
                    && !visitorManager.isActive() && !macroManager.isVisiting()) {
                macroManager.stop();
                if (client.player != null) {
                    client.player.sendMessage(net.minecraft.text.Text.literal(
                            "§c[JustFarming] Not in Garden – macro stopped."), false);
                }
            }
        });

        // Register world render event for pest plot overlay.
        // The OverlayRenderer is created lazily on first render because its
        // static RenderLayer fields require the render system to be initialised,
        // which has not happened yet during onInitializeClient().
        final java.util.concurrent.atomic.AtomicReference<OverlayRenderer> overlayRef = new java.util.concurrent.atomic.AtomicReference<>();
        WorldRenderEvents.AFTER_ENTITIES.register(ctx -> {
            OverlayRenderer renderer = overlayRef.get();
            if (renderer == null) {
                renderer = new OverlayRenderer(config, pestDetector, pestEntityDetector);
                overlayRef.set(renderer);
            }
            renderer.render(ctx);
        });

        // Drive visitor and macro smooth-camera rotation every render frame so
        // steps are ~3° at 60 FPS (vs ~18°/tick at 20 TPS), keeping total
        // angular speed the same while producing much finer per-step increments.
        WorldRenderEvents.BEFORE_ENTITIES.register(ctx -> {
            visitorManager.onRenderTick();
            macroManager.onRenderTick();
        });

        LOGGER.info("[JustFarming] Ready. Toggle macro: R | Open GUI: I | Freelook: L | Alternate direction: N | Commands: /just rewarp, /just rewarp clear, /just visitor");
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

    /** Returns the shared visitor manager instance. */
    public static VisitorManager getVisitorManager() {
        return visitorManager;
    }
}
