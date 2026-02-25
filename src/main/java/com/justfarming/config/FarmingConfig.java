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

    /** The crop type currently selected for farming */
    public CropType selectedCrop = CropType.WHEAT;

    /**
     * Macro speed: 1 = slow (100 ms tick), 2 = normal (50 ms tick), 3 = fast (20 ms tick).
     * Higher values move faster but may cause more desync on Hypixel.
     */
    public int macroSpeed = 2;

    /**
     * The player pitch (vertical look angle, degrees) used while farming.
     * Typical range: 30–80 degrees looking down at crops.
     */
    public float farmingPitch = 55.0f;

    /**
     * Whether to automatically replant crops after harvesting.
     */
    public boolean autoReplant = true;

    /**
     * Whether to automatically switch to the best farming tool in the hotbar.
     */
    public boolean autoToolSwitch = true;

    /**
     * Number of blocks to travel in one direction before turning around.
     */
    public int rowLength = 50;

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

    /** Returns the tick delay in milliseconds based on macroSpeed. */
    public int getTickDelayMs() {
        return switch (macroSpeed) {
            case 1 -> 100;
            case 3 -> 20;
            default -> 50;
        };
    }
}
