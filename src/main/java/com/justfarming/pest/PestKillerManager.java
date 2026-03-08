package com.justfarming.pest;

import com.justfarming.config.FarmingConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
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
 *   <li>After the teleport delay, flies to the plot centre at high altitude
 *       (y ≈ 103–120, above all crop blocks) so AOTV/AOTE teleports travel
 *       without obstruction.</li>
 *   <li>Once horizontally over the plot centre (within 6 blocks), descends to
 *       crop height (y ≈ 80–90) before scanning.</li>
 *   <li>Scans for pest entities via {@link PestEntityDetector}.  If any are
 *       found it flies directly to the nearest one.</li>
 *   <li>If no pests are detected at the plot centre, it left-clicks with the
 *       vacuum item to fire a "vacuum shot".  The shot emits a line of
 *       ANGRY_VILLAGER particles toward the nearest pest.  The mod follows
 *       that line to reach the pest.</li>
 *   <li>Switches to a vacuum item in the hotbar (any item whose name contains
 *       "Vacuum", case-insensitive) and right-clicks while aiming at the pest.
 *       Right-clicking continues until the pest disappears from the entity list.</li>
 *   <li>When no pests remain on the current plot, moves on to the next infested
 *       plot (if any) and repeats steps 2–5 until all plots have been cleared
 *       without returning to the garden between plots.</li>
 *   <li>Sends {@code /warp garden} only once all detected pests have been killed
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
     * current plot.  Kept short so the macro moves to the next plot almost
     * immediately once no pests remain; the entity detector refreshes every
     * tick (50 ms) so a few hundred milliseconds is more than enough.
     */
    private static final long SCAN_TIMEOUT_AFTER_KILL_MS = 500;

    /** Fallback kill radius (blocks) used when the config value is not set. */
    private static final double KILL_RADIUS = 5.0;

    /**
     * Failsafe duration (ms): if the pest has not been killed after holding
     * right-click for this long, fly to within 2 blocks and try again.
     */
    private static final long KILL_FAILSAFE_MS = 5000L;

    /**
     * Close-approach distance (blocks) used for the {@link #KILL_FAILSAFE_MS}
     * retry.  Getting this close to the pest maximises the chance of a clean
     * vacuum hit and removes any lingering line-of-sight issues.
     */
    private static final double CLOSE_APPROACH_RADIUS = 2.0;

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

    /**
     * Minimum vertical offset (blocks) below the player's eye level at which a
     * pest is considered to be "below" the player and reachable through floor
     * blocks via the vacuum right-click ability.
     */
    private static final double PEST_BELOW_THRESHOLD = 1.0;

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
     * Returns {@code true} if the player's hotbar (slots 0–8) contains at
     * least one item whose display name contains "vacuum" (case-insensitive).
     *
     * @param player the local player to check; must not be {@code null}
     * @return {@code true} when a vacuum item is found in the hotbar
     */
    public static boolean hasVacuumInHotbar(ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && getCleanItemName(stack).toLowerCase().contains("vacuum")) {
                return true;
            }
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
     * Base Y coordinate (blocks) to target when flying to a plot centre.
     * 80 keeps the player comfortably above all crops while remaining within
     * entity-scan range of ground-level pests.  The actual target height is
     * randomised to {@code PLOT_CENTRE_Y_BASE + [0, PLOT_CENTRE_Y_RANGE)}
     * (i.e. 80–85) on every plot visit to look more human-like.
     */
    private static final double PLOT_CENTRE_Y_BASE  = 80.0;

    /**
     * Random range (blocks) added on top of {@link #PLOT_CENTRE_Y_BASE} when
     * computing the per-visit flight height.  {@code random.nextInt(6)} returns
     * 0–5, producing actual heights 80, 81, 82, 83, 84, or 85.
     */
    private static final int    PLOT_CENTRE_Y_RANGE = 6;

    /**
     * Base navigation altitude (blocks Y) used when flying horizontally toward
     * a plot centre.  Flying above typical crop height (≈ 80) while staying
     * well below the plot border ceiling (102) keeps the player clear of all
     * crop blocks without requiring a long climb.
     * The actual altitude is randomised to
     * {@code NAV_ALTITUDE_BASE + [0, NAV_ALTITUDE_RANGE)} (87–91).
     */
    private static final double NAV_ALTITUDE_BASE  = 87.0;

    /**
     * Random range (blocks) added on top of {@link #NAV_ALTITUDE_BASE} when
     * computing the per-visit high-altitude navigation target.
     * {@code random.nextInt(5)} produces altitudes 87–91.
     */
    private static final int    NAV_ALTITUDE_RANGE = 5;

    /**
     * Maximum cruise altitude (blocks).  The pest killer never flies the player
     * above this Y during navigation; if the computed target is higher than this
     * cap the Y is clamped to this value.
     */
    private static final double MAX_CRUISE_Y        = 125.0;

    /**
     * Horizontal distance (blocks) from the computed plot centre at which the
     * {@link State#GOING_TO_PLOT_CENTER} state is considered to have arrived
     * horizontally.  Set to 6 so the player must be within 6 blocks of the
     * plot centre before descending to crop height.
     */
    private static final double PLOT_CENTRE_ARRIVE_RADIUS = 6.0;

    /**
     * Vertical distance (blocks) from the descent target Y at which
     * {@link State#DESCENDING_AT_PLOT} is considered complete and scanning
     * may begin.  A tolerance of 5 blocks avoids over-shooting and matches
     * the precision that creative-flight + camera-pitch naturally achieves.
     */
    private static final double DESCENT_ARRIVE_THRESHOLD = 5.0;

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
        /** Sent {@code /wardrobe}; waiting for the wardrobe GUI to open. */
        WARDROBE_OPENING,
        /** Wardrobe GUI is open; navigating to page 2 (for slots 10–18). */
        WARDROBE_NEXT_PAGE,
        /** Clicking the target armor slot in the wardrobe GUI. */
        WARDROBE_SLOT,
        /** Slot clicked; waiting for the wardrobe GUI to close before proceeding. */
        WARDROBE_CLOSING,
        /** Waiting before sending the plot teleport command. */
        PRE_TELEPORT_WAIT,
        /** Waiting for the server to teleport the player. */
        TELEPORTING,
        /** Flying to the centre of the current infested plot at high altitude. */
        GOING_TO_PLOT_CENTER,
        /** Horizontally over the plot centre; descending to crop-height (y ≈ 80–90). */
        DESCENDING_AT_PLOT,
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

    // ── Wardrobe state ───────────────────────────────────────────────────────

    /** Maximum time (ms) to wait for the wardrobe GUI to open after /wardrobe. */
    private static final long WARDROBE_OPEN_TIMEOUT_MS  = 4000L;
    /** How long (ms) to wait after clicking a button in the wardrobe GUI. */
    private static final long WARDROBE_CLICK_DELAY_MS   = 600L;
    /** How long (ms) to wait after closing the wardrobe before starting the pest routine. */
    private static final long WARDROBE_CLOSE_DELAY_MS   = 500L;

    /** {@code true} after the "Next Page" button was clicked in the wardrobe. */
    private boolean wardrobeNextPageClicked = false;
    /** {@code true} after the target wardrobe slot was clicked. */
    private boolean wardrobeSlotClicked     = false;

    /**
     * Remaining infested plot names to visit this run.  Stored as a
     * {@link LinkedList} so individual entries (closest plot) can be removed
     * by name, not just from the head.  Populated by {@link #start(Collection)}
     * and consumed one-by-one as each plot is cleared.
     */
    private final LinkedList<String> remainingPlots = new LinkedList<>();

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
    /**
     * Number of consecutive stuck checks where the player made essentially zero
     * progress (< {@link #ZERO_PROGRESS_THRESHOLD} blocks).  When this reaches
     * {@link #ZERO_PROGRESS_MAX_COUNT} the player is assumed to be stuck inside a
     * block (a common side-effect of a bad {@code /tptoplot}) and the routine
     * falls back to {@code /warp garden}.
     */
    private int   zeroProgressCount   = 0;

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
    /**
     * Progress threshold (blocks) below which movement is treated as effectively
     * zero (player is inside a solid block after a bad teleport).
     */
    private static final double ZERO_PROGRESS_THRESHOLD      = 0.05;
    /**
     * Number of consecutive zero-progress stuck checks before the routine gives
     * up and falls back to {@code /warp garden} (skipping any remaining plot
     * teleports).  Each check is {@link #STUCK_CHECK_INTERVAL_MS} apart, so
     * five checks = ~7.5 seconds of no movement, reducing false positives caused
     * by anti-cheat lag-back corrections during long flights.
     */
    private static final int    ZERO_PROGRESS_MAX_COUNT      = 5;

    // ── AOTE/AOTV use during pest-killer flight ──────────────────────────────

    /**
     * Minimum distance (blocks) to the flight target at which the pest killer
     * will use an Aspect of the End / Aspect of the Void to teleport closer.
     * Below this distance the routine relies on normal creative flight.
     */
    private static final double PEST_AOTV_TRIGGER_DIST = 18.0;

    /**
     * Duration (ms) to smooth the camera toward the AOTV/AOTE aim direction
     * before firing the right-click.  Mirrors the human-like aiming delay used
     * in the visitor routine.
     */
    private static final long   PEST_AOTV_AIM_MS       = 600L;

    /** Duration (ms) to hold the right-click when activating the AOTV/AOTE. */
    private static final long   PEST_AOTV_HOLD_MS      = 100L;

    /**
     * Time (ms) to wait after firing the AOTV/AOTE before resuming flight.
     * Gives the server time to process the teleport and update the player's
     * position.
     */
    private static final long   PEST_AOTV_WAIT_MS      = 800L;

    /**
     * Minimum cooldown (ms) between successive AOTV/AOTE fires so the mod
     * does not spam the ability while the player is still mid-flight.
     * Kept short (400 ms) because the {@link #PEST_AOTV_WAIT_MS} post-sequence
     * pause already covers the server-processing time; re-triggering quickly
     * lets the player continuously teleport toward the plot without stopping.
     */
    private static final long   PEST_AOTV_COOLDOWN_MS  = 400L;

    /**
     * Assumed teleport distance (blocks) per AOTV/AOTE use when the lore
     * cannot be parsed.  Matches the Aspect of the End default.
     */
    private static final int    PEST_AOTV_TELEPORT_DIST = 8;

    /**
     * Safety cap on the number of right-clicks fired in a single AOTV/AOTE
     * sequence.  Set to 25 to cover worst-case garden distances (≈ 200 blocks
     * / 8 blocks per hop) so the player teleports all the way to the plot
     * centre in a single sequence without stopping to fly.
     */
    private static final int    PEST_AOTV_MAX_CLICKS    = 25;

    /**
     * Minimum clicks per second used when randomising the inter-click delay for
     * AOTV/AOTE sequences.  Together with {@link #PEST_AOTV_MAX_CPS} this gives a
     * tight 7–8 CPS range that is fast but still looks human-like.
     */
    private static final int    PEST_AOTV_MIN_CPS       = 7;

    /**
     * Maximum clicks per second used when randomising the inter-click delay.
     * Together with {@link #PEST_AOTV_MIN_CPS} the effective rate varies between
     * 7 and {@value} CPS, producing fast but human-like clicking behaviour.
     */
    private static final int    PEST_AOTV_MAX_CPS       = 8;

    /**
     * Inclusive size of the CPS range ({@link #PEST_AOTV_MAX_CPS} −
     * {@link #PEST_AOTV_MIN_CPS} + 1) used as the {@code nextInt} bound when
     * picking the inter-click delay, pre-computed to avoid repeated arithmetic.
     */
    private static final int    PEST_AOTV_CPS_RANGE     = PEST_AOTV_MAX_CPS - PEST_AOTV_MIN_CPS + 1;

    /**
     * Minimum Y coordinate the player must be at before the AOTV/AOTE sequence
     * will start.  Set below the garden floor (≈ 68) so that AOTV fires as soon
     * as the player activates creative flight after {@code /warp garden}, allowing
     * the macro to teleport directly upward to the navigation altitude without
     * waiting for normal creative flight to climb to Y = 80 first.
     * The {@link #isNearWall} check still prevents teleporting through the garden
     * barn or other structures immediately after the warp.
     */
    private static final double PEST_AOTV_MIN_FLY_Y     = 65.0;

    /**
     * If any detected pest is within this distance (blocks) of the player,
     * the AOTV/AOTE teleport sequence will not start (or will be aborted if
     * already running).  Prevents the macro from teleporting when the player
     * is already next to a pest and can kill it via normal flight + right-click.
     */
    private static final double PEST_AOTV_NEAR_PEST_SKIP_DIST = 10.0;

    /**
     * When climbing to {@link #PEST_AOTV_MIN_FLY_Y}, the intermediate waypoint
     * is offset this fraction of the horizontal distance toward the final target.
     * A small value (5 %) keeps the yaw pointed in the right general direction
     * while ensuring the camera pitches steeply upward for fast altitude gain.
     */
    private static final double CLIMB_WAYPOINT_HORIZ_FRACTION = 0.05;

    /**
     * Extra altitude (blocks) added on top of {@link #PEST_AOTV_MIN_FLY_Y} for
     * the climb waypoint so the player clears the minimum threshold before AOTV
     * can fire, preventing repeated single-block overshoot checks.
     */
    private static final double CLIMB_WAYPOINT_ALTITUDE_MARGIN = 2.0;

    /**
     * Minimum start delay (ms) applied to {@link #preTeleportWaitMs} on every
     * {@link #start} call.  Ensures there is always at least 1 second of
     * motionless waiting after the command is issued before the first teleport.
     */
    private static final long   PEST_START_DELAY_MS     = 1000L;

    // ── AOTE/AOTV fields ─────────────────────────────────────────────────────

    /** Hotbar slot of the AOTV/AOTE item found for the current pest-killer run; -1 if none. */
    private int   pestAotvSlot         = -1;
    /**
     * Wall-clock time (ms) when the current aim/fire/wait AOTV sequence started.
     * {@code 0} means no sequence is active.
     */
    private long  pestAotvSeqStart     = 0L;
    /**
     * Wall-clock time (ms) of the last successful AOTV/AOTE fire.  Used together with
     * {@link #PEST_AOTV_COOLDOWN_MS} to prevent rapid re-triggering.
     */
    private long  pestAotvLastFireTime = 0L;
    /** Yaw angle (degrees) toward which the AOTV/AOTE is aimed for the current sequence. */
    private float pestAotvTargetYaw    = 0f;
    /** Pitch angle (degrees) toward which the AOTV/AOTE is aimed for the current sequence. */
    private float pestAotvTargetPitch  = 0f;
    /** Total number of right-clicks to fire in the current sequence (one per teleport hop). */
    private int   pestAotvTotalClicks       = 0;
    /** Number of right-clicks already completed in the current sequence. */
    private int   pestAotvClicksDone        = 0;
    /**
     * Wall-clock time (ms) of the next scheduled event in the multi-click loop:
     * either the next press (when no click is held) or the release of the current
     * press (when {@link #pestAotvClickHeld} is {@code true}).
     */
    private long  pestAotvNextEventTime     = 0L;
    /** {@code true} while the use-key is held for the current click in the sequence. */
    private boolean pestAotvClickHeld       = false;
    /**
     * Wall-clock time (ms) when all clicks in the sequence were completed;
     * {@code 0} while clicks are still pending.  Used to time the post-click
     * wait phase.
     */
    private long  pestAotvAllClicksDoneTime = 0L;

    // ── 1-block wall avoidance ──────────────────────────────────────────────

    /**
     * Minimum horizontal distance (blocks) to the target before running the
     * 1-block-wall-at-foot check.  When the target is closer than this the
     * player is essentially already at the destination, so the check is skipped.
     */
    private static final double WALL_MIN_CHECK_DISTANCE = 0.5;

    /**
     * Look-ahead distance (blocks) used when probing for a 1-block wall directly
     * ahead of the player.  1.5 blocks provides enough lead time for the camera
     * pitch adjustment to take effect before the player reaches the obstacle.
     */
    private static final double WALL_PROBE_DISTANCE = 1.5;

    /**
     * How many blocks above the player's current Y position the effective
     * navigation target is raised when a 1-block-tall wall is detected ahead.
     * 1.5 blocks clears a single full-height block with a small margin.
     */
    private static final double WALL_CLEARANCE_HEIGHT = 1.5;

    // ── Humanised pest-aim drift ─────────────────────────────────────────────

    /**
     * Maximum radius (blocks) of the camera-aim drift applied during
     * {@link State#KILLING_PEST}.  The camera aims at a point within this sphere
     * around the pest rather than locking exactly on it, producing more natural
     * looking camera movement.  This radius scales linearly down to 0 over
     * {@link #KILL_FAILSAFE_MS} seconds as a failsafe so the crosshair
     * converges onto the pest if it has not been killed after that time.
     */
    private static final double PEST_AIM_DRIFT_RADIUS     = 5.0;

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
     * Minimum clearance (blocks) between the player's feet and the nearest
     * solid block below.  When the ground is this close, sneak-descent is
     * suppressed and the jump key is held to keep the player airborne at all
     * times – the pest macro must never land on crops or the garden floor.
     */
    private static final int  GROUND_CLEARANCE_BLOCKS     = 3;

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

    /**
     * When {@code true}, the next {@link State#FLYING_TO_PEST} approach uses
     * {@link #CLOSE_APPROACH_RADIUS} as the arrival threshold instead of the full
     * vacuum range.  Set after {@link #KILL_FAILSAFE_MS} ms of fruitless
     * right-clicking so the player moves within guaranteed hit range.
     */
    private boolean closeApproachNeeded = false;

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
        closeApproachNeeded = false;
        resetAotvState();
        resetPlotState();
    }

    /**
     * Stop the pest killer immediately and release all held keys.
     * Restores the hotbar slot to its pre-vacuum state.
     */
    public void stop() {
        if (state == State.IDLE || state == State.DONE) return;
        LOGGER.info("[Just Farming-PestKiller] Stopped.");
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
        closeApproachNeeded = false;
        resetAotvState();
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
        LOGGER.info("[Just Farming-PestKiller] Starting pest killer routine.");
        currentPest = null;
        vacuumSlot = -1;
        preVacuumSlot = -1;
        pestKillWaitEnd = 0;
        lastSmoothLookTime = 0;
        returnWarpSentAt = 0;
        resetPlotState();

        remainingPlots.clear();
        if (pestPlots != null) {
            // Collect valid plot names.
            java.util.List<String> sorted = new java.util.ArrayList<>();
            for (String p : pestPlots) {
                if (p != null && !p.isBlank()) sorted.add(p);
            }
            // Sort plots by distance to the player's current position so the
            // macro always visits the closest infested plot first.
            ClientPlayerEntity startPlayer = client.player;
            if (startPlayer != null) {
                final double px = startPlayer.getX();
                final double pz = startPlayer.getZ();
                sorted.sort((a, b) -> {
                    double ax = GardenPlot.getCentreX(a), az = GardenPlot.getCentreZ(a);
                    double bx = GardenPlot.getCentreX(b), bz = GardenPlot.getCentreZ(b);
                    if (!Double.isNaN(ax) && !Double.isNaN(az)
                            && !Double.isNaN(bx) && !Double.isNaN(bz)) {
                        double da = Math.sqrt((px - ax) * (px - ax) + (pz - az) * (pz - az));
                        double db = Math.sqrt((px - bx) * (px - bx) + (pz - bz) * (pz - bz));
                        return Double.compare(da, db);
                    }
                    // Fallback: numeric sort for unknown plot names.
                    try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); }
                    catch (NumberFormatException e) { return a.compareTo(b); }
                });
            } else {
                // No player available – fall back to numeric order.
                sorted.sort((a, b) -> {
                    try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); }
                    catch (NumberFormatException e) {
                        LOGGER.warn("[Just Farming-PestKiller] Non-numeric plot name '{}' or '{}'; "
                                + "using string sort.", a, b);
                        return a.compareTo(b);
                    }
                });
            }
            remainingPlots.addAll(sorted);
        }

        String firstPlot = pollClosestPlot(); // pick closest to player
        currentPlotName = firstPlot;

        long base = config != null && config.pestKillerTeleportDelay > 0
                ? config.pestKillerTeleportDelay : 0;
        int globalRandom = (config != null) ? config.globalRandomizationMs : 0;
        preTeleportWaitMs = Math.max(PEST_START_DELAY_MS,
                base + random.nextInt(Math.max(1, globalRandom)));

        // If wardrobe armor swap is enabled, open the wardrobe first.
        // The wardrobe states will transition to PRE_TELEPORT_WAIT when done.
        wardrobeNextPageClicked = false;
        wardrobeSlotClicked     = false;
        if (config != null && config.pestWardrobeEnabled) {
            enterState(State.WARDROBE_OPENING);
            sendCommand("wardrobe");
            LOGGER.info("[Just Farming-PestKiller] Sent /wardrobe to equip armor slot {}.",
                    config.pestWardrobeSlot);
        } else {
            enterState(State.PRE_TELEPORT_WAIT);
        }
    }

    /**
     * Called every render frame to apply incremental camera rotation toward
     * {@link #targetYaw}/{@link #targetPitch} while actively flying or killing.
     */
    public void onRenderTick() {
        if (state != State.FLYING_TO_PEST
                && state != State.KILLING_PEST
                && state != State.GOING_TO_PLOT_CENTER
                && state != State.DESCENDING_AT_PLOT
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

            // ── Wardrobe states ──────────────────────────────────────────────

            case WARDROBE_OPENING -> {
                // Wait for a HandledScreen with "Wardrobe" in the title to open.
                if (client.currentScreen instanceof HandledScreen<?> screen) {
                    String title = screen.getTitle().getString().toLowerCase();
                    if (title.contains("wardrobe")) {
                        int targetSlot = config != null ? config.pestWardrobeSlot : 1;
                        if (targetSlot >= 10) {
                            // Need to navigate to page 2 first
                            enterState(State.WARDROBE_NEXT_PAGE);
                        } else {
                            enterState(State.WARDROBE_SLOT);
                        }
                    }
                } else if (now - stateEnteredAt >= WARDROBE_OPEN_TIMEOUT_MS) {
                    LOGGER.warn("[Just Farming-PestKiller] Wardrobe GUI did not open; skipping wardrobe step.");
                    enterState(State.PRE_TELEPORT_WAIT);
                }
            }

            case WARDROBE_NEXT_PAGE -> {
                if (!wardrobeNextPageClicked) {
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        if (tryClickWardrobeSlotWithName(screen, "next page")) {
                            wardrobeNextPageClicked = true;
                            LOGGER.info("[Just Farming-PestKiller] Clicked 'Next Page' in wardrobe.");
                            stateEnteredAt = now; // reset timer for the delay
                        } else if (now - stateEnteredAt >= WARDROBE_CLICK_DELAY_MS) {
                            LOGGER.warn("[Just Farming-PestKiller] Could not find 'Next Page' in wardrobe.");
                            enterState(State.WARDROBE_SLOT);
                        }
                    } else {
                        LOGGER.warn("[Just Farming-PestKiller] Wardrobe screen closed unexpectedly.");
                        enterState(State.PRE_TELEPORT_WAIT);
                    }
                } else if (now - stateEnteredAt >= WARDROBE_CLICK_DELAY_MS) {
                    enterState(State.WARDROBE_SLOT);
                }
            }

            case WARDROBE_SLOT -> {
                if (!wardrobeSlotClicked) {
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        int targetSlot = config != null ? Math.max(1, Math.min(18, config.pestWardrobeSlot)) : 1;
                        // After "Next Page", page 2 shows slots 10-18 labelled as "Slot 10", "Slot 11", etc.
                        // On page 1, slots 1-9 are labelled "Slot 1", "Slot 2", etc.
                        String slotLabel = "slot " + targetSlot;
                        if (tryClickWardrobeSlotWithName(screen, slotLabel)) {
                            wardrobeSlotClicked = true;
                            LOGGER.info("[Just Farming-PestKiller] Clicked '{}' in wardrobe.", slotLabel);
                            stateEnteredAt = now;
                        } else if (now - stateEnteredAt >= WARDROBE_CLICK_DELAY_MS) {
                            LOGGER.warn("[Just Farming-PestKiller] Could not find '{}' in wardrobe.", slotLabel);
                            // Close screen and proceed
                            if (player != null) player.closeHandledScreen();
                            enterState(State.WARDROBE_CLOSING);
                        }
                    } else {
                        // Screen closed before we could click – proceed anyway
                        enterState(State.WARDROBE_CLOSING);
                    }
                } else if (now - stateEnteredAt >= WARDROBE_CLICK_DELAY_MS) {
                    // Slot clicked; close the wardrobe screen
                    if (client.currentScreen != null && player != null) {
                        player.closeHandledScreen();
                    }
                    enterState(State.WARDROBE_CLOSING);
                }
            }

            case WARDROBE_CLOSING -> {
                // Wait for the screen to close, then start the normal pest routine
                if (client.currentScreen == null
                        || now - stateEnteredAt >= WARDROBE_CLOSE_DELAY_MS) {
                    LOGGER.info("[Just Farming-PestKiller] Wardrobe sequence complete; starting pest routine.");
                    enterState(State.PRE_TELEPORT_WAIT);
                }
            }

            case PRE_TELEPORT_WAIT -> {
                if (now - stateEnteredAt >= preTeleportWaitMs) {
                    // Pre-teleport delay elapsed: now send the warp command.
                    if (config != null && config.pestKillerWarpToPlot && currentPlotName != null) {
                        sendCommand("tptoplot " + currentPlotName);
                        LOGGER.info("[Just Farming-PestKiller] Sent /tptoplot {} to reach infested plot.", currentPlotName);
                    } else {
                        sendCommand("warp garden");
                        LOGGER.info("[Just Farming-PestKiller] Sent /warp garden to reach garden.");
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
                    // After teleporting, fly at high altitude (above all crop blocks)
                    // to the plot centre so AOTV can travel unobstructed.
                    if (currentPlotName != null) {
                        double cx = GardenPlot.getCentreX(currentPlotName);
                        double cz = GardenPlot.getCentreZ(currentPlotName);
                        if (!Double.isNaN(cx) && !Double.isNaN(cz)) {
                            double flightY = NAV_ALTITUDE_BASE + random.nextInt(NAV_ALTITUDE_RANGE);
                            plotCentreTarget = new Vec3d(cx, flightY, cz);
                            LOGGER.info("[Just Farming-PestKiller] Teleport wait elapsed; "
                                    + "flying to plot {} centre at high altitude ({}, {}, {}).",
                                    currentPlotName, (int) cx, (int) flightY, (int) cz);
                            enterState(State.GOING_TO_PLOT_CENTER);
                            return;
                        }
                    }
                    // No plot name or unknown plot; fall back to scanning in place.
                    LOGGER.info("[Just Farming-PestKiller] Teleport wait elapsed; scanning for pests.");
                    enterState(State.SCANNING);
                }
            }

            case GOING_TO_PLOT_CENTER -> {
                // If pests are already visible, skip flying to the centre.
                List<PestEntityDetector.PestEntity> pestsAtCentre = pestEntityDetector.getDetectedPests();
                if (!pestsAtCentre.isEmpty()) {
                    currentPest = pickNearestPest(player, pestsAtCentre);
                    if (currentPest != null) {
                        LOGGER.info("[Just Farming-PestKiller] Pest detected while flying to centre; "
                                + "targeting directly.");
                        releaseMovementKeys();
                        resetAotvState();
                        enterState(State.FLYING_TO_PEST);
                        return;
                    }
                }

                if (plotCentreTarget == null) {
                    resetAotvState();
                    enterState(State.SCANNING);
                    return;
                }

                // Check horizontal distance only (ignore Y) so we arrive directly
                // above the plot centre before descending.
                double dx = player.getX() - plotCentreTarget.x;
                double dz = player.getZ() - plotCentreTarget.z;
                double horizDist = Math.sqrt(dx * dx + dz * dz);
                if (horizDist <= PLOT_CENTRE_ARRIVE_RADIUS) {
                    // Arrived horizontally at the plot centre; descend to crop height.
                    double descentY = PLOT_CENTRE_Y_BASE + random.nextInt(PLOT_CENTRE_Y_RANGE);
                    plotCentreTarget = new Vec3d(plotCentreTarget.x, descentY, plotCentreTarget.z);
                    LOGGER.info("[Just Farming-PestKiller] Arrived over plot {} centre; "
                            + "descending to y={}.", currentPlotName, (int) descentY);
                    releaseMovementKeys();
                    resetAotvState();
                    enterState(State.DESCENDING_AT_PLOT);
                    return;
                }

                // Timeout – give up flying and scan from wherever we are.
                if (now - stateEnteredAt >= GOING_TO_PLOT_CENTRE_TIMEOUT_MS) {
                    LOGGER.info("[Just Farming-PestKiller] Timed out flying to plot centre; scanning.");
                    releaseMovementKeys();
                    resetAotvState();
                    enterState(State.SCANNING);
                    return;
                }

                // Use AOTV/AOTE when the target is far to reach it much faster.
                // Pass 0.0 so all the distance to the centre is covered.
                if (handlePestAotvToward(player, plotCentreTarget, 0.0)) return;
                flyToward(player, plotCentreTarget);
            }

            case DESCENDING_AT_PLOT -> {
                // Check if pests are already visible while descending.
                List<PestEntityDetector.PestEntity> pestsDA = pestEntityDetector.getDetectedPests();
                if (!pestsDA.isEmpty()) {
                    currentPest = pickNearestPest(player, pestsDA);
                    if (currentPest != null) {
                        LOGGER.info("[Just Farming-PestKiller] Pest detected while descending; "
                                + "targeting directly.");
                        releaseMovementKeys();
                        resetAotvState();
                        enterState(State.FLYING_TO_PEST);
                        return;
                    }
                }

                if (plotCentreTarget == null) {
                    enterState(State.SCANNING);
                    return;
                }

                // Arrived when within DESCENT_ARRIVE_THRESHOLD blocks of the descent target Y.
                double distToDescentY = Math.abs(player.getY() - plotCentreTarget.y);
                if (distToDescentY <= DESCENT_ARRIVE_THRESHOLD) {
                    LOGGER.info("[Just Farming-PestKiller] Descended to y={} over plot {}; scanning.",
                            (int) player.getY(), currentPlotName);
                    releaseMovementKeys();
                    resetAotvState();
                    enterState(State.SCANNING);
                    return;
                }

                // Timeout – start scanning from wherever we are.
                if (now - stateEnteredAt >= GOING_TO_PLOT_CENTRE_TIMEOUT_MS) {
                    LOGGER.info("[Just Farming-PestKiller] Timed out descending; scanning.");
                    releaseMovementKeys();
                    resetAotvState();
                    enterState(State.SCANNING);
                    return;
                }

                flyToward(player, plotCentreTarget);
            }

            case SCANNING -> {
                if (now < pestKillWaitEnd) return;

                List<PestEntityDetector.PestEntity> pests = pestEntityDetector.getDetectedPests();
                if (!pests.isEmpty()) {
                    currentPest = pickNearestPest(player, pests);
                    if (currentPest != null) {
                        LOGGER.info("[Just Farming-PestKiller] Found {} pest(s). Targeting: {} at {}.",
                                pests.size(), currentPest.displayName(), currentPest.position());
                        enterState(State.FLYING_TO_PEST);
                    }
                } else if (now - stateEnteredAt >= (atLeastOnePestKilledThisPlot
                        ? SCAN_TIMEOUT_AFTER_KILL_MS : SCAN_TIMEOUT_MS)) {
                    // No pests found at centre; try a vacuum shot to locate them via
                    // the particle trail (only one attempt per plot, regardless of
                    // whether the plot name is known – fire even without /tptoplot).
                    if (!vacuumShotAttempted) {
                        LOGGER.info("[Just Farming-PestKiller] No pests at plot centre; "
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
                        teleportToNextPlot(pollClosestPlot());
                    } else {
                        LOGGER.info("[Just Farming-PestKiller] No pests found after scanning all plots; "
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
                        LOGGER.info("[Just Farming-PestKiller] Pest detected after vacuum shot; targeting.");
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
                        LOGGER.warn("[Just Farming-PestKiller] No vacuum in hotbar; skipping vacuum shot.");
                        vacuumParticleTracker.stopTracking();
                        // Try next plot or return.
                        if (!remainingPlots.isEmpty()) {
                            teleportToNextPlot(pollClosestPlot());
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
                    LOGGER.info("[Just Farming-PestKiller] Fired vacuum shot to locate pest.");
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
                        LOGGER.info("[Just Farming-PestKiller] Particle trail detected; "
                                + "following to ({}, {}, {}).",
                                String.format("%.1f", waypoint.x),
                                String.format("%.1f", waypoint.y),
                                String.format("%.1f", waypoint.z));
                        particleWaypoint = waypoint;
                        enterState(State.FOLLOWING_PARTICLES);
                    } else {
                        LOGGER.info("[Just Farming-PestKiller] No particle trail; trying next plot.");
                        if (!remainingPlots.isEmpty()) {
                            teleportToNextPlot(pollClosestPlot());
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
                        LOGGER.info("[Just Farming-PestKiller] Pest detected while following particles; "
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
                    LOGGER.info("[Just Farming-PestKiller] Reached particle-trail waypoint; re-scanning.");
                    releaseMovementKeys();
                    enterState(State.SCANNING);
                    return;
                }

                if (now - stateEnteredAt >= FOLLOWING_PARTICLES_TIMEOUT_MS) {
                    LOGGER.info("[Just Farming-PestKiller] Particle-trail follow timed out.");
                    releaseMovementKeys();
                    if (!remainingPlots.isEmpty()) {
                        teleportToNextPlot(pollClosestPlot());
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
                    LOGGER.info("[Just Farming-PestKiller] Target pest gone; scanning for remaining pests.");
                    releaseMovementKeys();
                    resetAotvState();
                    enterState(State.SCANNING);
                    return;
                }
                Vec3d pestPos = currentPest.position();
                double dist = player.getEyePos().distanceTo(pestPos);
                double horizDist = horizontalDistance(player.getX(), player.getZ(), pestPos.x, pestPos.z);
                double vertDist = Math.abs(player.getEyePos().y - pestPos.y);
                // When a previous kill attempt timed out, fly to within CLOSE_APPROACH_RADIUS
                // instead of the full vacuum range so range is optimal.
                double approachRadius = closeApproachNeeded ? CLOSE_APPROACH_RADIUS : getEffectiveKillRadius();
                // The vacuum right-click ability works through blocks, so the player does not
                // need to physically descend into the crop field to kill a pest below.
                // Allow entering the kill state when:
                //   (a) within approachRadius in 3D distance, OR
                //   (b) within approachRadius in both horizontal AND vertical distance, OR
                //   (c) pest is below the player and within horizontal kill range – the
                //       vacuum beam reaches downward through floor blocks.
                boolean pestBelow = pestPos.y < player.getEyePos().y - PEST_BELOW_THRESHOLD;
                boolean killable = dist <= approachRadius
                        || (horizDist <= approachRadius && vertDist <= approachRadius)
                        || (pestBelow && horizDist <= approachRadius);
                if (killable) {
                    releaseMovementKeys();
                    resetAotvState();
                    closeApproachNeeded = false;
                    // Find vacuum before entering kill state
                    findAndEquipVacuum(player);
                    // Reset drift state so the aim wanders freely from the first tick.
                    pestAimOffset       = Vec3d.ZERO;
                    pestAimOffsetTarget = Vec3d.ZERO;
                    pestAimOffsetUpdateTime = 0;
                    enterState(State.KILLING_PEST);
                } else {
                    // Use AOTV/AOTE when the pest is far to reach it much faster.
                    // Pass the effective kill radius so only the teleports needed to
                    // bring the player within kill range are fired (e.g. 2 hops for a
                    // pest 24 blocks away with a kill radius of 8 blocks).
                    if (handlePestAotvToward(player, pestPos, getEffectiveKillRadius())) return;
                    flyToward(player, pestPos);
                }
            }

            case KILLING_PEST -> {
                List<PestEntityDetector.PestEntity> pests = pestEntityDetector.getDetectedPests();
                // Keep targeting the nearest pest
                currentPest = pickNearestPest(player, pests);
                if (currentPest == null) {
                    // Pest was killed; brief configurable pause before scanning for more.
                    LOGGER.info("[Just Farming-PestKiller] Pest killed; scanning for remaining pests.");
                    releaseMovementKeys();
                    closeApproachNeeded = false; // reset failsafe flag on successful kill
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
                // Also check horizontal and vertical distances so we keep right-clicking
                // when flying directly above or near a pest (vertical offset inflates 3D
                // dist beyond the 1.5× exit threshold while the pest is still in range).
                double horizDist = horizontalDistance(player.getX(), player.getZ(), pestPos.x, pestPos.z);
                double vertDist = Math.abs(player.getEyePos().y - pestPos.y);
                // The vacuum right-click works through blocks from above.  Don't exit kill
                // state when the pest is directly below – keep right-clicking downward.
                boolean pestBelow = pestPos.y < player.getEyePos().y - PEST_BELOW_THRESHOLD;
                // Exit kill state if the pest is out of range in 3D distance AND
                // either horizontal OR vertical distance exceeds the kill radius, so
                // the player will re-approach from the correct height and angle.
                // When the pest is below, only exit if both 3D and horizontal distance
                // are out of range (vertical out-of-range is acceptable since the
                // vacuum beam kills through blocks from above).
                boolean outOfRange;
                if (pestBelow) {
                    outOfRange = dist > getEffectiveKillRadius() * 1.5
                            && horizDist > getEffectiveKillRadius();
                } else {
                    outOfRange = dist > getEffectiveKillRadius() * 1.5
                            && (horizDist > getEffectiveKillRadius() || vertDist > getEffectiveKillRadius());
                }
                if (outOfRange) {
                    if (client.options != null) client.options.useKey.setPressed(false);
                    pestAimOffsetUpdateTime = 0; // reset drift on next approach
                    enterState(State.FLYING_TO_PEST);
                    return;
                }

                // Failsafe: if the pest has not been killed after KILL_FAILSAFE_MS of
                // continuous right-clicking, fly much closer and retry.  This handles
                // edge cases where the player is at the edge of vacuum range or there
                // is a slight line-of-sight obstruction.
                if (now - stateEnteredAt >= KILL_FAILSAFE_MS) {
                    LOGGER.info("[Just Farming-PestKiller] Kill failsafe triggered after {}ms; "
                            + "flying closer to pest.", now - stateEnteredAt);
                    if (client.options != null) client.options.useKey.setPressed(false);
                    pestAimOffsetUpdateTime = 0;
                    closeApproachNeeded = true;
                    enterState(State.FLYING_TO_PEST);
                    return;
                }

                // Humanised camera aim: keep the aim point within PEST_AIM_DRIFT_RADIUS of
                // the pest rather than locking exactly onto it.  The effective radius scales
                // linearly from PEST_AIM_DRIFT_RADIUS down to 0 over KILL_FAILSAFE_MS seconds
                // so that the crosshair gradually converges onto the pest as a failsafe if it
                // has not been killed yet.  Periodically pick a new random target offset and
                // smoothly interpolate toward it so the camera drifts naturally.
                long timeInKillState = now - stateEnteredAt;
                double effectiveDriftRadius = PEST_AIM_DRIFT_RADIUS
                        * Math.max(0.0, 1.0 - (double) timeInKillState / KILL_FAILSAFE_MS);
                if (now >= pestAimOffsetUpdateTime) {
                    // Pick a new random target inside the effective drift sphere.
                    double elevation = (random.nextDouble() - 0.5) * Math.PI;
                    double azimuth   = random.nextDouble() * 2.0 * Math.PI;
                    double radius    = random.nextDouble() * effectiveDriftRadius;
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
                    LOGGER.info("[Just Farming-PestKiller] Sent /warp garden after pest kill.");
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
                || next == State.FOLLOWING_PARTICLES || next == State.DESCENDING_AT_PLOT) {
            // Reset stuck detection so each new flight attempt starts fresh
            lastProgressPos      = null;
            lastProgressCheckTime = 0;
            strafeEndTime        = 0;
            strafeDirection      = 0;
            zeroProgressCount    = 0;
            doubleJumpStartTime  = 0;
            ceilingAvoidPhase    = 0;
            ceilingAvoidStartTime = 0;
        }
        if (next != State.IDLE && next != State.DONE) {
            LOGGER.info("[Just Farming-PestKiller] -> {}", next);
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

    /** Resets all AOTV/AOTE state fields to their default (inactive) values. */
    private void resetAotvState() {
        if (pestAotvSeqStart != 0 && client.options != null) {
            client.options.useKey.setPressed(false);
        }
        pestAotvSlot             = -1;
        pestAotvSeqStart         = 0L;
        pestAotvTargetYaw        = 0f;
        pestAotvTargetPitch      = 0f;
        pestAotvTotalClicks      = 0;
        pestAotvClicksDone       = 0;
        pestAotvNextEventTime    = 0L;
        pestAotvClickHeld        = false;
        pestAotvAllClicksDoneTime = 0L;
        // pestAotvLastFireTime is intentionally preserved so the cooldown
        // persists across state transitions.
    }

    /**
     * Searches the player's hotbar for an Aspect of the End or Aspect of the Void item.
     *
     * @param player the local player
     * @return the hotbar slot index (0–8), or {@code -1} if not found
     */
    private int findAotvSlotForPest(ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            String name = getCleanItemName(stack).toLowerCase();
            if (name.contains("aspect of the void") || name.contains("aspect of the end")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Manages an inline AOTV/AOTE aim→multi-click-spam→wait sequence to teleport
     * the player closer to {@code target} when the distance is large enough to
     * benefit.
     *
     * <p>The sequence has three phases:
     * <ol>
     *   <li><b>Aim</b> ({@link #PEST_AOTV_AIM_MS} ms): smooth-rotate the camera toward
     *       the target using the normal render-tick camera interpolation.</li>
     *   <li><b>Multi-click spam</b>: fire the exact number of right-clicks needed to
     *       bring the player within {@code approachRadius} of the target
     *       ({@code ceil((dist − approachRadius) / teleportDist)}).  Between
     *       consecutive clicks the delay is randomised over a
     *       {@link #PEST_AOTV_MIN_CPS}–{@link #PEST_AOTV_MAX_CPS} CPS range (5–6 CPS)
     *       so the clicking pattern looks fast but human-like.</li>
     *   <li><b>Wait</b> ({@link #PEST_AOTV_WAIT_MS} ms): give the server time to
     *       process the final teleport before resuming normal flight.</li>
     * </ol>
     *
     * <p>The sequence will not start unless the player is already flying
     * ({@code player.getAbilities().flying}) and above the farm at least at
     * {@link #PEST_AOTV_MIN_FLY_Y}, ensuring the teleports never launch the
     * player through crop blocks.
     *
     * @param player        the local player
     * @param target        the position to teleport toward
     * @param approachRadius distance (blocks) at which the player is considered
     *                      close enough to stop teleporting; pass {@code 0.0} to
     *                      fully close the gap (e.g. flying to plot centre), or
     *                      {@link #getEffectiveKillRadius()} when going to a pest
     *                      so only the minimum necessary teleports are fired
     * @return {@code true} while the sequence is active (caller should not call
     *         {@link #flyToward} in this case); {@code false} when inactive
     */
    private boolean handlePestAotvToward(ClientPlayerEntity player, Vec3d target, double approachRadius) {
        long now = System.currentTimeMillis();

        // ── Start a new sequence if not already active ─────────────────────────
        if (pestAotvSeqStart == 0) {
            // Only use AOTV while already flying and above the farm; flyToward()
            // will handle taking off and climbing before the sequence starts.
            if (!player.getAbilities().flying) return false;
            if (player.getY() < PEST_AOTV_MIN_FLY_Y) return false;

            // Do not start AOTV if the player is adjacent to a solid block –
            // teleporting while next to a wall (e.g. the barn after /warp garden)
            // can clip the player into the structure.  Let flyToward() move the
            // player clear of the obstacle first.
            if (isNearWall(player)) return false;

            // Respect the cooldown between successive sequence starts.
            if (now - pestAotvLastFireTime < PEST_AOTV_COOLDOWN_MS) return false;

            // Only fire when the target is far enough to be worth the overhead.
            double dist = player.getEyePos().distanceTo(target);
            if (dist <= PEST_AOTV_TRIGGER_DIST) return false;

            // Don't start if any detected pest is already within 10 blocks of the
            // player – close enough to reach via normal flight without teleporting.
            Vec3d eyePos = player.getEyePos();
            for (PestEntityDetector.PestEntity nearPest : pestEntityDetector.getDetectedPests()) {
                if (eyePos.distanceTo(nearPest.position()) <= PEST_AOTV_NEAR_PEST_SKIP_DIST) {
                    return false;
                }
            }

            // Find AOTV/AOTE in the hotbar.
            int slot = findAotvSlotForPest(player);
            if (slot < 0) return false;

            // Calculate how many teleport hops are needed to reach within approachRadius
            // of the target.  For plot-centre travel approachRadius is 0 so all the
            // distance is covered; for pest travel approachRadius is the kill radius so
            // only the hops required to enter kill range are fired.
            double remainingDist = Math.max(0.0, dist - approachRadius);
            // Already within approach radius – no teleport needed.
            if (remainingDist <= 0.0) return false;
            int clicks = (int) Math.ceil(remainingDist / PEST_AOTV_TELEPORT_DIST);
            clicks = Math.min(clicks, PEST_AOTV_MAX_CLICKS);

            // Compute the yaw and pitch toward the target so diagonal/downward
            // teleports are possible when the pest is below the player.
            Vec3d eye  = player.getEyePos();
            pestAotvTargetYaw        = computeYawTo(eye, target);
            pestAotvTargetPitch      = computePitchTo(eye, target);
            pestAotvSlot             = slot;
            pestAotvSeqStart         = now;
            pestAotvTotalClicks      = clicks;
            pestAotvClicksDone       = 0;
            pestAotvNextEventTime    = 0L;  // fire first click immediately after aim
            pestAotvClickHeld        = false;
            pestAotvAllClicksDoneTime = 0L;
            lastSmoothLookTime = 0; // reset so camera rotation starts smoothly
            releaseMovementKeys();
            LOGGER.info("[Just Farming-PestKiller] AOTV sequence started: {} hop(s) toward "
                    + "({}, {}, {}), yaw={}, pitch={}, dist={} blocks, approachRadius={} blocks.",
                    clicks, (int) target.x, (int) target.y, (int) target.z,
                    (int) pestAotvTargetYaw, (int) pestAotvTargetPitch, (int) dist, (int) approachRadius);
        }

        long elapsed = now - pestAotvSeqStart;

        // ── Phase 1: smooth-rotate toward the target ────────────────────────────
        if (elapsed < PEST_AOTV_AIM_MS) {
            targetYaw   = pestAotvTargetYaw;
            targetPitch = pestAotvTargetPitch;
            releaseMovementKeys();
            return true;
        }

        // Keep aim locked for all phases after the initial rotation.
        player.setYaw(pestAotvTargetYaw);
        player.setPitch(pestAotvTargetPitch);
        player.getInventory().setSelectedSlot(pestAotvSlot);
        // Keep flying forward toward the target while teleporting so the player
        // continuously closes the gap rather than hovering in place.
        // Sneak key is explicitly released so shift-descent never fires during AOTV use.
        if (client.options != null) {
            client.options.forwardKey.setPressed(true);
            client.options.sneakKey.setPressed(false);
        }

        // ── Phase 2: multi-click spam ───────────────────────────────────────────
        // Abort the sequence early if the player is now within 10 blocks of any
        // detected pest – there is no need to teleport further.
        if (pestAotvAllClicksDoneTime == 0L) {
            Vec3d eyeNow = player.getEyePos();
            for (PestEntityDetector.PestEntity nearPest : pestEntityDetector.getDetectedPests()) {
                if (eyeNow.distanceTo(nearPest.position()) <= PEST_AOTV_NEAR_PEST_SKIP_DIST) {
                    LOGGER.info("[Just Farming-PestKiller] AOTV sequence aborted: pest within {} blocks.",
                            PEST_AOTV_NEAR_PEST_SKIP_DIST);
                    if (client.options != null) client.options.useKey.setPressed(false);
                    pestAotvLastFireTime = now;
                    resetAotvState();
                    if (vacuumSlot >= 0) player.getInventory().setSelectedSlot(vacuumSlot);
                    return false;
                }
            }
        }

        if (pestAotvAllClicksDoneTime == 0L) {
            if (pestAotvClickHeld) {
                // Release the use-key once the brief hold duration has elapsed.
                if (now >= pestAotvNextEventTime) {
                    if (client.options != null) client.options.useKey.setPressed(false);
                    pestAotvClickHeld = false;
                    pestAotvClicksDone++;
                    LOGGER.info("[Just Farming-PestKiller] AOTV click {}/{} released (slot={}, yaw={}).",
                            pestAotvClicksDone, pestAotvTotalClicks,
                            pestAotvSlot, (int) pestAotvTargetYaw);
                    if (pestAotvClicksDone >= pestAotvTotalClicks) {
                        // All hops fired – begin the post-click wait.
                        pestAotvAllClicksDoneTime = now;
                    } else {
                        // Schedule next press with a random PEST_AOTV_MIN_CPS–PEST_AOTV_MAX_CPS CPS delay.
                        int cps = PEST_AOTV_MIN_CPS + random.nextInt(PEST_AOTV_CPS_RANGE);
                        long interClickDelay = 1000L / cps;
                        pestAotvNextEventTime = now + interClickDelay;
                    }
                }
            } else {
                // Fire the next press when the scheduled time arrives.
                if (now >= pestAotvNextEventTime) {
                    if (client.options != null) client.options.useKey.setPressed(true);
                    pestAotvClickHeld    = true;
                    pestAotvNextEventTime = now + PEST_AOTV_HOLD_MS;
                    LOGGER.info("[Just Farming-PestKiller] AOTV firing click {} of {} (slot={}, yaw={}).",
                            pestAotvClicksDone + 1, pestAotvTotalClicks,
                            pestAotvSlot, (int) pestAotvTargetYaw);
                }
            }
            return true;
        }

        // ── Phase 3: wait for the final teleport to land ────────────────────────
        if (now - pestAotvAllClicksDoneTime < PEST_AOTV_WAIT_MS) {
            return true;
        }

        // ── Sequence complete ────────────────────────────────────────────────────
        if (client.options != null) client.options.useKey.setPressed(false);
        pestAotvLastFireTime = now;
        int completedClicks = pestAotvTotalClicks; // save before resetAotvState() zeroes it
        resetAotvState();
        // Restore vacuum so killing can continue immediately.
        if (vacuumSlot >= 0) {
            player.getInventory().setSelectedSlot(vacuumSlot);
            LOGGER.info("[Just Farming-PestKiller] AOTV sequence complete ({} hops); "
                    + "restored vacuum slot {}.", completedClicks, vacuumSlot);
        }
        return false; // sequence done – caller may resume normal flight
    }

    /**
     * Moves to {@code nextPlot}, resets per-plot state, and begins navigation.
     *
     * <p>When {@link FarmingConfig#pestKillerWarpToPlot} is {@code false} the
     * player is already in the garden after the initial {@code /warp garden};
     * in that case the routine flies directly to the next plot centre (using
     * AOTV/AOTE if available) rather than issuing another {@code /warp garden}
     * and paying the full round-trip teleport penalty.  If the plot centre
     * cannot be computed (unknown plot name) the method falls back to the
     * normal PRE_TELEPORT_WAIT path.
     *
     * <p>When {@link FarmingConfig#pestKillerWarpToPlot} is {@code true} the
     * existing {@link State#PRE_TELEPORT_WAIT} → {@code /tptoplot} flow is used.
     */
    private void teleportToNextPlot(String nextPlot) {
        LOGGER.info("[Just Farming-PestKiller] No pests found on this plot; "
                + "moving to next infested plot: {}.", nextPlot);
        resetPlotState();
        currentPlotName = nextPlot;

        // Without /tptoplot the player is already somewhere in the garden;
        // fly directly toward the next plot centre at high altitude rather than re-warping.
        if (config != null && !config.pestKillerWarpToPlot && nextPlot != null) {
            double cx = GardenPlot.getCentreX(nextPlot);
            double cz = GardenPlot.getCentreZ(nextPlot);
            if (!Double.isNaN(cx) && !Double.isNaN(cz)) {
                double flightY = NAV_ALTITUDE_BASE + random.nextInt(NAV_ALTITUDE_RANGE);
                plotCentreTarget = new Vec3d(cx, flightY, cz);
                resetAotvState();
                LOGGER.info("[Just Farming-PestKiller] Flying directly to plot {} centre ({}, {}, {}).",
                        nextPlot, (int) cx, (int) flightY, (int) cz);
                enterState(State.GOING_TO_PLOT_CENTER);
                return;
            }
        }

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
     * Removes and returns the plot in {@link #remainingPlots} whose centre is
     * closest to the player's current XZ position.  Falls back to
     * {@link java.util.LinkedList#poll()} (head of queue) when the player is
     * unavailable or no plot centre can be computed.
     *
     * <p>Calling this instead of {@code remainingPlots.poll()} ensures that the
     * macro always travels to the nearest infested plot next, minimising travel
     * time between plots.
     */
    private String pollClosestPlot() {
        if (remainingPlots.isEmpty()) return null;
        ClientPlayerEntity player = client.player;
        if (player == null) return remainingPlots.poll();

        double px = player.getX();
        double pz = player.getZ();
        String closest = null;
        double minDist = Double.MAX_VALUE;
        for (String plot : remainingPlots) {
            double cx = GardenPlot.getCentreX(plot);
            double cz = GardenPlot.getCentreZ(plot);
            if (Double.isNaN(cx) || Double.isNaN(cz)) {
                if (closest == null) closest = plot; // keep as fallback
                continue;
            }
            double d = Math.sqrt((px - cx) * (px - cx) + (pz - cz) * (pz - cz));
            if (d < minDist) {
                minDist = d;
                closest = plot;
            }
        }
        if (closest != null) remainingPlots.remove(closest);
        return closest;
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
            LOGGER.warn("[Just Farming-PestKiller] No vacuum item found in hotbar.");
            return;
        }

        // Auto-detect kill range from the vacuum item name
        if (detectedVacuumRange < 0 && foundName != null) {
            String lowerName = foundName.toLowerCase();
            for (Map.Entry<String, Double> entry : VACUUM_RANGES.entrySet()) {
                if (lowerName.contains(entry.getKey())) {
                    detectedVacuumRange = entry.getValue();
                    LOGGER.info("[Just Farming-PestKiller] Auto-detected vacuum '{}' with kill range {} blocks.",
                            foundName, detectedVacuumRange);
                    break;
                }
            }
            if (detectedVacuumRange < 0) {
                LOGGER.info("[Just Farming-PestKiller] Unknown vacuum '{}'; using configured/default range.", foundName);
            }
        }

        // Detect the farming tool slot once, before switching away from it.
        preVacuumSlot = findFarmingToolSlot(player, found);
        vacuumSlot = found;
        player.getInventory().setSelectedSlot(vacuumSlot);
        LOGGER.info("[Just Farming-PestKiller] Equipped vacuum from hotbar slot {} (farming tool at slot {}).",
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
            LOGGER.info("[Just Farming-PestKiller] Using configured farming tool slot {}.",
                    config.farmingToolHotbarSlot);
            return config.farmingToolHotbarSlot;
        }

        // 2. Auto-detect: first non-vacuum hotbar slot that holds a known farming tool.
        for (int i = 0; i < 9; i++) {
            if (i == vacuumIdx) continue;
            ItemStack stack = player.getInventory().getStack(i);
            if (isFarmingTool(stack)) {
                LOGGER.info("[Just Farming-PestKiller] Auto-detected farming tool '{}' at hotbar slot {}.",
                        getCleanItemName(stack), i);
                return i;
            }
        }

        // 3. Fallback: first non-vacuum, non-empty hotbar slot.
        for (int i = 0; i < 9; i++) {
            if (i == vacuumIdx) continue;
            if (!player.getInventory().getStack(i).isEmpty()) {
                LOGGER.info("[Just Farming-PestKiller] No named farming tool found; using first non-empty slot {}.", i);
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
            LOGGER.info("[Just Farming-PestKiller] Restored hotbar slot {}.", preVacuumSlot);
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
                LOGGER.debug("[Just Farming-PestKiller] Ceiling detected; starting avoidance manoeuvre.");
            }
            executeCeilingAvoidance(player, now, target);
            return;
        }

        // Cap the effective target Y so the player never cruises above MAX_CRUISE_Y.
        // When the target is higher than the cap the player flies horizontally at the
        // cap altitude rather than climbing beyond it; if the target is at or below the
        // cap the original Y is used unchanged (including descending toward low pests).
        Vec3d effectiveTarget = (target.y > MAX_CRUISE_Y)
                ? new Vec3d(target.x, MAX_CRUISE_Y, target.z)
                : target;

        // ── 1-block wall avoidance ─────────────────────────────────────────────
        // If there is a solid block at the player's foot level in the direction of
        // travel but the block above the head is clear, this is a 1-block-tall wall
        // that can be cleared by flying 1.5 blocks higher.  Temporarily raise the
        // effective target Y so the camera pitches upward and creative-flight carries
        // the player over the obstacle without pressing the jump key (which can
        // trigger Hypixel's anti-cheat when used rapidly).
        double dyToTarget = target.y - player.getY();
        if (dyToTarget < 2.0 && hasOneBlockWallAtFoot(player, effectiveTarget)) {
            double clearY = Math.max(effectiveTarget.y, player.getY() + WALL_CLEARANCE_HEIGHT);
            effectiveTarget = new Vec3d(effectiveTarget.x, clearY, effectiveTarget.z);
        }

        Vec3d eye = player.getEyePos();

        // ── Altitude-climb priority (tptoplot disabled) ───────────────────────
        // When the player is below the minimum AOTV altitude and the horizontal
        // distance to the target is large enough to warrant AOTV, the direct
        // aim at the distant target produces a very shallow pitch angle that makes
        // the player move mostly horizontally and ascend very slowly.  In that
        // case, aim instead at an intermediate waypoint at PEST_AOTV_MIN_FLY_Y
        // directly above (offset 10 % toward the target) so the camera tilts
        // steeply upward and the player climbs quickly to the minimum altitude
        // before AOTV kicks in.
        double horizDistEarly = Math.sqrt(
                Math.pow(effectiveTarget.x - eye.x, 2) +
                Math.pow(effectiveTarget.z - eye.z, 2));
        if (player.getY() < PEST_AOTV_MIN_FLY_Y
                && effectiveTarget.y >= PEST_AOTV_MIN_FLY_Y
                && horizDistEarly > PEST_AOTV_TRIGGER_DIST) {
            // Intermediate waypoint: slightly toward target to keep the yaw correct,
            // but prioritise the climb by targeting a point directly above us.
            Vec3d climbTarget = new Vec3d(
                    eye.x + (effectiveTarget.x - eye.x) * CLIMB_WAYPOINT_HORIZ_FRACTION,
                    PEST_AOTV_MIN_FLY_Y + CLIMB_WAYPOINT_ALTITUDE_MARGIN,
                    eye.z + (effectiveTarget.z - eye.z) * CLIMB_WAYPOINT_HORIZ_FRACTION);
            lookAt(player, climbTarget);
            client.options.forwardKey.setPressed(true);
            client.options.jumpKey.setPressed(false); // pitch handles vertical; no extra jump
            client.options.sneakKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.backKey.setPressed(false);
            return;
        }

        // Point camera at the target (includes pitch for vertical direction)
        lookAt(player, effectiveTarget);

        double dist = eye.distanceTo(effectiveTarget);

        // Horizontal (XZ-plane) distance to the target, used to detect the
        // "pest directly below" case where the camera yaw is undefined.
        double horizDistToTarget = Math.sqrt(
                Math.pow(effectiveTarget.x - eye.x, 2) +
                Math.pow(effectiveTarget.z - eye.z, 2));

        // Suppress forward movement when camera is still rotating toward the target,
        // UNLESS the target is almost directly below (horizDist < 1.5) in which
        // case the yaw is undefined and we should not gate on alignment.
        float yawError = targetYaw - player.getYaw();
        while (yawError >  180f) yawError -= 360f;
        while (yawError < -180f) yawError += 360f;

        boolean directlyBelow = horizDistToTarget < 1.5 && effectiveTarget.y < eye.y - 1.0;
        boolean shouldFly = directlyBelow || Math.abs(yawError) <= MAX_FLY_YAW_ERROR_DEGREES;

        // Slow down when close to the pest
        if (shouldFly && dist < BRAKE_RADIUS) {
            // Pulse forward key: move one tick out of every ~(BRAKE_RADIUS/dist) ticks
            long ticks = client.world != null ? client.world.getTime() : 0;
            int pulseStride = Math.max(1, (int) Math.ceil(BRAKE_RADIUS / Math.max(1.0, dist)));
            shouldFly = (ticks % pulseStride == 0);
        }

        // In creative flight, vertical movement is driven entirely by camera pitch +
        // the forward key: pointing the camera up/down and pressing forward causes
        // genuine 3D diagonal movement.  Holding the jump key to "avoid the ground"
        // fights against natural descent and generates illegal-move corrections from
        // Hypixel's anti-cheat.  Similarly, obstacle-triggered jumps produce rapid
        // vertical oscillation near garden crops.  The jump key is therefore used
        // only when the target is genuinely above the player (dy > 1.5), and the
        // sneak key is suppressed near the ground to prevent landing on crops.
        // Exception: when the pest is almost directly below, the sneak key is always
        // allowed to descend regardless of ground proximity – otherwise the player
        // would hover above a ground-level pest with no movement keys pressed.
        double dy = effectiveTarget.y - eye.y;
        boolean isNearGround = hasGroundBelow(player);
        boolean shouldJump  = dy > 1.5;
        boolean shouldSneak = (!isNearGround || directlyBelow) && dy < -1.0;

        // ── Stuck detection ─────────────────────────────────────────────────
        if (lastProgressPos == null) {
            lastProgressPos      = eye;
            lastProgressCheckTime = now;
        }
        if (now - lastProgressCheckTime >= STUCK_CHECK_INTERVAL_MS) {
            double progress = lastProgressPos.distanceTo(eye);
            if (progress < MIN_PROGRESS_PER_INTERVAL && dist > MIN_STUCK_CHECK_DIST) {
                if (progress < ZERO_PROGRESS_THRESHOLD) {
                    // Essentially zero movement – player may be stuck inside a solid
                    // block (common after a bad /tptoplot).  Count consecutive zeroes
                    // and fall back to /warp garden if it persists.
                    zeroProgressCount++;
                    if (zeroProgressCount >= ZERO_PROGRESS_MAX_COUNT) {
                        LOGGER.info("[Just Farming-PestKiller] Player stuck in block (zero progress for {} checks); falling back to /warp garden.",
                                zeroProgressCount);
                        fallbackToWarpGarden();
                        return;
                    }
                } else {
                    zeroProgressCount = 0;
                }
                // Player has not moved enough – alternate strafe direction and hold it briefly
                strafeDirection = (strafeDirection >= 0) ? -1 : 1;
                strafeEndTime   = now + STRAFE_DURATION_MS;
                LOGGER.debug("[Just Farming-PestKiller] Stuck (progress={} blocks); strafing {}.",
                        String.format("%.2f", progress), strafeDirection > 0 ? "right" : "left");
            } else {
                zeroProgressCount = 0;
            }
            lastProgressPos       = eye;
            lastProgressCheckTime = now;
        }

        boolean isStrafeActive = now < strafeEndTime && strafeDirection != 0;

        // When the unstuck strafe is active and the effective target is above the player,
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
            LOGGER.debug("[Just Farming-PestKiller] Player not flying; initiating double-jump sequence.");
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
            LOGGER.debug("[Just Farming-PestKiller] Double-jump sequence completed.");
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
     * Returns {@code true} if there is at least one non-air block within
     * {@link #GROUND_CLEARANCE_BLOCKS} below the player's feet.
     *
     * <p>Used by {@link #flyToward} to prevent the player from landing on crops
     * or the garden floor: when the ground is this close, sneak-descent is
     * suppressed and the jump key is held to maintain a safe minimum altitude.
     */
    private boolean hasGroundBelow(ClientPlayerEntity player) {
        if (client.world == null) return false;
        int bx    = (int) Math.floor(player.getX());
        int bz    = (int) Math.floor(player.getZ());
        int feetY = (int) Math.floor(player.getY());
        for (int dy = 1; dy <= GROUND_CLEARANCE_BLOCKS; dy++) {
            if (!client.world.getBlockState(new BlockPos(bx, feetY - dy, bz)).isAir()) {
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
                LOGGER.debug("[Just Farming-PestKiller] Ceiling avoidance: backed up, now rising above ceiling.");
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
                LOGGER.debug("[Just Farming-PestKiller] Ceiling avoidance complete; resuming normal pathfinding.");
            }
        }
    }

    /**
     * Returns {@code true} if there is at least one non-air block at foot or
     * head level 1.5 blocks ahead of the player in the horizontal direction
     * toward {@code target}.
     *
     * <p>Used by {@link #flyToward} to trigger jumping one block higher whenever
     * a non-air block would otherwise obstruct horizontal forward flight,
     * ensuring the player always passes over obstacles rather than flying into
     * them.  Any non-air block (including crops, fences, etc.) counts as an
     * obstacle since the player cannot fly through it.
     */
    private boolean hasObstacleInPath(ClientPlayerEntity player, Vec3d target) {
        if (client.world == null) return false;
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        if (horizDist < 0.5) return false; // already very close; skip check
        // Unit vector in the horizontal direction toward the target
        double nx = dx / horizDist;
        double nz = dz / horizDist;
        // Check 1.5 blocks ahead so the player has enough lead time to rise
        // above the obstacle before reaching it.
        int bx = (int) Math.floor(player.getX() + nx * 1.5);
        int bz = (int) Math.floor(player.getZ() + nz * 1.5);
        int by = (int) Math.floor(player.getY()); // feet Y
        return !client.world.getBlockState(new BlockPos(bx, by,     bz)).isAir()
            || !client.world.getBlockState(new BlockPos(bx, by + 1, bz)).isAir();
    }

    /**
     * Returns {@code true} when there is a solid block at the player's foot
     * level 1.5 blocks ahead in the direction toward {@code target}, but the
     * block two above the foot (i.e. one above the player's head) is clear.
     *
     * <p>This identifies a <em>1-block-tall wall</em> – an obstacle the player
     * can fly straight over by ascending just 1.5 blocks, without needing to
     * navigate around it.  {@link #flyToward} uses this to temporarily raise
     * the effective navigation target so the camera pitches upward and
     * creative-flight carries the player cleanly over the wall.
     */
    private boolean hasOneBlockWallAtFoot(ClientPlayerEntity player, Vec3d target) {
        if (client.world == null) return false;
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        if (horizDist < WALL_MIN_CHECK_DISTANCE) return false;
        double nx = dx / horizDist;
        double nz = dz / horizDist;
        int bx = (int) Math.floor(player.getX() + nx * WALL_PROBE_DISTANCE);
        int bz = (int) Math.floor(player.getZ() + nz * WALL_PROBE_DISTANCE);
        int by = (int) Math.floor(player.getY()); // feet Y
        boolean footBlocked   = !client.world.getBlockState(new BlockPos(bx, by,     bz)).isAir();
        boolean aboveHeadClear = client.world.getBlockState(new BlockPos(bx, by + 2, bz)).isAir();
        return footBlocked && aboveHeadClear;
    }

    /**
     * Returns {@code true} if there is at least one non-air block adjacent to
     * the player in any of the four cardinal horizontal directions at foot or
     * head level.
     *
     * <p>Used by {@link #handlePestAotvToward} to prevent firing AOTV/AOTE
     * when the player has just warped and is standing next to a wall (e.g. the
     * barn structure after {@code /warp garden}).  The player should first fly
     * clear of the obstacle via {@link #flyToward} before teleporting.
     */
    private boolean isNearWall(ClientPlayerEntity player) {
        if (client.world == null) return false;
        int bx = (int) Math.floor(player.getX());
        int bz = (int) Math.floor(player.getZ());
        int by = (int) Math.floor(player.getY());
        // Check all four cardinal directions at feet (by) and head (by+1) level.
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dir : dirs) {
            if (!client.world.getBlockState(new BlockPos(bx + dir[0], by,     bz + dir[1])).isAir()
             || !client.world.getBlockState(new BlockPos(bx + dir[0], by + 1, bz + dir[1])).isAir()) {
                return true;
            }
        }
        return false;
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
     * Computes the Minecraft yaw (degrees) from {@code from} to {@code to}
     * on the horizontal plane.
     *
     * <p>Uses the standard Minecraft convention where 0° = south (+Z) and
     * 90° = west (−X), matching the sign produced by
     * {@code atan2(-dx, dz)}.
     *
     * @param from the source position
     * @param to   the destination position
     * @return yaw in degrees, in the range [−180, 180)
     */
    private static float computeYawTo(Vec3d from, Vec3d to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /**
     * Computes the Minecraft pitch (degrees) from {@code from} to {@code to}.
     *
     * <p>In Minecraft convention, positive pitch = looking down, negative = up.
     * The result is clamped to [−90, 90].
     *
     * @param from the source (eye) position
     * @param to   the destination position
     * @return pitch in degrees
     */
    private static float computePitchTo(Vec3d from, Vec3d to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        return (float) Math.toDegrees(Math.atan2(-dy, distXZ));
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
     *   <li>{@link FarmingConfig#pestKillerVacuumRange} when manually set (&gt; 0) –
     *       the user's distance-slider setting always takes highest priority so
     *       the configured range is respected exactly.</li>
     *   <li>Auto-detected range from the equipped vacuum's display name (see
     *       {@link #VACUUM_RANGES}).</li>
     *   <li>Hardcoded fallback {@link #KILL_RADIUS} (5 blocks).</li>
     * </ol>
     */
    private double getEffectiveKillRadius() {
        // User-configured value takes highest priority – respects the in-game
        // distance slider setting.
        if (config != null && config.pestKillerVacuumRange > 0) {
            return config.pestKillerVacuumRange;
        }
        if (detectedVacuumRange > 0) {
            return detectedVacuumRange;
        }
        return KILL_RADIUS;
    }

    /**
     * Returns the horizontal (XZ-plane) distance between two points.
     * Used to determine proximity to a pest independently of vertical offset,
     * so that the player can enter and stay in the kill state even when
     * directly above a ground-level pest.
     *
     * @param x1 X coordinate of the first point
     * @param z1 Z coordinate of the first point
     * @param x2 X coordinate of the second point
     * @param z2 Z coordinate of the second point
     * @return Euclidean distance on the XZ plane
     */
    private static double horizontalDistance(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void returnToFarm() {
        returnWarpSentAt = 0;
        enterState(State.RETURNING);
    }

    /**
     * Emergency fallback triggered when the player has made zero movement for
     * several consecutive stuck-detection checks (indicating they are stuck inside
     * a solid block after a bad {@code /tptoplot}).
     *
     * <p>Releases all movement keys, clears the remaining plot queue, sends
     * {@code /warp garden}, and re-enters the {@link State#TELEPORTING} wait so
     * the routine scans for pests at the garden spawn rather than attempting
     * further plot teleports.
     */
    private void fallbackToWarpGarden() {
        releaseMovementKeys();
        remainingPlots.clear();
        currentPlotName = null;
        zeroProgressCount = 0;
        lastProgressPos = null;
        lastProgressCheckTime = 0;
        strafeEndTime = 0;
        strafeDirection = 0;
        LOGGER.info("[Just Farming-PestKiller] Stuck-in-block fallback: sending /warp garden "
                + "and scanning from garden (skipping remaining plot teleports).");
        sendCommand("warp garden");
        int afterTpDelay = (config != null && config.pestKillerAfterTeleportDelay >= 0)
                ? config.pestKillerAfterTeleportDelay : (int) SCAN_WAIT_MS;
        int globalRandom = (config != null) ? config.globalRandomizationMs : 0;
        teleportWaitMs = afterTpDelay + random.nextInt(Math.max(1, globalRandom));
        enterState(State.TELEPORTING);
    }

    private void sendCommand(String command) {
        if (client.player != null && client.player.networkHandler != null) {
            client.player.networkHandler.sendChatCommand(command);
        }
    }

    /**
     * Scans all slots in a {@link HandledScreen} for an item whose plain-text
     * name contains {@code keyword} (case-insensitive) and left-clicks it.
     *
     * @param screen  the currently open handled screen
     * @param keyword substring to match against each item's display name
     * @return {@code true} if a matching slot was found and clicked
     */
    private boolean tryClickWardrobeSlotWithName(HandledScreen<?> screen, String keyword) {
        if (client.player == null) return false;
        if (!(client.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) {
            return false;
        }
        String lowerKw = keyword.toLowerCase();
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            String name = COLOR_CODE_PATTERN.matcher(stack.getName().getString())
                    .replaceAll("").toLowerCase();
            if (name.contains(lowerKw)) {
                client.interactionManager.clickSlot(
                        handler.syncId, i, 0, SlotActionType.PICKUP, client.player);
                return true;
            }
        }
        return false;
    }
}
