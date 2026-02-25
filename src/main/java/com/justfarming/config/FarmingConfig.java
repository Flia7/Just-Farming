package com.justfarming.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.justfarming.CropType;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persistent configuration for Just Farming mod.
 * Saved to and loaded from a JSON file in the Fabric config directory.
 */
public class FarmingConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "just-farming.json";

    // --- Config fields ---

    /** The crop type currently selected for farming (only COCOA_BEANS for now). */
    public CropType selectedCrop = CropType.COCOA_BEANS;

    /**
     * Pitch angle (vertical look angle, degrees) while farming.
     * Typical range: 30–80 degrees looking down at crops.
     */
    public float farmingPitch = 55.0f;

    /**
     * Yaw angle (horizontal rotation, degrees) while farming.
     * Range: -180 to 180. Locks the player's horizontal facing direction.
     */
    public float farmingYaw = 0.0f;

    /**
     * Whether to automatically switch to the best farming tool in the hotbar.
     */
    public boolean autoToolSwitch = true;

    /**
     * Whether to highlight chunk borders and plot numbers for plots with pests
     * on Hypixel Skyblock Garden.
     */
    public boolean pestHighlightEnabled = false;

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
