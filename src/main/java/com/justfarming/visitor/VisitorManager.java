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
     * 90° in one second, providing snappy but smooth tracking of visitor NPCs.
     */
    private static final float SMOOTH_LOOK_DEGREES_PER_SECOND = 90.0f;

    /**
     * Faster camera rotation speed (degrees/second) used when the player is
     * already within {@link #VISITOR_DETECT_RADIUS} of a visitor and needs to
     * align quickly but still smoothly (not a hard snap).  At 540°/s a 180°
     * turn completes in ~0.3 s – noticeably fast but visually continuous.
     */
    private static final float FAST_LOOK_DEGREES_PER_SECOND = 540.0f;

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

    // ── State machine ────────────────────────────────────────────────────────

    /** Internal states of the visitor routine. */
    public enum State {
        /** Not doing anything. */
        IDLE,
        /** Double-pressing space to turn off creative flight before the routine begins. */
        DISABLING_FLIGHT,
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
     */
    private boolean positionAnchored = false;

    /**
     * The world position to look at for every interaction while
     * {@link #positionAnchored} is {@code true}.  Set to the first visitor's
     * position the moment the player enters {@link #INTERACT_RADIUS}.
     */
    private Vec3d anchorLookPos = null;

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
        lastSmoothLookTime = 0;
        fastRotateActive  = false;
        disableFlightStartTime = 0;
        returnWarpDelay   = 0;
        returnWarpSentAt  = 0;
        midRunRescanPerformed = false;
        positionAnchored  = false;
        anchorLookPos     = null;
        walkLastJumpTime  = 0;
        long base = Math.max(0, config.visitorsTeleportDelay);
        teleportWaitMs = base + random.nextInt((int) TELEPORT_EXTRA_RANDOM_MS + 1)
                + random.nextInt(Math.max(1, config.globalRandomizationMs));
        // If the player is currently flying, disable flight first before teleporting.
        if (player != null && player.getAbilities().flying) {
            LOGGER.info("[Just Farming-Visitors] Player is flying; disabling flight before starting routine.");
            enterState(State.DISABLING_FLIGHT);
        } else {
            enterState(State.TELEPORTING);
            sendCommand("tptoplot barn");
        }
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
                        currentVisitor = pendingVisitors.remove(0);
                        LOGGER.info("[Just Farming-Visitors] Found {} visitor(s). Trading closest-first.",
                                pendingVisitors.size() + 1);
                        enterState(State.NAVIGATING);
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

                if (positionAnchored) {
                    // Already reached the first visitor's position – stand still and
                    // aim at the anchor point for all subsequent visitor interactions.
                    // Only send the interact packet once the visitor NPC has actually
                    // arrived within interact range; do not click blindly while the
                    // visitor is still far away or hasn't spawned at the barn yet.
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
                    // First visitor reached – anchor here for all subsequent interactions.
                    positionAnchored = true;
                    anchorLookPos = visitorPos;
                    fastRotateActive = dist <= VISITOR_DETECT_RADIUS;
                    lookAt(player, visitorPos, fastRotateActive
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
                    // Wait the longer of (actionDelay, VISITOR_MENU_MIN_PARSE_DELAY_MS)
                    // before parsing so that all slot-data packets sent by the server
                    // after the screen-open packet have time to arrive.
                    // Without this guard the parse can run while slots are still empty,
                    // producing zero requirements and causing the macro to skip bazaar.
                    if (now - stateEnteredAt >= Math.max(currentActionDelay, VISITOR_MENU_MIN_PARSE_DELAY_MS)) {
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
                        } else {
                            parseVisitorMenu(screen);
                            if (skipCurrentVisitorDueToPrice) {
                                // Price limit exceeded – click "Decline Offer" in the GUI
                                // before closing the menu so the server records the decline.
                                tryClickDeclineOffer(screen);
                            }
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
                            String name = stripFormatting(e.getCustomName().getString());
                            if (!KNOWN_VISITOR_NAMES.contains(name)) return false;
                            if (completedVisitorIds.contains(e.getId())) {
                                LOGGER.info("[Just Farming-Visitors] Skipping already-completed visitor: {}", name);
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

        // Direction from eye to target; keep pitch neutral during navigation.
        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x;
        double dz = target.z - eye.z;
        float baseTargetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        targetPitch = 0f;

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
                // Alternate strafe direction on each successive crash (right, then left, …).
                walkRecoveryDirection     = (walkRecoveryDirection == 1) ? -1 : 1;
                walkRecoveryBackupEndTime = now + WALK_RECOVERY_BACKUP_MS;
                walkRecoveryEndTime       = now + WALK_RECOVERY_BACKUP_MS + WALK_RECOVERY_STRAFE_MS;
                LOGGER.debug("[Just Farming-Visitors] Stuck ({} blocks); backing up then strafing {}.",
                        String.format("%.2f", progress),
                        walkRecoveryDirection > 0 ? "right" : "left");
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
                        && KNOWN_VISITOR_NAMES.contains(
                                stripFormatting(e.getCustomName().getString())))
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
