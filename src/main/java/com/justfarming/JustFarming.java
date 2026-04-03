package com.justfarming;

import com.justfarming.config.FarmingConfig;
import com.justfarming.gui.FarmingConfigScreen;
import com.justfarming.pest.PestDetector;
import com.justfarming.pest.PestEntityDetector;
import com.justfarming.pest.PestKillerManager;
import com.justfarming.profit.FarmingProfitTracker;
import com.justfarming.render.InventoryHudRenderer;
import com.justfarming.render.OverlayRenderer;
import com.justfarming.render.PaperDollRenderer;
import com.justfarming.render.ScoreboardHudRenderer;
import com.justfarming.visitor.VisitorManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

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
    private static PestKillerManager pestKillerManager;
    private static InventoryHudRenderer inventoryHudRenderer;
    private static PaperDollRenderer paperDollRenderer;
    private static FarmingProfitTracker profitTracker;
    private static ScoreboardHudRenderer scoreboardHudRenderer;
    /** {@code true} when the farming macro was running before the pest killer started. */
    private static boolean pestKillerShouldResumeMacro = false;

    // Keybindings
    private static KeyBinding toggleMacroKey;
    private static KeyBinding openGuiKey;
    private static KeyBinding freelookKey;
    private static KeyBinding alternateDirectionKey;
    private static final KeyBinding.Category KEY_CATEGORY = KeyBinding.Category.create(Identifier.of("just-farming", "categories"));

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Just Farming] Initialising...");

        // Load config
        config = FarmingConfig.load();

        // Create macro manager and pest detector
        macroManager = new MacroManager(net.minecraft.client.MinecraftClient.getInstance(), config);
        pestDetector = new PestDetector();
        pestEntityDetector = new PestEntityDetector();
        visitorManager = new VisitorManager(net.minecraft.client.MinecraftClient.getInstance(), config);
        pestKillerManager = new PestKillerManager(
                net.minecraft.client.MinecraftClient.getInstance(), config, pestEntityDetector);
        macroManager.setVisitorManager(visitorManager);
        macroManager.setPestKillerManager(pestKillerManager, pestDetector);
        pestKillerManager.setPestDetector(pestDetector);

        // Create inventory HUD renderer and paper-doll renderer
        inventoryHudRenderer = new InventoryHudRenderer(config);
        paperDollRenderer    = new PaperDollRenderer(config);

        // Create profit tracker and renderer
        profitTracker         = new FarmingProfitTracker();
        scoreboardHudRenderer = new ScoreboardHudRenderer(config);

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

        // Register /just <sub-command> client commands
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                        literal("just")
                                .then(literal("rewarp")
                                        .executes(ctx -> {
                                            macroManager.setRewarpHere();
                                            if (ctx.getSource().getPlayer() != null) {
                                                ctx.getSource().getPlayer().sendMessage(
                                                        net.minecraft.text.Text.literal("§a[Just Farming] Rewarp position set here."), true);
                                            }
                                            return 1;
                                        })
                                        .then(literal("clear")
                                                .executes(ctx -> {
                                                    macroManager.clearRewarps();
                                                    if (ctx.getSource().getPlayer() != null) {
                                                        ctx.getSource().getPlayer().sendMessage(
                                                                net.minecraft.text.Text.literal("§a[Just Farming] All rewarp positions cleared."), true);
                                                    }
                                                    return 1;
                                                })))
                                .then(literal("visitor")
                                        .executes(ctx -> {
                                            visitorManager.start();
                                            if (ctx.getSource().getPlayer() != null) {
                                                ctx.getSource().getPlayer().sendMessage(
                                                        net.minecraft.text.Text.literal("§a[Just Farming] Visitor routine started. Teleporting to barn..."), true);
                                            }
                                            return 1;
                                        }))
                                .then(literal("pest")
                                        .executes(ctx -> {
                                            if (!pestKillerManager.isActive()) {
                                                net.minecraft.client.network.ClientPlayerEntity cmdPlayer =
                                                        ctx.getSource().getPlayer();
                                                // Abort if no vacuum is in the hotbar.
                                                if (cmdPlayer != null
                                                        && !PestKillerManager.hasVacuumInHotbar(cmdPlayer)) {
                                                    cmdPlayer.sendMessage(
                                                            net.minecraft.text.Text.literal(
                                                                    "§c[Just Farming] No vacuum detected in hotbar, pest killer disabled"),
                                                            false);
                                                    return 1;
                                                }
                                                pestKillerShouldResumeMacro = macroManager.isRunning();
                                                if (macroManager.isRunning()) {
                                                    macroManager.stop();
                                                }
                                                pestKillerManager.start(new ArrayList<>(pestDetector.getPestPlots()));
                                                if (ctx.getSource().getPlayer() != null) {
                                                    ctx.getSource().getPlayer().sendMessage(
                                                            net.minecraft.text.Text.literal("§a[Just Farming] Pest killer started."), true);
                                                }
                                            } else {
                                                if (ctx.getSource().getPlayer() != null) {
                                                    ctx.getSource().getPlayer().sendMessage(
                                                            net.minecraft.text.Text.literal("§e[Just Farming] Pest killer is already running."), true);
                                                }
                                            }
                                            return 1;
                                        }))
                                .then(literal("farm")
                                        .executes(ctx -> {
                                            // Send /warp garden first, then start the macro.
                                            // The macro's first action is camera alignment (not movement),
                                            // so by the time it begins walking the warp has completed.
                                            if (ctx.getSource().getPlayer() != null
                                                    && ctx.getSource().getPlayer().networkHandler != null) {
                                                ctx.getSource().getPlayer().networkHandler.sendChatCommand("warp garden");
                                            }
                                            macroManager.start();
                                            if (ctx.getSource().getPlayer() != null) {
                                                ctx.getSource().getPlayer().sendMessage(
                                                        net.minecraft.text.Text.literal("§a[Just Farming] Warping to garden and starting farming..."), true);
                                            }
                                            return 1;
                                        }))
                                .then(literal("setspawn")
                                        .then(literal("clear")
                                                .executes(ctx -> {
                                                    macroManager.clearSpawn();
                                                    if (ctx.getSource().getPlayer() != null) {
                                                        ctx.getSource().getPlayer().sendMessage(
                                                                net.minecraft.text.Text.literal("§a[Just Farming] Spawn overlay position cleared."), true);
                                                    }
                                                    return 1;
                                                })))
                                );
        });
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
                // macroManager.toggle() handles all states including visitor-wait and pest killer
                boolean wasActive = macroManager.isRunning() || visitorManager.isActive()
                        || pestKillerManager.isActive();
                if (!wasActive && config.gardenOnlyEnabled && !pestDetector.isInGarden()) {
                    // Prevent starting the macro outside the Garden.
                    if (client.player != null) {
                        client.player.sendMessage(net.minecraft.text.Text.literal(
                                "§c[Just Farming] You must be in the Garden to use this macro."), true);
                    }
                    continue;
                }
                macroManager.toggle();
                if (client.player != null) {
                    if (macroManager.isRunning()) {
                        client.player.sendMessage(
                                net.minecraft.text.Text.literal("§a[Just Farming] Macro started."), true);
                    } else {
                        client.player.sendMessage(
                                net.minecraft.text.Text.literal("§c[Just Farming] Macro stopped."), true);
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
                            net.minecraft.text.Text.literal(macroManager.isFreelookActive()
                                    ? "§e[Just Farming] Freelook enabled."
                                    : "§e[Just Farming] Freelook disabled."), true);
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

            // Run pest killer tick
            if (config.autoPestKillerEnabled) {
                pestKillerManager.onTick();

                // When pest killer finishes and was started manually (not from the rewarp flow),
                // optionally restart the farming macro.
                // When started from the rewarp flow, MacroManager handles resumption directly.
                if (pestKillerManager.isDone() && !macroManager.isWaitingForPestKiller()) {
                    if (pestKillerShouldResumeMacro && !macroManager.isRunning()
                            && !visitorManager.isActive()) {
                        macroManager.start();
                        if (client.player != null) {
                            client.player.sendMessage(net.minecraft.text.Text.literal(
                                    "§a[Just Farming] Pest killer done – resuming macro."), true);
                        }
                    }
                    pestKillerShouldResumeMacro = false;
                    pestKillerManager.reset();
                }
            }

            // Update pest detection every tick
            pestDetector.update(client);
            pestEntityDetector.update(client);
            // Update profit tracker
            profitTracker.onTick(client, macroManager, pestKillerManager, visitorManager.isActive());
            // Refresh farming fortune from the tab list (throttled internally to every 2 s).
            profitTracker.refreshFarmingFortune(client, config.selectedCrop);

            // Stop macro if Garden-only mode is enabled and the player left the Garden.
            // Do not stop while the visitor routine is active, while the macro is in
            // the VISITING state (returning from visitors to the garden), or while the
            // pest killer is running (it teleports the player to infested plots outside
            // the current garden position).
            if (!pestDetector.isInGarden() && config.gardenOnlyEnabled && macroManager.isRunning()
                    && !visitorManager.isActive() && !macroManager.isVisiting()
                    && !macroManager.isWaitingForPestKiller()) {
                macroManager.stop();
                if (client.player != null) {
                    client.player.sendMessage(net.minecraft.text.Text.literal(
                            "§c[Just Farming] Not in Garden – macro stopped."), false);
                }
            }
        });

        // Register HUD render callback for the inventory overlay and paper-doll panel.
        HudRenderCallback.EVENT.register((drawContext, renderTickCounter) -> {
            // Optionally hide all Just Farming HUDs when Tab or F3 is held.
            if (config.hideHudsOnTabF3 && isTabOrF3Active()) return;
            boolean inGarden = pestDetector.isInGarden();
            // Inventory HUD and paper doll are only shown in the Garden.
            if (inGarden) {
                inventoryHudRenderer.render(drawContext);
                paperDollRenderer.render(drawContext,
                        config.inventoryOverlayX, config.inventoryOverlayY,
                        config.inventoryOverlayScale);
            }
            scoreboardHudRenderer.render(drawContext, getMacroStatusText());
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
            if (config.autoPestKillerEnabled) {
                pestKillerManager.onRenderTick();
            }
        });

        // Listen for Hypixel SkyBlock "You received", "RARE DROP!", and "PET DROP!" chat
        // messages to track pest drops and farming crop sack deposits.
        // When the pest killer is active, item drops go directly to the player's
        // collection storage and never pass through the inventory, so they must be
        // tracked from the chat message rather than from inventory diffs.
        // Action-bar (overlay) messages are also processed to catch sack-deposit
        // notifications like "+5 Red Mushroom ➜ Sack" from mooshroom-cow drops.
        // Also intercepts the server "/setspawn" confirmation to set the spawn highlight.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String text = message.getString();
            if (overlay) {
                // Action-bar messages: only parse sack-deposit "+N Item" patterns.
                profitTracker.onActionBarMessage(text, macroManager);
                return;
            }
            profitTracker.onChatMessage(text, pestKillerManager, macroManager);
            // Detect Booster Cookie inactive message during the visitor routine.
            // The server sends this when the player issues /bazaar without having
            // the Cookie Buff active.  Stop the visitor macro and return to farming.
            if (text.contains("You need the Cookie Buff to use this feature!")
                    && visitorManager.isActive()) {
                visitorManager.notifyCookieBuffInactive();
                LOGGER.warn("[Just Farming] Booster Cookie inactive message detected – notifying visitor manager.");
            }
            if (text.contains("[Bazaar] You cannot afford this!")
                    && visitorManager.isActive()) {
                visitorManager.notifyBazaarInsufficientCoins();
                LOGGER.warn("[Just Farming] Bazaar insufficient coins message detected – notifying visitor manager.");
            }
            // When the server confirms a /setspawn command, save the player's current
            // position as the spawn highlight block (without sending any command ourselves).
            if (text.contains("Your spawn location has been set")) {
                macroManager.setSpawnHere();
                net.minecraft.client.network.ClientPlayerEntity p =
                        net.minecraft.client.MinecraftClient.getInstance().player;
                if (p != null) {
                    p.sendMessage(net.minecraft.text.Text.literal(
                            "§a[Just Farming] Spawn overlay position set here."), false);
                }
                LOGGER.info("[Just Farming] Spawn highlight set from /setspawn confirmation.");
            }
        });

        // Stop all active systems when the player disconnects from a server so
        // that stale macro state does not interfere with the next session join
        // (which can cause premature packet sends or authentication timeouts).
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (macroManager.isRunning() || macroManager.isWaitingForVisitors()
                    || macroManager.isWaitingForPestKiller()) {
                macroManager.stop();
            }
            if (visitorManager.isActive()) {
                visitorManager.stop();
            }
            if (pestKillerManager.isActive()) {
                pestKillerManager.reset();
            }
            profitTracker.reset();
            pestKillerShouldResumeMacro = false;
            LOGGER.info("[Just Farming] Disconnected – all macros stopped.");
        });

        LOGGER.info("[Just Farming] Ready. Toggle macro: R | Open GUI: I | Freelook: L | Alternate direction: N | Commands: /just rewarp, /just rewarp clear, /just visitor, /just pest, /just farm, /just setspawn clear (spawn set automatically from /setspawn server confirmation)");
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

    /** Returns the shared pest killer manager instance. */
    public static PestKillerManager getPestKillerManager() {
        return pestKillerManager;
    }

    /** Returns the shared farming profit tracker instance. */
    public static FarmingProfitTracker getProfitTracker() {
        return profitTracker;
    }

    /**
     * Returns a short human-readable string describing the current macro state,
     * used as the scoreboard status line replacing the Hypixel date/server entry.
     *
     * <ul>
     *   <li>"Breaking Crops" – farming macro is active</li>
     *   <li>"Killing Pests"  – pest killer routine is active</li>
     *   <li>"Accepting Visitors" – visitor routine is active</li>
     *   <li>"Idle"           – no macro is running</li>
     * </ul>
     */
    private static String getMacroStatusText() {
        if (pestKillerManager != null && pestKillerManager.isActive()) return "Killing Pests";
        if (visitorManager   != null && visitorManager.isActive())    return "Accepting Visitors";
        if (macroManager     != null && macroManager.isRunning())     return "Breaking Crops";
        return "Idle";
    }

    /**
     * Returns {@code true} when the player is holding Tab (player list) or
     * the F3 debug screen is currently visible.  Used to hide all Just Farming
     * HUD overlays when {@link FarmingConfig#hideHudsOnTabF3} is enabled.
     */
    private static boolean isTabOrF3Active() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return false;
        // F3 debug screen
        if (mc.getDebugHud() != null && mc.getDebugHud().shouldShowDebugHud()) return true;
        // Tab key (player list)
        if (mc.options != null && mc.options.playerListKey != null
                && mc.options.playerListKey.isPressed()) return true;
        return false;
    }
}
