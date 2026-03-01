package com.justfarming.pest;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
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
 * entity types against the known underlying entity types used by each pest.
 *
 * <p>Pests in the Hypixel Skyblock Garden are represented on the client as
 * specific vanilla entity types:
 * <ul>
 *   <li>{@link EntityType#SILVERFISH} – ground pests (Beetle, Cricket, Earthworm,
 *       Field Mouse, Locust, Mite, Rat, Slug, Praying Mantis)</li>
 *   <li>{@link EntityType#BAT} – flying pests (Fly, Mosquito, Moth, Firefly, Dragonfly)</li>
 * </ul>
 * Filtering by entity type first (O(1) check) and only then reading the custom
 * name for the display label is significantly more efficient than scanning all
 * entity names via string matching.
 */
public class PestEntityDetector {

    /**
     * Vanilla entity types that Hypixel Skyblock uses to represent Garden pests
     * on the client:
     * <ul>
     *   <li>SILVERFISH – all ground pest variants</li>
     *   <li>BAT        – all flying pest variants</li>
     * </ul>
     */
    private static final Set<EntityType<?>> PEST_ENTITY_TYPES = Set.of(
            EntityType.SILVERFISH,
            EntityType.BAT
    );

    /** Pattern for stripping Minecraft colour codes (§X). */
    private static final Pattern COLOR_CODE = Pattern.compile("§[0-9a-fklmnorA-FKLMNOR]");

    /**
     * Minimum average side-length of an entity bounding box before the detector
     * substitutes a default visible box (e.g. for zero-size entities).
     */
    private static final double MIN_BOX_SIZE = 0.1;

    /** Half-width of the fallback bounding box applied to zero-size entities. */
    private static final double DEFAULT_BOX_HALF_WIDTH = 0.4;

    /** Height of the fallback bounding box applied to zero-size entities. */
    private static final double DEFAULT_BOX_HEIGHT = 0.8;

    /** Default ESP label used when the pest entity has no custom name set. */
    private static final String DEFAULT_PEST_LABEL = "Pest";
    private List<PestEntity> detectedPests = Collections.emptyList();

    /**
     * Scans the client world for pest entities and caches the results.
     * Should be called once per tick, and only when the player is in the Garden.
     */
    public void update(MinecraftClient client) {
        ClientWorld world = client.world;
        if (world == null || client.player == null) {
            detectedPests = Collections.emptyList();
            return;
        }

        List<PestEntity> found = new ArrayList<>();
        for (Entity entity : world.getEntities()) {
            if (entity instanceof PlayerEntity) continue;
            if (!entity.isAlive()) continue;

            // Primary filter: entity type must be one of the known pest types.
            // This is an O(1) hash-set lookup and avoids string parsing for
            // every entity in the world.
            if (!PEST_ENTITY_TYPES.contains(entity.getType())) continue;

            // Get the custom name for the ESP label (if the server has set one).
            String displayName = getCleanName(entity);
            if (displayName == null) {
                displayName = DEFAULT_PEST_LABEL;
            }

            Box box = entity.getBoundingBox();
            if (box.getAverageSideLength() < MIN_BOX_SIZE) {
                double x = entity.getX(), z = entity.getZ(), y = entity.getY();
                box = new Box(x - DEFAULT_BOX_HALF_WIDTH, y, z - DEFAULT_BOX_HALF_WIDTH,
                              x + DEFAULT_BOX_HALF_WIDTH, y + DEFAULT_BOX_HEIGHT,
                              z + DEFAULT_BOX_HALF_WIDTH);
            }
            found.add(new PestEntity(
                    new Vec3d(entity.getX(), entity.getY(), entity.getZ()),
                    box,
                    displayName,
                    entity.getType()
            ));
        }
        detectedPests = Collections.unmodifiableList(found);
    }

    /**
     * Clears the cached pest list. Call when leaving the Garden so that stale
     * entries are not rendered on other islands.
     */
    public void clear() {
        detectedPests = Collections.emptyList();
    }

    /**
     * Returns the list of pest entities detected in the most recent
     * {@link #update} call.
     */
    public List<PestEntity> getDetectedPests() {
        return detectedPests;
    }

    /**
     * Returns the entity's custom name after stripping colour codes, or
     * {@code null} if no custom name is set.  Used for ESP display labels.
     */
    private static String getCleanName(Entity entity) {
        // Prefer the explicitly-set custom name (server name-tags, /summon NBT, etc.)
        if (entity.getCustomName() != null) {
            String raw = entity.getCustomName().getString();
            if (raw != null && !raw.isBlank()) {
                return COLOR_CODE.matcher(raw).replaceAll("").trim();
            }
        }
        // Fall back to display name returned by the entity (covers Hypixel custom mobs
        // that expose their name through entity-type metadata without a CustomName NBT).
        net.minecraft.text.Text display = entity.getDisplayName();
        if (display == null) return null;
        String raw = display.getString();
        if (raw == null || raw.isBlank()) return null;
        return COLOR_CODE.matcher(raw).replaceAll("").trim();
    }

    /**
     * Snapshot of a detected pest entity's position, bounding box, name, and entity type.
     */
    public record PestEntity(Vec3d position, Box boundingBox, String displayName, EntityType<?> entityType) {}
}
