package com.justfarming.pest;

import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks ANGRY_VILLAGER particles emitted by a vacuum-shot left-click and
 * computes the direction of travel (toward the pest).
 *
 * <p>Usage pattern:
 * <ol>
 *   <li>Call {@link #startTracking(Vec3d)} with the player eye position just
 *       before firing the vacuum shot.</li>
 *   <li>The particle-spawning mixin calls {@link #onParticle(double, double, double)}
 *       for every ANGRY_VILLAGER particle the server spawns.</li>
 *   <li>After waiting for particles to accumulate, call {@link #getWaypoint()} to
 *       obtain a target coordinate 10 blocks ahead of
 *       the last particle in the direction of travel.</li>
 *   <li>Call {@link #stopTracking()} when done.</li>
 * </ol>
 *
 * <p>All public methods are {@code synchronized} so they can safely be called
 * from both the client/render thread and the network-receive thread.
 */
public class VacuumParticleTracker {

    // ── Constants ─────────────────────────────────────────────────────────────

    /**
     * Maximum distance (blocks) from the player position at which the first
     * particle is accepted.  Particles that spawn further away are ignored to
     * avoid false positives from other effects in the world.
     */
    private static final double MAX_FIRST_PARTICLE_DIST = 5.0;

    /**
     * Maximum distance (blocks) between two consecutive particles in the trail.
     * Particles further apart than this are dropped so we don't connect unrelated
     * particle effects.
     */
    private static final double MAX_CONSECUTIVE_DIST = 2.0;

    /**
     * Number of blocks to project ahead of the last particle (in the trail
     * direction) to compute the fly-toward waypoint.
     */
    private static final double WAYPOINT_LOOKAHEAD = 10.0;

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static VacuumParticleTracker instance;

    public static VacuumParticleTracker getInstance() {
        if (instance == null) {
            instance = new VacuumParticleTracker();
        }
        return instance;
    }

    // ── State ─────────────────────────────────────────────────────────────────

    /** Whether we are currently accumulating particles. */
    private volatile boolean tracking = false;

    /** Player eye position at the moment tracking started; used as proximity filter. */
    private volatile Vec3d playerPos = null;

    /** First accepted particle in the trail. */
    private Vec3d firstParticle = null;

    /** Most recent accepted particle in the trail. */
    private Vec3d lastParticle = null;

    /** Wall-clock time (ms) of the most recently accepted particle. */
    private long lastParticleTime = 0;

    /** Full list of accepted trail particles (for debugging / future use). */
    private final List<Vec3d> trail = new ArrayList<>();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Begin accumulating particle positions.
     *
     * @param playerPos the player's eye position at the time the vacuum is fired;
     *                  used to accept only particles that originate nearby.
     */
    public synchronized void startTracking(Vec3d playerPos) {
        tracking = true;
        this.playerPos = playerPos;
        firstParticle = null;
        lastParticle = null;
        lastParticleTime = 0;
        trail.clear();
    }

    /** Stop accumulating particles. */
    public synchronized void stopTracking() {
        tracking = false;
    }

    /** Returns {@code true} while this tracker is accumulating particles. */
    public synchronized boolean isTracking() {
        return tracking;
    }

    /**
     * Called by the particle-spawn mixin when an ANGRY_VILLAGER particle is
     * detected.  This method is thread-safe and can be invoked from the network
     * thread.
     *
     * @param x world X coordinate of the particle
     * @param y world Y coordinate of the particle
     * @param z world Z coordinate of the particle
     */
    public synchronized void onParticle(double x, double y, double z) {
        if (!tracking) return;

        Vec3d pos = new Vec3d(x, y, z);

        if (firstParticle == null) {
            // Accept only particles close to the player so we don't pick up
            // distant effects unrelated to the vacuum shot.
            if (playerPos != null && playerPos.distanceTo(pos) > MAX_FIRST_PARTICLE_DIST) {
                return;
            }
            firstParticle = pos;
            lastParticle = pos;
            trail.add(pos);
            lastParticleTime = System.currentTimeMillis();
            return;
        }

        // Accept only particles that are close to the previous one so the trail
        // stays contiguous and doesn't jump across the world.
        if (lastParticle.distanceTo(pos) <= MAX_CONSECUTIVE_DIST) {
            trail.add(pos);
            lastParticle = pos;
            lastParticleTime = System.currentTimeMillis();
        }
    }

    /**
     * Returns {@code true} if enough particle data has been collected to
     * compute a meaningful direction of travel.
     */
    public synchronized boolean hasDirection() {
        return firstParticle != null
                && lastParticle != null
                && firstParticle.distanceTo(lastParticle) > 0.5;
    }

    /**
     * Returns a waypoint 10 blocks ahead of the last
     * particle in the direction of travel, or {@code null} if no direction is
     * available yet.
     */
    public synchronized Vec3d getWaypoint() {
        if (!hasDirection()) return null;
        Vec3d dir = lastParticle.subtract(firstParticle).normalize();
        return lastParticle.add(dir.multiply(WAYPOINT_LOOKAHEAD));
    }

    /**
     * Returns the wall-clock time (ms) of the most recently accepted particle,
     * or {@code 0} if none has been accepted yet.
     */
    public synchronized long getLastParticleTime() {
        return lastParticleTime;
    }

    /** Clears all accumulated state without changing the tracking flag. */
    public synchronized void reset() {
        firstParticle = null;
        lastParticle = null;
        lastParticleTime = 0;
        trail.clear();
    }
}
