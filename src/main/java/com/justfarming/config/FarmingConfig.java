package com.justfarming.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.InstanceCreator;
import com.justfarming.CropType;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent configuration for Just Farming mod.
 * Saved to and loaded from a JSON file in the Fabric config directory.
 */
public class FarmingConfig {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(FarmingConfig.class, (InstanceCreator<FarmingConfig>) type -> new FarmingConfig())
            .create();
    private static final String CONFIG_FILE = "just-farming.json";

    // --- Config fields ---

    /** The crop type currently selected for farming. */
    public CropType selectedCrop = CropType.COCOA_BEANS;

    /**
     * Whether to highlight chunk borders and plot numbers for plots with pests
     * on Hypixel Skyblock Garden.
     */
    public boolean pestHighlightEnabled = true;

    /**
     * Whether to display floating labels (plot name + pest count) above
     * infested garden plots. Only effective when {@link #pestHighlightEnabled}
     * is also {@code true}.
     */
    public boolean pestLabelsEnabled = true;

    /**
     * Whether to draw ESP (highlighted bounding boxes) around pest mob
     * entities, visible through walls.
     */
    public boolean pestEspEnabled = true;

    /**
     * Scale multiplier for the large floating plot title in the world.
     * Adjust to make the "Plot N" label larger or smaller.
     */
    public float pestTitleScale = 2.25f;

    /**
     * Whether to draw tracer lines from the player to each pest mob entity.
     */
    public boolean pestTracerEnabled = true;

    /**
     * Whether to release (ungrab) the mouse cursor while the macro is running,
     * so the user can interact with other windows on their desktop.
     */
    public boolean unlockedMouseEnabled = false;

    /**
     * When {@code true}, the macro continues to move and break blocks even while
     * any GUI (including the FLIA config screen) is open.  This mirrors
     * the behaviour of FarmHelper's "macro enabled in GUI" option and removes
     * the brief pause that can occur when opening or closing a screen.
     */
    public boolean macroEnabledInGui = false;

    /**
     * When {@code true}, all Just Farming HUD overlays (inventory, profit, scoreboard)
     * are automatically hidden whenever the player opens the debug screen (F3) or
     * the player list (Tab).  Useful for taking screenshots or checking vanilla info
     * without manually disabling each overlay.
     */
    public boolean hideHudsOnTabF3 = false;

    /**
     * When {@code true}, replaces Minecraft's default scoreboard sidebar with a
     * custom "Just Farming" styled scoreboard that shows a branded header and a
     * cleaner layout.
     */
    public boolean customScoreboardEnabled = true;

    // --- Inventory HUD Overlay ---

    /**
     * When {@code true}, renders the player's main inventory (27 slots, excluding
     * the hotbar) directly on the HUD so items can be monitored without opening
     * a screen.
     */
    public boolean inventoryOverlayEnabled = false;

    /**
     * Horizontal pixel offset of the inventory overlay's top-left corner
     * from the left edge of the screen.  0 = screen left edge.
     */
    public int inventoryOverlayX = 0;

    /**
     * Vertical pixel offset of the inventory overlay's top-left corner
     * from the top edge of the screen.  0 = screen top edge.
     */
    public int inventoryOverlayY = 60;

    /**
     * Scale multiplier for the inventory HUD overlay.
     * 1.0 = native pixel size (16 px per slot icon).
     * Range: 0.5–3.0.
     */
    public float inventoryOverlayScale = 1.0f;

    /**
     * When {@code true}, renders a paper-doll player model to the right of the
     * inventory HUD overlay, along with a compact WASD keystrokes + CPS display
     * below the model.  Only visible while {@link #inventoryOverlayEnabled} is
     * also {@code true}.
     */
    public boolean paperDollEnabled = false;

    // --- Profit Tracker HUD ---

    /**
     * When {@code true}, the Farming Profit HUD overlay is shown, displaying
     * items collected, NPC profit, and profit per hour for the current session.
     * All prices are stored in the mod and require no external API calls.
     */
    public boolean profitTrackerEnabled = true;

    /**
     * When {@code true}, the Pest Profit section is shown inside the Farming
     * Profit HUD, tracking items and coins gained while the pest killer is
     * active.  When {@code false}, only farming profit (and its profit/hour)
     * is shown.
     */
    public boolean pestProfitEnabled = true;

    /**
     * Horizontal pixel position of the top-left corner of the Profit HUD
     * panel, measured from the left edge of the screen.
     */
    public int profitHudX = 10;

    /**
     * Vertical pixel position of the top-left corner of the Profit HUD
     * panel, measured from the top edge of the screen.
     */
    public int profitHudY = 10;

    /**
     * When {@code true}, the macro will only start (and will auto-stop) when
     * the player is detected to be in the Hypixel Skyblock Garden.
     */
    public boolean gardenOnlyEnabled = true;

    /**
     * When {@code true}, the macro will search the player's hotbar for a
     * Squeaky Mousemat at startup and left-click it (using a block-attack packet
     * so Hypixel Skyblock's item ability fires), instead of relying on the
     * player's rotation being snapped by the server.
     */
    public boolean squeakyMousematEnabled = false;

    /**
     * Delay in milliseconds before switching to the Squeaky Mousemat hotbar slot.
     * Only applies when the player is not already holding the mousemat.
     */
    public int mousematSwapToDelay = 0;

    /**
     * Delay in milliseconds between switching to the Squeaky Mousemat
     * hotbar slot and sending the left-click ability.
     */
    public int mousematPreDelay = 200;

    /**
     * Delay in milliseconds between sending the Squeaky Mousemat
     * left-click and restoring the player's original hotbar slot.
     */
    public int mousematPostDelay = 200;

    /**
     * Delay in milliseconds between restoring the farming tool slot and
     * resuming farming (entering the DETECTING state).
     */
    public int mousematResumeDelay = 0;

    // --- Rewarp trigger position ---

    /** Whether a rewarp position has been set. */
    public boolean rewarpSet = false;

    /** Rewarp trigger X coordinate. */
    public double rewarpX = 0.0;

    /** Rewarp trigger Y coordinate. */
    public double rewarpY = 0.0;

    /** Rewarp trigger Z coordinate. */
    public double rewarpZ = 0.0;

    /** Radius (in blocks) around the rewarp point that triggers the warp. */
    public double rewarpRange = 3.0;

    /**
     * Minimum delay in milliseconds between reaching the rewarp trigger and
     * actually sending {@code /warp garden} (i.e. the lane-swap delay).
     */
    public int rewarpDelayMin = 100;

    /**
     * Extra random milliseconds added on top of {@link #rewarpDelayMin}.
     * The actual delay will be {@code rewarpDelayMin + random(0, rewarpDelayRandom)}.
     */
    public int rewarpDelayRandom = 0;

    /**
     * Upper bound (exclusive) of the global random jitter (in milliseconds)
     * added on top of every configurable delay throughout the macro.
     * The actual extra per action is {@code random(0, globalRandomizationMs)}.
     */
    public int globalRandomizationMs = 150;

    /**
     * Minimum delay in milliseconds between detecting the end of a farming row
     * and actually flipping direction (i.e. the lane-swap delay).
     */
    public int laneSwapDelayMin = 0;

    /**
     * Extra random milliseconds added on top of {@link #laneSwapDelayMin}.
     * The actual delay will be {@code laneSwapDelayMin + random(0, laneSwapDelayRandom)}.
     */
    public int laneSwapDelayRandom = 0;

    // --- Visitor Settings ---

    /**
     * When {@code true}, the macro will teleport to the barn ({@code /tptoplot barn})
     * at the rewarp trigger, interact with garden visitors, and optionally buy
     * required items from the Bazaar before accepting each visitor's offer.
     */
    public boolean visitorsEnabled = false;

    /**
     * When {@code true} (and {@link #visitorsEnabled} is also {@code true}), the
     * visitor routine will run {@code /bazaar <item>} for each item a visitor needs
     * and attempt to purchase the required amount via "Buy Instantly".
     */
    public boolean visitorsBuyFromBazaar = true;

    /**
     * Base delay in milliseconds applied to every visitor action: bazaar GUI
     * clicks, NPC interactions, camera rotation and player movement.
     * A randomised extra of up to {@link #visitorsActionDelayRandom} ms is
     * added on top of this value for each individual action.
     */
    public int visitorsActionDelay = 600;

    /**
     * Maximum extra random milliseconds added on top of
     * {@link #visitorsActionDelay} for each visitor action.
     * The actual per-action delay will be
     * {@code visitorsActionDelay + random(0, visitorsActionDelayRandom)}.
     */
    public int visitorsActionDelayRandom = 0;

    /**
     * How long (in milliseconds) to wait after sending {@code /tptoplot barn}
     * before scanning for visitor NPCs.  Increase this if the server is slow
     * to process the teleport.
     * An additional non-configurable random extra of up to 200 ms is always
     * added on top of this value.
     */
    public int visitorsTeleportDelay = 4000;

    /**
     * Delay in milliseconds simulating the player typing {@code /bazaar <item>}
     * in chat before the command is actually sent.  Use this to make the macro
     * look more human-like when searching the bazaar.
     */
    public int bazaarSearchDelay = 1500;

    /**
     * Visitor names to skip automatically.  Any visitor whose name appears in
     * this list will be ignored by the visitor routine regardless of their
     * required items or rewards.
     */
    public List<String> visitorBlacklist = new ArrayList<>();

    // --- Pest Killer Settings ---

    /**
     * When {@code true}, the mod will automatically kill pests when they are
     * detected in the Garden by flying toward them and using a vacuum item.
     */
    public boolean autoPestKillerEnabled = false;

    /**
     * When {@code true} (and {@link #autoPestKillerEnabled} is also {@code true}),
     * the pest killer will run {@code /tptoplot <plot>} to warp directly to the
     * infested plot.  When {@code false}, it runs {@code /warp garden} instead.
     */
    public boolean pestKillerWarpToPlot = false;

    /**
     * How long (in milliseconds) to wait before sending the plot teleport command.
     * Increase this to add a delay before the mod warps to the infested plot,
     * making the behaviour look more human-like.
     * After the teleport command is sent, the mod always waits a fixed 500 ms
     * (plus up to 150 ms random) before scanning for pest entities.
     */
    public int pestKillerTeleportDelay = 4000;

    /**
     * Minimum delay (in milliseconds) between killing a pest and starting to
     * search for the next one.  A short pause here makes the behaviour look
     * more human-like and gives the server time to despawn the killed mob.
     */
    public int pestKillerGoToNextPestDelay = 0;

    /**
     * Preferred hotbar slot (0–8) for the farming tool that is active while
     * the pest-killer routine is not running.  Set to {@code -1} (the default)
     * to have the mod auto-detect the farming tool as the first non-vacuum,
     * non-empty hotbar slot.
     *
     * <p>When the player has multiple farming tools in the hotbar (e.g. one per
     * crop type) this setting lets them pin a specific slot so the pest killer
     * always returns to the correct tool after killing pests, regardless of
     * which slot was last selected.
     *
     * <p>Range: -1 (Auto) … 8 (Slot 9).  Default {@code -1}.
     */
    public int farmingToolHotbarSlot = -1;

    /**
     * Maximum distance (in blocks) from which the vacuum item is used to kill a pest.
     * This value is used as a fallback when the vacuum type cannot be auto-detected.
     *
     * <p>When the mod can identify the vacuum in your hotbar it automatically uses
     * the correct range for that item (Skymart Vacuum = 5, Turbo Vacuum = 7.5,
     * Hyper Vacuum = 10, InfiniVacuum = 12.5, InfiniVacuum™ Hooverius = 15).
     * Set this to {@code 0} to rely solely on auto-detection; if auto-detection
     * also fails the hardcoded 5-block default is used.
     *
     * <p>Range: 0–15 blocks.  Default {@code 5}.
     */
    public int pestKillerVacuumRange = 5;

    /**
     * How long (in milliseconds) to wait after the plot teleport command lands
     * before scanning for pest entities.  Increase this if the server is slow
     * to load entity data after a teleport.
     *
     * <p>Range: 0–5000 ms.  Default {@code 500}.
     */
    public int pestKillerAfterTeleportDelay = 500;

    /**
     * Minimum number of visitors that must be present at the barn for the
     * visitor routine to proceed.  If fewer visitors are found after the
     * teleport wait, the routine skips the barn visit and warps directly back
     * to the Garden.
     *
     * <p>Range: 1–6 (Hypixel SkyBlock allows up to 5 concurrent visitors, with
     * a possible 6th appearing late).  Default {@code 1} means the routine
     * always runs when at least one visitor is present.
     */
    public int visitorsMinCount = 1;

    /**
     * Last-selected preset mode for the config GUI.
     * 0 = Blatant, 1 = Smart, 2 = Custom (default).
     * Persisted so re-opening the GUI shows the correct preset name.
     */
    public int configPreset = 2;

    /**
     * Maximum total NPC sell value (in coins) of all items a visitor requests
     * before the visitor is automatically skipped.  Set to {@code 0} to disable
     * this check (no limit).
     *
     * <p>The value is compared against the sum of {@code amount × NPC sell price}
     * for every item the visitor requires.  If the total exceeds this threshold
     * the visitor's offer is declined without buying or accepting anything.
     *
     * <p>Range: 0–5,000,000.  Steps of 50,000 in the GUI slider.
     */
    public int visitorsMaxPrice = 0;

    // --- Per-Crop Settings ---

    /**
     * Per-crop camera and key overrides.
     *
     * <p>The map key is the {@link CropType#name()} string (e.g. {@code "COCOA_BEANS"}).
     * When an entry is present, the macro uses its values instead of the crop's built-in
     * defaults.  When absent the built-in defaults apply.
     */
    public Map<String, CropCustomSettings> cropSettings = new HashMap<>();

    /** Returns the custom settings for {@code crop}, or {@code null} if none are saved. */
    public CropCustomSettings getCropSettings(CropType crop) {
        return cropSettings.get(crop.name());
    }

    /** Returns the effective yaw for {@code crop} (custom override or built-in default). */
    public float getEffectiveYaw(CropType crop) {
        CropCustomSettings cs = getCropSettings(crop);
        return cs != null ? cs.yaw : crop.getDefaultYaw();
    }

    /** Returns the effective pitch for {@code crop} (custom override or built-in default). */
    public float getEffectivePitch(CropType crop) {
        CropCustomSettings cs = getCropSettings(crop);
        return cs != null ? cs.pitch : crop.getDefaultPitch();
    }

    // -------------------------------------------------------------------------
    // Nested: per-crop customisable settings
    // -------------------------------------------------------------------------

    /**
     * Stores a player-configurable override for a single crop type.
     *
     * <p>All fields are always present once an entry is saved.  Use
     * {@link #fromDefaults(CropType)} to build an entry pre-populated with a
     * crop's built-in defaults.
     */
    public static class CropCustomSettings {

        /** Camera yaw (horizontal rotation, degrees). */
        public float yaw;
        /** Camera pitch (vertical look angle, degrees). */
        public float pitch;

        /** Hold the forward movement key during active farming states. */
        public boolean forward;
        /** Hold the back movement key during active farming states. */
        public boolean back;
        /** Hold the left-strafe key during active farming states. */
        public boolean left;
        /** Hold the right-strafe key during active farming states. */
        public boolean right;
        /** Hold the attack key during active farming states. */
        public boolean attack;

        /** Required by Gson. */
        public CropCustomSettings() {}

        public CropCustomSettings(float yaw, float pitch,
                                   boolean forward, boolean back,
                                   boolean left, boolean right,
                                   boolean attack) {
            this.yaw     = yaw;
            this.pitch   = pitch;
            this.forward = forward;
            this.back    = back;
            this.left    = left;
            this.right   = right;
            this.attack  = attack;
        }

        /**
         * Build a {@code CropCustomSettings} pre-filled with the built-in defaults
         * for the given crop type.
         */
        public static CropCustomSettings fromDefaults(CropType crop) {
            boolean fwd = crop.isSShape() || crop.isForwardBack();
            boolean bk  = !crop.isSShape() && !crop.isLeftBack()
                          && !crop.isCactus() && !crop.isForwardBack();
            boolean lft = !crop.isForwardBack();
            return new CropCustomSettings(
                    crop.getDefaultYaw(), crop.getDefaultPitch(),
                    fwd, bk, lft, false, true);
        }
    }

    // --- Load / Save ---

    /**
     * Load config from disk. If no config file exists, returns default config.
     */
    public static FarmingConfig load() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
        if (Files.exists(configPath)) {
            try (Reader reader = Files.newBufferedReader(configPath)) {
                FarmingConfig cfg = GSON.fromJson(reader, FarmingConfig.class);
                if (cfg != null) {
                    return cfg;
                }
            } catch (IOException e) {
                LoggerFactory.getLogger("just-farming").warn("Failed to load config, using defaults: {}", e.getMessage());
            }
        }
        return new FarmingConfig();
    }

    /**
     * Save config to disk.
     */
    public void save() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
        try (Writer writer = Files.newBufferedWriter(configPath)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            LoggerFactory.getLogger("just-farming").warn("Failed to save config: {}", e.getMessage());
        }
    }
}
