package com.justfarming.pest;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detects pest mob entities in the Hypixel Skyblock Garden by matching
 * their display names against the known pest types.
 *
 * <p>Pests are mob entities whose custom name (after stripping colour codes)
 * contains one of the known pest names. This follows the same approach
 * used by SkyHanni and FarmHelper.
 */
public class PestEntityDetector {

    /** Known pest display names on Hypixel Skyblock. */
    private static final Set<String> PEST_NAMES = Set.of(
            "beetle", "cricket", "earthworm", "field mouse", "fly",
            "locust", "mite", "mosquito", "moth", "rat", "slug",
            "praying mantis", "firefly", "dragonfly"
    );

    /** Pattern for stripping Minecraft colour codes (§X). */
    private static final Pattern COLOR_CODE = Pattern.compile("§[0-9a-fklmnorA-FKLMNOR]");

    /** Cached list of detected pest entities, refreshed each tick. */
    private List<PestEntity> detectedPests = Collections.emptyList();

    /**
     * Scans the client world for pest entities and caches the results.
     * Should be called once per tick.
     */
    public void update(MinecraftClient client) {
        ClientWorld world = client.world;
        if (world == null || client.player == null) {
            detectedPests = Collections.emptyList();
            return;
        }

        List<PestEntity> found = new ArrayList<>();
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof LivingEntity)) continue;
            if (entity instanceof PlayerEntity) continue;
            if (!entity.isAlive()) continue;

            String name = getCleanName(entity);
            if (name == null) continue;

            String lower = name.toLowerCase();
            for (String pestName : PEST_NAMES) {
                if (lower.contains(pestName)) {
                    found.add(new PestEntity(
                            new Vec3d(entity.getX(), entity.getY(), entity.getZ()),
                            entity.getBoundingBox(),
                            name
                    ));
                    break;
                }
            }
        }
        detectedPests = Collections.unmodifiableList(found);
    }

    /**
     * Returns the list of pest entities detected in the most recent
     * {@link #update} call.
     */
    public List<PestEntity> getDetectedPests() {
        return detectedPests;
    }

    private static String getCleanName(Entity entity) {
        // Prefer the explicitly-set custom name (server name-tags, /summon NBT, etc.)
        if (entity.getCustomName() != null) {
            String raw = entity.getCustomName().getString();
            if (raw != null && !raw.isBlank()) {
                return COLOR_CODE.matcher(raw).replaceAll("").trim();
            }
        }
        // Fall back to the display name (covers custom entity types whose name is
        // returned by getName() even without a CustomName NBT, e.g. Hypixel Skyblock
        // pest mobs that expose their name through entity-type metadata only).
        net.minecraft.text.Text display = entity.getDisplayName();
        if (display == null) return null;
        String raw = display.getString();
        if (raw == null || raw.isBlank()) return null;
        return COLOR_CODE.matcher(raw).replaceAll("").trim();
    }

    /**
     * Snapshot of a detected pest entity's position, bounding box, and name.
     */
    public record PestEntity(Vec3d position, Box boundingBox, String displayName) {}
}
