package com.justfarming.visitor;

import com.justfarming.config.FarmingConfig;
import com.justfarming.pest.PestKillerManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.CarpetBlock;
import net.minecraft.block.AbstractPressurePlateBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages the garden-visitor interaction routine for Just Farming.
 *
 * <p>When {@link FarmingConfig#visitorsEnabled} is {@code true} the macro
 * calls {@link #start()} instead of warping back to the Garden directly.
 * The routine:
 * <ol>
 *   <li>Sends {@code /tptoplot barn} to teleport to the barn area.</li>
 *   <li>Scans for nearby living entities (visitor NPCs) with a custom name.</li>
 *   <li>Walks toward each visitor and right-clicks to open their menu.</li>
 *   <li>Parses the opened inventory screen to extract required items.</li>
 *   <li>Closes the menu and (if {@link FarmingConfig#visitorsBuyFromBazaar}
 *       is {@code true}) sends {@code /bazaar <item>} for each requirement,
 *       clicking "Buy Instantly" and confirming the purchase.</li>
 *   <li>Re-opens the visitor menu and clicks "Accept Offer".</li>
 *   <li>Repeats for every visitor found.</li>
 *   <li>Sends {@code /warp garden} to return to farming.</li>
 * </ol>
 */
public class VisitorManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("just-farming");

    // ── Timing constants ────────────────────────────────────────────────────

    /** Default time to wait after {@code /tptoplot barn} before scanning (ms). */
    private static final long TELEPORT_WAIT_DEFAULT_MS = 4000;

    /**
     * Non-configurable random extra added on top of the teleport delay (ms).
     * The actual teleport wait is {@code visitorsTeleportDelay + random(0, TELEPORT_EXTRA_RANDOM_MS)}.
     */
    private static final long TELEPORT_EXTRA_RANDOM_MS = 200;

    /** Default base delay for every visitor action (ms). */
    private static final long ACTION_DELAY_DEFAULT_MS = 600;

    /**
     * Maximum time (ms) to keep polling for a bazaar screen / item to appear before
     * giving up.  Allows the routine to survive server lag without skipping purchases.
     */
    private static final long BAZAAR_WAIT_MS = 1500;

    /**
     * Minimum time (ms) to wait after the visitor menu opens before parsing its
     * slot contents.  This floor ensures all slot-data packets sent by the server
     * after the screen-open packet have arrived, even when the user configures a
     * very short (or zero) action delay.  Without this guard the parse can run
     * while slots are still empty, producing zero requirements and causing the
     * macro to skip the bazaar step.
     */
    private static final long VISITOR_MENU_MIN_PARSE_DELAY_MS = 500;

    /** Timeout for the sign-editor screen to appear after clicking "Buy Instantly" (ms). */
    private static final long ENTERING_AMOUNT_TIMEOUT_MS = 3000;

    /**
     * Minimum time (ms) to wait in ENTERING_AMOUNT before falling back to the
     * HandledScreen branch.  This gives the server enough time to close the
     * bazaar screen and open the sign editor before we decide that the sign
     * is not coming.
     */
    private static final long ENTERING_AMOUNT_HANDLED_SCREEN_FALLBACK_MS = 1500;

    /** Minimum ms between consecutive entity-interact attempts. */
    private static final long INTERACT_COOLDOWN_MS = 1200;

    /** How long to scan for visitors before giving up and returning to the farm (ms). */
    private static final long SCAN_TIMEOUT_MS = 3000;

    /** Pause after {@code /warp garden} to allow the command to register (ms). */
    private static final long WARP_COMMAND_WAIT_MS = 3000;

    /**
     * Minimum pause (ms) after the bazaar GUI closes before the player starts
     * walking toward the visitor.  Prevents the abrupt camera snap that occurs
     * when movement begins immediately after an ESC close.
     */
    private static final long POST_BAZAAR_WALK_DELAY_MS = 1000;

    /**
     * Camera rotation speed (degrees per second) for smooth look-at movement.
     * The rotation is time-based (using elapsed wall-clock milliseconds) so the
     * angular speed is independent of frame rate: at this rate the camera covers
     * 180° in one second, matching the pest-killer rotation speed for consistent
     * yaw/pitch tracking of visitor NPCs.
     * Reduced by 35% from 180°/s to 117°/s for a more natural-looking movement.
     */
    private static final float SMOOTH_LOOK_DEGREES_PER_SECOND = 117.0f;

    /**
     * Faster camera rotation speed (degrees/second) used when the player is
     * already within {@link #VISITOR_DETECT_RADIUS} of a visitor and needs to
     * align quickly but still smoothly (not a hard snap).
     * Reduced by 35% from 540°/s to 351°/s for a more natural-looking movement.
     */
    private static final float FAST_LOOK_DEGREES_PER_SECOND = 351.0f;

    /** How long (ms) to hold the space key for each press in the disable-flight sequence. */
    private static final long DISABLE_FLIGHT_PRESS_MS  = 100L;
    /** Gap (ms) between the two space-key presses in the disable-flight sequence. */
    private static final long DISABLE_FLIGHT_GAP_MS    = 60L;
    /** Extra wait (ms) after the second press before declaring the sequence complete. */
    private static final long DISABLE_FLIGHT_DONE_MS   = 200L;

    /**
     * Assumed elapsed time (ms) used on the very first {@code smoothRotateCamera}
     * call, before a previous timestamp is available.  50 ms corresponds to one
     * tick at the standard 20 TPS rate.
     */
    private static final float SMOOTH_LOOK_INITIAL_DELTA_MS = 50.0f;

    /**
     * Maximum elapsed time (ms) used in {@code smoothRotateCamera}.
     * Caps the step during a severe lag spike so the camera doesn't teleport
     * to the target after a multi-second freeze.
     */
    private static final float SMOOTH_LOOK_MAX_DELTA_MS = 250.0f;

    /**
     * Exponential acceleration factor for camera rotation.
     * At progress=0 speed = 1× base; at progress=1 speed = (1 + factor)× base.
     */
    private static final float SMOOTH_LOOK_ACCEL_FACTOR = 3.0f;

    /**
     * Maximum random yaw offset (degrees) applied to the walking direction so the
     * visitor approach path varies slightly between runs (humanlike behaviour).
     */
    private static final float WALK_JITTER_MAX_DEGREES = 10.0f;

    /**
     * How often (ms) the random walk-direction jitter is re-randomised.
     * Keeping this fairly short (< 1 s) produces natural-looking path variation.
     */
    private static final long WALK_JITTER_INTERVAL_MS = 800;

    /**
     * Vertical offset (blocks) added to the walk-target Y when computing the
     * camera pitch during {@link #walkToward}.  Aiming at roughly head height
     * of the target produces a more natural look-direction than aiming at the
     * target's foot position.
     */
    private static final double WALK_PITCH_HEAD_OFFSET = 1.0;

    /**
     * Minimum horizontal XZ distance (blocks) used as a divisor when computing
     * the pitch toward the walk target.  Prevents division by zero when the
     * target is directly above or below the player's eye.
     */
    private static final double WALK_PITCH_MIN_DIST_XZ = 0.01;

    /**
     * Distance threshold (blocks) above {@link #INTERACT_RADIUS} at which the
     * off-axis navigation offset is active.  When the player is within this
     * distance of the visitor the offset is cleared and the camera aims directly.
     */
    private static final double NAV_OFFSET_DISABLE_DIST = 1.5;

    /**
     * Minimum lateral offset (blocks) applied to the navigation target while
     * walking toward a visitor from far away.  Keeps the camera slightly
     * off-axis so the visitor trade menu is not accidentally triggered.
     */
    private static final float NAV_OFFSET_MIN_BLOCKS = 1.0f;

    /**
     * Random range (blocks) added on top of {@link #NAV_OFFSET_MIN_BLOCKS}
     * when computing the lateral navigation offset, giving a total range of
     * {@code NAV_OFFSET_MIN_BLOCKS} to
     * {@code NAV_OFFSET_MIN_BLOCKS + NAV_OFFSET_RANGE_BLOCKS}.
     */
    private static final float NAV_OFFSET_RANGE_BLOCKS = 1.5f;

    /**
     * Minimum landing radius (blocks) from V5 used when randomising the
     * AOTV/AOTE teleport landing position.
     */
    private static final double AOTV_LANDING_MIN_RADIUS = 1.0;

    /**
     * Random range (blocks) added on top of {@link #AOTV_LANDING_MIN_RADIUS}
     * when choosing the AOTV landing offset, giving a total radius of
     * {@code AOTV_LANDING_MIN_RADIUS} to
     * {@code AOTV_LANDING_MIN_RADIUS + AOTV_LANDING_RADIUS_RANGE} (1–3 blocks).
     */
    private static final double AOTV_LANDING_RADIUS_RANGE = 2.0;

    /**
     * Steer-angle offsets (degrees) tried in order when the direct path is blocked by a wall.
     * Covers small nudges first (likely NPC avoidance) through wide detours (±90°) so the
     * pathfinder can navigate around corners before resorting to a forced-forward fallback.
     */
    private static final float[] WALL_STEER_ANGLES = { 15f, -15f, 30f, -30f, 45f, -45f, 60f, -60f, 90f, -90f };

    // ── Sign-editor key constants ────────────────────────────────────────────

    /** GLFW key code for Backspace. */
    private static final int GLFW_KEY_BACKSPACE = 259;

    /**
     * Number of backspace presses sent to clear the sign editor's default text.
     * Hypixel Skyblock sign prompts are always short, so 50 presses is more
     * than enough to clear any pre-filled text.
     */
    private static final int MAX_SIGN_TEXT_CLEAR_ITERATIONS = 50;

    /** Horizontal search radius (blocks) around the player when scanning for visitors. */
    private static final double SCAN_RADIUS = 32.0;

    /** Distance (blocks) at which the player is considered "close enough" to interact. */
    private static final double INTERACT_RADIUS = 3.5;

    /**
     * Radius (blocks) within which a visitor NPC is detected for fast-rotation
     * mode.  When the player comes within this distance of the current visitor,
     * the camera switches to the faster {@link #FAST_LOOK_DEGREES_PER_SECOND}
     * rotation rate so it aligns quickly for the interact packet.
     */
    private static final double VISITOR_DETECT_RADIUS = 1.8;

    /**
     * Maximum angular error (degrees) between the player's current yaw/pitch and
     * the visitor's direction before the macro will send the interact packet.
     * Requiring the camera to be aimed within this threshold ensures the player
     * visibly looks at the visitor before right-clicking, preventing the
     * "magically interacting at a distance" appearance.
     */
    private static final float INTERACT_AIM_THRESHOLD_DEGREES = 10.0f;

    /**
     * Minimum distance (blocks) the player maintains from non-target visitor NPCs
     * while navigating.  When the chosen path would pass within this radius of
     * another visitor, the pathfinder steers around them.
     */
    private static final double NPC_AVOIDANCE_DIST = 2.5;

    /**
     * Amplitude (degrees) of the random tremor added to each camera rotation step
     * in {@link #smoothRotateCamera}, replicating the micro-vibration of a real
     * mouse player.  Small enough to be imperceptible to a human observer.
     */
    private static final float SMOOTH_LOOK_TREMOR_AMPLITUDE = 0.12f;

    /**
     * Scale factor applied to the pitch component of the camera tremor.
     * Humans produce less vertical hand-shake than horizontal, so pitch
     * tremor is intentionally smaller than yaw tremor.
     */
    private static final float SMOOTH_LOOK_TREMOR_PITCH_SCALE = 0.5f;

    /**
     * Angular threshold (degrees) within which the camera is considered "on
     * target" and only micro-rotation corrections are applied.  When both yaw
     * and pitch errors are smaller than this value the camera makes tiny
     * random adjustments rather than full rotations, simulating natural hand
     * micro-tremor when a player holds their mouse still while aiming at a target.
     */
    private static final float MICRO_ROTATION_THRESHOLD_DEGREES = 2.0f;

    /**
     * Amplitude (degrees) of the micro-rotation corrections applied when the
     * camera is already within {@link #MICRO_ROTATION_THRESHOLD_DEGREES} of
     * the target.  Smaller than {@link #SMOOTH_LOOK_TREMOR_AMPLITUDE} to
     * produce very subtle movements that keep the aim alive without drifting.
     */
    private static final float MICRO_ROTATION_AMPLITUDE = 0.08f;

    /**
     * How far ahead (blocks) to probe the terrain when navigating.
     * Slightly more than half a block so we reliably detect edges and walls
     * before the player's centre reaches them.
     * At high SkyBlock movement speeds this value is scaled up dynamically.
     */
    private static final double PROBE_STEP = 0.6;



    /**
     * Maximum angular error (degrees) between the player's current yaw and the
     * chosen walk direction before walking is suppressed at normal (1×) speed.
     * At higher SkyBlock movement speeds this value is tightened proportionally
     * (down to a minimum of {@link #MIN_WALK_YAW_ERROR_DEGREES}) so the player
     * doesn't overshoot the visitor when moving at extreme speeds.
     */
    private static final float MAX_WALK_YAW_ERROR_DEGREES = 45f;

    /**
     * Minimum angular error threshold (degrees) applied at high movement speeds.
     * The effective threshold is {@code max(MIN_WALK_YAW_ERROR_DEGREES,
     * MAX_WALK_YAW_ERROR_DEGREES / speedMult)}, guaranteeing the player is always
     * aimed to within this angle before forward movement is enabled.
     */
    private static final float MIN_WALK_YAW_ERROR_DEGREES = 15f;

    /**
     * Minimum cosine/sine magnitude for a forward or strafe component to be
     * considered "dominant enough" to press the corresponding key in
     * {@link #pressKeysTowardNoRotate}.  A value of 0.3 corresponds to roughly
     * ±72° from the axis, giving a comfortable dead-zone that avoids jittery
     * key toggling when the target is almost exactly to the side.
     */
    private static final double APPROACH_KEY_THRESHOLD = 0.3;

    /**
     * Vanilla-default player walk-speed attribute value.
     * Used as a baseline to measure how much faster the player is moving on
     * Hypixel SkyBlock (due to Speed buffs from armour, pets, enchants, etc.).
     */
    private static final double BASE_WALK_SPEED = 0.1;

    // ── Wall-crash / stuck recovery ──────────────────────────────────────────

    /**
     * Interval (ms) between position-progress checks used to detect when the
     * player is stuck against a wall.  Shorter than {@code WALK_JITTER_INTERVAL_MS}
     * so a crash is detected before the jitter re-randomises.
     */
    private static final long   WALK_STUCK_CHECK_INTERVAL_MS    = 1000L;

    /**
     * Minimum distance (blocks) the player must have travelled since the last
     * progress check to be considered "not stuck".  Very small to allow for
     * low-speed braking near the visitor target.
     */
    private static final double WALK_MIN_PROGRESS_PER_INTERVAL  = 0.3;

    /**
     * How long (ms) to hold the back key during the initial phase of wall-crash
     * recovery, briefly separating the player from the wall before strafing.
     */
    private static final long   WALK_RECOVERY_BACKUP_MS         = 400L;

    /**
     * How long (ms) to hold the strafe key after the backup phase, steering the
     * player around the obstacle that caused the crash.
     */
    private static final long   WALK_RECOVERY_STRAFE_MS         = 600L;

    /**
     * Minimum cooldown (ms) between successive jump-to-clear-wall presses.
     * Prevents continuously triggering jumps with Jump Boost active, which
     * would launch the player far above the barn area.  The player only jumps
     * when a full block (non-stair/slab) is directly blocking forward progress,
     * and at most once per this interval.
     */
    private static final long   WALK_JUMP_COOLDOWN_MS           = 1500L;

    // ── Aspect of the Void / Aspect of the End teleport constants ────────────

    /**
     * Time (ms) to smooth the camera toward the AOTV/AOTE teleport direction
     * before firing the right-click.  Gives a human-like aiming delay.
     */
    private static final long AOTV_AIM_DELAY_MS      = 600L;

    /**
     * Duration (ms) to hold the right-click (use-key) when triggering the
     * AOTV/AOTE teleport ability.  Minecraft registers a single use within
     * this window.
     */
    private static final long AOTV_CLICK_HOLD_MS     = 100L;

    /**
     * Time (ms) to wait after the right-click before resuming navigation.
     * This gives the server time to process the teleport and update the
     * player's position before we start walking again.
     */
    private static final long AOTV_TELEPORT_WAIT_MS  = 1000L;

    /**
     * Default AOTV teleport distance (blocks) used when the lore cannot be parsed.
     */
    private static final int  AOTV_DEFAULT_DISTANCE  = 8;

    /**
     * Maximum distance from the current visitor at which the wall-mode AOTV
     * teleport is triggered.  When the player is within this range of the
     * target visitor AND a 1-block wall is detected ahead, the teleport fires.
     */
    private static final double AOTV_WALL_TRIGGER_DIST = 20.0;

    /**
     * Distance (blocks) from V1 at which the close-approach AOTV mode fires.
     * When the player is within this range of V1 (the nearest visitor) and the
     * path toward V5 is clear (see {@link #isPathClearToward}), the AOTV/AOTE
     * teleport fires toward V5.  This threshold replaces {@link #INTERACT_RADIUS}
     * in barn skins with tall decorative walls where the player can walk right
     * up to V1 before teleporting over the internal wall.
     */
    private static final double AOTV_CLOSE_APPROACH_DIST = 0.5;

    /** Pattern for parsing the teleport distance from AOTV/AOTE item lore. */
    private static final Pattern AOTV_TELEPORT_PATTERN =
            Pattern.compile("teleport\\s+(\\d+)\\s+blocks", Pattern.CASE_INSENSITIVE);

    /**
     * Display-name substring (lower-case) for the Aspect of the Void sword.
     * Matched case-insensitively against stripped item names in the hotbar.
     */
    private static final String AOTV_ITEM_NAME = "aspect of the void";

    /**
     * Display-name substring (lower-case) for the Aspect of the End sword.
     * Matched case-insensitively against stripped item names in the hotbar.
     */
    private static final String AOTE_ITEM_NAME = "aspect of the end";

    /**
     * Maximum number of main-series visitors (V1–V5) involved in the
     * reversed AOTV trading order.  Hypixel SkyBlock allows up to 5
     * concurrent barn visitors, so the main series is at most 5 entries;
     * since V1 is held separately as {@link #currentVisitor} during the
     * approach phase, up to 4 additional visitors (V2–V5) remain in
     * {@link #pendingVisitors} when the reversal is computed.
     */
    private static final int AOTV_MAIN_VISITOR_REVERSE_COUNT = 4;

    /**
     * Pitch angle (degrees) used when aiming the AOTV/AOTE before firing.
     * A small upward angle (-15°) ensures the crosshair passes above the
     * visitor NPC hitboxes, preventing accidental menu interactions during
     * the rotation phase.
     */
    private static final float AOTV_SAFE_PITCH = -15f;

    /**
     * Minimum start delay (ms) inserted before the first action of the visitor
     * routine.  Ensures the player has at least 1 second of idle time after
     * issuing the {@code /just visitor} command before any movement begins.
     */
    private static final long VISITOR_START_DELAY_MS = 1000L;

    // ── State machine ────────────────────────────────────────────────────────

    /** Internal states of the visitor routine. */
    public enum State {
        /** Not doing anything. */
        IDLE,
        /** Waiting 1 second before starting movement (inserted by {@code /just visitor}). */
        PRE_START_WAIT,
        /** Double-pressing space to turn off creative flight before the routine begins. */
        DISABLING_FLIGHT,
        /** Waiting for the server to teleport the player to the barn. */
        TELEPORTING,
        /** Looking for visitor NPC entities near the player. */
        SCANNING,
        /** Walking toward the {@link #currentVisitor}. */
        NAVIGATING,
        /**
         * Aiming and right-clicking with an Aspect of the Void / Aspect of the End
         * to teleport into the visitor area.  The player rotates to a safe pitch/yaw
         * (aimed past the visitor NPCs) then fires a single right-click, after which
         * the trading queue is built in farthest-first order (V5→V4→V3→V2→V1→V6?).
         */
        USING_AOTV,
        /** Interact packet sent; waiting for the visitor menu to appear. */
        INTERACTING,
        /** The visitor menu is open; parsing required items. */
        READING_VISITOR_MENU,
        /** Waiting for the visitor menu to close before the next action. */
        CLOSING_MENU,
        /** Simulating the player typing {@code /bazaar <item>}; waiting bazaarSearchDelay ms. */
        TYPING_BAZAAR_COMMAND,
        /** {@code /bazaar <item>} sent; waiting for the bazaar search-results screen. */
        OPENING_BAZAAR,
        /** Bazaar search-results screen is open; clicking the matching item. */
        CLICKING_BAZAAR_ITEM,
        /** The bazaar item page is open; navigating to "Buy Instantly". */
        READING_BAZAAR,
        /** "Buy Instantly" clicked; waiting for the sign screen to enter the quantity. */
        ENTERING_AMOUNT,
        /** Quantity entered in sign; waiting for a confirmation screen. */
        CONFIRMING_PURCHASE,
        /** All items bought; walking back to the visitor to accept their offer. */
        ACCEPTING_OFFER,
        /** Visitor menu re-opened; waiting for the accept click to register. */
        WAITING_FOR_ACCEPT,
        /** Sent {@code /warp garden}; brief pause before handing back to the macro. */
        RETURNING_TO_FARM,
        /** Routine finished; macro manager can transition back to farming. */
        DONE,
    }

    private State state = State.IDLE;
    private long  stateEnteredAt     = 0;
    private long  interactCooldownUntil = 0;
    /** Per-state cached action delay (ms), re-rolled on each state entry. */
    private long  currentActionDelay = ACTION_DELAY_DEFAULT_MS;
    /** Cached teleport wait (base + random extra), set when entering TELEPORTING. */
    private long  teleportWaitMs = TELEPORT_WAIT_DEFAULT_MS;
    /**
     * Per-state random extra (0–150 ms) added to non-configurable timing
     * constants so hardcoded delays vary slightly on each state entry.
     */
    private int   randomExtra150 = 0;
    /** Computed delay (rewarpDelayMin + random) to wait before sending /warp garden. */
    private long  returnWarpDelay = 0;
    /** Timestamp (ms) when /warp garden was sent in RETURNING_TO_FARM; 0 = not yet sent. */
    private long  returnWarpSentAt = 0;

    private final MinecraftClient client;
    private FarmingConfig config;
    private final Random random = new Random();

    // Smooth camera rotation targets
    private float targetYaw   = 0f;
    private float targetPitch = 0f;
    /** Wall-clock time (ms) of the previous smoothRotateCamera call; 0 = not yet called. */
    private long  lastSmoothLookTime = 0;
    /** Combined angular distance at rotation start; used for exponential acceleration. */
    private float initialAngularDist = 0f;
    /**
     * When {@code true}, {@link #onRenderTick()} rotates the camera at
     * {@link #FAST_LOOK_DEGREES_PER_SECOND} rather than the normal rate.
     * Set when the player is within {@link #VISITOR_DETECT_RADIUS} of a visitor.
     */
    private boolean fastRotateActive = false;

    /** Wall-clock time (ms) when the disable-flight sequence started; 0 = not active. */
    private long disableFlightStartTime = 0;

    // Walk-direction jitter for humanlike path variation
    /** Current random yaw offset (degrees) added to the walking direction. */
    private float walkJitter = 0f;
    /** Timestamp (ms) when the walk jitter should next be re-randomised. */
    private long  walkJitterNextUpdate = 0;

    /**
     * Lateral (perpendicular) offset in blocks applied to the approach navigation
     * target while the player is walking toward a visitor but still outside
     * {@link #INTERACT_RADIUS}.  Keeping the camera slightly off-axis prevents the
     * routine from accidentally triggering the visitor trade menu while still
     * approaching (e.g. right after a {@code /tptoplot barn} lands the player
     * very close to a visitor).  Re-randomised each time the {@link State#NAVIGATING}
     * state is entered.
     */
    private float navAimAsideBlocks = 0f;

    // Wall-crash / stuck-detection state for walkToward
    /** Player's position at the last stuck-detection progress check; {@code null} = not yet set. */
    private Vec3d walkLastProgressPos         = null;
    /** Timestamp (ms) of the last stuck-detection progress check. */
    private long  walkLastProgressCheckTime   = 0;
    /**
     * Direction of the active recovery-strafe: {@code +1} = right, {@code -1} = left,
     * {@code 0} = no active recovery.  Alternates on each successive crash so the
     * player tries both sides over time.
     */
    private int   walkRecoveryDirection       = 0;
    /**
     * Wall-clock time (ms) at which the back-up phase of wall-crash recovery ends.
     * {@code 0} means no backup is active.
     */
    private long  walkRecoveryBackupEndTime   = 0;
    /**
     * Wall-clock time (ms) at which the strafe phase of wall-crash recovery ends.
     * {@code 0} means no strafe recovery is active.
     */
    private long  walkRecoveryEndTime         = 0;
    /**
     * Wall-clock time (ms) of the last jump triggered to clear a blocking wall.
     * Used as a cooldown so the player does not continuously jump when a solid
     * block is ahead (which would launch the player far above with Jump Boost).
     */
    private long  walkLastJumpTime            = 0;

    // Stair-entry vertical navigation state
    // Visitor tracking
    private Entity       currentVisitor  = null;
    private final List<Entity> pendingVisitors = new ArrayList<>();
    /**
     * Entity IDs of visitors whose offer has already been accepted this run.
     * Used to prevent re-navigating to a visitor that is still physically present
     * after their offer was completed (they take a moment to despawn on Hypixel).
     */
    private final Set<Integer> completedVisitorIds = new HashSet<>();
    /**
     * {@code true} once the end-of-queue rescan has been performed this run.
     * Prevents a second scan if the queue runs out immediately after the first
     * rescan finds no additional visitors.
     */
    private boolean midRunRescanPerformed = false;

    /**
     * When {@code true}, the player has already reached the first visitor's
     * interact position and must not walk any further.  All subsequent visitors
     * are handled from this fixed anchor spot (Hypixel SkyBlock barn visitors
     * all spawn at the same visitor-point, so staying put is both faster and
     * more natural-looking than navigating to each NPC individually).
     * Not used when {@link #useAotv} is {@code true}.
     */
    private boolean positionAnchored = false;

    /**
     * The world position to look at for every interaction while
     * {@link #positionAnchored} is {@code true}.  Set to the first visitor's
     * position the moment the player enters {@link #INTERACT_RADIUS}.
     */
    private Vec3d anchorLookPos = null;

    // ── Aspect of the Void / Aspect of the End state ─────────────────────────

    /**
     * Hotbar slot of an "Aspect of the Void" or "Aspect of the End" item, or
     * {@code -1} if none was found during the last {@link #scanForVisitors} call.
     */
    private int     aotvSlot             = -1;

    /**
     * Teleport distance (blocks) parsed from the AOTV/AOTE item lore.
     * Defaults to {@link #AOTV_DEFAULT_DISTANCE} when the lore cannot be parsed.
     */
    private int     aotvTeleportDistance = AOTV_DEFAULT_DISTANCE;

    /**
     * {@code true} when an AOTV/AOTE item was detected and the current run
     * should use it to teleport into the visitor area before trading.
     * Only set when at least 2 visitors are present (teleport makes no sense
     * for a single visitor).
     */
    private boolean useAotv              = false;

    /**
     * {@code true} after the right-click use-key has been pressed in the
     * {@link State#USING_AOTV} state, preventing a second trigger.
     */
    private boolean aotvTeleportFired   = false;

    /**
     * Yaw angle (degrees) in which the AOTV/AOTE teleport should be aimed.
     * Computed in the {@link State#SCANNING} phase before entering
     * {@link State#USING_AOTV}.
     */
    private float   aotvTeleportYaw     = 0f;

    /**
     * Pitch angle (degrees) for the AOTV/AOTE aim.  Set to
     * {@link #AOTV_SAFE_PITCH} so the crosshair is aimed above the visitor
     * NPC hitboxes during the rotation phase.
     */
    private float   aotvTeleportPitch   = AOTV_SAFE_PITCH;

    /**
     * World position of the farthest visitor (V5) saved during scanning.
     * Used in no-wall AOTV mode to aim the teleport toward the far end of
     * the visitor row.  {@code null} when AOTV is not in use or the scan
     * found fewer than two visitors.
     */
    private Vec3d   aotvV5Pos           = null;

    // Item requirements extracted from the current visitor's menu
    private final List<VisitorRequirement> pendingRequirements = new ArrayList<>();
    private int requirementIndex = 0;

    // Sign-editor state for entering the purchase quantity
    private String amountToType   = null;
    private int    signTypingStep = 0;
    /** Timestamp of the last character typed into the sign editor (ms). */
    private long   signLastTypedAt = 0;

    /**
     * When {@code true}, the next {@link State#CLOSING_MENU} transition came
     * from accepting a visitor offer and should proceed to {@link #nextVisitor()}
     * rather than {@link #startAcceptingOffer()}.
     * Starts as {@code false} and is reset by {@link #start()} and {@link #stop()}.
     */
    private boolean postAccept = false;

    /**
     * When {@code true}, the next {@link State#CLOSING_MENU} transition came
     * from confirming a bazaar purchase and should call
     * {@link #nextRequirementOrAccept()} rather than restarting from
     * requirement index 0.
     * Starts as {@code false} and is reset by {@link #start()} and {@link #stop()}.
     */
    private boolean postPurchase = false;

    /**
     * When {@code true}, the current visitor's offer was declined because its
     * total NPC sell value exceeded {@link com.justfarming.config.FarmingConfig#visitorsMaxPrice}.
     * The next {@link State#CLOSING_MENU} transition will call {@link #nextVisitor()}
     * instead of proceeding to bazaar or accept.
     * Reset by {@link #start()} and {@link #stop()}.
     */
    private boolean skipCurrentVisitorDueToPrice = false;

    /**
     * When {@code true}, the current visitor's offer was declined because the
     * visitor's name appears in {@link com.justfarming.config.FarmingConfig#visitorBlacklist}.
     * The next {@link State#CLOSING_MENU} transition will call {@link #nextVisitor()}
     * without going to the bazaar or accepting the offer.
     * Reset by {@link #start()} and {@link #stop()}.
     */
    private boolean skipCurrentVisitorDueToBlacklist = false;

    // Regex patterns for requirement lines like "64x Wheat", "Wheat ×32", "64 Wheat"
    private static final Pattern PAT_AMOUNT_FIRST =
            Pattern.compile("(\\d[\\d,]*)\\s*[xX×]?\\s+(.+)");
    private static final Pattern PAT_AMOUNT_LAST  =
            Pattern.compile("(.+?)\\s*[xX×](\\d[\\d,]*)");

    /**
     * Canonical set of Hypixel SkyBlock Garden visitor NPC names.
     * Only entities whose custom name exactly matches one of these (after stripping
     * formatting codes) will be treated as visitors during pathfinding.
     * Source: https://hypixel-skyblock.fandom.com/wiki/The_Garden/Visitors
     */
    private static final Set<String> KNOWN_VISITOR_NAMES = Set.of(
            "Adventurer", "Alchemage", "Alchemist", "An", "Andrew", "Anita",
            "Archaeologist", "Arthur", "Baker", "Banker Broadjaw", "Bartender",
            "Bednom", "Beth", "Bruuh", "Carpenter", "Chantelle", "Chief Scorn",
            "Chunk", "Clerk Seraphine", "Cold Enjoyer", "Dalbrek", "Dante Goon",
            "Duke", "Dulin", "Duncan", "Dusk", "Elle", "Emissary Carlton",
            "Emissary Ceanna", "Emissary Fraiser", "Emissary Sisko", "Emissary Wilson",
            "Erihann", "Fann", "Farm Merchant", "Farmer Jon", "Farmhand",
            "Fear Mongerer", "Felix", "Fisherman Gerald", "Fragilis", "Friendly Hiker",
            "Frozen Alex", "Gary", "Gemma", "Geonathan Greatforge", "Gimley",
            "Gold Forger", "Grandma Wolf", "Guy", "Gwendolyn", "Hendrik", "Hoppity",
            "Hornum", "Hungry Hiker", "Iron Forger", "Jack", "Jacob", "Jacobus",
            "Jamie", "Jerry", "Jotraeline Greatforge", "Lazy Miner", "Leo", "Liam",
            "Librarian", "Lift Operator", "Ludleth", "Lumber Jack", "Lumina", "Lynn",
            "Madame Eleanor Q. Goldsworth III", "Maeve", "Marco", "Marigold", "Mason",
            "Master Tactician Funk", "Mayor Aatrox", "Mayor Cole", "Mayor Diana",
            "Mayor Diaz", "Mayor Finnegan", "Mayor Foxy", "Mayor Marina", "Mayor Paul",
            "Moby", "Odawa", "Old Man Garry", "Old Shaman Nyko", "Ophelia", "Oringo",
            "Pamela", "Pearl Dealer", "Pest Wrangler", "Pest Wrangler?", "Pete",
            "Phillip", "Plumber Joe", "Puzzler", "Queen Mismyla", "Queen Nyx",
            "Ravenous Rhino", "Resident Neighbor", "Resident Snooty", "Rhys", "Romero",
            "Royal Resident", "Rusty", "Ryan", "Ryu", "Sam", "Sargwyn",
            "Scout Scardius", "Seymour", "Shaggy",
            "Sherry", "Shifty", "Sirius", "Spaceman", "Spider Tamer", "St. Jerry",
            "Stella", "Tammy", "Tarwen", "Terry", "The Trapper", "Tia the Fairy",
            "Tom", "Tomioka", "Trevor", "Trinity", "Tyashoi Alchemist", "Tyzzo",
            "Vargul", "Vex", "Vincent", "Vinyl Collector", "Weaponsmith", "Wizard",
            "Xalx", "Zog"
    );

    /**
     * Names of NPCs that are permanently present in the Hypixel SkyBlock Garden
     * barn area <em>and</em> can also appear as visitors.  When an entity with
     * one of these names is found during a scan its raw (unstripped) custom name
     * must start with {@code "§6"} (gold, no bold) to be treated as a visitor.
     *
     * <p>In Hypixel SkyBlock, active visitor NPCs show their name in plain gold
     * ({@code §6Name}), while the same characters acting as permanent resident
     * NPCs use a different colour or bold formatting ({@code §6§lName},
     * {@code §eNpc}, etc.).  Checking the raw prefix prevents the macro from
     * navigating to, and attempting to trade with, these resident NPCs when they
     * are not in visitor mode.
     *
     * <p>If you observe that a legitimate visitor is being skipped, enable DEBUG
     * logging and look for the log line
     * {@code "Skipping garden-resident NPC … raw='…'"} to verify the raw name
     * format and adjust this check accordingly.
     */
    private static final Set<String> GARDEN_RESIDENT_NPC_NAMES = Set.of(
            "Sam", "Jacob", "Anita", "Phillip", "Pamela"
    );

    // ── Constructor ──────────────────────────────────────────────────────────

    public VisitorManager(MinecraftClient client, FarmingConfig config) {
        this.client = client;
        this.config = config;
    }

    /** Update the config reference (called after GUI saves). */
    public void setConfig(FarmingConfig config) {
        this.config = config;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /** Returns {@code true} while the visitor routine is running. */
    public boolean isActive() {
        return state != State.IDLE && state != State.DONE;
    }

    /** Returns {@code true} once the routine finishes (warp sent / no visitors). */
    public boolean isDone() {
        return state == State.DONE;
    }

    /** Returns the current internal state (for status display). */
    public State getState() {
        return state;
    }

    /**
     * Stop the visitor routine immediately and return to IDLE state.
     * Called when the player presses the toggle-macro key to cancel.
     */
    public void stop() {
        if (state == State.IDLE || state == State.DONE) return;
        LOGGER.info("[Just Farming-Visitors] Visitor routine stopped by user.");
        releaseMovementKeys();
        postAccept = false;
        postPurchase = false;
        skipCurrentVisitorDueToPrice = false;
        skipCurrentVisitorDueToBlacklist = false;
        returnWarpDelay = 0;
        returnWarpSentAt = 0;
        midRunRescanPerformed = false;
        positionAnchored = false;
        anchorLookPos = null;
        walkLastProgressPos = null;
        walkLastProgressCheckTime = 0;
        walkRecoveryDirection = 0;
        walkRecoveryBackupEndTime = 0;
        walkRecoveryEndTime = 0;
        walkLastJumpTime = 0;
        fastRotateActive = false;
        disableFlightStartTime = 0;
        useAotv = false;
        aotvTeleportFired = false;
        aotvSlot = -1;
        aotvTeleportDistance = AOTV_DEFAULT_DISTANCE;
        aotvV5Pos = null;
        aotvTeleportYaw = 0f;
        aotvTeleportPitch = AOTV_SAFE_PITCH;
        navAimAsideBlocks = 0f;
        enterState(State.IDLE);
    }


    public void start() {
        LOGGER.info("[Just Farming-Visitors] Starting visitor routine.");
        // Auto-swap to farming tool (same logic as MacroManager.start)
        ClientPlayerEntity player = client.player;
        if (player != null) {
            if (config.farmingToolHotbarSlot >= 0 && config.farmingToolHotbarSlot <= 8
                    && !player.getInventory().getStack(config.farmingToolHotbarSlot).isEmpty()) {
                player.getInventory().setSelectedSlot(config.farmingToolHotbarSlot);
                LOGGER.info("[Just Farming-Visitors] Switched to farming tool slot {}.", config.farmingToolHotbarSlot);
            } else if (config.farmingToolHotbarSlot < 0) {
                for (int i = 0; i < 9; i++) {
                    ItemStack stack = player.getInventory().getStack(i);
                    if (PestKillerManager.isFarmingTool(stack)) {
                        player.getInventory().setSelectedSlot(i);
                        LOGGER.info("[Just Farming-Visitors] Auto-detected farming tool '{}' at slot {}; switching.",
                                PestKillerManager.getCleanItemName(stack), i);
                        break;
                    }
                }
            }
        }
        pendingVisitors.clear();
        pendingRequirements.clear();
        completedVisitorIds.clear();
        requirementIndex  = 0;
        currentVisitor    = null;
        interactCooldownUntil = 0;
        postAccept        = false;
        postPurchase      = false;
        skipCurrentVisitorDueToPrice = false;
        skipCurrentVisitorDueToBlacklist = false;
        walkJitter        = 0f;
        walkJitterNextUpdate = 0;
        navAimAsideBlocks = 0f;
        lastSmoothLookTime = 0;
        initialAngularDist = 0f;
        fastRotateActive  = false;
        disableFlightStartTime = 0;
        returnWarpDelay   = 0;
        returnWarpSentAt  = 0;
        midRunRescanPerformed = false;
        positionAnchored  = false;
        anchorLookPos     = null;
        walkLastJumpTime  = 0;
        useAotv           = false;
        aotvTeleportFired = false;
        aotvSlot          = -1;
        aotvTeleportDistance = AOTV_DEFAULT_DISTANCE;
        aotvV5Pos         = null;
        aotvTeleportYaw   = 0f;
        aotvTeleportPitch = AOTV_SAFE_PITCH;
        long base = Math.max(0, config.visitorsTeleportDelay);
        teleportWaitMs = base + random.nextInt((int) TELEPORT_EXTRA_RANDOM_MS + 1)
                + random.nextInt(Math.max(1, config.globalRandomizationMs));
        // Initialise camera targets to the player's current rotation so the first
        // render tick in NAVIGATING does not snap the camera to a stale 0° default.
        if (player != null) {
            targetYaw   = player.getYaw();
            targetPitch = player.getPitch();
        }
        // Wait 1 second before starting any movement.
        enterState(State.PRE_START_WAIT);
    }

    /**
     * Called every render frame from the render thread to apply incremental
     * camera rotation toward {@link #targetYaw}/{@link #targetPitch}.
     *
     * <p>Running this at render-frame frequency (typically 60+ FPS) instead of
     * only on game ticks (20 TPS) produces micro-steps of roughly 0.5° per
     * frame at 60 FPS, well within the 0.2°–1.5° per-step range the
     * time-based formula computes.  Total angular speed stays at
     * {@link #SMOOTH_LOOK_DEGREES_PER_SECOND} because each step is proportional
     * to actual elapsed wall-clock time.
     *
     * <p>Only active while the routine is in a navigation or offer-accepting
     * state; all other states are ignored to avoid interfering with
     * other camera logic.
     */
    public void onRenderTick() {
        if (state != State.NAVIGATING && state != State.ACCEPTING_OFFER
                && state != State.USING_AOTV) return;
        ClientPlayerEntity player = client.player;
        if (player == null) return;
        float speed = fastRotateActive ? FAST_LOOK_DEGREES_PER_SECOND : SMOOTH_LOOK_DEGREES_PER_SECOND;
        smoothRotateCamera(player, speed);
    }

    /** Called every client tick from the main tick event. */
    public void onTick() {
        if (state == State.IDLE || state == State.DONE) return;

        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        long now = System.currentTimeMillis();

        switch (state) {

            case PRE_START_WAIT -> {
                // Wait 1 second before starting any movement or flight-disable.
                if (now - stateEnteredAt >= VISITOR_START_DELAY_MS) {
                    LOGGER.info("[Just Farming-Visitors] Pre-start delay elapsed; beginning routine.");
                    if (player.getAbilities().flying) {
                        LOGGER.info("[Just Farming-Visitors] Player is flying; disabling flight before starting routine.");
                        enterState(State.DISABLING_FLIGHT);
                    } else {
                        enterState(State.TELEPORTING);
                        sendCommand("tptoplot barn");
                    }
                }
            }

            case DISABLING_FLIGHT -> {
                // Double-press space to turn off creative-style flight before starting.
                if (disableFlightStartTime == 0) {
                    disableFlightStartTime = now;
                }
                long elapsed   = now - disableFlightStartTime;
                long phase1End = DISABLE_FLIGHT_PRESS_MS;
                long phase2End = DISABLE_FLIGHT_PRESS_MS + DISABLE_FLIGHT_GAP_MS;
                long phase3End = DISABLE_FLIGHT_PRESS_MS * 2 + DISABLE_FLIGHT_GAP_MS;

                if (elapsed >= phase3End + DISABLE_FLIGHT_DONE_MS) {
                    // Sequence complete – proceed to teleport whether or not flight
                    // is already off (it should be, but guard against edge cases).
                    disableFlightStartTime = 0;
                    if (client.options != null) client.options.jumpKey.setPressed(false);
                    LOGGER.info("[Just Farming-Visitors] Disable-flight sequence complete; teleporting.");
                    enterState(State.TELEPORTING);
                    sendCommand("tptoplot barn");
                } else {
                    boolean jumpPressed = elapsed < phase1End
                            || (elapsed >= phase2End && elapsed < phase3End);
                    if (client.options != null) {
                        client.options.jumpKey.setPressed(jumpPressed);
                        client.options.forwardKey.setPressed(false);
                    }
                }
            }

            case TELEPORTING -> {
                if (now - stateEnteredAt >= teleportWaitMs) {
                    LOGGER.info("[Just Farming-Visitors] Teleport wait elapsed; scanning.");
                    enterState(State.SCANNING);
                }
            }

            case SCANNING -> {
                scanForVisitors(player);
                if (!pendingVisitors.isEmpty()) {
                    int found = pendingVisitors.size();
                    int minCount = Math.max(1, config.visitorsMinCount);
                    if (found < minCount) {
                        LOGGER.info("[Just Farming-Visitors] Found {} visitor(s), fewer than minimum {}; returning to farm.",
                                found, minCount);
                        pendingVisitors.clear();
                        returnToFarm();
                    } else {
                        // Detect AOTV/AOTE. scanForVisitors already sorted pendingVisitors closest-first.
                        detectAndConfigureAotv(player);
                        currentVisitor = pendingVisitors.remove(0);
                        if (useAotv) {
                            // Enter USING_AOTV directly – no approach walk toward V1.
                            // aotvTeleportYaw and aotvTeleportPitch are already set by
                            // detectAndConfigureAotv (toward V5 at safe pitch).
                            aotvTeleportFired = false;
                            lastSmoothLookTime = 0;
                            initialAngularDist = 0f;
                            // Prime targetYaw/targetPitch to the AOTV direction NOW so that
                            // onRenderTick() starts rotating the camera toward the correct
                            // aim point from the very first render frame.  Without this,
                            // there is a window of one or more render frames (between this
                            // enterState call and the first onTick handling of USING_AOTV)
                            // where targetYaw still points at the old stale direction,
                            // producing a visible "wrong-direction then snap" camera jerk.
                            targetYaw   = aotvTeleportYaw;
                            targetPitch = aotvTeleportPitch;
                            LOGGER.info("[Just Farming-Visitors] Found {} visitor(s). AOTV detected; "
                                    + "rotating to safe angle and teleporting toward V5 to trade V5→V1.",
                                    pendingVisitors.size() + 1);
                            enterState(State.USING_AOTV);
                        } else {
                            LOGGER.info("[Just Farming-Visitors] Found {} visitor(s). Trading closest-first.",
                                    pendingVisitors.size() + 1);
                            enterState(State.NAVIGATING);
                        }
                    }
                } else if (now - stateEnteredAt >= SCAN_TIMEOUT_MS) {
                    // Waited 3 s and still no visitors – go back to farming
                    LOGGER.info("[Just Farming-Visitors] No visitors found; returning to farm.");
                    returnToFarm();
                }
            }

            case NAVIGATING -> {
                if (currentVisitor == null || !currentVisitor.isAlive()) {
                    nextVisitor();
                    return;
                }

                Vec3d visitorPos = new Vec3d(currentVisitor.getX(), currentVisitor.getY(), currentVisitor.getZ());
                double dist = new Vec3d(player.getX(), player.getY(), player.getZ()).distanceTo(visitorPos);

                // ── Normal navigation (non-AOTV, or AOTV after the teleport) ────
                if (positionAnchored && !useAotv) {
                    // Anchored (non-AOTV only): stand still and wait for each visitor
                    // to come within interact range.
                    releaseMovementKeys();
                    boolean withinRange = dist <= INTERACT_RADIUS;
                    fastRotateActive = withinRange;
                    float rotationSpeed = withinRange ? FAST_LOOK_DEGREES_PER_SECOND : SMOOTH_LOOK_DEGREES_PER_SECOND;
                    Vec3d lookTarget = anchorLookPos != null ? anchorLookPos : visitorPos;
                    lookAt(player, lookTarget, rotationSpeed);
                    if (withinRange && isAimedAtTarget(player) && now >= interactCooldownUntil) {
                        fastRotateActive = false;
                        interactWithEntity(player, currentVisitor);
                        interactCooldownUntil = now + INTERACT_COOLDOWN_MS + randomExtra150;
                        enterState(State.INTERACTING);
                    }
                } else if (dist <= INTERACT_RADIUS) {
                    releaseMovementKeys();
                    if (!useAotv) {
                        // Non-AOTV: anchor at this position for all subsequent visitors.
                        positionAnchored = true;
                        anchorLookPos    = visitorPos;
                    }
                    fastRotateActive = dist <= VISITOR_DETECT_RADIUS;
                    Vec3d lookTarget = (!useAotv && anchorLookPos != null) ? anchorLookPos : visitorPos;
                    lookAt(player, lookTarget, fastRotateActive
                            ? FAST_LOOK_DEGREES_PER_SECOND
                            : SMOOTH_LOOK_DEGREES_PER_SECOND);
                    if (isAimedAtTarget(player) && now >= interactCooldownUntil) {
                        fastRotateActive = false;
                        interactWithEntity(player, currentVisitor);
                        interactCooldownUntil = now + INTERACT_COOLDOWN_MS + randomExtra150;
                        enterState(State.INTERACTING);
                    }
                } else {
                    fastRotateActive = false;
                    // When still walking toward the visitor, aim slightly to the side
                    // (navAimAsideBlocks) so the camera never points directly at them
                    // during approach.  This prevents the macro from accidentally
                    // opening the trade menu when the player teleports close to a visitor.
                    // The offset is cleared once the player is within INTERACT_RADIUS
                    // and the routine switches to direct look-at for the interact.
                    Vec3d walkTarget = dist > INTERACT_RADIUS + NAV_OFFSET_DISABLE_DIST
                            ? computeOffAxisNavTarget(player, visitorPos, navAimAsideBlocks)
                            : visitorPos;
                    walkToward(player, walkTarget);
                }
            }

            case USING_AOTV -> {
                long elapsed = now - stateEnteredAt;

                // Phase 1: Smoothly rotate the camera toward the teleport direction.
                // targetYaw/targetPitch were already primed when entering this state
                // (so onRenderTick() started rotating toward the correct target from
                // the very first frame); they are re-asserted here every tick to keep
                // the smooth rotation going until the camera actually arrives.
                if (elapsed < AOTV_AIM_DELAY_MS) {
                    targetYaw   = aotvTeleportYaw;
                    targetPitch = aotvTeleportPitch;
                    fastRotateActive = true;
                    releaseMovementKeys();
                    return;
                }

                // Phase 2: Fire the right-click once the camera has smoothly reached
                // the target direction.  Continue rotating at the fast rate until the
                // aim is within the threshold, then snap for precision and fire.
                // A hard timeout (2× aim delay) ensures the click always fires even
                // if tremor keeps the camera marginally off-target.
                if (!aotvTeleportFired) {
                    float yawErr = player.getYaw() - aotvTeleportYaw;
                    while (yawErr >  180f) yawErr -= 360f;
                    while (yawErr < -180f) yawErr += 360f;
                    float pitchErr = player.getPitch() - aotvTeleportPitch;

                    boolean aimed = Math.abs(yawErr) <= INTERACT_AIM_THRESHOLD_DEGREES
                            && Math.abs(pitchErr) <= INTERACT_AIM_THRESHOLD_DEGREES;
                    if (!aimed && elapsed < AOTV_AIM_DELAY_MS * 2) {
                        // Camera not yet on target – keep rotating smoothly.
                        targetYaw        = aotvTeleportYaw;
                        targetPitch      = aotvTeleportPitch;
                        fastRotateActive = true;
                        return;
                    }
                    fastRotateActive = false;

                    // Add small random offsets to the final yaw/pitch snap to vary the
                    // landing position slightly between runs (more human-like behaviour).
                    float randomizedSnapYaw   = aotvTeleportYaw   + (random.nextFloat() * 2f - 1f) * 3.0f;
                    float randomizedSnapPitch = aotvTeleportPitch + (random.nextFloat() * 2f - 1f) * 2.0f;
                    player.setYaw(randomizedSnapYaw);
                    player.setPitch(randomizedSnapPitch);
                    player.getInventory().setSelectedSlot(aotvSlot);
                    if (client.options != null) {
                        client.options.useKey.setPressed(true);
                    }
                    aotvTeleportFired = true;
                    LOGGER.info("[Just Farming-Visitors] AOTV right-click fired (yaw={}, pitch={}).",
                            (int) randomizedSnapYaw, (int) randomizedSnapPitch);
                    return;
                }

                // Release the use key after the brief hold duration.
                if (elapsed >= AOTV_AIM_DELAY_MS + AOTV_CLICK_HOLD_MS) {
                    if (client.options != null) {
                        client.options.useKey.setPressed(false);
                    }
                }

                // Phase 3: Wait for the teleport to land, then build the reversed
                // trading queue and start navigating to the farthest visitor (V5).
                if (elapsed >= AOTV_AIM_DELAY_MS + AOTV_CLICK_HOLD_MS + AOTV_TELEPORT_WAIT_MS) {
                    if (client.options != null) {
                        client.options.useKey.setPressed(false);
                    }

                    // pendingVisitors currently holds [V2, V3, V4, V5, V6?…].
                    // currentVisitor is V1 (the approach target).
                    // Build the trading order: V5, V4, V3, V2, V1, V6…
                    int mainCount = Math.min(AOTV_MAIN_VISITOR_REVERSE_COUNT, pendingVisitors.size());
                    List<Entity> mainReversed = new ArrayList<>(pendingVisitors.subList(0, mainCount));
                    List<Entity> extra        = new ArrayList<>(pendingVisitors.subList(mainCount, pendingVisitors.size()));
                    Collections.reverse(mainReversed);          // [V5, V4, V3, V2]
                    mainReversed.add(currentVisitor);           // [V5, V4, V3, V2, V1]
                    pendingVisitors.clear();
                    pendingVisitors.addAll(mainReversed);
                    pendingVisitors.addAll(extra);              // [V5, V4, V3, V2, V1, V6?]

                    currentVisitor    = pendingVisitors.remove(0); // V5

                    String cvName = currentVisitor != null && currentVisitor.getCustomName() != null
                            ? stripFormatting(currentVisitor.getCustomName().getString()) : "?";
                    LOGGER.info("[Just Farming-Visitors] AOTV teleport complete; navigating to '{}' next "
                            + "({} visitors remaining).", cvName, pendingVisitors.size());

                    // Restore farming tool now that the AOTV teleport is done.
                    restoreToFarmingTool(player);

                    enterState(State.NAVIGATING);
                }
            }

            case INTERACTING -> {
                if (now - stateEnteredAt >= currentActionDelay) {
                    if (client.currentScreen instanceof HandledScreen<?>) {
                        enterState(State.READING_VISITOR_MENU);
                    } else {
                        // Menu did not open; retry navigation
                        enterState(State.NAVIGATING);
                    }
                }
            }

            case READING_VISITOR_MENU -> {
                if (client.currentScreen instanceof HandledScreen<?> screen) {
                    // Wait the longer of (actionDelay, VISITOR_MENU_MIN_PARSE_DELAY_MS)
                    // before parsing so that all slot-data packets sent by the server
                    // after the screen-open packet have time to arrive.
                    // Without this guard the parse can run while slots are still empty,
                    // producing zero requirements and causing the macro to skip bazaar.
                    if (now - stateEnteredAt >= Math.max(currentActionDelay, VISITOR_MENU_MIN_PARSE_DELAY_MS)) {
                        boolean declining = false;
                        if (isCurrentVisitorBlacklisted()) {
                            // Visitor is blacklisted – click "Refuse Offer" so the server
                            // records the decline, then close the menu and move on.
                            String bName = currentVisitor != null && currentVisitor.getCustomName() != null
                                    ? stripFormatting(currentVisitor.getCustomName().getString()) : "unknown";
                            LOGGER.info("[Just Farming-Visitors] Blacklisted visitor {}; clicking Refuse Offer.", bName);
                            tryClickDeclineOffer(screen);
                            if (currentVisitor != null) {
                                completedVisitorIds.add(currentVisitor.getId());
                            }
                            skipCurrentVisitorDueToBlacklist = true;
                            declining = true;
                        } else {
                            parseVisitorMenu(screen);
                            if (skipCurrentVisitorDueToPrice) {
                                // Price limit exceeded – click "Decline Offer" in the GUI
                                // before closing the menu so the server records the decline.
                                tryClickDeclineOffer(screen);
                                declining = true;
                            }
                        }
                        if (!declining) {
                            // Close the screen before opening the bazaar (or accepting).
                            player.closeHandledScreen();
                        }
                        // When declining, do NOT close immediately: let the server close the
                        // screen after processing the Refuse Offer click.  CLOSING_MENU will
                        // force-close with a fallback timeout if the server does not do so.
                        enterState(State.CLOSING_MENU);
                    }
                } else {
                    enterState(State.NAVIGATING);
                }
            }

            case CLOSING_MENU -> {
                if (client.currentScreen == null) {
                    if (postAccept) {
                        // Offer was accepted – move on to the next visitor.
                        postAccept = false;
                        nextVisitor();
                    } else if (skipCurrentVisitorDueToBlacklist) {
                        // Visitor declined because it is blacklisted – skip to the next one.
                        skipCurrentVisitorDueToBlacklist = false;
                        nextVisitor();
                    } else if (skipCurrentVisitorDueToPrice) {
                        // Visitor declined due to price limit – skip to the next one.
                        skipCurrentVisitorDueToPrice = false;
                        nextVisitor();
                    } else if (postPurchase) {
                        // A bazaar purchase was just confirmed – advance to the
                        // next requirement or (if all done) accept the offer.
                        postPurchase = false;
                        nextRequirementOrAccept();
                    } else if (!pendingRequirements.isEmpty() && config.visitorsBuyFromBazaar) {
                        requirementIndex = 0;
                        openBazaarForCurrentRequirement();
                    } else {
                        startAcceptingOffer();
                    }
                } else if ((skipCurrentVisitorDueToBlacklist || skipCurrentVisitorDueToPrice || postAccept)
                        && now - stateEnteredAt > currentActionDelay + 1500L) {
                    // Fallback: the visitor menu did not auto-close after "Accept/Refuse Offer".
                    // Force-close it so the routine can continue to the next visitor.
                    player.closeHandledScreen();
                }
            }

            case TYPING_BAZAAR_COMMAND -> {
                long typingDelay = Math.max(0, config.bazaarSearchDelay);
                if (now - stateEnteredAt >= typingDelay) {
                    String itemName = pendingRequirements.get(requirementIndex).itemName;
                    sendCommand("bazaar " + itemName);
                    enterState(State.OPENING_BAZAAR);
                    LOGGER.info("[Just Farming-Visitors] Opening bazaar for: {}", itemName);
                }
            }

            case OPENING_BAZAAR -> {
                // Poll every tick until the bazaar search-results screen opens.
                // Only give up after BAZAAR_WAIT_MS so server lag doesn't cause skips.
                if (client.currentScreen instanceof HandledScreen<?>) {
                    if (now - stateEnteredAt >= currentActionDelay) {
                        enterState(State.CLICKING_BAZAAR_ITEM);
                    }
                } else if (now - stateEnteredAt >= BAZAAR_WAIT_MS) {
                    LOGGER.warn("[Just Farming-Visitors] Bazaar screen did not open; skipping.");
                    nextRequirementOrAccept();
                }
            }

            case CLICKING_BAZAAR_ITEM -> {
                // Wait the minimum action delay, then keep polling until the item appears
                // in the bazaar results (server lag can delay item rendering).
                if (now - stateEnteredAt >= currentActionDelay) {
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        String itemName = pendingRequirements.get(requirementIndex).itemName;
                        if (tryClickItemByName(screen, itemName)) {
                            enterState(State.READING_BAZAAR);
                        } else if (now - stateEnteredAt >= BAZAAR_WAIT_MS) {
                            LOGGER.warn("[Just Farming-Visitors] Could not find '{}' in bazaar; skipping.", itemName);
                            player.closeHandledScreen();
                            nextRequirementOrAccept();
                        }
                        // else: results may still be loading due to lag – keep polling
                    } else if (now - stateEnteredAt >= BAZAAR_WAIT_MS) {
                        nextRequirementOrAccept();
                    }
                }
            }

            case READING_BAZAAR -> {
                // Wait the minimum action delay, then poll until "Buy Instantly" is visible.
                if (now - stateEnteredAt >= currentActionDelay) {
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        if (tryClickBuyInstantly(screen)) {
                            int amount = pendingRequirements.get(requirementIndex).amount;
                            amountToType  = String.valueOf(amount);
                            signTypingStep = 0;
                            enterState(State.ENTERING_AMOUNT);
                        } else if (now - stateEnteredAt >= BAZAAR_WAIT_MS) {
                            player.closeHandledScreen();
                            nextRequirementOrAccept();
                        }
                        // else: item page may still be loading due to lag – keep polling
                    } else if (now - stateEnteredAt >= BAZAAR_WAIT_MS) {
                        nextRequirementOrAccept();
                    }
                }
            }

            case ENTERING_AMOUNT -> {
                net.minecraft.client.gui.screen.Screen currentScreen = client.currentScreen;
                if (currentScreen instanceof net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen signScreen) {
                    // Step 0: clear any default text already in the sign with backspaces
                    if (signTypingStep == 0) {
                        for (int i = 0; i < MAX_SIGN_TEXT_CLEAR_ITERATIONS; i++) {
                            signScreen.keyPressed(new net.minecraft.client.input.KeyInput(GLFW_KEY_BACKSPACE, 0, 0));
                        }
                        signTypingStep = 1;
                        signLastTypedAt = now; // start 300 ms typing-spread timer
                    } else if (amountToType != null && signTypingStep <= amountToType.length()) {
                        // Space digits so the full sequence takes at least 300 ms.
                        // For N digits: perCharMs = max(75, 300/N) → total ≥ 300 ms for N ≤ 4;
                        // for N > 4 the 75 ms floor keeps pacing human-like (total > 300 ms).
                        long perCharMs = Math.max(75L, 300L / (long) amountToType.length());
                        if (now - signLastTypedAt >= perCharMs) {
                            signScreen.charTyped(new net.minecraft.client.input.CharInput(
                                    (int) amountToType.charAt(signTypingStep - 1), 0));
                            signTypingStep++;
                            signLastTypedAt = now;
                        }
                    } else {
                        // All digits typed – close the sign editor via its close() method,
                        // which calls finishEditing() to send the sign text to the server.
                        signScreen.close();
                        enterState(State.CONFIRMING_PURCHASE);
                    }
                } else if (currentScreen instanceof HandledScreen<?> screen
                        && now - stateEnteredAt >= ENTERING_AMOUNT_HANDLED_SCREEN_FALLBACK_MS) {
                    // Hypixel shows an amount-selection screen after "Buy Instantly"
                    // that contains a "Custom Amount" sign item.  Click it to open
                    // the sign editor; reset the state timer so we wait for the sign.
                    if (tryClickSlotWithName(screen, "Custom Amount")) {
                        stateEnteredAt = now;
                    } else {
                        // No Custom Amount button found – fall back to confirming
                        // whatever screen is currently open.
                        tryClickConfirm(screen);
                        postPurchase = true;
                        enterState(State.CLOSING_MENU);
                    }
                } else if (currentScreen == null && now - stateEnteredAt >= 500) {
                    // Screen closed on its own (purchase may have completed)
                    nextRequirementOrAccept();
                } else if (now - stateEnteredAt >= ENTERING_AMOUNT_TIMEOUT_MS) {
                    LOGGER.warn("[Just Farming-Visitors] Timed out waiting for sign screen; skipping requirement.");
                    if (currentScreen != null) {
                        player.closeHandledScreen();
                    }
                    nextRequirementOrAccept();
                }
            }

            case CONFIRMING_PURCHASE -> {
                // Poll until the confirmation screen appears (lag can delay it).
                if (now - stateEnteredAt >= currentActionDelay) {
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        tryClickConfirm(screen);
                        // Press ESC to close the bazaar screen after confirming
                        player.closeHandledScreen();
                        postPurchase = true;
                        enterState(State.CLOSING_MENU);
                    } else if (now - stateEnteredAt >= BAZAAR_WAIT_MS) {
                        nextRequirementOrAccept();
                    }
                    // else: confirmation screen may still be loading – keep polling
                }
            }

            case ACCEPTING_OFFER -> {
                if (now - stateEnteredAt < POST_BAZAAR_WALK_DELAY_MS + randomExtra150) return;
                if (currentVisitor == null || !currentVisitor.isAlive()) {
                    nextVisitor();
                    return;
                }
                Vec3d visitorPos = new Vec3d(currentVisitor.getX(), currentVisitor.getY(), currentVisitor.getZ());
                // Always aim at the anchored look position (set when the first visitor was
                // reached). If positionAnchored is somehow false, fall back to the current
                // visitor's position so the routine still works in degenerate cases.
                Vec3d lookTarget = (positionAnchored && anchorLookPos != null) ? anchorLookPos : visitorPos;
                double dist = new Vec3d(player.getX(), player.getY(), player.getZ()).distanceTo(visitorPos);
                releaseMovementKeys();
                fastRotateActive = dist <= VISITOR_DETECT_RADIUS;
                lookAt(player, lookTarget, fastRotateActive
                        ? FAST_LOOK_DEGREES_PER_SECOND
                        : SMOOTH_LOOK_DEGREES_PER_SECOND);
                if (isAimedAtTarget(player) && now >= interactCooldownUntil) {
                    fastRotateActive = false;
                    interactWithEntity(player, currentVisitor);
                    interactCooldownUntil = now + INTERACT_COOLDOWN_MS + randomExtra150;
                    enterState(State.WAITING_FOR_ACCEPT);
                }
            }

            case WAITING_FOR_ACCEPT -> {
                if (now - stateEnteredAt >= currentActionDelay) {
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        boolean accepted = tryClickAcceptOffer(screen);
                        // Record the visitor as completed so we never interact with
                        // them again if they haven't despawned yet (prevents re-clicking
                        // an already-accepted offer during the post-accept re-scan).
                        if (currentVisitor != null) {
                            completedVisitorIds.add(currentVisitor.getId());
                        }
                        if (!accepted) {
                            // Accept button not present – offer was already completed.
                            // Skip directly to the next visitor without marking postAccept
                            // (which would cause an extra CLOSING_MENU→nextVisitor() cycle).
                            player.closeHandledScreen();
                            nextVisitor();
                        } else {
                            // Do NOT close the screen: the server auto-closes the visitor
                            // menu after "Accept Offer" is processed, just like "Refuse Offer".
                            // Sending a close packet immediately after the click can cause the
                            // server to discard the accept click.  CLOSING_MENU will
                            // force-close with a fallback timeout if the server does not do so.
                            postAccept = true;
                            enterState(State.CLOSING_MENU);
                        }
                    } else {
                        nextVisitor();
                    }
                }
            }

            case RETURNING_TO_FARM -> {
                if (returnWarpSentAt == 0) {
                    // Wait the rewarp delay (from farming config) before sending /warp garden
                    if (now - stateEnteredAt >= returnWarpDelay) {
                        sendCommand("warp garden");
                        returnWarpSentAt = now;
                        LOGGER.info("[Just Farming-Visitors] Sent /warp garden after rewarp delay.");
                    }
                } else if (now - returnWarpSentAt >= WARP_COMMAND_WAIT_MS + randomExtra150) {
                    enterState(State.DONE);
                }
            }

            default -> {}
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void enterState(State next) {
        state          = next;
        stateEnteredAt = System.currentTimeMillis();
        currentActionDelay = rollActionDelay();
        randomExtra150 = random.nextInt(Math.max(1, config.globalRandomizationMs));
        if (next == State.NAVIGATING || next == State.ACCEPTING_OFFER) {
            // Reset stuck / wall-crash detection so each new navigation leg starts fresh.
            walkLastProgressPos       = null;
            walkLastProgressCheckTime = 0;
            walkRecoveryDirection     = 0;
            walkRecoveryBackupEndTime = 0;
            walkRecoveryEndTime       = 0;
            walkLastJumpTime          = 0;
        }
        if (next == State.NAVIGATING) {
            // Re-randomise the lateral offset so the approach angle varies each time and
            // the camera never looks directly at the visitor while walking toward them.
            // Bias the offset at least NAV_OFFSET_MIN_BLOCKS to one side so it is always noticeable.
            float sign = random.nextBoolean() ? 1f : -1f;
            navAimAsideBlocks = sign * (NAV_OFFSET_MIN_BLOCKS + random.nextFloat() * NAV_OFFSET_RANGE_BLOCKS);
        }
        LOGGER.info("[Just Farming-Visitors] -> {}", next);
    }

    /**
     * Compute a per-action delay: base delay + random(0, randomExtra).
     * Uses the configured values from {@link FarmingConfig}.
     */
    private long rollActionDelay() {
        int base = Math.max(0, config.visitorsActionDelay);
        int extra = config.visitorsActionDelayRandom > 0
                ? random.nextInt(config.visitorsActionDelayRandom + 1) : 0;
        return base + extra;
    }

    /**
     * Populate {@link #pendingVisitors} with NPC entities in range.
     *
     * <p>Visitors are sorted closest-first so the acceptance sequence starts
     * with the nearest visitor (V1 → V2 → V3 → V4 → V5).  After all initial
     * visitors are processed a single end-of-queue rescan is performed; any
     * 6th visitor found at that point is visited last.
     */
    private void scanForVisitors(ClientPlayerEntity player) {
        pendingVisitors.clear();
        Box searchBox = new Box(
                player.getX() - SCAN_RADIUS, player.getY() - 8, player.getZ() - SCAN_RADIUS,
                player.getX() + SCAN_RADIUS, player.getY() + 8, player.getZ() + SCAN_RADIUS);
        client.world.getEntitiesByClass(LivingEntity.class, searchBox,
                        e -> {
                            if (e.getCustomName() == null || e instanceof PlayerEntity) return false;
                            if (!isKnownVisitorEntity(e)) return false;
                            if (completedVisitorIds.contains(e.getId())) {
                                LOGGER.info("[Just Farming-Visitors] Skipping already-completed visitor: {}",
                                        stripFormatting(e.getCustomName().getString()));
                                return false;
                            }
                            // Blacklisted visitors are included so the routine can navigate
                            // to them, open their menu, and click "Refuse Offer".  They are
                            // handled in READING_VISITOR_MENU rather than skipped here.
                            return true;
                        })
                .forEach(pendingVisitors::add);

        double px = player.getX(), py = player.getY(), pz = player.getZ();

        if (pendingVisitors.size() >= 2) {
            // Sort closest-first: trade with the nearest visitor first (V1, V2, V3, …).
            pendingVisitors.sort((a, b) -> {
                double da = Math.pow(a.getX() - px, 2) + Math.pow(a.getY() - py, 2) + Math.pow(a.getZ() - pz, 2);
                double db = Math.pow(b.getX() - px, 2) + Math.pow(b.getY() - py, 2) + Math.pow(b.getZ() - pz, 2);
                return Double.compare(da, db); // ascending: closest first
            });
        }
        // Single visitor: no reordering needed.
    }

    /**
     * Returns {@code true} when {@code entity} should be treated as an active
     * visitor NPC for the purposes of pathfinding and NPC-avoidance logic.
     *
     * <p>For most visitor names the check is a simple membership test in
     * {@link #KNOWN_VISITOR_NAMES}.  For the small subset of names listed in
     * {@link #GARDEN_RESIDENT_NPC_NAMES} an additional raw-name format check is
     * applied: the custom name must start with {@code "§6"} (plain gold) but
     * <em>not</em> with {@code "§6§l"} (gold-bold), because permanent resident
     * NPCs use bold or a different colour while active visitor instances use
     * plain gold.
     *
     * <p>If you observe that a legitimate visitor is being skipped, enable DEBUG
     * logging and look for the log line
     * {@code "Skipping garden-resident NPC … raw='…'"} to verify the raw name
     * format and adjust this check accordingly.
     *
     * @param entity a living entity with a non-null custom name
     * @return {@code true} if the entity should be treated as an active visitor
     */
    private static boolean isKnownVisitorEntity(LivingEntity entity) {
        if (entity.getCustomName() == null) return false;
        String rawName = entity.getCustomName().getString();
        String name    = stripFormatting(rawName);
        if (!KNOWN_VISITOR_NAMES.contains(name)) return false;
        if (GARDEN_RESIDENT_NPC_NAMES.contains(name)) {
            boolean visitorMode = rawName.startsWith("§6") && !rawName.startsWith("§6§l");
            if (!visitorMode) {
                LOGGER.debug("[Just Farming-Visitors] Skipping garden-resident NPC '{}' "
                        + "(not in visitor mode, raw='{}')", name, rawName);
            }
            return visitorMode;
        }
        return true;
    }

    /**
     * Detects whether an Aspect of the Void or Aspect of the End item is present
     * in the player's hotbar.  If found, and at least two visitors are available,
     * configures the AOTV teleport: saves the farthest visitor's position (V5),
     * parses the teleport distance from the item lore, pre-computes
     * {@link #aotvTeleportYaw} toward V5 and sets {@link #aotvTeleportPitch} to
     * {@link #AOTV_SAFE_PITCH} so the crosshair passes above the visitor NPCs.
     *
     * <p>Must be called after {@link #scanForVisitors} has populated and sorted
     * {@link #pendingVisitors} (closest-first).
     *
     * @param player the local player
     */
    private void detectAndConfigureAotv(ClientPlayerEntity player) {
        aotvSlot             = -1;
        aotvTeleportDistance = AOTV_DEFAULT_DISTANCE;
        aotvV5Pos            = null;
        aotvTeleportYaw      = 0f;
        aotvTeleportPitch    = AOTV_SAFE_PITCH;
        useAotv              = false;

        if (pendingVisitors.size() < 2) return; // AOTV only useful for 2+ visitors

        int foundSlot = findAotvSlot(player);
        if (foundSlot < 0) return;

        // V5 = the farthest of the first AOTV_MAIN_VISITOR_REVERSE_COUNT+1 visitors
        // (or the last if fewer visitors are present).
        int v5Idx   = Math.min(AOTV_MAIN_VISITOR_REVERSE_COUNT, pendingVisitors.size() - 1);
        Entity v5   = pendingVisitors.get(v5Idx);
        // Randomise the teleport landing position within a 3-block radius of V5 so
        // the player does not always land at the exact same spot (more human-like),
        // and to avoid accidentally landing on top of the visitor NPC itself.
        double landAngle  = random.nextDouble() * 2 * Math.PI;
        double landRadius = AOTV_LANDING_MIN_RADIUS + random.nextDouble() * AOTV_LANDING_RADIUS_RANGE;
        aotvV5Pos   = new Vec3d(
                v5.getX() + Math.cos(landAngle) * landRadius,
                v5.getY(),
                v5.getZ() + Math.sin(landAngle) * landRadius);

        aotvSlot             = foundSlot;
        aotvTeleportDistance = parseAotvDistance(player.getInventory().getStack(foundSlot));
        useAotv              = true;
        // Pre-compute the aim direction toward V5 so USING_AOTV can start rotating
        // immediately without any approach-walk phase.
        aotvTeleportYaw      = computeAotvV5Yaw(player);
        aotvTeleportPitch    = AOTV_SAFE_PITCH;

        String itemName = PestKillerManager.getCleanItemName(player.getInventory().getStack(foundSlot));
        LOGGER.info("[Just Farming-Visitors] AOTV detected: '{}' at slot {}, dist={} blocks, "
                + "V5 at ({}, {}, {}), aim yaw={}, pitch={}.",
                itemName, foundSlot, aotvTeleportDistance,
                (int) aotvV5Pos.x, (int) aotvV5Pos.y, (int) aotvV5Pos.z,
                (int) aotvTeleportYaw, (int) aotvTeleportPitch);
    }

    /**
     * Searches the player's hotbar for an "Aspect of the Void" or "Aspect of the End" item.
     *
     * @param player the local player
     * @return the hotbar slot index (0–8), or {@code -1} if not found
     */
    private int findAotvSlot(ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            String name = stripFormatting(stack.getName().getString()).toLowerCase();
            if (name.contains(AOTV_ITEM_NAME) || name.contains(AOTE_ITEM_NAME)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Parses the teleport distance from an Aspect of the Void / Aspect of the End
     * item's lore.  Looks for a line matching "teleport N blocks".
     *
     * @param stack the AOTV/AOTE item stack
     * @return parsed distance in blocks, or {@link #AOTV_DEFAULT_DISTANCE} if parsing fails
     */
    private int parseAotvDistance(ItemStack stack) {
        LoreComponent lore = stack.getOrDefault(DataComponentTypes.LORE, LoreComponent.DEFAULT);
        for (Text line : lore.lines()) {
            String stripped = stripFormatting(line.getString()).toLowerCase();
            Matcher m = AOTV_TELEPORT_PATTERN.matcher(stripped);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {}
            }
        }
        return AOTV_DEFAULT_DISTANCE;
    }

    /**
     * Restores the player's selected hotbar slot to their farming tool after
     * the AOTV/AOTE teleport has fired.
     *
     * <p>Priority order mirrors {@link #start()}: configured slot first,
     * then auto-detected farming tool, otherwise no change.
     *
     * @param player the local player
     */
    private void restoreToFarmingTool(ClientPlayerEntity player) {
        if (player == null) return;
        if (config.farmingToolHotbarSlot >= 0 && config.farmingToolHotbarSlot <= 8
                && !player.getInventory().getStack(config.farmingToolHotbarSlot).isEmpty()) {
            player.getInventory().setSelectedSlot(config.farmingToolHotbarSlot);
            LOGGER.info("[Just Farming-Visitors] Restored farming tool slot {} after AOTV teleport.",
                    config.farmingToolHotbarSlot);
        } else if (config.farmingToolHotbarSlot < 0) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = player.getInventory().getStack(i);
                if (PestKillerManager.isFarmingTool(stack)) {
                    player.getInventory().setSelectedSlot(i);
                    LOGGER.info("[Just Farming-Visitors] Restored farming tool '{}' at slot {} after AOTV teleport.",
                            PestKillerManager.getCleanItemName(stack), i);
                    break;
                }
            }
        }
    }

    /**
     * Computes the yaw angle (degrees) from the player's current position toward
     * the saved V5 position ({@link #aotvV5Pos}).  Falls back to the player's
     * current yaw when {@code aotvV5Pos} is {@code null}.
     *
     * @param player the local player
     * @return yaw in degrees toward V5
     */
    private float computeAotvV5Yaw(ClientPlayerEntity player) {
        if (aotvV5Pos != null) {
            return (float) Math.toDegrees(Math.atan2(
                    -(aotvV5Pos.x - player.getX()), aotvV5Pos.z - player.getZ()));
        }
        return player.getYaw();
    }

    /**
     * Returns {@code true} when there is a solid wall of more than one block
     * directly ahead of the player (i.e. solid blocks at both feet level
     * <em>and</em> head level).  A 1-block-tall obstacle that could be cleared
     * with a single jump returns {@code false} here; use
     * {@link #isOneBlockWallAhead} for that case.
     *
     * <p>Used to detect the tall decorative walls found in some Hypixel SkyBlock
     * barn skins (e.g. pinwheel) where the AOTV must be aimed at V5 and fired
     * only when the crosshair direction is clear.
     *
     * @param player the local player
     * @param yawDeg the horizontal direction to probe (Minecraft yaw degrees)
     * @return {@code true} if solid blocks are present at both feet and head level
     */
    private boolean isTallWallAhead(ClientPlayerEntity player, double yawDeg) {
        if (client.world == null) return false;
        double yawRad = Math.toRadians(yawDeg);
        double nextX  = player.getX() - Math.sin(yawRad) * PROBE_STEP;
        double nextZ  = player.getZ() + Math.cos(yawRad) * PROBE_STEP;
        int bx        = (int) Math.floor(nextX);
        int bz        = (int) Math.floor(nextZ);
        int feetY     = (int) Math.floor(player.getY());
        BlockState feetState = client.world.getBlockState(new BlockPos(bx, feetY,     bz));
        BlockState headState = client.world.getBlockState(new BlockPos(bx, feetY + 1, bz));
        boolean feetWall = !feetState.isAir() && !isStepBlock(feetState);
        boolean headWall = !headState.isAir() && !isStepBlock(headState);
        return feetWall && headWall;
    }

    /**
     * Returns {@code true} when the path in the given {@code yawDeg} direction
     * is clear of solid blocks at both feet and head level for the first
     * {@code numBlocks} block-steps ahead.
     *
     * <p>Used before firing the AOTV/AOTE teleport to verify that the teleport
     * direction has no immediate wall obstruction, which would stop the
     * teleport short of the intended destination.  A return value of {@code true}
     * means the player can teleport successfully in that direction.
     *
     * @param player    the local player
     * @param yawDeg    the horizontal aim direction (Minecraft yaw degrees)
     * @param numBlocks number of block-steps to check ahead
     * @return {@code true} when all checked positions are clear
     */
    private boolean isPathClearToward(ClientPlayerEntity player, double yawDeg, int numBlocks) {
        if (client.world == null) return false;
        double yawRad = Math.toRadians(yawDeg);
        int feetY     = (int) Math.floor(player.getY());
        BlockPos.Mutable probe = new BlockPos.Mutable();
        for (int step = 1; step <= numBlocks; step++) {
            double cx = player.getX() - Math.sin(yawRad) * step;
            double cz = player.getZ() + Math.cos(yawRad) * step;
            int bx    = (int) Math.floor(cx);
            int bz    = (int) Math.floor(cz);
            if (!client.world.getBlockState(probe.set(bx, feetY,     bz)).isAir()
                    || !client.world.getBlockState(probe.set(bx, feetY + 1, bz)).isAir()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Steers the player toward {@code target} without rotating the camera.
     *
     * <p>Used during the AOTV approach phase so the camera stays frozen at
     * its current position (preventing accidental interaction with visitor NPCs)
     * while the player still moves toward V1.  The direction to the target is
     * decomposed into forward/back and left/right key presses relative to the
     * player's <em>current</em> look direction; no {@code targetYaw} or
     * {@code smoothRotateCamera} calls are made.
     *
     * @param player the local player
     * @param target the position to move toward
     */
    private void pressKeysTowardNoRotate(ClientPlayerEntity player, Vec3d target) {
        if (client.options == null) return;
        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x;
        double dz = target.z - eye.z;
        // Yaw toward the target (Minecraft convention: 0°=south, 90°=west).
        float toTargetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        // Angular difference from current look direction, normalised to [-180, 180].
        float delta = toTargetYaw - player.getYaw();
        while (delta >  180f) delta -= 360f;
        while (delta < -180f) delta += 360f;
        // Decompose into forward/strafe components.
        double rad     = Math.toRadians(delta);
        double fwd     = Math.cos(rad);   // >0 = forward, <0 = backward
        double side    = Math.sin(rad);   // >0 = right,   <0 = left
        client.options.forwardKey.setPressed(fwd  >  APPROACH_KEY_THRESHOLD);
        client.options.backKey.setPressed(   fwd  < -APPROACH_KEY_THRESHOLD);
        client.options.rightKey.setPressed(  side >  APPROACH_KEY_THRESHOLD);
        client.options.leftKey.setPressed(   side < -APPROACH_KEY_THRESHOLD);
        // Jump to clear any 1-block wall directly in the current look direction.
        client.options.jumpKey.setPressed(isOneBlockWallAhead(player, player.getYaw()));
    }

    /**
     * Navigate one tick toward {@code target}.
     *
     * <p>This simplified pathfinder is designed specifically for the Hypixel SkyBlock
     * barn area and handles all SkyBlock movement buffs robustly:
     * <ul>
     *   <li><b>Speed buffs (lvl 1–10+):</b> The speed multiplier is read from the
     *       player's {@code MOVEMENT_SPEED} attribute so every SkyBlock source
     *       (Speed pet, armour stats, sugar cane enchants, potions, etc.) is
     *       automatically accounted for at any level.  The camera rotation rate and
     *       the yaw-error threshold both scale with the multiplier so the player
     *       always faces the right direction before committing to forward movement,
     *       even at extreme speeds.</li>
     *   <li><b>Pulsed walking near the target:</b> Inside the braking zone the
     *       forward key is pressed only every {@code ceil(speedMult)} ticks,
     *       reducing the average velocity so the player stops at
     *       {@link #INTERACT_RADIUS} without overshooting at Speed X or higher.</li>
     *   <li><b>Jump Boost (lvl 1–10+):</b> The jump key is <em>never</em> pressed.
     *       At Jump Boost X the server-side vertical impulse would fling the player
     *       far above the barn; Minecraft's auto-step handles stair traversal
     *       without any jump input.</li>
     *   <li><b>Stuck detection:</b> If position progress drops below
     *       {@link #WALK_MIN_PROGRESS_PER_INTERVAL} blocks per second the pathfinder
     *       backs up briefly then strafes to escape the obstacle.</li>
     *   <li><b>Smooth camera:</b> Rotation runs at render-frame frequency via
     *       {@link #onRenderTick()} at a rate scaled by the speed multiplier so the
     *       camera always keeps pace with the player's momentum.</li>
     * </ul>
     */
    private void walkToward(ClientPlayerEntity player, Vec3d target) {
        if (client.options == null || client.world == null) return;

        long now = System.currentTimeMillis();

        // Periodically re-randomise walk-direction jitter for humanlike path variation.
        if (now >= walkJitterNextUpdate) {
            walkJitter = (random.nextFloat() * 2f - 1f) * WALK_JITTER_MAX_DEGREES;
            walkJitterNextUpdate = now + WALK_JITTER_INTERVAL_MS;
        }

        // Direction from eye to target; compute proper yaw and pitch toward the target
        // so the camera aims naturally (including slight up/down tilt) as in the pest
        // killer's flyToward.
        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x;
        double dy = (target.y + WALK_PITCH_HEAD_OFFSET) - eye.y; // aim at head height of the target
        double dz = target.z - eye.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float baseTargetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        targetPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(distXZ, WALK_PITCH_MIN_DIST_XZ)));

        // Current SkyBlock speed multiplier (accounts for Speed buffs at any level).
        double speedMult = getSpeedMultiplier(player);

        // ── Stuck / wall-crash detection ──────────────────────────────────────
        Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        if (walkLastProgressPos == null) {
            walkLastProgressPos       = playerPos;
            walkLastProgressCheckTime = now;
        }
        if (now - walkLastProgressCheckTime >= WALK_STUCK_CHECK_INTERVAL_MS) {
            double progress    = walkLastProgressPos.distanceTo(playerPos);
            double distToTarget = playerPos.distanceTo(target);
            if (progress < WALK_MIN_PROGRESS_PER_INTERVAL && distToTarget > INTERACT_RADIUS + 0.5) {
                // If a 1-block-tall wall is directly ahead the player just needs to
                // jump to clear it – suppress the backup+strafe recovery so it doesn't
                // cause erratic camera rotations and backward movement.
                if (!isOneBlockWallAhead(player, baseTargetYaw)) {
                    // Alternate strafe direction on each successive crash (right, then left, …).
                    walkRecoveryDirection     = (walkRecoveryDirection == 1) ? -1 : 1;
                    walkRecoveryBackupEndTime = now + WALK_RECOVERY_BACKUP_MS;
                    walkRecoveryEndTime       = now + WALK_RECOVERY_BACKUP_MS + WALK_RECOVERY_STRAFE_MS;
                    LOGGER.debug("[Just Farming-Visitors] Stuck ({} blocks); backing up then strafing {}.",
                            String.format("%.2f", progress),
                            walkRecoveryDirection > 0 ? "right" : "left");
                } else {
                    LOGGER.debug("[Just Farming-Visitors] Stuck ({} blocks) but 1-block wall ahead; "
                            + "skipping backup+strafe, jump will handle it.",
                            String.format("%.2f", progress));
                }
            }
            walkLastProgressPos       = playerPos;
            walkLastProgressCheckTime = now;
        }

        // Phase 1 of crash-recovery: back up to detach from the wall.
        if (now < walkRecoveryBackupEndTime) {
            targetYaw = baseTargetYaw;
            smoothRotateCamera(player, SMOOTH_LOOK_DEGREES_PER_SECOND * (float) Math.max(1.0, speedMult));
            client.options.forwardKey.setPressed(false);
            client.options.backKey.setPressed(true);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            return;
        }

        boolean isRecoveryActive = now < walkRecoveryEndTime && walkRecoveryDirection != 0;

        // Scale the look-ahead probe with speed so we detect obstacles sooner when
        // moving fast.  The near probe (steerProbeStep) is fixed at PROBE_STEP so
        // steering decisions trigger only when an obstacle is immediately ahead.
        double probeStep      = Math.min(PROBE_STEP * speedMult, 5.0);
        double steerProbeStep = PROBE_STEP;

        // Suppress walk jitter on stair/slab surfaces to avoid unnecessary rotations.
        boolean onStepSurface = isStepBlock(client.world.getBlockState(playerFeetBlockPos(player)));

        // ── Path selection ────────────────────────────────────────────────────
        boolean shouldWalk = false;
        float   chosenYaw  = baseTargetYaw;

        if (isPathClear(player, baseTargetYaw, probeStep)) {
            // Far path is clear – walk with humanlike jitter (suppressed on stairs).
            shouldWalk = true;
            chosenYaw  = onStepSurface ? baseTargetYaw : baseTargetYaw + walkJitter;
        } else if (isPathClear(player, baseTargetYaw, steerProbeStep)) {
            // Far probe blocked but the immediate path is still open – the obstacle
            // is not yet close enough to steer around.  Continue straight; crash
            // recovery handles the case where it is a genuine imminent wall.
            shouldWalk = true;
            chosenYaw  = onStepSurface ? baseTargetYaw : baseTargetYaw + walkJitter;
        } else {
            // Immediate path blocked – before trying steer angles, check whether
            // the obstacle is a 1-block-tall wall that can be jumped straight over.
            // If so, keep looking forward and let the jump key clear the obstacle
            // instead of rotating sideways, which causes erratic movement.
            if (isOneBlockWallAhead(player, baseTargetYaw)) {
                shouldWalk = true;
                chosenYaw  = baseTargetYaw;
            } else {
                // Try progressive steer angles to find a gap.
                for (float steer : WALL_STEER_ANGLES) {
                    if (isPathClear(player, baseTargetYaw + steer, steerProbeStep)) {
                        shouldWalk = true;
                        chosenYaw  = baseTargetYaw + steer;
                        break;
                    }
                }
                if (!shouldWalk) {
                    // All angles blocked – nudge straight forward to escape edge cases
                    // where isPassable is overly conservative (e.g. on a stair lip).
                    shouldWalk = true;
                    chosenYaw  = baseTargetYaw;
                }
            }
        }

        // ── Smooth camera rotation ────────────────────────────────────────────
        // Scale rotation speed by the movement multiplier so the camera always
        // keeps up with the player's momentum at any SkyBlock speed level.
        targetYaw = chosenYaw;
        smoothRotateCamera(player, SMOOTH_LOOK_DEGREES_PER_SECOND * (float) Math.max(1.0, speedMult));

        // ── Speed-aware pulsed walking near the target ────────────────────────
        // At SkyBlock Speed X+ the player covers multiple blocks per tick.  Pulse
        // the forward key inside the braking zone to slow the approach naturally
        // and stop at INTERACT_RADIUS without relying on precise braking distance.
        if (shouldWalk) {
            double dist         = playerPos.distanceTo(target);
            double brakingRadius = INTERACT_RADIUS + speedMult * 2.0;
            if (dist < brakingRadius) {
                int  pulseStride = Math.max(1, (int) Math.ceil(speedMult));
                long ticks       = client.world.getTime();
                shouldWalk = (ticks % pulseStride == 0);
            }
        }

        // ── Yaw-error gate ────────────────────────────────────────────────────
        // Suppress forward movement until the camera is close enough to the chosen
        // direction.  At higher SkyBlock speeds the threshold tightens so the
        // player does not overshoot before the camera has finished turning.
        if (shouldWalk) {
            float yawError = chosenYaw - player.getYaw();
            while (yawError >  180f) yawError -= 360f;
            while (yawError < -180f) yawError += 360f;
            float speedAwareThreshold = Math.max(MIN_WALK_YAW_ERROR_DEGREES,
                    MAX_WALK_YAW_ERROR_DEGREES / (float) Math.max(1.0, speedMult));
            if (Math.abs(yawError) > speedAwareThreshold) {
                shouldWalk = false;
            }
        }

        // Apply movement keys.
        // Jump key is pressed only when a solid full-block wall (non-stair/slab) directly
        // blocks forward progress and the jump cooldown has elapsed.  Minecraft's auto-step
        // handles stair and slab traversal without any jump, so we avoid unnecessary jumps
        // that would launch the player with Jump Boost active.
        boolean shouldJump = false;
        if (!isRecoveryActive && now - walkLastJumpTime >= WALK_JUMP_COOLDOWN_MS) {
            shouldJump = isJumpableWallAhead(player, chosenYaw);
            if (shouldJump) {
                walkLastJumpTime = now;
            }
        }
        client.options.forwardKey.setPressed(shouldWalk);
        client.options.jumpKey.setPressed(shouldJump);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(isRecoveryActive && walkRecoveryDirection < 0);
        client.options.rightKey.setPressed(isRecoveryActive && walkRecoveryDirection > 0);
    }
    /**
     * Returns {@code true} if {@code state} is a stair, slab, carpet, or
     * pressure-plate block.
     *
     * <p>These partial blocks occupy only part of their block space and can be
     * stepped onto automatically by Minecraft's movement code, so the pathfinder
     * treats them as walkable surfaces rather than walls.  Carpets (1/16 block
     * height) and pressure plates are common decorative elements in the Hypixel
     * SkyBlock barn that would otherwise be misidentified as impassable walls.
     */
    private static boolean isStepBlock(BlockState state) {
        return state.getBlock() instanceof StairsBlock
                || state.getBlock() instanceof SlabBlock
                || state.getBlock() instanceof CarpetBlock
                || state.getBlock() instanceof AbstractPressurePlateBlock;
    }

    /**
     * Returns {@code true} when there is a solid full-block wall (non-stair, non-slab,
     * non-air) at the player's feet level or head level directly ahead in the given
     * {@code yawDeg} direction.
     *
     * <p>This is used to decide whether to press the jump key.  Stair and slab
     * blocks are excluded because Minecraft's auto-step code handles them without
     * any jump input; pressing jump over a stair would launch the player
     * unnecessarily high (especially with Jump Boost active).
     */
    private boolean isJumpableWallAhead(ClientPlayerEntity player, double yawDeg) {
        if (client.world == null) return false;
        double yawRad = Math.toRadians(yawDeg);
        double probeStep = PROBE_STEP; // check one probe-step ahead
        double nextX = player.getX() - Math.sin(yawRad) * probeStep;
        double nextZ = player.getZ() + Math.cos(yawRad) * probeStep;
        int bx = (int) Math.floor(nextX);
        int bz = (int) Math.floor(nextZ);
        int feetY = (int) Math.floor(player.getY());
        BlockState feetState = client.world.getBlockState(new BlockPos(bx, feetY, bz));
        BlockState headState = client.world.getBlockState(new BlockPos(bx, feetY + 1, bz));
        // A full block (not air, not stair/slab step-over) at feet or head level
        // requires a jump to clear.
        boolean feetWall = !feetState.isAir() && !isStepBlock(feetState);
        boolean headWall = !headState.isAir() && !isStepBlock(headState);
        return feetWall || headWall;
    }

    /**
     * Returns {@code true} when there is exactly a 1-block-tall wall directly
     * ahead: a solid full-block (non-stair/slab) at the player's feet level
     * with air (or a step-over block) at head level.
     *
     * <p>Such an obstacle can be cleared by a single jump without any sideways
     * steering.  This is used in {@link #walkToward} to detect the common
     * Hypixel SkyBlock barn step-up and jump over it immediately, avoiding the
     * erratic rotation that would otherwise occur when the steer-angle system
     * tries to route around a jumpable wall.
     */
    private boolean isOneBlockWallAhead(ClientPlayerEntity player, double yawDeg) {
        if (client.world == null) return false;
        double yawRad    = Math.toRadians(yawDeg);
        double nextX     = player.getX() - Math.sin(yawRad) * PROBE_STEP;
        double nextZ     = player.getZ() + Math.cos(yawRad) * PROBE_STEP;
        int    bx        = (int) Math.floor(nextX);
        int    bz        = (int) Math.floor(nextZ);
        int    feetY     = (int) Math.floor(player.getY());
        BlockState feetState = client.world.getBlockState(new BlockPos(bx, feetY,     bz));
        BlockState headState = client.world.getBlockState(new BlockPos(bx, feetY + 1, bz));
        // Solid full-block at feet level AND clear space above → 1-block tall jump.
        boolean feetWall  = !feetState.isAir() && !isStepBlock(feetState);
        boolean headClear = headState.isAir() || isStepBlock(headState);
        return feetWall && headClear;
    }

    /**
     * Maximum number of blocks below the probe position to scan when checking for a
     * floor ahead.  Allows the pathfinder to walk off ledges and fall down to a
     * surface up to this many blocks below without treating the drop as a wall.
     * SkyBlock's barn and garden areas have solid floors and no true voids, so a
     * generous depth is safe here.  Falls of any height inside the barn are safe in
     * practice because Hypixel SkyBlock effectively disables fall damage on the
     * Garden island, and visitors are never placed over open voids.
     */
    private static final int FLOOR_SCAN_DEPTH = 10;

    /**
     * Returns {@code true} if the path one {@code probeStep} ahead in the given
     * {@code yawDeg} direction is passable: there must be a solid floor within
     * {@link #FLOOR_SCAN_DEPTH} blocks below and no wall block at feet or head level.
     *
     * <p>Stair and slab blocks at feet level are treated as step-up surfaces that
     * Minecraft traverses automatically (≤ 0.5 block step), not as walls.
     * The floor check scans up to {@link #FLOOR_SCAN_DEPTH} blocks below to allow
     * descending stairs and stepping off ledges of any height (within the depth
     * limit).  A stair or slab at the probe's feet level also counts as its own
     * floor (e.g. a stair step-up or step-down with open space beneath it).
     *
     * <p>When stepping DOWN (probe's foot block is air), the head check at
     * {@code feetY+1} is skipped.  After descending, the player's head sits within
     * the air at {@code feetY} and does not reach {@code feetY+1}, so a block there
     * (e.g. a wall or ceiling above the stair step) must not block the path.
     */
    private boolean isPassable(ClientPlayerEntity player, double yawDeg, double probeStep) {
        double yawRad = Math.toRadians(yawDeg);
        double nextX  = player.getX() + -Math.sin(yawRad) * probeStep;
        double nextZ  = player.getZ() +  Math.cos(yawRad) * probeStep;
        int    feetY  = (int) Math.floor(player.getY());
        int    bx     = (int) Math.floor(nextX);
        int    bz     = (int) Math.floor(nextZ);
        // A block is a wall only if it is non-air and is not a stair/slab step-over.
        BlockPos.Mutable probe = new BlockPos.Mutable(bx, feetY, bz);
        BlockState feetState = client.world.getBlockState(probe);
        // Head-level check: when the probe's foot space is clear (feetState is air)
        // the player is stepping DOWN.  After descending, the player's head sits
        // within the feetY block (which is already air) and does NOT reach feetY+1,
        // so the feetY+1 check is skipped.  For same-level or step-up movement
        // (feetState non-air) the standard feetY+1 check still applies.
        // When feetState is a step block (stair/slab), the block at feetY+1 may be
        // the next stair step in an ascending staircase.  Treating that next step
        // as a wall would incorrectly block upward stair traversal, so step blocks
        // at head level are also permitted when standing on a step surface.
        boolean headBlocked;
        if (feetState.isAir()) {
            headBlocked = false; // step-down: head after descent is within the air at feetY
        } else if (isStepBlock(feetState)) {
            // Stepping up: the block immediately above a stair/slab may be the next
            // step and must not be treated as a wall.
            BlockState headState = client.world.getBlockState(probe.set(bx, feetY + 1, bz));
            headBlocked = !headState.isAir() && !isStepBlock(headState);
        } else {
            headBlocked = !client.world.getBlockState(probe.set(bx, feetY + 1, bz)).isAir();
        }
        boolean wallAhead = (!feetState.isAir() && !isStepBlock(feetState)) || headBlocked;
        // Allow stepping down up to FLOOR_SCAN_DEPTH blocks (covers descending stairs,
        // multi-block drops, and platform-to-platform navigation in the barn).
        // Also treat a stair/slab at feet level as its own floor so step-up and
        // step-down stairs with open space beneath are correctly marked passable.
        // A single mutable BlockPos is reused for all floor-scan iterations to
        // avoid allocating a new object on every tick.
        boolean floorAhead = isStepBlock(feetState);
        for (int dy = 1; dy <= FLOOR_SCAN_DEPTH && !floorAhead; dy++) {
            floorAhead = !client.world.getBlockState(probe.set(bx, feetY - dy, bz)).isAir();
        }
        return floorAhead && !wallAhead;
    }

    /**
     * Returns {@code true} if the path {@code probeStep} ahead in the given
     * {@code yawDeg} direction is terrain-passable (see {@link #isPassable}) and
     * not obstructed by a non-target visitor NPC within {@link #NPC_AVOIDANCE_DIST}.
     */
    private boolean isPathClear(ClientPlayerEntity player, double yawDeg, double probeStep) {
        if (!isPassable(player, yawDeg, probeStep)) return false;
        // Check at the farther of (probeStep, NPC_AVOIDANCE_DIST) so the look-ahead
        // covers the full avoidance radius even when probeStep is larger than it.
        double checkDist = Math.max(probeStep, NPC_AVOIDANCE_DIST);
        double yawRad = Math.toRadians(yawDeg);
        double probeX = player.getX() - Math.sin(yawRad) * checkDist;
        double probeZ = player.getZ() + Math.cos(yawRad) * checkDist;
        Box avoidBox = new Box(probeX - 1.0, player.getY() - 2.0, probeZ - 1.0,
                               probeX + 1.0, player.getY() + 2.0, probeZ + 1.0);
        return client.world.getEntitiesByClass(LivingEntity.class, avoidBox,
                e -> e != currentVisitor
                        && !(e instanceof PlayerEntity)
                        && e.getCustomName() != null
                        && isKnownVisitorEntity(e))
                .isEmpty();
    }

    /**
     * Returns the player's movement speed as a multiple of the vanilla default.
     * 1.0 = normal speed; values above 1.0 indicate SkyBlock speed buffs.
     */
    private double getSpeedMultiplier(ClientPlayerEntity player) {
        double attrVal = player.getAttributeValue(EntityAttributes.MOVEMENT_SPEED);
        return Math.max(1.0, attrVal / BASE_WALK_SPEED);
    }

    /**
     * Computes a navigation target that is {@code offsetBlocks} blocks to the side
     * of {@code target} as seen from the player's current position.
     *
     * <p>Used when walking toward a visitor to keep the camera slightly off-axis,
     * preventing the routine from accidentally opening the visitor trade menu while
     * still approaching.  The perpendicular direction is computed from the player→target
     * vector, and a positive {@code offsetBlocks} shifts the target to the right of
     * the approach direction while a negative value shifts it to the left.
     *
     * @param player       the local player
     * @param target       the original navigation target (the visitor's position)
     * @param offsetBlocks lateral offset in blocks; positive = right, negative = left
     * @return the off-axis target position (same Y as {@code target})
     */
    private Vec3d computeOffAxisNavTarget(ClientPlayerEntity player, Vec3d target, float offsetBlocks) {
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        double mag = Math.sqrt(dx * dx + dz * dz);
        if (mag < 0.001) return target;
        // Perpendicular unit vector (clockwise rotation of the forward direction).
        double perpX = dz / mag;
        double perpZ = -dx / mag;
        return new Vec3d(target.x + perpX * offsetBlocks, target.y, target.z + perpZ * offsetBlocks);
    }

    /** Smoothly rotate the player's camera toward {@code target} over multiple ticks. */
    private void lookAt(ClientPlayerEntity player, Vec3d target) {
        lookAt(player, target, SMOOTH_LOOK_DEGREES_PER_SECOND);
    }

    /**
     * Smoothly rotate the player's camera toward {@code target} at
     * {@code degreesPerSecond} degrees per second.  Higher values make the
     * camera align quickly (used when within {@link #VISITOR_DETECT_RADIUS}).
     */
    private void lookAt(ClientPlayerEntity player, Vec3d target, float degreesPerSecond) {
        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x;
        double dy = (target.y + 1.0) - eye.y; // aim at roughly head height
        double dz = target.z - eye.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        targetYaw   = (float) Math.toDegrees(Math.atan2(-dx, dz));
        targetPitch = (float) -Math.toDegrees(Math.atan2(dy, distXZ));
        smoothRotateCamera(player, degreesPerSecond);
    }

    /**
     * Move the player's yaw and pitch one step closer to
     * {@link #targetYaw}/{@link #targetPitch} at a time-based rate of
     * {@link #SMOOTH_LOOK_DEGREES_PER_SECOND} degrees per second, replicating the
     * feel of a player naturally moving their mouse.
     *
     * <p>Using elapsed real time instead of a fixed per-tick step means the camera
     * glides smoothly regardless of frame rate or server lag spikes.
     */
    private void smoothRotateCamera(ClientPlayerEntity player) {
        smoothRotateCamera(player, SMOOTH_LOOK_DEGREES_PER_SECOND);
    }

    /**
     * Move the player's yaw and pitch one step closer to
     * {@link #targetYaw}/{@link #targetPitch} at the given {@code degreesPerSecond}
     * base rate, with exponential ease-in acceleration.  Higher base values produce
     * faster camera tracking which is required at elevated SkyBlock movement speeds.
     *
     * <p>Camera movement is fully delta-based: each call computes the angular delta
     * proportional to the elapsed wall-clock time since the previous call
     * ({@code deltaMs / 1000 * effectiveSpeed}) and adds it to the current
     * rotation, rather than computing a new absolute Vec3d target.  This ensures
     * smooth, frame-rate-independent rotation without sudden jumps after lag spikes.
     */
    private void smoothRotateCamera(ClientPlayerEntity player, float degreesPerSecond) {
        long now = System.currentTimeMillis();
        // Cap the elapsed time to SMOOTH_LOOK_MAX_DELTA_MS so a severe lag spike
        // does not teleport the camera by applying a huge angular delta in one step.
        float deltaMs;
        if (lastSmoothLookTime == 0) {
            deltaMs = SMOOTH_LOOK_INITIAL_DELTA_MS;
            initialAngularDist = 0f; // reset for a fresh rotation start
        } else {
            deltaMs = Math.min(SMOOTH_LOOK_MAX_DELTA_MS, (float)(now - lastSmoothLookTime));
        }
        // Always advance the timestamp so the next call computes an accurate delta.
        lastSmoothLookTime = now;

        float currentYaw   = player.getYaw();
        float currentPitch = player.getPitch();

        // Normalise the yaw error to [-180, 180] so we always take the shortest arc.
        float yawDiff = targetYaw - currentYaw;
        while (yawDiff >  180f) yawDiff -= 360f;
        while (yawDiff < -180f) yawDiff += 360f;

        float pitchDiff = targetPitch - currentPitch;

        // When already close to the target, apply micro-rotation corrections to
        // simulate the natural micro-tremor of a player holding their mouse still
        // while aiming.  This makes the aim look alive rather than completely frozen.
        if (Math.abs(yawDiff) < MICRO_ROTATION_THRESHOLD_DEGREES
                && Math.abs(pitchDiff) < MICRO_ROTATION_THRESHOLD_DEGREES) {
            float microYaw   = generateTremor(MICRO_ROTATION_AMPLITUDE);
            float microPitch = generateTremor(MICRO_ROTATION_AMPLITUDE * SMOOTH_LOOK_TREMOR_PITCH_SCALE);
            player.setYaw(currentYaw + microYaw);
            player.setPitch(Math.max(-90f, Math.min(90f, currentPitch + microPitch)));
            return;
        }

        // Exponential ease-in: starts at base speed, accelerates as progress increases.
        float remaining = (float) Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
        if (initialAngularDist <= 0f || remaining > initialAngularDist) {
            initialAngularDist = remaining;
        }
        float progress = (initialAngularDist > 0f) ? 1.0f - remaining / initialAngularDist : 0f;
        float speedMult = 1.0f + SMOOTH_LOOK_ACCEL_FACTOR * progress;
        // Maximum angular delta this step, proportional to actual elapsed time.
        float step = degreesPerSecond * speedMult * deltaMs / 1000.0f;

        // Compute rotation deltas: clamp each axis to at most one step, then add
        // a tiny tremor that mimics the natural micro-vibration of a real mouse.
        // Both components are pure deltas applied to the current rotation – no
        // absolute Vec3d target is used.
        float deltaYaw   = Math.max(-step, Math.min(step, yawDiff))
                         + generateTremor(SMOOTH_LOOK_TREMOR_AMPLITUDE);
        float deltaPitch = Math.max(-step, Math.min(step, pitchDiff))
                         + generateTremor(SMOOTH_LOOK_TREMOR_AMPLITUDE * SMOOTH_LOOK_TREMOR_PITCH_SCALE);

        // Apply deltas to the current rotation.  Pitch is clamped after applying
        // the tremor so it never escapes the valid [-90, 90] range.
        player.setYaw(currentYaw + deltaYaw);
        player.setPitch(Math.max(-90f, Math.min(90f, currentPitch + deltaPitch)));
    }

    /**
     * Returns a random tremor value in {@code [-amplitude, amplitude]} to simulate
     * the micro-vibration of a real mouse player.
     */
    private float generateTremor(float amplitude) {
        return (random.nextFloat() * 2f - 1f) * amplitude;
    }

    private void releaseMovementKeys() {
        if (client.options == null) return;
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
    }

    private void interactWithEntity(ClientPlayerEntity player, Entity entity) {
        if (client.interactionManager != null) {
            client.interactionManager.interactEntity(player, entity, Hand.MAIN_HAND);
            player.swingHand(Hand.MAIN_HAND);
        }
    }

    /**
     * Parse the open visitor-menu screen and populate {@link #pendingRequirements}.
     *
     * <p>Hypixel Skyblock renders visitor offers as chest-type GUIs.  One item
     * in the GUI (the "offer" info-item, e.g. a paper/book) contains the full
     * offer in its lore with distinct section headers:
     * <pre>
     *   Items Required:
     *    • 64x Wheat
     *   Rewards:
     *    • +500 Farming XP
     *    • 16x Emerald
     * </pre>
     * We perform <em>section-aware</em> parsing: only lines that appear after an
     * "Items Required" header and before the "Rewards" header are considered as
     * requirements.  For slots whose lore has no section markers, we fall back
     * to scanning just the item's display name (never lore lines), which avoids
     * accidentally treating reward items as requirements.
     */
    private void parseVisitorMenu(HandledScreen<?> screen) {
        pendingRequirements.clear();
        if (!(client.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) {
            return;
        }
        int slotCount = handler.getRows() * 9;

        // Find the "Accept Offer" slot – only its lore contains the items the visitor
        // is asking for.  Scanning every slot would accidentally pick up NPC names,
        // reward-item text, and other unrelated lore as requirements.
        ItemStack acceptOfferStack = null;
        for (int i = 0; i < slotCount; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            String name = stripFormatting(stack.getName().getString()).toLowerCase();
            if (name.contains("accept offer") || name.equals("accept")
                    || name.contains("confirm offer")) {
                acceptOfferStack = stack;
                break;
            }
        }

        if (acceptOfferStack == null) {
            LOGGER.info("[Just Farming-Visitors] Could not find 'Accept Offer' slot in visitor menu; "
                    + "skipping requirement parse.");
        } else {
            LoreComponent lore = acceptOfferStack.getOrDefault(
                    DataComponentTypes.LORE, LoreComponent.DEFAULT);
            List<Text> lines = lore.lines();
            // Extract only the lines in the "Items Required" section of the
            // Accept Offer lore (everything between "Items Required" and the
            // "Reward"/"You will receive" header).
            boolean inRequired = false;
            for (Text line : lines) {
                String stripped = stripFormatting(line.getString());
                String lower    = stripped.toLowerCase();
                if (lower.contains("items required") || lower.startsWith("required:")) {
                    inRequired = true;
                    continue;
                }
                if (lower.contains("reward") || lower.contains("you will receive")
                        || lower.contains("you'll receive")) {
                    inRequired = false;
                }
                if (inRequired) {
                    tryAddRequirement(stripped);
                }
            }
        }

        if (!pendingRequirements.isEmpty()) {
            LOGGER.info("[Just Farming-Visitors] Visitor requires: {}", pendingRequirements);
            // Check max-visitor-price limit.
            // visitorsMaxPrice == 0 means "no limit" (feature disabled).
            if (config.visitorsMaxPrice > 0) {
                double totalValue = VisitorNpcPrices.getTotalNpcValue(pendingRequirements);
                if (totalValue > config.visitorsMaxPrice) {
                    LOGGER.info("[Just Farming-Visitors] Visitor NPC value ({} coins) exceeds max ({} coins); declining.",
                            (long) totalValue, config.visitorsMaxPrice);
                    pendingRequirements.clear();
                    skipCurrentVisitorDueToPrice = true;
                }
            }
        } else {
            LOGGER.info("[Just Farming-Visitors] Could not parse any requirements from visitor menu.");
        }
    }

    /**
     * Attempt to parse {@code line} as an item requirement and add it to
     * {@link #pendingRequirements} if successful.
     */
    private void tryAddRequirement(String line) {
        if (line == null || line.isBlank()) return;
        VisitorRequirement req = parseRequirementLine(line);
        if (req != null) {
            pendingRequirements.add(req);
        }
    }

    /**
     * Parse a single text line into a {@link VisitorRequirement}.
     *
     * <p>Handled formats:
     * <ul>
     *   <li>{@code 64x Wheat}  / {@code 64 Wheat}</li>
     *   <li>{@code Wheat x64}  / {@code Wheat ×64}</li>
     *   <li>{@code Enchanted Hay Bale} (no quantity prefix → amount 1)</li>
     * </ul>
     */
    private static VisitorRequirement parseRequirementLine(String line) {
        String clean = stripFormatting(line).trim();
        if (clean.isEmpty()) return null;

        // "64x Wheat" or "64 Wheat"
        Matcher m1 = PAT_AMOUNT_FIRST.matcher(clean);
        if (m1.matches()) {
            try {
                int    amount = Integer.parseInt(m1.group(1).replace(",", ""));
                String name   = m1.group(2).trim();
                if (!name.isEmpty() && amount > 0) return new VisitorRequirement(name, amount);
            } catch (NumberFormatException ignored) {}
        }

        // "Wheat x64" or "Wheat ×64"
        Matcher m2 = PAT_AMOUNT_LAST.matcher(clean);
        if (m2.matches()) {
            try {
                int    amount = Integer.parseInt(m2.group(2).replace(",", ""));
                String name   = m2.group(1).trim();
                if (!name.isEmpty() && amount > 0) return new VisitorRequirement(name, amount);
            } catch (NumberFormatException ignored) {}
        }

        // Fallback: no quantity prefix at all → Hypixel shows just the item name when amount is 1
        return new VisitorRequirement(clean, 1);
    }

    /** Strip Minecraft color/format codes (§x) and leading/trailing whitespace. */
    private static String stripFormatting(String text) {
        if (text == null) return "";
        return text.replaceAll("§.", "").trim();
    }

    /**
     * Returns {@code true} when the player's camera is aimed within
     * {@link #INTERACT_AIM_THRESHOLD_DEGREES} of the current
     * {@link #targetYaw}/{@link #targetPitch} aim point.
     * Used before sending interact packets so that the player visibly turns
     * toward a visitor NPC rather than interacting "magically" at a distance.
     */
    private boolean isAimedAtTarget(ClientPlayerEntity player) {
        float yawDiff = targetYaw - player.getYaw();
        while (yawDiff >  180f) yawDiff -= 360f;
        while (yawDiff < -180f) yawDiff += 360f;
        float pitchDiff = targetPitch - player.getPitch();
        return Math.abs(yawDiff)   <= INTERACT_AIM_THRESHOLD_DEGREES
                && Math.abs(pitchDiff) <= INTERACT_AIM_THRESHOLD_DEGREES;
    }

    private void openBazaarForCurrentRequirement() {
        if (requirementIndex >= pendingRequirements.size()) {
            startAcceptingOffer();
            return;
        }
        amountToType   = null;
        signTypingStep = 0;
        enterState(State.TYPING_BAZAAR_COMMAND);
        LOGGER.info("[Just Farming-Visitors] Preparing to type /bazaar for: {}",
                pendingRequirements.get(requirementIndex).itemName);
    }

    private void nextRequirementOrAccept() {
        requirementIndex++;
        if (requirementIndex < pendingRequirements.size() && config.visitorsBuyFromBazaar) {
            openBazaarForCurrentRequirement();
        } else {
            startAcceptingOffer();
        }
    }

    private void startAcceptingOffer() {
        pendingRequirements.clear();
        requirementIndex = 0;
        enterState(State.ACCEPTING_OFFER);
    }

    private void nextVisitor() {
        currentVisitor = null;
        // Reset the position anchor so the player walks to each new visitor rather
        // than standing at the first visitor's position.  This also fixes the bug
        // where the second (and later) visitors are never interacted with because
        // positionAnchored prevents any movement toward them.
        positionAnchored = false;
        anchorLookPos = null;

        if (!pendingVisitors.isEmpty()) {
            currentVisitor = pendingVisitors.remove(0);
            LOGGER.info("[Just Farming-Visitors] Moving to next visitor.");
            enterState(State.NAVIGATING);
        } else {
            // End-of-queue rescan: after all initially-found visitors are processed,
            // scan once more for any 6th visitor who spawned during the run.
            // The 6th visitor is visited last (closest-first within the new scan).
            if (!midRunRescanPerformed && client.player != null) {
                midRunRescanPerformed = true;
                scanForVisitors(client.player);
            }
            if (!pendingVisitors.isEmpty()) {
                currentVisitor = pendingVisitors.remove(0);
                LOGGER.info("[Just Farming-Visitors] Found additional visitor(s) after rescan.");
                enterState(State.NAVIGATING);
            } else {
                LOGGER.info("[Just Farming-Visitors] All visitors processed.");
                returnToFarm();
            }
        }
    }

    private void returnToFarm() {
        // Compute the rewarp delay from farming config (base + random extra)
        long base = Math.max(0, config.rewarpDelayMin);
        long extra = config.rewarpDelayRandom > 0 ? random.nextInt(config.rewarpDelayRandom + 1) : 0;
        returnWarpDelay = base + extra;
        returnWarpSentAt = 0;
        enterState(State.RETURNING_TO_FARM);
    }

    /**
     * Scan the open screen for a slot whose item name contains "Buy Instantly"
     * and click it.
     *
     * @return {@code true} if the button was found and clicked.
     */
    private boolean tryClickBuyInstantly(HandledScreen<?> screen) {
        return tryClickSlotWithName(screen, "Buy Instantly");
    }

    /**
     * Scan the open screen for a slot whose plain-text name contains
     * {@code targetName} (case-insensitive) and click it.  Used to click the
     * matching item in the Bazaar search-results screen.
     *
     * @return {@code true} if a matching slot was found and clicked.
     */
    private boolean tryClickItemByName(HandledScreen<?> screen, String targetName) {
        if (client.player == null) return false;
        if (!(client.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) {
            return false;
        }
        String lowerTarget = targetName.toLowerCase();
        // Two-pass: prefer an exact name match over a partial containment match
        // to avoid clicking "Wild Rose" when we need "Compacted Wild Rose".
        int exactSlot   = -1;
        int partialSlot = -1;
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            String name = stripFormatting(stack.getName().getString()).toLowerCase();
            if (name.isEmpty()) continue;
            if (name.equals(lowerTarget)) {
                exactSlot = i;
                break;
            }
            if (partialSlot < 0 && name.contains(lowerTarget)) {
                partialSlot = i;
            }
        }
        int slot = exactSlot >= 0 ? exactSlot : partialSlot;
        if (slot >= 0) {
            String matched = stripFormatting(handler.getSlot(slot).getStack().getName().getString());
            client.interactionManager.clickSlot(
                    handler.syncId, slot, 0, SlotActionType.PICKUP, client.player);
            LOGGER.info("[Just Farming-Visitors] Clicked bazaar item '{}' at slot {}.", matched, slot);
            return true;
        }
        return false;
    }

    /**
     * Scan the open screen for a confirmation slot and click it.
     *
     * <p>Recognises {@code "Confirm"} and {@code "Yes"} (standard Hypixel
     * confirm buttons) as well as {@code "Custom Amount"} – the renamed-crop
     * item that Hypixel places in the post-sign confirmation screen when
     * buying a custom quantity from the Bazaar.  Note: the pre-sign
     * amount-selection screen also contains a "Custom Amount" sign item, but
     * that click is handled separately in {@link com.justfarming.visitor.VisitorManager.State#ENTERING_AMOUNT}
     * before this method is ever reached.
     *
     * @return {@code true} if a matching button was found and clicked.
     */
    private boolean tryClickConfirm(HandledScreen<?> screen) {
        return tryClickSlotWithName(screen, "Confirm", "Yes", "Custom Amount");
    }

    /**
     * Scan the open screen for the visitor "Accept Offer" / "Accept" button
     * and click it.
     *
     * @return {@code true} if a matching button was found and clicked.
     */
    private boolean tryClickAcceptOffer(HandledScreen<?> screen) {
        return tryClickSlotWithName(screen, "Accept Offer", "Accept", "Confirm Offer");
    }

    /**
     * Scan the open screen for the visitor "Refuse Offer" / "Decline Offer" button
     * and click it.  Called when the visitor's requested items exceed the configured
     * max price so that the server records the decline rather than just silently
     * closing the menu.
     *
     * <p>Hypixel SkyBlock uses "Refuse Offer" as the button label; "Decline Offer"
     * and plain "Decline" / "Refuse" are kept as fallbacks for any variant spellings.
     *
     * @return {@code true} if a matching button was found and clicked.
     */
    private boolean tryClickDeclineOffer(HandledScreen<?> screen) {
        return tryClickSlotWithName(screen, "Refuse Offer", "Decline Offer", "Refuse", "Decline");
    }

    /**
     * Returns {@code true} if {@link #currentVisitor} is in the configured
     * {@link com.justfarming.config.FarmingConfig#visitorBlacklist}.
     */
    private boolean isCurrentVisitorBlacklisted() {
        if (currentVisitor == null) return false;
        if (config.visitorBlacklist == null || config.visitorBlacklist.isEmpty()) return false;
        if (currentVisitor.getCustomName() == null) return false;
        String name = stripFormatting(currentVisitor.getCustomName().getString());
        return config.visitorBlacklist.contains(name);
    }

    /**
     * Generic helper: scan all slots of the current
     * {@link GenericContainerScreenHandler} for an item whose plain-text name
     * contains any of {@code keywords} (case-insensitive), and left-click it.
     */
    private boolean tryClickSlotWithName(HandledScreen<?> screen, String... keywords) {
        if (client.player == null) return false;
        if (!(client.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) {
            return false;
        }
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            String name = stripFormatting(stack.getName().getString()).toLowerCase();
            for (String kw : keywords) {
                if (name.contains(kw.toLowerCase())) {
                    client.interactionManager.clickSlot(
                            handler.syncId, i, 0, SlotActionType.PICKUP, client.player);
                    LOGGER.info("[Just Farming-Visitors] Clicked '{}' at slot {}.", kw, i);
                    return true;
                }
            }
        }
        return false;
    }

    private void sendCommand(String command) {
        if (client.player != null && client.player.networkHandler != null) {
            client.player.networkHandler.sendChatCommand(command);
        }
    }

    /**
     * Returns the {@link BlockPos} at the player's feet level
     * (i.e. {@code floor(x), floor(y), floor(z)}).
     */
    private static BlockPos playerFeetBlockPos(ClientPlayerEntity player) {
        return new BlockPos(
                (int) Math.floor(player.getX()),
                (int) Math.floor(player.getY()),
                (int) Math.floor(player.getZ()));
    }
}
