package com.justfarming.pest;

import com.justfarming.config.FarmingConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
/**
 * Manages the auto pest killer routine for Just Farming.
 *
 * <p>When {@link FarmingConfig#autoPestKillerEnabled} is {@code true} and pests
 * are detected, this manager:
 * <ol>
 *   <li>Teleports to the first infested plot (via {@code /tptoplot <plot>}) or to
 *       the garden (via {@code /warp garden}), depending on
 *       {@link FarmingConfig#pestKillerWarpToPlot}.</li>
 *   <li>After the teleport delay, flies to the centre of the plot to maximise
 *       entity-scan radius.</li>
 *   <li>Scans for pest entities via {@link PestEntityDetector}.  If any are
 *       found it flies directly to the nearest one.</li>
 *   <li>If no pests are detected at the plot centre, it left-clicks with the
 *       vacuum item to fire a "vacuum shot".  The shot emits a line of
 *       ANGRY_VILLAGER particles toward the nearest pest.  The mod follows
 *       that line to reach the pest.</li>
 *   <li>Switches to a vacuum item in the hotbar (any item whose name contains
 *       "Vacuum", case-insensitive) and right-clicks while aiming at the pest.
 *       Right-clicking continues until the pest disappears from the entity list.</li>
 *   <li>When no pests remain on the current plot, teleports to the next infested
 *       plot (if any) and repeats steps 2–5 until all plots have been cleared.</li>
 *   <li>Sends {@code /warp garden} when all detected pests have been killed
 *       and marks itself {@link State#DONE}.</li>
 * </ol>
 */
public class PestKillerManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("just-farming");

    // ── Timing constants ────────────────────────────────────────────────────

    /**
     * Fixed wait (ms) after the teleport command lands before scanning for pests.
     * Used as a fallback default when {@link FarmingConfig#pestKillerAfterTeleportDelay}
     * is not available (e.g. config is null).
     */
    private static final long SCAN_WAIT_MS = 500;

    /**
     * How long (ms) to poll for pest entities after teleporting before giving
     * up and returning to the farm.  Allows the server time to render mob data.
     */
    private static final long SCAN_TIMEOUT_MS = 3000;

    /**
     * Scan timeout (ms) used after at least one pest has been killed on the
     * current plot.  Raised from 1 s to 2.5 s so that the last remaining pest,
     * which may temporarily fall outside the client's entity-render range while
     * the player is flying, is not missed due to an overly short scan window.
     */
    private static final long SCAN_TIMEOUT_AFTER_KILL_MS = 2500;

    /** Fallback kill radius (blocks) used when the config value is not set. */
    private static final double KILL_RADIUS = 5.0;

    /**
     * Kill ranges (blocks) for each known Hypixel vacuum type, ordered from
     * most-specific to least-specific so that the first matching substring wins.
     * Values mirror the actual in-game vacuum ranges.
     *
     * <p>Keys are matched case-insensitively against the stripped item display
     * name via {@link String#contains}.  The more specific
     * {@code "infiniVacuum™ Hooverius"} entry must appear before the shorter
     * {@code "infiniVacuum"} entry so that the 15-block variant is recognised
     * correctly; the ™ character is part of the actual in-game item name.
     */
    private static final Map<String, Double> VACUUM_RANGES = new LinkedHashMap<>();
    static {
        VACUUM_RANGES.put("infinivacuum™ hooverius", 15.0);
        VACUUM_RANGES.put("infinivacuum",            12.5);
        VACUUM_RANGES.put("hyper vacuum",            10.0);
        VACUUM_RANGES.put("turbo vacuum",             7.5);
        VACUUM_RANGES.put("skymart vacuum",           5.0);
    }

    /** Distance (blocks) at which the player starts slowing down near the pest. */
    private static final double BRAKE_RADIUS = 10.0;

    /** Pre-compiled pattern for stripping Minecraft/Hypixel colour-code sequences (§X). */
    private static final Pattern COLOR_CODE_PATTERN = Pattern.compile("§.");

    /**
     * Substrings (lower-case) present in the display name of every Hypixel SkyBlock
     * farming tool.  Used by {@link #isFarmingTool(ItemStack)} to distinguish a
     * farming tool from other hotbar items (potions, swords, vacuums, etc.).
     *
     * <p>Covered tools include:
     * <ul>
     *   <li>All hoes – Rookie Hoe, Euclid's Wheat Hoe, Gauss Carrot Hoe,
     *       Pythagorean Potato Hoe, Turing Sugar Cane Hoe, Newton Nether Wart Hoe,
     *       Eclipse Hoe, Wild Rose Hoe, etc.</li>
     *   <li>Dicers – Pumpkin Dicer, Melon Dicer.</li>
     *   <li>Cocoa Chopper.</li>
     *   <li>Fungi Cutter.</li>
     *   <li>Cactus Knife.</li>
     * </ul>
     */
    private static final Set<String> FARMING_TOOL_KEYWORDS = Set.of(
            "hoe", "dicer", "chopper", "cutter", "cactus knife");

    /**
     * Returns the stripped display name of {@code stack} (colour codes removed,
     * leading/trailing whitespace trimmed).
     *
     * @param stack the hotbar item to inspect; must not be {@code null}
     * @return cleaned display name, never {@code null}
     */
    public static String getCleanItemName(ItemStack stack) {
        return COLOR_CODE_PATTERN.matcher(stack.getName().getString()).replaceAll("").trim();
    }

    /**
     * Returns {@code true} if {@code stack} is a Hypixel SkyBlock farming tool,
     * detected by matching the item's stripped display name against
     * {@link #FARMING_TOOL_KEYWORDS}.
     *
     * @param stack the hotbar item to inspect; must not be {@code null}
     * @return {@code true} when the item name contains a known farming tool keyword
     */
    public static boolean isFarmingTool(ItemStack stack) {
        if (stack.isEmpty()) return false;
        String cleanName = getCleanItemName(stack).toLowerCase();
        for (String keyword : FARMING_TOOL_KEYWORDS) {
            if (cleanName.contains(keyword)) return true;
        }
        return false;
    }

    /**
     * Camera rotation speed (degrees per second) used when pointing the camera
     * at a pest entity.  Running at render frequency (~60 FPS) produces fine
     * steps of ~1.5° per frame while keeping total angular speed human-like.
     */
    private static final float SMOOTH_LOOK_DEGREES_PER_SECOND = 180.0f;

    /** Initial assumed elapsed time for the first smooth-look step (ms). */
    private static final float SMOOTH_LOOK_INITIAL_DELTA_MS = 50.0f;

    /** Maximum elapsed time used in smooth-look calculations to cap lag spikes (ms). */
    private static final float SMOOTH_LOOK_MAX_DELTA_MS = 250.0f;

    /** Random tremor amplitude (degrees) added to each camera rotation step. */
    private static final float SMOOTH_LOOK_TREMOR_AMPLITUDE = 0.12f;

    /** Scale factor for the pitch component of the camera tremor. */
    private static final float SMOOTH_LOOK_TREMOR_PITCH_SCALE = 0.5f;

    /**
     * Maximum angular error (degrees) the camera can be off-target before
     * the forward key is suppressed to avoid flying in the wrong direction.
     */
    private static final float MAX_FLY_YAW_ERROR_DEGREES = 30f;

    /** Pause after {@code /warp garden} to allow the command to register (ms). */
    private static final long WARP_COMMAND_WAIT_MS = 3000;

    /**
     * Y coordinate (blocks) to target when flying to a plot centre.
     * 80 is a comfortable height above most crops while still being within
     * entity-scan range of ground-level pests.
     */
    private static final double PLOT_CENTRE_Y = 80.0;

    /**
     * Horizontal distance (blocks) from the computed plot centre at which the
     * {@link State#GOING_TO_PLOT_CENTER} state is considered complete.
     */
    private static final double PLOT_CENTRE_ARRIVE_RADIUS = 8.0;

    /**
     * Maximum time (ms) to spend flying to the plot centre before giving up
     * and falling back to a direct scan.
     */
    private static final long GOING_TO_PLOT_CENTRE_TIMEOUT_MS = 12_000;

    /**
     * How long (ms) to hold the attack key when firing the vacuum shot.
     * Minecraft registers a single click within this window.
     */
    private static final long VACUUM_SHOT_FIRE_MS = 100L;

    /**
     * Maximum time (ms) to wait for ANGRY_VILLAGER particle data after firing
     * the vacuum shot before giving up and moving to the next plot.
     */
    private static final long VACUUM_SHOT_WAIT_MS = 4_000;

    /**
     * Maximum time (ms) to spend following the particle trail before
     * re-scanning for pest entities.
     */
    private static final long FOLLOWING_PARTICLES_TIMEOUT_MS = 8_000;

    // ── State machine ────────────────────────────────────────────────────────

    /** Internal states of the pest killer routine. */
    public enum State {
        /** Not doing anything. */
        IDLE,
        /** Waiting before sending the plot teleport command. */
        PRE_TELEPORT_WAIT,
        /** Waiting for the server to teleport the player. */
        TELEPORTING,
        /** Flying to the centre of the current infested plot. */
        GOING_TO_PLOT_CENTER,
        /** Looking for pest entities near the player. */
        SCANNING,
        /** Left-clicked vacuum; waiting for particle-trail data. */
        VACUUM_SHOT,
        /** Following the particle trail direction toward the pest. */
        FOLLOWING_PARTICLES,
        /** Flying toward the current target pest. */
        FLYING_TO_PEST,
        /** Close enough to pest; right-clicking with the vacuum to kill it. */
        KILLING_PEST,
        /** Sent {@code /warp garden}; brief pause before handing back to the macro. */
        RETURNING,
        /** Routine finished; caller can restart the farming macro. */
        DONE,
    }

    private State state = State.IDLE;
    private long  stateEnteredAt = 0;
    /** Computed wait before sending the teleport command (pestKillerTeleportDelay + random). */
    private long  preTeleportWaitMs = 0;
    /** Fixed scan wait after teleporting (SCAN_WAIT_MS + random), set when entering TELEPORTING. */
    private long  teleportWaitMs = SCAN_WAIT_MS;
    /** Timestamp (ms) when /warp garden was sent in RETURNING; 0 = not yet sent. */
    private long  returnWarpSentAt = 0;
    /** Per-state random extra (0–150 ms) added to hardcoded timing constants. */
    private int   randomExtra = 0;

    /**
     * Queue of infested plot names still to be visited this run.  Populated by
     * {@link #start(Collection)} and consumed one-by-one in the SCANNING state
     * when no pests are found on the current plot.  The first plot is removed
     * from the queue before TELEPORTING is entered; each subsequent entry is
     * dequeued when the scan for the previous plot times out with no pests.
     */
    private final Queue<String> remainingPlots = new LinkedList<>();

    // Target pest
    private PestEntityDetector.PestEntity currentPest = null;

    // Camera rotation
    private float targetYaw   = 0f;
    private float targetPitch = 0f;
    /** Wall-clock time (ms) of the last smooth-look call; 0 = not yet called. */
    private long  lastSmoothLookTime = 0;

    // Humanised camera-aim drift (KILLING_PEST state)
    /** Current camera-aim offset applied on top of the pest's actual position. */
    private Vec3d pestAimOffset       = Vec3d.ZERO;
    /** Target offset toward which {@link #pestAimOffset} smoothly interpolates. */
    private Vec3d pestAimOffsetTarget = Vec3d.ZERO;
    /** Wall-clock time (ms) when the next drift-target update should occur; 0 = immediate. */
    private long  pestAimOffsetUpdateTime = 0;

    // Stuck detection (used by flyToward to recover when the player stops making progress)
    private Vec3d lastProgressPos     = null;
    private long  lastProgressCheckTime = 0;
    /** Current strafe direction used during an unstuck manoeuvre: +1 = right, -1 = left, 0 = none. */
    private int   strafeDirection      = 0;
    /** Wall-clock time (ms) at which the current strafe manoeuvre ends; 0 = inactive. */
    private long  strafeEndTime        = 0;

    // Double-jump flight activation
    /**
     * Wall-clock time (ms) when the current double-jump sequence started; 0 = inactive.
     * Set to 0 once the player is confirmed flying or the sequence completes.
     */
    private long doubleJumpStartTime = 0;

    // Ceiling avoidance
    /**
     * Current phase of the ceiling-avoidance manoeuvre:
     * 0 = none, 1 = backing up while rising, 2 = rising above the ceiling.
     */
    private int  ceilingAvoidPhase     = 0;
    /** Wall-clock time (ms) when the current ceiling-avoidance phase started; 0 = inactive. */
    private long ceilingAvoidStartTime = 0;

    /** How often (ms) to check whether the player is stuck. */
    private static final long   STUCK_CHECK_INTERVAL_MS      = 1500;
    /** Minimum distance (blocks) the player must travel per check interval to be considered "un-stuck". */
    private static final double MIN_PROGRESS_PER_INTERVAL    = 0.5;
    /** How long (ms) to hold the strafe key when an unstuck manoeuvre is triggered. */
    private static final long   STRAFE_DURATION_MS           = 600;
    /** Minimum distance to the target (blocks) below which stuck detection is skipped. */
    private static final double MIN_STUCK_CHECK_DIST         = 2.0;

    // ── Humanised pest-aim drift ─────────────────────────────────────────────

    /**
     * Maximum radius (blocks) of the camera-aim drift applied during
     * {@link State#KILLING_PEST}.  The camera aims at a point within this sphere
     * around the pest rather than locking exactly on it, producing more natural
     * looking camera movement.
     */
    private static final double PEST_AIM_DRIFT_RADIUS     = 1.5;

    /**
     * Base interval (ms) between drift-target updates during
     * {@link State#KILLING_PEST}.  The actual interval also adds a small random
     * component to prevent a perfectly periodic pattern.
     */
    private static final long   PEST_AIM_DRIFT_UPDATE_MS  = 600L;

    /**
     * Maximum additional random jitter (ms) added to {@link #PEST_AIM_DRIFT_UPDATE_MS}
     * so the drift-target updates never fall into a detectable fixed rhythm.
     */
    private static final int    PEST_AIM_DRIFT_JITTER_MS  = 300;

    /**
     * Lerp rate (0–1) applied each tick to smoothly move {@code pestAimOffset}
     * toward {@code pestAimOffsetTarget}.  At 8 % per tick the offset converges
     * to within 1 % of the target in approximately 55 ticks (~2.75 s at 20 TPS),
     * producing a gentle, organic-looking camera drift rather than sudden jumps.
     */
    private static final double PEST_AIM_LERP_RATE        = 0.08;

    // ── Double-jump (flight activation) ─────────────────────────────────────

    /**
     * Duration (ms) of each jump-key press in the double-jump sequence used to
     * activate creative-style flight on Hypixel SkyBlock.
     */
    private static final long DOUBLE_JUMP_PRESS_MS = 100L;

    /**
     * Gap (ms) between the two jump-key presses in the double-jump sequence.
     * A short but non-zero release window is required so the server registers
     * two distinct key-press events rather than a single held press.
     */
    private static final long DOUBLE_JUMP_GAP_MS = 60L;

    /**
     * Additional wait (ms) after the second jump-press of the double-jump sequence
     * before the sequence is reset and can be retried.  Gives the game time to
     * process the key events and update {@code player.getAbilities().flying} before
     * another sequence begins.
     */
    private static final long DOUBLE_JUMP_COMPLETION_WAIT_MS = 200L;

    // ── Ceiling avoidance ────────────────────────────────────────────────────

    /**
     * Number of blocks above the player's head (feet Y + 2) to scan for a
     * ceiling that would block upward flight toward the pest.
     */
    private static final int  CEILING_SCAN_HEIGHT         = 3;

    /**
     * How long (ms) to hold the back key while rising during the ceiling-avoidance
     * manoeuvre (phase 1: back up + rise).
     */
    private static final long CEILING_BACKUP_MS           = 600L;

    /**
     * Maximum time (ms) to spend rising in phase 2 of ceiling avoidance before
     * giving up and resuming normal pathfinding.
     */
    private static final long CEILING_RISE_TIMEOUT_MS     = 2500L;

    // Hotbar state
    /** Hotbar slot of the vacuum item; -1 if not found. */
    private int vacuumSlot = -1;
    /** Hotbar slot of the farming tool detected at routine start; -1 if unknown. */
    private int preVacuumSlot = -1;
    /**
     * Wall-clock timestamp (ms) after which post-kill scan may begin.
     * Set to {@code now + pestKillerGoToNextPestDelay} each time a pest is
     * killed so there is a short, configurable gap before flying to the next one.
     */
    private long pestKillWaitEnd = 0;
    /**
     * Kill range (blocks) automatically detected from the equipped vacuum's
     * display name.  {@code -1} means no vacuum has been detected yet; the
     * effective range will fall back to {@link FarmingConfig#pestKillerVacuumRange}
     * or {@link #KILL_RADIUS}.
     */
    private double detectedVacuumRange = -1.0;

    // ── Plot-centre navigation ───────────────────────────────────────────────

    /** Name of the plot currently being visited (e.g. "4"), or {@code null}. */
    private String currentPlotName = null;

    /**
     * World-space target for {@link State#GOING_TO_PLOT_CENTER}: the centre of
     * {@link #currentPlotName} at {@link #PLOT_CENTRE_Y}.  {@code null} when
     * the plot name is unknown or has no registered centre.
     */
    private Vec3d plotCentreTarget = null;

    // ── Vacuum-shot particle tracking ────────────────────────────────────────

    /**
     * Waypoint computed from the particle trail after a vacuum shot; the mod
     * flies toward this point in {@link State#FOLLOWING_PARTICLES}.
     * {@code null} when no valid trail has been captured.
     */
    private Vec3d particleWaypoint = null;

    /**
     * {@code true} once the vacuum shot (attack-key press) has been fired in
     * the current {@link State#VACUUM_SHOT} run so we don't fire twice.
     */
    private boolean vacuumShotFired = false;

    /**
     * {@code true} after one full vacuum-shot attempt has already been made for
     * the current plot.  Prevents an infinite SCANNING → VACUUM_SHOT loop.
     * Also set to {@code true} when at least one pest is killed via direct
     * entity tracking, so a vacuum shot is not wasted after clearing the plot.
     */
    private boolean vacuumShotAttempted = false;

    /**
     * {@code true} once at least one pest has been killed on the current plot
     * via direct entity tracking.  Used to apply a shorter scan timeout after
     * clearing a plot so the macro moves quickly to the next infested plot
     * instead of waiting the full {@link #SCAN_TIMEOUT_MS}.
     */
    private boolean atLeastOnePestKilledThisPlot = false;

    /** Shared particle tracker singleton used for vacuum-shot guidance. */
    private final VacuumParticleTracker vacuumParticleTracker = VacuumParticleTracker.getInstance();

    private final MinecraftClient client;
    private FarmingConfig config;
    private final PestEntityDetector pestEntityDetector;
    private final Random random = new Random();

    // ── Constructor ──────────────────────────────────────────────────────────

    public PestKillerManager(MinecraftClient client, FarmingConfig config,
                             PestEntityDetector pestEntityDetector) {
        this.client = client;
        this.config = config;
        this.pestEntityDetector = pestEntityDetector;
    }

    /** Update the config reference (called after GUI saves). */
    public void setConfig(FarmingConfig config) {
        this.config = config;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /** Returns {@code true} while the pest killer routine is running. */
    public boolean isActive() {
        return state != State.IDLE && state != State.DONE;
    }

    /** Returns {@code true} once the routine finishes. */
    public boolean isDone() {
        return state == State.DONE;
    }

    /** Returns the current internal state (for status display). */
    public State getState() {
        return state;
    }

    /**
     * Resets the manager back to {@link State#IDLE} so it can be triggered again.
     * Call after {@link #isDone()} returns {@code true}.
     */
    public void reset() {
        enterState(State.IDLE);
        restoreHotbarSlot();
        releaseMovementKeys();
        doubleJumpStartTime = 0;
        ceilingAvoidPhase = 0;
        ceilingAvoidStartTime = 0;
        remainingPlots.clear();
        detectedVacuumRange = -1.0;
        pestKillWaitEnd = 0;
        pestAimOffset = Vec3d.ZERO;
        pestAimOffsetTarget = Vec3d.ZERO;
        pestAimOffsetUpdateTime = 0;
        resetPlotState();
    }

    /**
     * Stop the pest killer immediately and release all held keys.
     * Restores the hotbar slot to its pre-vacuum state.
     */
    public void stop() {
        if (state == State.IDLE || state == State.DONE) return;
        LOGGER.info("[JustFarming-PestKiller] Stopped.");
        releaseMovementKeys();
        restoreHotbarSlot();
        vacuumParticleTracker.stopTracking();
        doubleJumpStartTime = 0;
        ceilingAvoidPhase = 0;
        ceilingAvoidStartTime = 0;
        pestKillWaitEnd = 0;
        remainingPlots.clear();
        detectedVacuumRange = -1.0;
        pestAimOffset = Vec3d.ZERO;
        pestAimOffsetTarget = Vec3d.ZERO;
        pestAimOffsetUpdateTime = 0;
        resetPlotState();
        enterState(State.IDLE);
    }

    /**
     * Start the pest killer routine targeting a single infested plot.
     *
     * <p>Convenience overload for {@link #start(Collection)}; equivalent to
     * {@code start(pestPlotName == null ? List.of() : List.of(pestPlotName))}.
     *
     * @param pestPlotName the name of the plot with pests (e.g. {@code "4"}), or
     *                     {@code null} to warp to the garden without a plot target.
     */
    public void start(String pestPlotName) {
        if (pestPlotName != null && !pestPlotName.isBlank()) {
            start(List.of(pestPlotName));
        } else {
            start(List.of());
        }
    }

    /**
     * Start the pest killer routine, visiting each infested plot in order.
     *
     * <p>The routine teleports to the first plot in {@code pestPlots}, scans for
     * pests and kills them.  When the scan for one plot times out with no pests
     * found, it teleports to the next plot in the collection instead of returning
     * to the farm immediately.  Only after all plots have been visited (and no
     * pests remain) does the routine send {@code /warp garden} and finish.
     *
     * @param pestPlots ordered collection of plot names with pests
     *                  (e.g. {@code ["4", "12"]}).  May be empty, in which case
     *                  the routine warps to the garden and scans there.
     */
    public void start(Collection<String> pestPlots) {
        LOGGER.info("[JustFarming-PestKiller] Starting pest killer routine.");
        currentPest = null;
        vacuumSlot = -1;
        preVacuumSlot = -1;
        pestKillWaitEnd = 0;
        lastSmoothLookTime = 0;
        returnWarpSentAt = 0;
        resetPlotState();

        remainingPlots.clear();
        if (pestPlots != null) {
            // Sort plots numerically so the visit order is predictable.
            java.util.List<String> sorted = new java.util.ArrayList<>();
            for (String p : pestPlots) {
                if (p != null && !p.isBlank()) sorted.add(p);
            }
            sorted.sort((a, b) -> {
                try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); }
                catch (NumberFormatException e) {
                    LOGGER.warn("[JustFarming-PestKiller] Non-numeric plot name '{}' or '{}'; using string sort.", a, b);
                    return a.compareTo(b);
                }
            });
            remainingPlots.addAll(sorted);
        }

        String firstPlot = remainingPlots.poll(); // remove and use the first plot
        currentPlotName = firstPlot;

        long base = config != null && config.pestKillerTeleportDelay > 0
                ? config.pestKillerTeleportDelay : 0;
        int globalRandom = (config != null) ? config.globalRandomizationMs : 0;
        preTeleportWaitMs = base + random.nextInt(Math.max(1, globalRandom));

        enterState(State.PRE_TELEPORT_WAIT);
    }

    /**
     * Called every render frame to apply incremental camera rotation toward
     * {@link #targetYaw}/{@link #targetPitch} while actively flying or killing.
     */
    public void onRenderTick() {
        if (state != State.FLYING_TO_PEST
                && state != State.KILLING_PEST
                && state != State.GOING_TO_PLOT_CENTER
                && state != State.FOLLOWING_PARTICLES) return;
        ClientPlayerEntity player = client.player;
        if (player == null) return;
        smoothRotateCamera(player);
    }

    /** Called every client tick from the main tick event. */
    public void onTick() {
        if (state == State.IDLE || state == State.DONE) return;

        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        long now = System.currentTimeMillis();

        switch (state) {

            case PRE_TELEPORT_WAIT -> {
                if (now - stateEnteredAt >= preTeleportWaitMs) {
                    // Pre-teleport delay elapsed: now send the warp command.
                    if (config != null && config.pestKillerWarpToPlot && currentPlotName != null) {
                        sendCommand("tptoplot " + currentPlotName);
                        LOGGER.info("[JustFarming-PestKiller] Sent /tptoplot {} to reach infested plot.", currentPlotName);
                    } else {
                        sendCommand("warp garden");
                        LOGGER.info("[JustFarming-PestKiller] Sent /warp garden to reach garden.");
                    }
                    // Configurable post-teleport wait (pestKillerAfterTeleportDelay)
                    // before scanning for pest entities; global randomization is added.
                    int afterTpDelay = (config != null && config.pestKillerAfterTeleportDelay >= 0)
                            ? config.pestKillerAfterTeleportDelay : (int) SCAN_WAIT_MS;
                    int globalRandom = (config != null) ? config.globalRandomizationMs : 0;
                    teleportWaitMs = afterTpDelay + random.nextInt(Math.max(1, globalRandom));
                    enterState(State.TELEPORTING);
                }
            }

            case TELEPORTING -> {
                if (now - stateEnteredAt >= teleportWaitMs) {
                    // After teleporting, fly to the plot centre to maximise the scan
                    // radius before looking for pest entities.
                    if (currentPlotName != null) {
                        double cx = GardenPlot.getCentreX(currentPlotName);
                        double cz = GardenPlot.getCentreZ(currentPlotName);
                        if (!Double.isNaN(cx) && !Double.isNaN(cz)) {
                            plotCentreTarget = new Vec3d(cx, PLOT_CENTRE_Y, cz);
                            LOGGER.info("[JustFarming-PestKiller] Teleport wait elapsed; "
                                    + "flying to plot {} centre ({}, {}, {}).",
                                    currentPlotName, (int) cx, (int) PLOT_CENTRE_Y, (int) cz);
                            enterState(State.GOING_TO_PLOT_CENTER);
                            return;
                        }
                    }
                    // No plot name or unknown plot; fall back to scanning in place.
                    LOGGER.info("[JustFarming-PestKiller] Teleport wait elapsed; scanning for pests.");
                    enterState(State.SCANNING);
                }
            }

            case GOING_TO_PLOT_CENTER -> {
                // If pests are already visible, skip flying to the centre.
                List<PestEntityDetector.PestEntity> pestsAtCentre = pestEntityDetector.getDetectedPests();
                if (!pestsAtCentre.isEmpty()) {
                    currentPest = pickNearestPest(player, pestsAtCentre);
                    if (currentPest != null) {
                        LOGGER.info("[JustFarming-PestKiller] Pest detected while flying to centre; "
                                + "targeting directly.");
                        releaseMovementKeys();
                        enterState(State.FLYING_TO_PEST);
                        return;
                    }
                }

                if (plotCentreTarget == null) {
                    enterState(State.SCANNING);
                    return;
                }

                // Check horizontal distance only (ignore Y) so we don't overshoot
                // vertically into a ceiling.
                double dx = player.getX() - plotCentreTarget.x;
                double dz = player.getZ() - plotCentreTarget.z;
                double horizDist = Math.sqrt(dx * dx + dz * dz);
                if (horizDist <= PLOT_CENTRE_ARRIVE_RADIUS) {
                    LOGGER.info("[JustFarming-PestKiller] Arrived at plot centre; scanning.");
                    releaseMovementKeys();
                    enterState(State.SCANNING);
                    return;
                }

                // Timeout – give up flying and scan from wherever we are.
                if (now - stateEnteredAt >= GOING_TO_PLOT_CENTRE_TIMEOUT_MS) {
                    LOGGER.info("[JustFarming-PestKiller] Timed out flying to plot centre; scanning.");
                    releaseMovementKeys();
                    enterState(State.SCANNING);
                    return;
                }

                flyToward(player, plotCentreTarget);
            }

            case SCANNING -> {
                // Honour the post-kill wait before starting to scan for the next pest.
                if (now < pestKillWaitEnd) return;

                List<PestEntityDetector.PestEntity> pests = pestEntityDetector.getDetectedPests();
                if (!pests.isEmpty()) {
                    currentPest = pickNearestPest(player, pests);
                    if (currentPest != null) {
                        LOGGER.info("[JustFarming-PestKiller] Found {} pest(s). Targeting: {} at {}.",
                                pests.size(), currentPest.displayName(), currentPest.position());
                        enterState(State.FLYING_TO_PEST);
                    }
                } else if (now - stateEnteredAt >= (atLeastOnePestKilledThisPlot
                        ? SCAN_TIMEOUT_AFTER_KILL_MS : SCAN_TIMEOUT_MS)) {
                    // No pests found at centre; try a vacuum shot to locate them via
                    // the particle trail (only one attempt per plot).
                    if (!vacuumShotAttempted && currentPlotName != null) {
                        LOGGER.info("[JustFarming-PestKiller] No pests at plot centre; "
                                + "firing vacuum shot to locate them.");
                        vacuumShotAttempted = true;
                        vacuumShotFired = false;
                        vacuumParticleTracker.stopTracking();
                        vacuumParticleTracker.reset();
                        vacuumParticleTracker.startTracking(player.getEyePos());
                        enterState(State.VACUUM_SHOT);
                    } else if (!remainingPlots.isEmpty()) {
                        // Pests on this plot have been cleared; move on to the next
                        // infested plot rather than returning to the farm immediately.
                        teleportToNextPlot(remainingPlots.poll());
                    } else {
                        LOGGER.info("[JustFarming-PestKiller] No pests found after scanning all plots; "
                                + "returning to farm.");
                        returnToFarm();
                    }
                }
            }

            case VACUUM_SHOT -> {
                // Check if a pest has become visible while we wait for particles.
                List<PestEntityDetector.PestEntity> pestsVS = pestEntityDetector.getDetectedPests();
                if (!pestsVS.isEmpty()) {
                    currentPest = pickNearestPest(player, pestsVS);
                    if (currentPest != null) {
                        LOGGER.info("[JustFarming-PestKiller] Pest detected after vacuum shot; targeting.");
                        client.options.attackKey.setPressed(false);
                        vacuumParticleTracker.stopTracking();
                        enterState(State.FLYING_TO_PEST);
                        return;
                    }
                }

                long elapsed = now - stateEnteredAt;

                // Equip the vacuum and fire a single left-click during the first
                // VACUUM_SHOT_FIRE_MS window.
                if (!vacuumShotFired) {
                    findAndEquipVacuum(player);
                    if (vacuumSlot < 0) {
                        LOGGER.warn("[JustFarming-PestKiller] No vacuum in hotbar; skipping vacuum shot.");
                        vacuumParticleTracker.stopTracking();
                        // Try next plot or return.
                        if (!remainingPlots.isEmpty()) {
                            teleportToNextPlot(remainingPlots.poll());
                        } else {
                            returnToFarm();
                        }
                        return;
                    }
                    if (player.getInventory().getSelectedSlot() != vacuumSlot) {
                        player.getInventory().setSelectedSlot(vacuumSlot);
                    }
                    client.options.attackKey.setPressed(true);
                    vacuumShotFired = true;
                    LOGGER.info("[JustFarming-PestKiller] Fired vacuum shot to locate pest.");
                } else if (elapsed >= VACUUM_SHOT_FIRE_MS) {
                    // Release the attack key after the brief hold.
                    client.options.attackKey.setPressed(false);
                }

                // Wait for the particle trail to accumulate, then follow it.
                if (elapsed >= VACUUM_SHOT_WAIT_MS) {
                    client.options.attackKey.setPressed(false);
                    vacuumParticleTracker.stopTracking();
                    Vec3d waypoint = vacuumParticleTracker.getWaypoint();
                    if (waypoint != null) {
                        LOGGER.info("[JustFarming-PestKiller] Particle trail detected; "
                                + "following to ({}, {}, {}).",
                                String.format("%.1f", waypoint.x),
                                String.format("%.1f", waypoint.y),
                                String.format("%.1f", waypoint.z));
                        particleWaypoint = waypoint;
                        enterState(State.FOLLOWING_PARTICLES);
                    } else {
                        LOGGER.info("[JustFarming-PestKiller] No particle trail; trying next plot.");
                        if (!remainingPlots.isEmpty()) {
                            teleportToNextPlot(remainingPlots.poll());
                        } else {
                            returnToFarm();
                        }
                    }
                }
            }

            case FOLLOWING_PARTICLES -> {
                // Check whether any pest is now visible.
                List<PestEntityDetector.PestEntity> pestsFP = pestEntityDetector.getDetectedPests();
                if (!pestsFP.isEmpty()) {
                    currentPest = pickNearestPest(player, pestsFP);
                    if (currentPest != null) {
                        LOGGER.info("[JustFarming-PestKiller] Pest detected while following particles; "
                                + "targeting directly.");
                        releaseMovementKeys();
                        enterState(State.FLYING_TO_PEST);
                        return;
                    }
                }

                if (particleWaypoint == null) {
                    releaseMovementKeys();
                    enterState(State.SCANNING);
                    return;
                }

                double distWP = new Vec3d(player.getX(), player.getY(), player.getZ()).distanceTo(particleWaypoint);
                if (distWP <= getEffectiveKillRadius()) {
                    // Arrived near the waypoint; switch to scanning to find the pest.
                    LOGGER.info("[JustFarming-PestKiller] Reached particle-trail waypoint; re-scanning.");
                    releaseMovementKeys();
                    enterState(State.SCANNING);
                    return;
                }

                if (now - stateEnteredAt >= FOLLOWING_PARTICLES_TIMEOUT_MS) {
                    LOGGER.info("[JustFarming-PestKiller] Particle-trail follow timed out.");
                    releaseMovementKeys();
                    if (!remainingPlots.isEmpty()) {
                        teleportToNextPlot(remainingPlots.poll());
                    } else {
                        returnToFarm();
                    }
                    return;
                }

                flyToward(player, particleWaypoint);
            }

            case FLYING_TO_PEST -> {
                List<PestEntityDetector.PestEntity> pests = pestEntityDetector.getDetectedPests();
                // Re-pick the nearest pest every tick in case the previous one was killed
                currentPest = pickNearestPest(player, pests);
                if (currentPest == null) {
                    // No more pests in detection range; scan for any remaining
                    LOGGER.info("[JustFarming-PestKiller] Target pest gone; scanning for remaining pests.");
                    releaseMovementKeys();
                    enterState(State.SCANNING);
                    return;
                }
                Vec3d pestPos = currentPest.position();
                double dist = player.getEyePos().distanceTo(pestPos);
                if (dist <= getEffectiveKillRadius()) {
                    releaseMovementKeys();
                    // Find vacuum before entering kill state
                    findAndEquipVacuum(player);
                    // Reset drift state so the aim wanders freely from the first tick.
                    pestAimOffset       = Vec3d.ZERO;
                    pestAimOffsetTarget = Vec3d.ZERO;
                    pestAimOffsetUpdateTime = 0;
                    enterState(State.KILLING_PEST);
                } else {
                    flyToward(player, pestPos);
                }
            }

            case KILLING_PEST -> {
                List<PestEntityDetector.PestEntity> pests = pestEntityDetector.getDetectedPests();
                // Keep targeting the nearest pest
                currentPest = pickNearestPest(player, pests);
                if (currentPest == null) {
                    // Pest was killed; brief configurable pause before scanning for more.
                    LOGGER.info("[JustFarming-PestKiller] Pest killed; scanning for remaining pests.");
                    releaseMovementKeys();
                    int goNextDelay = (config != null) ? config.pestKillerGoToNextPestDelay : 0;
                    pestKillWaitEnd = (goNextDelay > 0) ? now + goNextDelay : 0;
                    // Mark that at least one pest was killed on this plot so the scanner
                    // uses a shorter timeout before moving to the next plot.
                    atLeastOnePestKilledThisPlot = true;
                    // Skip vacuum shot for this plot: pests were found via direct tracking,
                    // so a shot is unnecessary. vacuumShotAttempted is reset in resetPlotState()
                    // when the next plot begins.
                    vacuumShotAttempted = true;
                    enterState(State.SCANNING);
                    return;
                }

                Vec3d pestPos = currentPest.position();
                double dist = player.getEyePos().distanceTo(pestPos);
                // If the pest moved out of kill range, fly toward it again
                if (dist > getEffectiveKillRadius() * 1.5) {
                    if (client.options != null) client.options.useKey.setPressed(false);
                    pestAimOffsetUpdateTime = 0; // reset drift on next approach
                    enterState(State.FLYING_TO_PEST);
                    return;
                }

                // Humanised camera aim: keep the aim point within PEST_AIM_DRIFT_RADIUS of
                // the pest rather than locking exactly onto it.  Periodically pick a new
                // random target offset and smoothly interpolate toward it so the camera
                // drifts naturally without any sudden jumps.
                if (now >= pestAimOffsetUpdateTime) {
                    // Pick a new random target inside the drift sphere using spherical coords.
                    double elevation = (random.nextDouble() - 0.5) * Math.PI;
                    double azimuth   = random.nextDouble() * 2.0 * Math.PI;
                    double radius    = random.nextDouble() * PEST_AIM_DRIFT_RADIUS;
                    pestAimOffsetTarget = new Vec3d(
                            Math.cos(elevation) * Math.cos(azimuth) * radius,
                            Math.sin(elevation) * radius,
                            Math.cos(elevation) * Math.sin(azimuth) * radius);
                    pestAimOffsetUpdateTime = now + PEST_AIM_DRIFT_UPDATE_MS + random.nextInt(PEST_AIM_DRIFT_JITTER_MS);
                }
                // Smoothly lerp the current offset toward the target (8 % per tick ≈ ~40 ticks to converge).
                pestAimOffset = new Vec3d(
                        pestAimOffset.x + (pestAimOffsetTarget.x - pestAimOffset.x) * PEST_AIM_LERP_RATE,
                        pestAimOffset.y + (pestAimOffsetTarget.y - pestAimOffset.y) * PEST_AIM_LERP_RATE,
                        pestAimOffset.z + (pestAimOffsetTarget.z - pestAimOffset.z) * PEST_AIM_LERP_RATE);

                lookAt(player, new Vec3d(
                        pestPos.x + pestAimOffset.x,
                        pestPos.y + pestAimOffset.y,
                        pestPos.z + pestAimOffset.z));

                // Ensure we're still holding the vacuum
                if (vacuumSlot >= 0 && player.getInventory().getSelectedSlot() != vacuumSlot) {
                    player.getInventory().setSelectedSlot(vacuumSlot);
                }

                // Hold the use key (right-click) while aimed at pest.
                // Continue until the pest disappears from the entity list.
                if (client.options != null) {
                    client.options.useKey.setPressed(true);
                }
            }

            case RETURNING -> {
                if (returnWarpSentAt == 0) {
                    sendCommand("warp garden");
                    returnWarpSentAt = now;
                    LOGGER.info("[JustFarming-PestKiller] Sent /warp garden after pest kill.");
                } else if (now - returnWarpSentAt >= WARP_COMMAND_WAIT_MS + randomExtra) {
                    enterState(State.DONE);
                }
            }

            default -> {}
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void enterState(State next) {
        state = next;
        stateEnteredAt = System.currentTimeMillis();
        int globalRandom = (config != null) ? config.globalRandomizationMs : 0;
        randomExtra = random.nextInt(Math.max(1, globalRandom));
        if (next == State.FLYING_TO_PEST || next == State.GOING_TO_PLOT_CENTER
                || next == State.FOLLOWING_PARTICLES) {
            // Reset stuck detection so each new flight attempt starts fresh
            lastProgressPos      = null;
            lastProgressCheckTime = 0;
            strafeEndTime        = 0;
            strafeDirection      = 0;
            doubleJumpStartTime  = 0;
            ceilingAvoidPhase    = 0;
            ceilingAvoidStartTime = 0;
        }
        if (next != State.IDLE && next != State.DONE) {
            LOGGER.info("[JustFarming-PestKiller] -> {}", next);
        }
    }

    /**
     * Resets per-plot state (plot name, centre target, particle data, and
     * vacuum-shot flags).  Called when starting a new plot or resetting the
     * manager entirely.
     */
    private void resetPlotState() {
        currentPlotName = null;
        plotCentreTarget = null;
        particleWaypoint = null;
        vacuumShotFired = false;
        vacuumShotAttempted = false;
        atLeastOnePestKilledThisPlot = false;
        vacuumParticleTracker.stopTracking();
        vacuumParticleTracker.reset();
    }

    /**
     * Teleports to {@code nextPlot} (or warps to the garden if
     * {@link FarmingConfig#pestKillerWarpToPlot} is false), resets per-plot
     * state, and enters {@link State#TELEPORTING}.
     */
    private void teleportToNextPlot(String nextPlot) {
        LOGGER.info("[JustFarming-PestKiller] No pests found on this plot; "
                + "teleporting to next infested plot: {}.", nextPlot);
        resetPlotState();
        currentPlotName = nextPlot;
        long base = config != null && config.pestKillerTeleportDelay > 0
                ? config.pestKillerTeleportDelay : 0;
        int globalRandom = (config != null) ? config.globalRandomizationMs : 0;
        preTeleportWaitMs = base + random.nextInt(Math.max(1, globalRandom));
        enterState(State.PRE_TELEPORT_WAIT);
    }

    /** Returns the pest entity closest to the player's eye position, or {@code null} if none. */
    private PestEntityDetector.PestEntity pickNearestPest(ClientPlayerEntity player,
                                                           List<PestEntityDetector.PestEntity> pests) {
        if (pests.isEmpty()) return null;
        Vec3d eye = player.getEyePos();
        PestEntityDetector.PestEntity nearest = null;
        double minDist = Double.MAX_VALUE;
        for (PestEntityDetector.PestEntity pest : pests) {
            double d = eye.distanceTo(pest.position());
            if (d < minDist) {
                minDist = d;
                nearest = pest;
            }
        }
        return nearest;
    }

    /**
     * Searches the hotbar for the first item whose display name contains
     * "vacuum" (case-insensitive), records the farming tool slot as
     * {@link #preVacuumSlot} (via {@link #findFarmingToolSlot}), switches to the
     * vacuum slot, and auto-detects its kill range from {@link #VACUUM_RANGES}.
     * Does nothing if no vacuum is found.
     *
     * <p>The farming tool slot is detected once per routine: if {@link #preVacuumSlot}
     * is already set from a previous call this method is a no-op (the vacuum stays
     * equipped between kills so the player's tool is not swapped back and forth for
     * every pest).  The original tool slot is only restored at the end of the full
     * pest-killer routine.
     */
    private void findAndEquipVacuum(ClientPlayerEntity player) {
        if (vacuumSlot >= 0) return; // already found and equipped

        int found = -1;
        String foundName = null;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                String cleanName = getCleanItemName(stack);
                if (cleanName.toLowerCase().contains("vacuum")) {
                    found = i;
                    foundName = cleanName;
                    break;
                }
            }
        }

        if (found < 0) {
            LOGGER.warn("[JustFarming-PestKiller] No vacuum item found in hotbar.");
            return;
        }

        // Auto-detect kill range from the vacuum item name
        if (detectedVacuumRange < 0 && foundName != null) {
            String lowerName = foundName.toLowerCase();
            for (Map.Entry<String, Double> entry : VACUUM_RANGES.entrySet()) {
                if (lowerName.contains(entry.getKey())) {
                    detectedVacuumRange = entry.getValue();
                    LOGGER.info("[JustFarming-PestKiller] Auto-detected vacuum '{}' with kill range {} blocks.",
                            foundName, detectedVacuumRange);
                    break;
                }
            }
            if (detectedVacuumRange < 0) {
                LOGGER.info("[JustFarming-PestKiller] Unknown vacuum '{}'; using configured/default range.", foundName);
            }
        }

        // Detect the farming tool slot once, before switching away from it.
        preVacuumSlot = findFarmingToolSlot(player, found);
        vacuumSlot = found;
        player.getInventory().setSelectedSlot(vacuumSlot);
        LOGGER.info("[JustFarming-PestKiller] Equipped vacuum from hotbar slot {} (farming tool at slot {}).",
                vacuumSlot, preVacuumSlot);
    }

    /**
     * Determines which hotbar slot holds the farming tool that should be active
     * while the pest-killer routine is not running.
     *
     * <p>Priority order:
     * <ol>
     *   <li>{@link FarmingConfig#farmingToolHotbarSlot} when set to a valid slot
     *       (0–8) and that slot contains an item.</li>
     *   <li>The first non-vacuum hotbar slot whose item name matches a known
     *       farming-tool keyword (see {@link #isFarmingTool}).</li>
     *   <li>The first non-vacuum, non-empty hotbar slot (legacy fallback).</li>
     *   <li>The currently selected slot as a last resort.</li>
     * </ol>
     *
     * @param player     the local player
     * @param vacuumIdx  the hotbar index of the vacuum (excluded from auto-detection)
     * @return the hotbar slot index to restore after the routine ends, or {@code -1}
     *         if none could be determined
     */
    private int findFarmingToolSlot(ClientPlayerEntity player, int vacuumIdx) {
        // 1. Honour the explicit per-crop (or global) preferred slot from config.
        if (config != null && config.farmingToolHotbarSlot >= 0
                && config.farmingToolHotbarSlot <= 8
                && !player.getInventory().getStack(config.farmingToolHotbarSlot).isEmpty()) {
            LOGGER.info("[JustFarming-PestKiller] Using configured farming tool slot {}.",
                    config.farmingToolHotbarSlot);
            return config.farmingToolHotbarSlot;
        }

        // 2. Auto-detect: first non-vacuum hotbar slot that holds a known farming tool.
        for (int i = 0; i < 9; i++) {
            if (i == vacuumIdx) continue;
            ItemStack stack = player.getInventory().getStack(i);
            if (isFarmingTool(stack)) {
                LOGGER.info("[JustFarming-PestKiller] Auto-detected farming tool '{}' at hotbar slot {}.",
                        getCleanItemName(stack), i);
                return i;
            }
        }

        // 3. Fallback: first non-vacuum, non-empty hotbar slot.
        for (int i = 0; i < 9; i++) {
            if (i == vacuumIdx) continue;
            if (!player.getInventory().getStack(i).isEmpty()) {
                LOGGER.info("[JustFarming-PestKiller] No named farming tool found; using first non-empty slot {}.", i);
                return i;
            }
        }

        // 4. Last resort: currently selected slot.
        return player.getInventory().getSelectedSlot();
    }

    /**
     * Restores the hotbar slot to {@link #preVacuumSlot} if it was changed.
     */
    private void restoreHotbarSlot() {
        ClientPlayerEntity player = client.player;
        if (player == null) return;
        if (preVacuumSlot >= 0 && vacuumSlot >= 0) {
            player.getInventory().setSelectedSlot(preVacuumSlot);
            LOGGER.info("[JustFarming-PestKiller] Restored hotbar slot {}.", preVacuumSlot);
        }
        vacuumSlot = -1;
        preVacuumSlot = -1;
    }

    /**
     * Fly one tick toward {@code target} by pointing the camera at it (including
     * pitch for vertical movement) and pressing the forward key.  In the Hypixel
     * Skyblock Garden, players have creative-style flight so forward + pitch causes
     * genuine 3D movement.
     *
     * <p>The jump key is held when the target is above the player.  The sneak key
     * is never pressed so the player always remains airborne; the camera pitches
     * downward when the target is below, and the resulting forward + pitch-down
     * motion carries the player diagonally toward the pest without risking a landing.
     *
     * <p>When the player stops making progress toward the target (stuck detection),
     * a brief left/right strafe manoeuvre is triggered to help navigate around any
     * obstacle that is blocking the direct path.
     */
    private void flyToward(ClientPlayerEntity player, Vec3d target) {
        if (client.options == null) return;

        long now = System.currentTimeMillis();

        // ── Step 1: Ensure the player is flying ──────────────────────────────
        // On Hypixel SkyBlock Garden the player has creative-style flight.
        // If they land on a surface (e.g. after hitting a wall) flight is
        // deactivated; a double-jump sequence re-activates it.
        if (!player.getAbilities().flying) {
            executeDoubleJump(player, now);
            return; // wait for flight to be active before moving
        }
        // Flight confirmed – clear the double-jump sequence tracker.
        doubleJumpStartTime = 0;

        // ── Step 2: Ceiling avoidance ─────────────────────────────────────────
        // If there is a solid block within CEILING_SCAN_HEIGHT blocks above the
        // player's head, normal upward flight is blocked.  Execute the three-phase
        // avoidance manoeuvre: back up while rising → rise clear of the ceiling →
        // resume direct pathfinding.
        if (ceilingAvoidPhase != 0 || hasCeilingAbove(player)) {
            if (ceilingAvoidPhase == 0) {
                ceilingAvoidPhase    = 1;
                ceilingAvoidStartTime = now;
                LOGGER.debug("[JustFarming-PestKiller] Ceiling detected; starting avoidance manoeuvre.");
            }
            executeCeilingAvoidance(player, now, target);
            return;
        }

        // Point camera at the target (includes pitch for vertical direction)
        lookAt(player, target);

        Vec3d eye = player.getEyePos();
        double dist = eye.distanceTo(target);

        // Suppress forward movement when camera is still rotating toward the target
        float yawError = targetYaw - player.getYaw();
        while (yawError >  180f) yawError -= 360f;
        while (yawError < -180f) yawError += 360f;

        boolean shouldFly = Math.abs(yawError) <= MAX_FLY_YAW_ERROR_DEGREES;

        // Slow down when close to the pest
        if (shouldFly && dist < BRAKE_RADIUS) {
            // Pulse forward key: move one tick out of every ~(BRAKE_RADIUS/dist) ticks
            long ticks = client.world != null ? client.world.getTime() : 0;
            int pulseStride = Math.max(1, (int) Math.ceil(BRAKE_RADIUS / Math.max(1.0, dist)));
            shouldFly = (ticks % pulseStride == 0);
        }

        // Vertical movement: jump to ascend when the pest is above.
        // The sneak key is intentionally never pressed so the player always stays
        // airborne.  When the pest is below, the camera pitches downward (set by
        // lookAt above) and the forward + pitch-down motion carries the player
        // diagonally toward the pest without risking a landing.
        double dy = target.y - eye.y;
        boolean shouldJump  = dy >  1.5;
        boolean shouldSneak = false; // never descend; camera pitch handles vertical direction

        // ── Stuck detection ─────────────────────────────────────────────────
        if (lastProgressPos == null) {
            lastProgressPos      = eye;
            lastProgressCheckTime = now;
        }
        if (now - lastProgressCheckTime >= STUCK_CHECK_INTERVAL_MS) {
            double progress = lastProgressPos.distanceTo(eye);
            if (progress < MIN_PROGRESS_PER_INTERVAL && dist > MIN_STUCK_CHECK_DIST) {
                // Player has not moved enough – alternate strafe direction and hold it briefly
                strafeDirection = (strafeDirection >= 0) ? -1 : 1;
                strafeEndTime   = now + STRAFE_DURATION_MS;
                LOGGER.debug("[JustFarming-PestKiller] Stuck (progress={} blocks); strafing {}.",
                        String.format("%.2f", progress), strafeDirection > 0 ? "right" : "left");
            }
            lastProgressPos       = eye;
            lastProgressCheckTime = now;
        }

        boolean isStrafeActive = now < strafeEndTime && strafeDirection != 0;

        // When the unstuck strafe is active and the target is above the player,
        // also press jump so the player can climb over vertical obstacles that
        // are blocking forward progress rather than simply sliding sideways.
        boolean stuckClimbNeeded = isStrafeActive && dy > 0;

        // Allow forward movement alongside strafe so the player still approaches
        // the target while the unstuck manoeuvre is active.
        client.options.forwardKey.setPressed(shouldFly);
        client.options.jumpKey.setPressed(shouldJump || stuckClimbNeeded);
        client.options.sneakKey.setPressed(shouldSneak);
        client.options.leftKey.setPressed(isStrafeActive && strafeDirection < 0);
        client.options.rightKey.setPressed(isStrafeActive && strafeDirection > 0);
        client.options.backKey.setPressed(false);
    }

    /**
     * Executes a timed double-jump sequence to re-activate creative-style flight
     * when the player has landed on a surface.
     *
     * <p>The sequence is:
     * <ol>
     *   <li>0 – {@link #DOUBLE_JUMP_PRESS_MS} ms: hold jump key (first press).</li>
     *   <li>{@code DOUBLE_JUMP_PRESS_MS} – {@code DOUBLE_JUMP_PRESS_MS + DOUBLE_JUMP_GAP_MS} ms:
     *       release the jump key so the server registers two distinct events.</li>
     *   <li>{@code DOUBLE_JUMP_PRESS_MS + DOUBLE_JUMP_GAP_MS} –
     *       {@code DOUBLE_JUMP_PRESS_MS * 2 + DOUBLE_JUMP_GAP_MS} ms:
     *       hold jump key again (second press).</li>
     *   <li>After the sequence: reset {@link #doubleJumpStartTime} so the sequence
     *       can be retried if the player is still not flying.</li>
     * </ol>
     */
    private void executeDoubleJump(ClientPlayerEntity player, long now) {
        if (doubleJumpStartTime == 0) {
            doubleJumpStartTime = now;
            LOGGER.debug("[JustFarming-PestKiller] Player not flying; initiating double-jump sequence.");
        }
        long elapsed  = now - doubleJumpStartTime;
        long phase1End = DOUBLE_JUMP_PRESS_MS;
        long phase2End = DOUBLE_JUMP_PRESS_MS + DOUBLE_JUMP_GAP_MS;
        long phase3End = DOUBLE_JUMP_PRESS_MS * 2 + DOUBLE_JUMP_GAP_MS;

        boolean jumpPressed = elapsed < phase1End
                || (elapsed >= phase2End && elapsed < phase3End);
        client.options.jumpKey.setPressed(jumpPressed);
        client.options.forwardKey.setPressed(false); // don't move while re-activating flight

        if (elapsed >= phase3End + DOUBLE_JUMP_COMPLETION_WAIT_MS) {
            // Sequence complete; reset so we retry if the player is still not flying.
            doubleJumpStartTime = 0;
            LOGGER.debug("[JustFarming-PestKiller] Double-jump sequence completed.");
        }
    }

    /**
     * Returns {@code true} if there is at least one non-air block within
     * {@link #CEILING_SCAN_HEIGHT} blocks above the player's head.
     *
     * <p>The player occupies blocks at {@code floor(Y)} (feet) and
     * {@code floor(Y)+1} (head), so the first potential ceiling block is at
     * {@code floor(Y)+2}.  Detecting a ceiling here means the player cannot
     * ascend further without hitting a solid surface.
     */
    private boolean hasCeilingAbove(ClientPlayerEntity player) {
        if (client.world == null) return false;
        int bx = (int) Math.floor(player.getX());
        int bz = (int) Math.floor(player.getZ());
        int headTopY = (int) Math.floor(player.getY()) + 2; // one block above head
        for (int dy = 0; dy < CEILING_SCAN_HEIGHT; dy++) {
            if (!client.world.getBlockState(new BlockPos(bx, headTopY + dy, bz)).isAir()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Executes the active phase of the ceiling-avoidance manoeuvre.
     *
     * <ul>
     *   <li><b>Phase 1</b>: Back up while pressing jump to rise away from
     *       under the ceiling for {@link #CEILING_BACKUP_MS} ms.</li>
     *   <li><b>Phase 2</b>: Continue rising until the ceiling clears (no longer
     *       detected by {@link #hasCeilingAbove}) or {@link #CEILING_RISE_TIMEOUT_MS}
     *       elapses.  On completion {@link #ceilingAvoidPhase} is reset to 0
     *       so normal pathfinding can resume.</li>
     * </ul>
     */
    private void executeCeilingAvoidance(ClientPlayerEntity player, long now, Vec3d target) {
        long elapsed = now - ceilingAvoidStartTime;

        if (ceilingAvoidPhase == 1) {
            // Phase 1: back up while rising to escape from under the ceiling
            lookAt(player, target); // keep tracking the pest visually
            client.options.backKey.setPressed(true);
            client.options.forwardKey.setPressed(false);
            client.options.jumpKey.setPressed(true);
            client.options.sneakKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            if (elapsed >= CEILING_BACKUP_MS) {
                ceilingAvoidPhase    = 2;
                ceilingAvoidStartTime = now;
                LOGGER.debug("[JustFarming-PestKiller] Ceiling avoidance: backed up, now rising above ceiling.");
            }
        } else if (ceilingAvoidPhase == 2) {
            // Phase 2: rise until the ceiling above clears or the timeout expires
            lookAt(player, target);
            client.options.backKey.setPressed(false);
            client.options.jumpKey.setPressed(true);
            client.options.sneakKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.forwardKey.setPressed(false);
            boolean cleared = !hasCeilingAbove(player) || elapsed >= CEILING_RISE_TIMEOUT_MS;
            if (cleared) {
                ceilingAvoidPhase    = 0;
                ceilingAvoidStartTime = 0;
                LOGGER.debug("[JustFarming-PestKiller] Ceiling avoidance complete; resuming normal pathfinding.");
            }
        }
    }

    /** Aim the camera toward {@code target} using smooth interpolation. */
    private void lookAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        // Minecraft yaw convention: 0° = south (+Z), 90° = west (-X).
        // atan2(-dx, dz) maps the (dx, dz) direction vector to a Minecraft yaw.
        targetYaw   = (float) Math.toDegrees(Math.atan2(-dx, dz));
        targetPitch = (float) -Math.toDegrees(Math.atan2(dy, distXZ));
        smoothRotateCamera(player);
    }

    /**
     * Applies one incremental camera rotation step toward
     * {@link #targetYaw}/{@link #targetPitch} at a time-based rate.
     */
    private void smoothRotateCamera(ClientPlayerEntity player) {
        long now = System.currentTimeMillis();
        float deltaMs = (lastSmoothLookTime == 0)
                ? SMOOTH_LOOK_INITIAL_DELTA_MS
                : Math.min(SMOOTH_LOOK_MAX_DELTA_MS, (float)(now - lastSmoothLookTime));
        lastSmoothLookTime = now;

        float step = SMOOTH_LOOK_DEGREES_PER_SECOND * deltaMs / 1000.0f;

        float currentYaw   = player.getYaw();
        float currentPitch = player.getPitch();

        float yawDiff   = targetYaw - currentYaw;
        while (yawDiff >  180f) yawDiff -= 360f;
        while (yawDiff < -180f) yawDiff += 360f;
        float pitchDiff = targetPitch - currentPitch;

        if (Math.abs(yawDiff) < 1.0f && Math.abs(pitchDiff) < 1.0f) return;

        float newYaw   = currentYaw   + Math.max(-step, Math.min(step, yawDiff));
        float newPitch = currentPitch + Math.max(-step, Math.min(step, pitchDiff));
        newPitch = Math.max(-90f, Math.min(90f, newPitch));

        float tremorYaw   = (random.nextFloat() * 2f - 1f) * SMOOTH_LOOK_TREMOR_AMPLITUDE;
        float tremorPitch = (random.nextFloat() * 2f - 1f)
                * SMOOTH_LOOK_TREMOR_AMPLITUDE * SMOOTH_LOOK_TREMOR_PITCH_SCALE;

        player.setYaw(newYaw + tremorYaw);
        player.setPitch(newPitch + tremorPitch);
    }

    private void releaseMovementKeys() {
        if (client.options == null) return;
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
        client.options.useKey.setPressed(false);
    }

    /**
     * Returns the effective kill/vacuum radius in blocks.
     *
     * <p>Priority order:
     * <ol>
     *   <li>Auto-detected range from the equipped vacuum's display name (see
     *       {@link #VACUUM_RANGES}).</li>
     *   <li>{@link FarmingConfig#pestKillerVacuumRange} when manually set (&gt; 0).</li>
     *   <li>Hardcoded fallback {@link #KILL_RADIUS} (5 blocks).</li>
     * </ol>
     */
    private double getEffectiveKillRadius() {
        if (detectedVacuumRange > 0) {
            return detectedVacuumRange;
        }
        if (config != null && config.pestKillerVacuumRange > 0) {
            return config.pestKillerVacuumRange;
        }
        return KILL_RADIUS;
    }

    private void returnToFarm() {
        returnWarpSentAt = 0;
        enterState(State.RETURNING);
    }

    private void sendCommand(String command) {
        if (client.player != null && client.player.networkHandler != null) {
            client.player.networkHandler.sendChatCommand(command);
        }
    }
}
