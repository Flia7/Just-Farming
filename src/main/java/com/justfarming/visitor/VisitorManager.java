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
     * 90° in one second, providing snappy but smooth tracking of visitor NPCs.
     */
    private static final float SMOOTH_LOOK_DEGREES_PER_SECOND = 90.0f;

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
     * Radius (blocks) within which a visitor NPC is detected for immediate
     * interaction during the behindPoint navigation phase.  When the player
     * comes within this distance of any visitor while navigating toward the
     * behindPoint, the macro interacts with that visitor directly instead of
     * continuing to walk.  Corresponds to roughly half a block on each side
     * of the visitor's position.
     */
    private static final double VISITOR_DETECT_RADIUS = 0.5;

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
     * How far ahead (blocks) to probe the terrain when navigating.
     * Slightly more than half a block so we reliably detect edges and walls
     * before the player's centre reaches them.
     * At high SkyBlock movement speeds this value is scaled up dynamically.
     */
    private static final double PROBE_STEP = 0.6;

    /**
     * Distance (blocks) past the farthest visitor to navigate before starting
     * to process them.  The player approaches from behind the last visitor so
     * they can then walk forward through the visitor queue naturally.
     */
    private static final double BEHIND_VISITOR_DIST = 1.5;

    /**
     * Fallback distances (blocks) tried in decreasing order when the primary
     * {@link #BEHIND_VISITOR_DIST} behind-point turns out to be inside a wall.
     * If all fallbacks are also blocked the behind-point is skipped entirely.
     */
    private static final double[] BEHIND_VISITOR_DIST_FALLBACKS = { 1.0, 0.5 };

    /**
     * How close (blocks) the player must get to the behind-point before it is
     * considered reached and normal visitor navigation begins.
     */
    private static final double BEHIND_POINT_REACH_DIST = 1.5;

    /**
     * Maximum time (ms) to spend navigating toward the behind-point before
     * giving up and proceeding directly to the first visitor.  Guards against
     * a behind-point that is technically passable but effectively unreachable
     * (e.g., separated from the player by a wall at a different angle).
     */
    private static final long BEHIND_POINT_TIMEOUT_MS = 5000;

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
     * Vanilla-default player walk-speed attribute value.
     * Used as a baseline to measure how much faster the player is moving on
     * Hypixel SkyBlock (due to Speed buffs from armour, pets, enchants, etc.).
     */
    private static final double BASE_WALK_SPEED = 0.1;

    // ── Stair-entry vertical navigation ──────────────────────────────────────

    /**
     * Minimum height difference (blocks) between the player and the target before
     * stair-entry detection is activated.  Below this threshold the path is treated
     * as flat (auto-step handles small rises/drops) so no stair search is needed.
     */
    private static final double STAIR_NAV_HEIGHT_THRESHOLD = 2.0;

    /**
     * Horizontal radius (blocks) to scan when searching for a staircase entry point.
     * Covers the full width of the Hypixel SkyBlock barn area.
     */
    private static final int STAIR_SEARCH_RADIUS = 15;

    /**
     * How long (ms) to keep a cached stair-entry waypoint before recomputing it.
     * Recomputing every tick would be expensive (up to ~14 000 block lookups);
     * a 2-second cache keeps CPU usage low while still updating as the player moves.
     */
    private static final long STAIR_ENTRY_CACHE_MS = 2000L;

    /**
     * Maximum angular difference (degrees) between the direction toward the stair
     * entry and the direction toward the ultimate target.  Stair entries that fall
     * outside this cone are ignored so the pathfinder never walks away from the
     * target to reach a staircase on the opposite side of the barn.
     */
    private static final float STAIR_FORWARD_CONE_DEGREES = 120f;

    // ── Flat-terrain navigation waypoints ────────────────────────────────────

    /**
     * Horizontal radius (blocks) to scan when looking for a flat-terrain
     * navigation waypoint.  Used when the direct path is blocked at the same
     * Y-level and stair navigation is not active.
     */
    private static final int    NAV_WAYPOINT_RADIUS     = 8;

    /**
     * How long (ms) to keep a cached flat-terrain navigation waypoint before
     * recomputing it.  A short cache keeps the waypoint responsive to changes
     * in the player's position while avoiding expensive per-tick block scans.
     */
    private static final long   NAV_WAYPOINT_CACHE_MS   = 1500L;

    /**
     * Half-angle (degrees) of the forward cone searched for flat-terrain
     * navigation waypoints.  Only blocks within this arc of the direct path
     * toward the target are considered, preventing the pathfinder from turning
     * away from the visitor to reach a waypoint behind a wall on the far side.
     */
    private static final float  NAV_WAYPOINT_CONE_DEG   = 90f;

    /**
     * Distance (blocks) at which a flat-terrain navigation waypoint is
     * considered reached and normal visitor-directed navigation resumes.
     */
    private static final double NAV_WAYPOINT_REACH_DIST = 1.5;

    /**
     * Minimum horizontal distance (blocks) from the player to consider a
     * candidate as a flat-terrain navigation waypoint.  Blocks too close to
     * the player's current position are excluded so the waypoint always
     * represents a meaningful forward step.
     */
    private static final double NAV_WAYPOINT_MIN_DIST   = 1.0;

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

    // ── State machine ────────────────────────────────────────────────────────

    /** Internal states of the visitor routine. */
    public enum State {
        /** Not doing anything. */
        IDLE,
        /** Waiting for the server to teleport the player to the barn. */
        TELEPORTING,
        /** Looking for visitor NPC entities near the player. */
        SCANNING,
        /** Walking toward the {@link #currentVisitor}. */
        NAVIGATING,
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

    // Walk-direction jitter for humanlike path variation
    /** Current random yaw offset (degrees) added to the walking direction. */
    private float walkJitter = 0f;
    /** Timestamp (ms) when the walk jitter should next be re-randomised. */
    private long  walkJitterNextUpdate = 0;

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

    // Stair-entry vertical navigation state
    /**
     * Cached stair-entry waypoint computed by {@link #findStairEntry}.
     * {@code null} when no stair entry search has been performed yet or when
     * vertical navigation is no longer needed.
     */
    private Vec3d cachedStairEntry     = null;
    /** Wall-clock time (ms) when {@link #cachedStairEntry} was last computed. */
    private long  cachedStairEntryTime = 0;

    // Flat-terrain navigation waypoint state
    /**
     * Cached passable floor block used as an intermediate navigation target
     * when the direct path to the visitor is blocked at the same height level.
     * The player navigates to this "detected block" before rotating toward the
     * distant visitor, preventing the pathfinder from getting stuck while
     * simultaneously steering around a wall and turning toward the target.
     * {@code null} when not active.
     */
    private Vec3d cachedNavWaypoint     = null;
    /** Wall-clock time (ms) when {@link #cachedNavWaypoint} was last computed. */
    private long  cachedNavWaypointTime = 0;

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
     * When {@code true}, the routine is in the first {@link State#NAVIGATING}
     * leg of this visitor visit.  Reserved for future use.
     */
    private boolean firstNavigationInRoutine = false;
    /**
     * Intermediate waypoint set to the nearest visitor's position.  The routine
     * navigates here first (without trading) so the player arrives behind the
     * visitor queue, then rotates and walks toward the farthest visitor to begin
     * trading.  {@code null} when there is only one visitor or once the point
     * has been reached.
     */
    private Vec3d behindPoint = null;
    /** Wall-clock time (ms) when navigation toward {@link #behindPoint} began; 0 = not started. */
    private long  behindPointStartTime = 0;

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
            "Pearl Dealer", "Pest Wrangler", "Pest Wrangler?", "Pete", "Plumber Joe",
            "Puzzler", "Queen Mismyla", "Queen Nyx", "Ravenous Rhino",
            "Resident Neighbor", "Resident Snooty", "Rhys", "Romero", "Royal Resident",
            "Rusty", "Ryan", "Ryu", "Sargwyn", "Scout Scardius", "Seymour", "Shaggy",
            "Sherry", "Shifty", "Sirius", "Spaceman", "Spider Tamer", "St. Jerry",
            "Stella", "Tammy", "Tarwen", "Terry", "The Trapper", "Tia the Fairy",
            "Tom", "Tomioka", "Trevor", "Trinity", "Tyashoi Alchemist", "Tyzzo",
            "Vargul", "Vex", "Vincent", "Vinyl Collector", "Weaponsmith", "Wizard",
            "Xalx", "Zog"
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
        LOGGER.info("[JustFarming-Visitors] Visitor routine stopped by user.");
        releaseMovementKeys();
        postAccept = false;
        postPurchase = false;
        skipCurrentVisitorDueToPrice = false;
        behindPoint = null;
        behindPointStartTime = 0;
        returnWarpDelay = 0;
        returnWarpSentAt = 0;
        midRunRescanPerformed = false;
        firstNavigationInRoutine = false;
        walkLastProgressPos = null;
        walkLastProgressCheckTime = 0;
        walkRecoveryDirection = 0;
        walkRecoveryBackupEndTime = 0;
        walkRecoveryEndTime = 0;
        cachedStairEntry = null;
        cachedStairEntryTime = 0;
        enterState(State.IDLE);
    }


    public void start() {
        LOGGER.info("[JustFarming-Visitors] Starting visitor routine.");
        // Auto-swap to farming tool (same logic as MacroManager.start)
        ClientPlayerEntity player = client.player;
        if (player != null) {
            if (config.farmingToolHotbarSlot >= 0 && config.farmingToolHotbarSlot <= 8
                    && !player.getInventory().getStack(config.farmingToolHotbarSlot).isEmpty()) {
                player.getInventory().setSelectedSlot(config.farmingToolHotbarSlot);
                LOGGER.info("[JustFarming-Visitors] Switched to farming tool slot {}.", config.farmingToolHotbarSlot);
            } else if (config.farmingToolHotbarSlot < 0) {
                for (int i = 0; i < 9; i++) {
                    ItemStack stack = player.getInventory().getStack(i);
                    if (PestKillerManager.isFarmingTool(stack)) {
                        player.getInventory().setSelectedSlot(i);
                        LOGGER.info("[JustFarming-Visitors] Auto-detected farming tool '{}' at slot {}; switching.",
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
        behindPoint       = null;
        behindPointStartTime = 0;
        interactCooldownUntil = 0;
        postAccept        = false;
        postPurchase      = false;
        skipCurrentVisitorDueToPrice = false;
        walkJitter        = 0f;
        walkJitterNextUpdate = 0;
        lastSmoothLookTime = 0;
        returnWarpDelay   = 0;
        returnWarpSentAt  = 0;
        midRunRescanPerformed = false;
        firstNavigationInRoutine = true;
        cachedStairEntry = null;
        cachedStairEntryTime = 0;
        long base = config.visitorsTeleportDelay > 0
                ? config.visitorsTeleportDelay : TELEPORT_WAIT_DEFAULT_MS;
        teleportWaitMs = base + random.nextInt((int) TELEPORT_EXTRA_RANDOM_MS + 1)
                + random.nextInt(Math.max(1, config.globalRandomizationMs));
        enterState(State.TELEPORTING);
        sendCommand("tptoplot barn");
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
        if (state != State.NAVIGATING && state != State.ACCEPTING_OFFER) return;
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

            case TELEPORTING -> {
                if (now - stateEnteredAt >= teleportWaitMs) {
                    LOGGER.info("[JustFarming-Visitors] Teleport wait elapsed; scanning.");
                    enterState(State.SCANNING);
                }
            }

            case SCANNING -> {
                scanForVisitors(player);
                if (!pendingVisitors.isEmpty()) {
                    int found = pendingVisitors.size();
                    int minCount = Math.max(1, config.visitorsMinCount);
                    if (found < minCount) {
                        LOGGER.info("[JustFarming-Visitors] Found {} visitor(s), fewer than minimum {}; returning to farm.",
                                found, minCount);
                        pendingVisitors.clear();
                        returnToFarm();
                    } else {
                        currentVisitor = pendingVisitors.remove(0);
                        // Use the nearest visitor's position (last in the farthest-first
                        // sorted list) as the navigation node.  The player first walks to
                        // the nearest visitor without trading, then rotates and approaches
                        // the farthest visitor to begin the acceptance sequence.
                        if (!pendingVisitors.isEmpty()) {
                            Entity nearestVisitor = pendingVisitors.get(pendingVisitors.size() - 1);
                            behindPoint = new Vec3d(nearestVisitor.getX(), nearestVisitor.getY(), nearestVisitor.getZ());
                        } else {
                            behindPoint = null;
                        }
                        behindPointStartTime = 0;
                        if (behindPoint != null) {
                            LOGGER.info("[JustFarming-Visitors] Navigation node at nearest visitor's location: {}.",
                                    String.format("%.1f, %.1f, %.1f", behindPoint.x, behindPoint.y, behindPoint.z));
                        }
                        LOGGER.info("[JustFarming-Visitors] Found {} visitor(s). Navigating farthest first.",
                                pendingVisitors.size() + 1);
                        enterState(State.NAVIGATING);
                    }
                } else if (now - stateEnteredAt >= SCAN_TIMEOUT_MS) {
                    // Waited 3 s and still no visitors – go back to farming
                    LOGGER.info("[JustFarming-Visitors] No visitors found; returning to farm.");
                    returnToFarm();
                }
            }

            case NAVIGATING -> {
                if (currentVisitor == null || !currentVisitor.isAlive()) {
                    nextVisitor();
                    return;
                }

                Vec3d visitorPos = new Vec3d(currentVisitor.getX(), currentVisitor.getY(), currentVisitor.getZ());
                // Navigate to the behind-point first so the player approaches the
                // visitor queue from the far end rather than walking through the line.
                if (behindPoint != null) {
                    // Start the timeout clock on the first tick we handle behindPoint.
                    if (behindPointStartTime == 0) behindPointStartTime = now;
                    double behindDist = new Vec3d(player.getX(), player.getY(), player.getZ())
                            .distanceTo(behindPoint);
                    boolean reached  = behindDist <= BEHIND_POINT_REACH_DIST;
                    boolean timedOut = (now - behindPointStartTime) >= BEHIND_POINT_TIMEOUT_MS;

                    // Detect any visitor within VISITOR_DETECT_RADIUS during navigation
                    // and interact with them immediately instead of walking past.
                    Entity nearbyVisitor = findVisitorWithinRadius(player, VISITOR_DETECT_RADIUS);
                    if (nearbyVisitor != null) {
                        if (nearbyVisitor != currentVisitor) {
                            // Push the original target back to the front of the queue
                            // so it is visited after the nearby one.
                            pendingVisitors.add(0, currentVisitor);
                            pendingVisitors.remove(nearbyVisitor);
                            currentVisitor = nearbyVisitor;
                            visitorPos = new Vec3d(nearbyVisitor.getX(), nearbyVisitor.getY(), nearbyVisitor.getZ());
                        }
                        behindPoint = null;
                        behindPointStartTime = 0;
                    } else if (reached || timedOut) {
                        if (timedOut && !reached) {
                            LOGGER.info("[JustFarming-Visitors] Behind-point not reached in {}ms; skipping.", BEHIND_POINT_TIMEOUT_MS);
                        }
                        behindPoint = null;
                        behindPointStartTime = 0;
                    } else {
                        // Skip NPC avoidance: the path to the behind-point intentionally
                        // passes through the line of visitors so we must not treat them
                        // as obstacles here.
                        walkToward(player, behindPoint, false);
                        return;
                    }
                }
                double dist = new Vec3d(player.getX(), player.getY(), player.getZ()).distanceTo(visitorPos);
                if (dist <= INTERACT_RADIUS) {
                    releaseMovementKeys();
                    // Always update the camera target so the player visibly turns
                    // toward the visitor before the interact packet is sent.
                    lookAt(player, visitorPos);
                    if (isAimedAtTarget(player) && now >= interactCooldownUntil) {
                        interactWithEntity(player, currentVisitor);
                        interactCooldownUntil = now + INTERACT_COOLDOWN_MS + randomExtra150;
                        enterState(State.INTERACTING);
                    }
                } else {
                    walkToward(player, visitorPos);
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
                    // Wait the action delay before parsing so that all slot-data packets
                    // sent by the server after the screen-open packet have time to arrive.
                    // Without this guard the parse can run while slots are still empty,
                    // producing zero requirements and causing the macro to skip bazaar.
                    if (now - stateEnteredAt >= currentActionDelay) {
                        parseVisitorMenu(screen);
                        if (skipCurrentVisitorDueToPrice) {
                            // Price limit exceeded – click "Decline Offer" in the GUI
                            // before closing the menu so the server records the decline.
                            tryClickDeclineOffer(screen);
                        }
                        // Close the screen before opening the bazaar (or accepting)
                        player.closeHandledScreen();
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
                }
            }

            case TYPING_BAZAAR_COMMAND -> {
                long typingDelay = config.bazaarSearchDelay > 0
                        ? config.bazaarSearchDelay : BAZAAR_WAIT_MS;
                if (now - stateEnteredAt >= typingDelay) {
                    String itemName = pendingRequirements.get(requirementIndex).itemName;
                    sendCommand("bazaar " + itemName);
                    enterState(State.OPENING_BAZAAR);
                    LOGGER.info("[JustFarming-Visitors] Opening bazaar for: {}", itemName);
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
                    LOGGER.warn("[JustFarming-Visitors] Bazaar screen did not open; skipping.");
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
                            LOGGER.warn("[JustFarming-Visitors] Could not find '{}' in bazaar; skipping.", itemName);
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
                    LOGGER.warn("[JustFarming-Visitors] Timed out waiting for sign screen; skipping requirement.");
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
                double dist = new Vec3d(player.getX(), player.getY(), player.getZ()).distanceTo(visitorPos);
                if (dist <= INTERACT_RADIUS) {
                    releaseMovementKeys();
                    // Always update the camera target so the player visibly turns
                    // toward the visitor before the interact packet is sent.
                    lookAt(player, visitorPos);
                    if (isAimedAtTarget(player) && now >= interactCooldownUntil) {
                        interactWithEntity(player, currentVisitor);
                        interactCooldownUntil = now + INTERACT_COOLDOWN_MS + randomExtra150;
                        enterState(State.WAITING_FOR_ACCEPT);
                    }
                } else {
                    walkToward(player, visitorPos);
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
                            // Close after accepting; mark that the next CLOSING_MENU
                            // should move to the next visitor, not re-enter accepting.
                            postAccept = true;
                            player.closeHandledScreen();
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
                        LOGGER.info("[JustFarming-Visitors] Sent /warp garden after rewarp delay.");
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
            // Reset flat-terrain navigation waypoint so it is recomputed for the
            // new navigation leg rather than carrying over a stale target.
            cachedNavWaypoint     = null;
            cachedNavWaypointTime = 0;
        }
        LOGGER.info("[JustFarming-Visitors] -> {}", next);
    }

    /**
     * Compute a per-action delay: base delay + random(0, randomExtra).
     * Uses the configured values from {@link FarmingConfig}.
     */
    private long rollActionDelay() {
        int base = config.visitorsActionDelay > 0 ? config.visitorsActionDelay : (int) ACTION_DELAY_DEFAULT_MS;
        int extra = config.visitorsActionDelayRandom > 0
                ? random.nextInt(config.visitorsActionDelayRandom + 1) : 0;
        return base + extra;
    }

    /** Populate {@link #pendingVisitors} with NPC entities in range.
     *
     * <p>Visitors are sorted farthest-first so the initial acceptance sequence
     * is V5 → V4 → V3 → V2 → V1.  After all initial visitors are processed a
     * single end-of-queue rescan is performed; any 6th visitor found at that
     * point is visited last (V6).
     */
    private void scanForVisitors(ClientPlayerEntity player) {
        pendingVisitors.clear();
        Box searchBox = new Box(
                player.getX() - SCAN_RADIUS, player.getY() - 8, player.getZ() - SCAN_RADIUS,
                player.getX() + SCAN_RADIUS, player.getY() + 8, player.getZ() + SCAN_RADIUS);
        client.world.getEntitiesByClass(LivingEntity.class, searchBox,
                        e -> {
                            if (e.getCustomName() == null || e instanceof PlayerEntity) return false;
                            String name = stripFormatting(e.getCustomName().getString());
                            if (!KNOWN_VISITOR_NAMES.contains(name)) return false;
                            if (completedVisitorIds.contains(e.getId())) {
                                LOGGER.info("[JustFarming-Visitors] Skipping already-accepted visitor: {}", name);
                                return false;
                            }
                            if (config.visitorBlacklist != null && config.visitorBlacklist.contains(name)) {
                                LOGGER.info("[JustFarming-Visitors] Skipping blacklisted visitor: {}", name);
                                return false;
                            }
                            return true;
                        })
                .forEach(pendingVisitors::add);

        double px = player.getX(), py = player.getY(), pz = player.getZ();

        if (pendingVisitors.size() >= 2) {
            // Sort farthest-first: process from the end of the visitor line
            // toward the nearest so the sequence becomes [V5, V4, V3, V2, V1].
            pendingVisitors.sort((a, b) -> {
                double da = Math.pow(a.getX() - px, 2) + Math.pow(a.getY() - py, 2) + Math.pow(a.getZ() - pz, 2);
                double db = Math.pow(b.getX() - px, 2) + Math.pow(b.getY() - py, 2) + Math.pow(b.getZ() - pz, 2);
                return Double.compare(db, da); // descending: farthest first
            });
        }
        // Single visitor: no reordering needed.
    }

    /**
     * Returns the first visitor in {@link #pendingVisitors} (or equal to
     * {@link #currentVisitor}) that is within {@code radius} blocks of the
     * player's feet position, or {@code null} if none is within range.
     *
     * @param player the local player
     * @param radius maximum Euclidean distance (blocks) to consider
     * @return a nearby alive visitor entity, or {@code null}
     */
    private Entity findVisitorWithinRadius(ClientPlayerEntity player, double radius) {
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        double radiusSq = radius * radius;
        for (Entity v : pendingVisitors) {
            if (v == null || !v.isAlive()) continue;
            double dx = v.getX() - px, dy = v.getY() - py, dz = v.getZ() - pz;
            if (dx * dx + dy * dy + dz * dz <= radiusSq) return v;
        }
        if (currentVisitor != null && currentVisitor.isAlive()) {
            double dx = currentVisitor.getX() - px,
                   dy = currentVisitor.getY() - py,
                   dz = currentVisitor.getZ() - pz;
            if (dx * dx + dy * dy + dz * dz <= radiusSq) return currentVisitor;
        }
        return null;
    }

    /**
     * Navigate one tick toward {@code target}, applying NPC avoidance.
     * Convenience overload; equivalent to {@code walkToward(player, target, true)}.
     */
    private void walkToward(ClientPlayerEntity player, Vec3d target) {
        walkToward(player, target, true);
    }

    /**
     * Navigate one tick toward {@code target}.
     *
     * @param avoidNpcs when {@code true}, NPC entities other than
     *                  {@link #currentVisitor} are treated as obstacles and the
     *                  pathfinder steers around them.  Pass {@code false} when
     *                  navigating to the {@link #behindPoint} so that the line of
     *                  pending visitors does not block the path to the far end of
     *                  the queue.
     */
    private void walkToward(ClientPlayerEntity player, Vec3d target, boolean avoidNpcs) {
        if (client.options == null || client.world == null) return;

        long now = System.currentTimeMillis();
        // Periodically re-randomise the walk-direction jitter for humanlike path variation.
        // (random.nextFloat() * 2f - 1f) produces a uniform value in [-1, 1].
        if (now >= walkJitterNextUpdate) {
            walkJitter = (random.nextFloat() * 2f - 1f) * WALK_JITTER_MAX_DEGREES;
            walkJitterNextUpdate = now + WALK_JITTER_INTERVAL_MS;
        }

        // Compute the direct yaw toward the target (pitch is unchanged by jitter).
        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x;
        double dz = target.z - eye.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float baseTargetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        // Keep the camera looking straight ahead (pitch 0) while pathfinding.
        // Computing pitch from the vertical angle to the visitor causes the camera
        // to tilt up and down continuously when the player descends stairs, because
        // the player's eye height changes rapidly relative to the (fixed) visitor
        // position.  Pitch is set precisely by lookAt() when the player is close
        // enough to interact, so a neutral pitch during navigation is correct.
        targetPitch = 0f;

        // Compute how many times faster the player is moving compared to vanilla.
        // This covers Speed-effect buffs, armour bonuses, and other SkyBlock modifiers
        // that all ultimately manifest as a higher MOVEMENT_SPEED attribute value.
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
                // Not making progress: start a crash-recovery sequence (back up, then strafe).
                // Alternate strafe direction between crashes so we try both sides over time.
                // Starting from 0 (no previous recovery), the first crash goes right (+1),
                // subsequent crashes alternate: +1 → -1 → +1 → ...
                walkRecoveryDirection     = (walkRecoveryDirection == 1) ? -1 : 1;
                walkRecoveryBackupEndTime = now + WALK_RECOVERY_BACKUP_MS;
                walkRecoveryEndTime       = now + WALK_RECOVERY_BACKUP_MS + WALK_RECOVERY_STRAFE_MS;
                LOGGER.debug("[JustFarming-Visitors] Stuck (progress={} blocks); backing up then strafing {}.",
                        String.format("%.2f", progress),
                        walkRecoveryDirection > 0 ? "right" : "left");
            }
            walkLastProgressPos       = playerPos;
            walkLastProgressCheckTime = now;
        }

        // Phase 1 of crash-recovery: back up briefly so the player detaches from the wall.
        if (now < walkRecoveryBackupEndTime) {
            targetYaw = baseTargetYaw;
            // Scale rotation speed by movement speed so the camera catches up quickly
            // even at high SkyBlock movement speeds.
            smoothRotateCamera(player, SMOOTH_LOOK_DEGREES_PER_SECOND * (float) Math.max(1.0, speedMult));
            client.options.forwardKey.setPressed(false);
            client.options.backKey.setPressed(true);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            return;
        }

        // Whether the strafe phase of crash-recovery is still active.
        boolean isRecoveryActive = now < walkRecoveryEndTime && walkRecoveryDirection != 0;

        // Scale the probe-step with speed so we look further ahead when moving fast,
        // giving enough reaction distance to stop before hitting a wall.
        double probeStep = Math.min(PROBE_STEP * speedMult, 5.0);

        // Shorter, fixed probe used exclusively for steer-angle decisions.
        // Using the speed-scaled probe for rotation caused the camera to start
        // turning before the player reached the obstacle (e.g. a barn entrance
        // wall detected 4–5 blocks away), leading to over-rotation and the player
        // heading in the wrong direction.  The near probe is deliberately kept at
        // PROBE_STEP so steering only triggers when the obstacle is immediately
        // ahead, while the larger probeStep is still used for braking/NPC avoidance.
        double steerProbeStep = PROBE_STEP;

        // Detect whether the player is currently standing on a stair or slab block.
        // On step surfaces, skipping the walk-direction jitter prevents the camera
        // from rotating left and right unnecessarily while traversing a staircase.
        boolean onStepSurface = isStepBlock(client.world.getBlockState(playerFeetBlockPos(player)));

        // ── Stair-entry vertical navigation ──────────────────────────────────
        // When the target is at a significantly different height and the near path
        // is blocked, scan for the closest staircase entry point and aim toward it.
        // For ascending (target above player): aim for the lowest nearby stair block
        // (the bottom/entry of the staircase).
        // For descending (target below player): aim for the highest nearby stair block
        // (the top/entry of the staircase).
        // Only activate when the near path is actually blocked – on clear paths the
        // normal isPathClear / steer-angle logic handles everything.
        double heightDiff = target.y - player.getY();
        if (Math.abs(heightDiff) > STAIR_NAV_HEIGHT_THRESHOLD
                && !isPathClear(player, baseTargetYaw, steerProbeStep, avoidNpcs)) {
            if (cachedStairEntry == null || now - cachedStairEntryTime > STAIR_ENTRY_CACHE_MS) {
                cachedStairEntry     = findStairEntry(player, heightDiff > 0, baseTargetYaw);
                cachedStairEntryTime = now;
            }
            if (cachedStairEntry != null) {
                double sdx = cachedStairEntry.x - eye.x;
                double sdz = cachedStairEntry.z - eye.z;
                double stairDist = Math.sqrt(sdx * sdx + sdz * sdz);
                // Only redirect when the stair entry is far enough to give a meaningful
                // heading (very small distances would produce a near-zero direction vector).
                if (stairDist > 1.5) {
                    baseTargetYaw = (float) Math.toDegrees(Math.atan2(-sdx, sdz));
                }
            }
        } else if (Math.abs(heightDiff) <= STAIR_NAV_HEIGHT_THRESHOLD) {
            // No longer need vertical stair navigation; clear cached entry.
            cachedStairEntry = null;
        }

        // ── Flat-terrain navigation waypoints ──────────────────────────────────
        // When the near path is blocked at the same height level (stair navigation
        // is not active), scan for the nearest passable floor block in the forward
        // cone and redirect toward it.  The player navigates to this "detected
        // block" before the camera rotates toward the distant visitor, preventing
        // the pathfinder from getting stuck while simultaneously trying to steer
        // around a wall and turn toward the target.
        //
        // isPathClear is checked every tick (even with a cached waypoint) so that
        // the waypoint is discarded immediately once the path to the visitor clears,
        // rather than persisting until the cache expires.
        if (Math.abs(heightDiff) <= STAIR_NAV_HEIGHT_THRESHOLD
                && !isPathClear(player, baseTargetYaw, steerProbeStep, avoidNpcs)) {
            if (cachedNavWaypoint == null
                    || now - cachedNavWaypointTime > NAV_WAYPOINT_CACHE_MS) {
                cachedNavWaypoint     = findNavWaypoint(player, baseTargetYaw, avoidNpcs);
                cachedNavWaypointTime = now;
            }
            if (cachedNavWaypoint != null) {
                double wdx   = cachedNavWaypoint.x - eye.x;
                double wdz   = cachedNavWaypoint.z - eye.z;
                double wdist = Math.sqrt(wdx * wdx + wdz * wdz);
                if (wdist <= NAV_WAYPOINT_REACH_DIST) {
                    // Waypoint reached; discard it so normal navigation resumes.
                    cachedNavWaypoint = null;
                } else {
                    // Redirect toward the waypoint so the camera rotates toward the
                    // detected block instead of the distant visitor.
                    baseTargetYaw = (float) Math.toDegrees(Math.atan2(-wdx, wdz));
                }
            }
        } else {
            // Path is clear, or height difference is too large (stair nav handles it);
            // discard any cached flat-terrain waypoint.
            cachedNavWaypoint = null;
        }

        // Try the direct path first; if clear, apply jitter for humanlike variation.
        // Jitter is suppressed on stair/slab surfaces to avoid useless rotations.
        // If the far probe detects a wall but the near probe is still clear, keep
        // walking straight – the obstacle is not yet close enough to require a turn,
        // and rotating early would send the player the wrong way before they reach
        // it (the "rotates before passing the wall" bug).
        // Only apply steer angles when the near path is also blocked so rotation
        // happens at the last moment, preventing over-rotation on corners.
        boolean shouldWalk = false;
        float chosenYaw = baseTargetYaw;
        if (isPathClear(player, baseTargetYaw, probeStep, avoidNpcs)) {
            // Far path is clear – walk with jitter.
            shouldWalk = true;
            chosenYaw  = onStepSurface ? baseTargetYaw : baseTargetYaw + walkJitter; // skip jitter on stairs
        } else if (isPathClear(player, baseTargetYaw, steerProbeStep, avoidNpcs)) {
            // Far probe blocked but near path is clear: obstacle is not yet close.
            // Continue straight without rotating; crash recovery will handle the
            // case where the far obstacle is a genuine imminent wall rather than
            // a temporary read (e.g. entrance-gap wall detected at a bad angle).
            shouldWalk = true;
            chosenYaw  = onStepSurface ? baseTargetYaw : baseTargetYaw + walkJitter;
        } else {
            // Near path is blocked – steer around the immediate obstacle.
            for (float steer : WALL_STEER_ANGLES) {
                if (isPathClear(player, baseTargetYaw + steer, steerProbeStep, avoidNpcs)) {
                    shouldWalk = true;
                    chosenYaw  = baseTargetYaw + steer; // steer around the wall (no jitter)
                    break;
                }
            }
            // All path checks failed – force forward movement anyway so the pathfinder
            // does not stand still indefinitely.  The player nudging forward helps
            // escape edge cases where isPassable is overly conservative (e.g. standing
            // just short of a stair step) and gets the player closer to visitors.
            if (!shouldWalk) {
                shouldWalk = true;
                chosenYaw  = baseTargetYaw;
            }
        }

        // Aim camera toward the chosen direction using a speed-scaled rotation rate.
        // At high SkyBlock movement speeds the player can overshoot a turn before a
        // slow camera has finished rotating, so the rate scales with speedMult so the
        // camera always keeps up with the player's momentum.
        targetYaw = chosenYaw;
        smoothRotateCamera(player, SMOOTH_LOOK_DEGREES_PER_SECOND * (float) Math.max(1.0, speedMult));

        if (shouldWalk) {
            // Speed-aware pulsed walking near the target.
            //
            // At high SkyBlock movement speeds the player can overshoot the visitor
            // in a single tick.  Once inside the "braking zone" we only press the
            // forward key every pulseStride ticks (proportional to speed), reducing
            // the average forward velocity enough to stop precisely at INTERACT_RADIUS.
            double dist = playerPos.distanceTo(target);
            double brakingRadius = INTERACT_RADIUS + speedMult * 2.0;
            if (dist < brakingRadius) {
                int pulseStride = Math.max(1, (int) Math.ceil(speedMult));
                long ticks = client.world.getTime(); // world game-tick counter
                shouldWalk = (ticks % pulseStride == 0);
            }
        }

        // Suppress walking when the camera is still far from the chosen direction.
        // The forward key moves the player in the direction they are currently looking,
        // so pressing it while the camera is too far off-target would carry them in the
        // wrong direction (e.g. away from the visitor or into a wall after rounding the
        // behind-point).  At higher movement speeds (SkyBlock buffs) the allowable yaw
        // error is tightened proportionally so the player always walks in precisely the
        // right direction before committing to forward movement.
        if (shouldWalk) {
            float yawError = chosenYaw - player.getYaw();
            while (yawError >  180f) yawError -= 360f;
            while (yawError < -180f) yawError += 360f;
            // Scale the threshold down with speed: at 1× speed, allow up to
            // MAX_WALK_YAW_ERROR_DEGREES; at higher speeds require tighter aim
            // (floor of MIN_WALK_YAW_ERROR_DEGREES) so the player doesn't overshoot
            // at extreme speeds.
            float speedAwareYawThreshold = Math.max(MIN_WALK_YAW_ERROR_DEGREES,
                    MAX_WALK_YAW_ERROR_DEGREES / (float) Math.max(1.0, speedMult));
            if (Math.abs(yawError) > speedAwareYawThreshold) {
                shouldWalk = false;
            }
        }

        // Only walk forward when there is solid ground ahead and no wall blocking the path.
        // Never trigger a jump – on Hypixel SkyBlock speed/jump buffs can reach level 10,
        // so autonomous jumping causes erratic movement near visitor NPCs.
        client.options.forwardKey.setPressed(shouldWalk);
        client.options.jumpKey.setPressed(false);
        client.options.backKey.setPressed(false);
        // During the strafe phase of crash-recovery, press the appropriate strafe key
        // alongside forward movement so the player slides around the obstacle.
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
     * Scans nearby blocks for a staircase entry point to use when vertical
     * navigation is required.
     *
     * <p>When {@code goingUp} is {@code true}, the method returns the position
     * of the <em>lowest</em> stair or slab block found within
     * {@link #STAIR_SEARCH_RADIUS} horizontal blocks of the player (at the current
     * Y level or above).  This is the bottom of the nearest staircase, i.e. the
     * point the player must walk to in order to begin climbing.
     *
     * <p>When {@code goingUp} is {@code false}, the method returns the position
     * of the <em>highest</em> stair or slab block found within the same horizontal
     * radius (at the current Y level or below).  This is the top of the nearest
     * staircase, i.e. the point the player must walk to in order to begin descending.
     *
     * <p>Only stair entries within {@link #STAIR_FORWARD_CONE_DEGREES} of the
     * direct path toward the ultimate target ({@code targetYaw}) are considered, so
     * the pathfinder never turns away from the target to find a staircase on the
     * opposite side of the barn.
     *
     * @param player    the local player
     * @param goingUp   {@code true} when the target is above the player
     * @param targetYaw horizontal yaw (degrees) toward the ultimate navigation target
     * @return position of the stair entry block (XZ-centred, at block Y), or
     *         {@code null} if none was found within the search area
     */
    private Vec3d findStairEntry(ClientPlayerEntity player, boolean goingUp, float targetYaw) {
        if (client.world == null) return null;
        int px = (int) Math.floor(player.getX());
        int py = (int) Math.floor(player.getY());
        int pz = (int) Math.floor(player.getZ());

        Vec3d best  = null;
        int   bestY = goingUp ? Integer.MAX_VALUE : Integer.MIN_VALUE;

        // Y range: scan upward from player's feet level when ascending, downward
        // when descending.  The opposite bound equals the full height of the barn.
        int yFrom = goingUp ? py : py - STAIR_SEARCH_RADIUS;
        int yTo   = goingUp ? py + STAIR_SEARCH_RADIUS : py;

        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int dx = -STAIR_SEARCH_RADIUS; dx <= STAIR_SEARCH_RADIUS; dx++) {
            for (int dz = -STAIR_SEARCH_RADIUS; dz <= STAIR_SEARCH_RADIUS; dz++) {
                for (int y = yFrom; y <= yTo; y++) {
                    pos.set(px + dx, y, pz + dz);
                    if (!isStepBlock(client.world.getBlockState(pos))) continue;

                    // Only consider blocks in the forward hemisphere relative to the
                    // ultimate target direction so we never navigate away from the visitor.
                    double sdx = (px + dx + 0.5) - player.getX();
                    double sdz = (pz + dz + 0.5) - player.getZ();
                    double dist = Math.sqrt(sdx * sdx + sdz * sdz);
                    if (dist < 0.5) continue; // skip blocks directly under the player
                    float stairYaw = (float) Math.toDegrees(Math.atan2(-sdx, sdz));
                    float yawDiff  = stairYaw - targetYaw;
                    while (yawDiff >  180f) yawDiff -= 360f;
                    while (yawDiff < -180f) yawDiff += 360f;
                    if (Math.abs(yawDiff) > STAIR_FORWARD_CONE_DEGREES) continue;

                    if ((goingUp && y < bestY) || (!goingUp && y > bestY)) {
                        bestY = y;
                        best  = new Vec3d(px + dx + 0.5, y, pz + dz + 0.5);
                    }
                }
            }
        }
        return best;
    }

    /**
     * Scans nearby floor blocks for a passable intermediate navigation waypoint
     * to use when the direct path toward the target is blocked on flat terrain.
     *
     * <p>Searches a {@link #NAV_WAYPOINT_RADIUS}-block horizontal radius around
     * the player at the player's current feet Y-level, considering only positions
     * within {@link #NAV_WAYPOINT_CONE_DEG} degrees of {@code targetYaw} to avoid
     * routing the player away from the visitor.  Among the candidates, the nearest
     * position that is both a valid standing spot
     * (see {@link #isBehindPointPassable}) and has a clear immediate step toward
     * it (see {@link #isPathClear}) is returned.
     *
     * @param player    the local player
     * @param targetYaw horizontal yaw (degrees) toward the ultimate navigation target
     * @param avoidNpcs whether to apply NPC-avoidance when checking the path
     * @return the nearest suitable waypoint (XZ-centred, at player's feet Y), or
     *         {@code null} if no passable block was found in the search area
     */
    private Vec3d findNavWaypoint(ClientPlayerEntity player, float targetYaw, boolean avoidNpcs) {
        if (client.world == null) return null;
        int px = (int) Math.floor(player.getX());
        int py = (int) Math.floor(player.getY());
        int pz = (int) Math.floor(player.getZ());

        Vec3d  best     = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -NAV_WAYPOINT_RADIUS; dx <= NAV_WAYPOINT_RADIUS; dx++) {
            for (int dz = -NAV_WAYPOINT_RADIUS; dz <= NAV_WAYPOINT_RADIUS; dz++) {
                double sdx  = (px + dx + 0.5) - player.getX();
                double sdz  = (pz + dz + 0.5) - player.getZ();
                double dist = Math.sqrt(sdx * sdx + sdz * sdz);
                if (dist < NAV_WAYPOINT_MIN_DIST) continue; // skip the block occupied by the player

                // Only blocks inside the forward cone toward the target.
                float  wayYaw  = (float) Math.toDegrees(Math.atan2(-sdx, sdz));
                float  yawDiff = wayYaw - targetYaw;
                while (yawDiff >  180f) yawDiff -= 360f;
                while (yawDiff < -180f) yawDiff += 360f;
                if (Math.abs(yawDiff) > NAV_WAYPOINT_CONE_DEG) continue;

                // The candidate must be a valid standing position at the player's Y level.
                Vec3d candidate = new Vec3d(px + dx + 0.5, py, pz + dz + 0.5);
                if (!isBehindPointPassable(candidate)) continue;

                // The immediate step toward the candidate must be passable so the
                // player can actually start walking toward it without hitting a wall.
                if (!isPathClear(player, wayYaw, PROBE_STEP, avoidNpcs)) continue;

                if (dist < bestDist) {
                    bestDist = dist;
                    best     = candidate;
                }
            }
        }
        return best;
    }

    /**
     * Compute an auto-pathfinding behind-point just past {@code visitor}, on the
     * far side from the player, so the pathfinder approaches the visitor queue
     * from the rear before rotating to interact.
     *
     * <p>The point is placed {@link #BEHIND_VISITOR_DIST} blocks past the visitor
     * along the player→visitor axis.  If that position is inside a wall the
     * method tries the fallback distances in {@link #BEHIND_VISITOR_DIST_FALLBACKS}
     * before giving up and returning {@code null}.
     *
     * @param player  the local player (used for the player position)
     * @param visitor the target visitor NPC entity
     * @return a passable behind-point, or {@code null} if none could be found
     */
    private Vec3d computeBehindPoint(ClientPlayerEntity player, Entity visitor) {
        if (client.world == null) return null;
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        double vx = visitor.getX(), vy = visitor.getY(), vz = visitor.getZ();
        double dx = vx - px;
        double dz = vz - pz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) return null; // visitor on top of player – skip behind-point
        // Normalised direction from player toward visitor
        double nx = dx / len;
        double nz = dz / len;
        // Try the primary distance then each fallback
        double[] distances = new double[1 + BEHIND_VISITOR_DIST_FALLBACKS.length];
        distances[0] = BEHIND_VISITOR_DIST;
        for (int i = 0; i < BEHIND_VISITOR_DIST_FALLBACKS.length; i++) {
            distances[i + 1] = BEHIND_VISITOR_DIST_FALLBACKS[i];
        }
        for (double dist : distances) {
            Vec3d candidate = new Vec3d(vx + nx * dist, vy, vz + nz * dist);
            if (isBehindPointPassable(candidate)) {
                return candidate;
            }
        }
        return null; // no passable behind-point found; proceed directly to visitor
    }

    /**
     * Returns {@code true} if {@code point} is a valid standing position:
     * the block at the feet level must be air or a step block (stair/slab),
     * the head level must be air, and there must be a solid floor one block below
     * (or a step block at feet level, since stair/slab blocks provide their own
     * surface — e.g. a visitor standing on stairs has {@code floor(y)} land on the
     * stair block itself, making it the effective floor even though the block
     * directly below is air).
     * Used to verify that the computed behind-point is not embedded in a wall
     * before the player is sent there.
     */
    private boolean isBehindPointPassable(Vec3d point) {
        int bx = (int) Math.floor(point.x);
        int by = (int) Math.floor(point.y);
        int bz = (int) Math.floor(point.z);
        BlockState floorState = client.world.getBlockState(new BlockPos(bx, by - 1, bz));
        BlockState feetState  = client.world.getBlockState(new BlockPos(bx, by,     bz));
        BlockState headState  = client.world.getBlockState(new BlockPos(bx, by + 1, bz));
        // hasFloor: the block below is solid OR the feet block itself is a step surface
        // (when floor(y) lands on a stair/slab, that stair/slab is the standing surface).
        boolean hasFloor  = !floorState.isAir() || isStepBlock(feetState);
        boolean feetClear = feetState.isAir()   || isStepBlock(feetState);
        boolean headClear = headState.isAir();
        return hasFloor && feetClear && headClear;
    }

    /**
     * Returns {@code true} if the path {@code probeStep} ahead in the given
     * {@code yawDeg} direction is terrain-passable (see {@link #isPassable}) and,
     * when {@code checkNpcs} is {@code true}, not obstructed by a non-target
     * visitor NPC within {@link #NPC_AVOIDANCE_DIST}.
     *
     * <p>NPC checking is skipped (pass {@code false}) when navigating toward the
     * {@link #behindPoint} so that the line of pending visitors does not falsely
     * block the path toward the far end of the queue.
     */
    private boolean isPathClear(ClientPlayerEntity player, double yawDeg, double probeStep,
                                 boolean checkNpcs) {
        if (!isPassable(player, yawDeg, probeStep)) return false;
        if (!checkNpcs) return true;
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
                        && KNOWN_VISITOR_NAMES.contains(
                                stripFormatting(e.getCustomName().getString())))
                .isEmpty();
    }

    /**
     * Convenience overload that always checks for NPC obstacles.
     * Equivalent to {@code isPathClear(player, yawDeg, probeStep, true)}.
     */
    private boolean isPathClear(ClientPlayerEntity player, double yawDeg, double probeStep) {
        return isPathClear(player, yawDeg, probeStep, true);
    }

    /**
     * Returns the player's movement speed as a multiple of the vanilla default.
     * 1.0 = normal speed; values above 1.0 indicate SkyBlock speed buffs.
     */
    private double getSpeedMultiplier(ClientPlayerEntity player) {
        double attrVal = player.getAttributeValue(EntityAttributes.MOVEMENT_SPEED);
        return Math.max(1.0, attrVal / BASE_WALK_SPEED);
    }

    /** Smoothly rotate the player's camera toward {@code target} over multiple ticks. */
    private void lookAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x;
        double dy = (target.y + 1.0) - eye.y; // aim at roughly head height
        double dz = target.z - eye.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        targetYaw   = (float) Math.toDegrees(Math.atan2(-dx, dz));
        targetPitch = (float) -Math.toDegrees(Math.atan2(dy, distXZ));
        smoothRotateCamera(player);
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
     * rate.  Higher values produce faster camera tracking which is required at
     * elevated SkyBlock movement speeds to maintain accurate directional control.
     *
     * <p>Camera movement is fully delta-based: each call computes the angular delta
     * proportional to the elapsed wall-clock time since the previous call
     * ({@code deltaMs / 1000 * degreesPerSecond}) and adds it to the current
     * rotation, rather than computing a new absolute Vec3d target.  This ensures
     * smooth, frame-rate-independent rotation without sudden jumps after lag spikes.
     */
    private void smoothRotateCamera(ClientPlayerEntity player, float degreesPerSecond) {
        long now = System.currentTimeMillis();
        // Cap the elapsed time to SMOOTH_LOOK_MAX_DELTA_MS so a severe lag spike
        // does not teleport the camera by applying a huge angular delta in one step.
        float deltaMs = (lastSmoothLookTime == 0)
                ? SMOOTH_LOOK_INITIAL_DELTA_MS
                : Math.min(SMOOTH_LOOK_MAX_DELTA_MS, (float)(now - lastSmoothLookTime));
        // Always advance the timestamp so the next call computes an accurate delta.
        lastSmoothLookTime = now;

        // Maximum angular delta this step, proportional to actual elapsed time.
        float step = degreesPerSecond * deltaMs / 1000.0f;

        float currentYaw   = player.getYaw();
        float currentPitch = player.getPitch();

        // Normalise the yaw error to [-180, 180] so we always take the shortest arc.
        float yawDiff = targetYaw - currentYaw;
        while (yawDiff >  180f) yawDiff -= 360f;
        while (yawDiff < -180f) yawDiff += 360f;

        float pitchDiff = targetPitch - currentPitch;

        // Already on target – nothing to do.  Threshold is set above the tremor
        // amplitude so the camera does not oscillate around the aim point.
        if (Math.abs(yawDiff) < 1.0f && Math.abs(pitchDiff) < 1.0f) return;

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
        for (int i = 0; i < slotCount; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;

            LoreComponent lore = stack.getOrDefault(
                    DataComponentTypes.LORE, LoreComponent.DEFAULT);
            List<Text> lines = lore.lines();

            // Check whether this item's lore contains an "Items Required" section.
            boolean hasRequiredSection = lines.stream().anyMatch(l -> {
                String s = stripFormatting(l.getString()).toLowerCase();
                return s.contains("items required") || s.startsWith("required:");
            });

            if (hasRequiredSection) {
                // Section-aware parse: only extract lines from the required section.
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
            } else {
                // Fallback: only look at the item's display name, never lore lines.
                // This avoids treating reward-item lore as requirements.
                tryAddRequirement(stripFormatting(stack.getName().getString()));
            }
        }
        if (!pendingRequirements.isEmpty()) {
            LOGGER.info("[JustFarming-Visitors] Visitor requires: {}", pendingRequirements);
            // Check max-visitor-price limit.
            // visitorsMaxPrice == 0 means "no limit" (feature disabled).
            if (config.visitorsMaxPrice > 0) {
                double totalValue = VisitorNpcPrices.getTotalNpcValue(pendingRequirements);
                if (totalValue > config.visitorsMaxPrice) {
                    LOGGER.info("[JustFarming-Visitors] Visitor NPC value ({} coins) exceeds max ({} coins); declining.",
                            (long) totalValue, config.visitorsMaxPrice);
                    pendingRequirements.clear();
                    skipCurrentVisitorDueToPrice = true;
                }
            }
        } else {
            LOGGER.info("[JustFarming-Visitors] Could not parse any requirements from visitor menu.");
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
        return null;
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
        LOGGER.info("[JustFarming-Visitors] Preparing to type /bazaar for: {}",
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
        behindPoint = null; // behind-point only applies to the first (farthest) visitor
        behindPointStartTime = 0;
        currentVisitor = null;

        if (!pendingVisitors.isEmpty()) {
            currentVisitor = pendingVisitors.remove(0);
            LOGGER.info("[JustFarming-Visitors] Moving to next visitor.");
            enterState(State.NAVIGATING);
        } else {
            // End-of-queue rescan: after all initially-found visitors are processed,
            // scan once more for any 6th visitor who spawned late at the far end of
            // the line.  This keeps the visit order V5 → V4 → V3 → V2 → V1 → V6
            // (V6 is handled last, after the player has walked back toward the queue).
            if (!midRunRescanPerformed && client.player != null) {
                midRunRescanPerformed = true;
                scanForVisitors(client.player);
            }
            if (!pendingVisitors.isEmpty()) {
                currentVisitor = pendingVisitors.remove(0);
                LOGGER.info("[JustFarming-Visitors] Found additional visitor(s) after rescan.");
                enterState(State.NAVIGATING);
            } else {
                LOGGER.info("[JustFarming-Visitors] All visitors processed.");
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
            LOGGER.info("[JustFarming-Visitors] Clicked bazaar item '{}' at slot {}.", matched, slot);
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
                    LOGGER.info("[JustFarming-Visitors] Clicked '{}' at slot {}.", kw, i);
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
