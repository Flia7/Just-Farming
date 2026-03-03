package com.justfarming.visitor;

import com.justfarming.config.FarmingConfig;
import net.minecraft.block.BlockState;
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

    /** Steer-angle offsets (degrees) tried in order when the direct path is blocked by a wall. */
    private static final float[] WALL_STEER_ANGLES = { 15f, -15f, 30f, -30f };

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
     * chosen walk direction before walking is suppressed.  Prevents the player
     * from pressing the forward key in the wrong direction while the camera is
     * still rotating to face the target – which would carry them away from or
     * into a wall.
     */
    private static final float MAX_WALK_YAW_ERROR_DEGREES = 45f;

    /**
     * Vanilla-default player walk-speed attribute value.
     * Used as a baseline to measure how much faster the player is moving on
     * Hypixel SkyBlock (due to Speed buffs from armour, pets, enchants, etc.).
     */
    private static final double BASE_WALK_SPEED = 0.1;

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
     * Number of visitors whose offer has been accepted so far in this run.
     * Used to trigger the mid-run rescan for a 6th visitor after the second
     * visitor (the farthest one) has been processed.
     */
    private int visitorsAccepted = 0;
    /**
     * {@code true} once the mid-run rescan (triggered after the 2nd visitor)
     * has been performed this run.  Prevents the end-of-queue rescan from
     * scanning a second time when the initial scan had only 2 visitors.
     */
    private boolean midRunRescanPerformed = false;
    /**
     * Intermediate waypoint placed {@link #BEHIND_VISITOR_DIST} blocks past the
     * farthest visitor (away from the player's starting position).  The routine
     * navigates here before turning to accept the first visitor so it approaches
     * from behind the queue.  {@code null} once the point has been reached or
     * when not applicable.
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
        behindPoint = null;
        behindPointStartTime = 0;
        returnWarpDelay = 0;
        returnWarpSentAt = 0;
        visitorsAccepted = 0;
        midRunRescanPerformed = false;
        enterState(State.IDLE);
    }


    public void start() {
        LOGGER.info("[JustFarming-Visitors] Starting visitor routine.");
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
        walkJitter        = 0f;
        walkJitterNextUpdate = 0;
        lastSmoothLookTime = 0;
        returnWarpDelay   = 0;
        returnWarpSentAt  = 0;
        visitorsAccepted  = 0;
        midRunRescanPerformed = false;
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
                    currentVisitor = pendingVisitors.remove(0);
                    behindPoint = null;
                    behindPointStartTime = 0;
                    LOGGER.info("[JustFarming-Visitors] Found {} visitor(s). Navigating farthest first.",
                            pendingVisitors.size() + 1);
                    enterState(State.NAVIGATING);
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
                    if (reached || timedOut) {
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
                    if (now >= interactCooldownUntil) {
                        lookAt(player, visitorPos);
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
                    if (now >= interactCooldownUntil) {
                        lookAt(player, visitorPos);
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
     * <p>Visitors are sorted farthest-first so the acceptance sequence is
     * 5 → 4 → [6 if present] → 3 → 2 → 1 when combined with the mid-run
     * rescan that fires after the second visitor (V4) is accepted.
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
        double dy = (target.y + 1.0) - eye.y; // aim at roughly head height
        double dz = target.z - eye.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float baseTargetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        targetPitch = (float) -Math.toDegrees(Math.atan2(dy, distXZ));

        // Compute how many times faster the player is moving compared to vanilla.
        // This covers Speed-effect buffs, armour bonuses, and other SkyBlock modifiers
        // that all ultimately manifest as a higher MOVEMENT_SPEED attribute value.
        double speedMult = getSpeedMultiplier(player);

        // Scale the probe-step with speed so we look further ahead when moving fast,
        // giving enough reaction distance to stop before hitting a wall.
        double probeStep = Math.min(PROBE_STEP * speedMult, 5.0);

        // Try the direct path first; if clear, apply jitter for humanlike variation.
        // If blocked (wall or nearby visitor NPC), try steering angles to navigate around.
        boolean shouldWalk = false;
        float chosenYaw = baseTargetYaw;
        if (isPathClear(player, baseTargetYaw, probeStep, avoidNpcs)) {
            shouldWalk = true;
            chosenYaw  = baseTargetYaw + walkJitter; // jitter for varied paths when clear
        } else {
            for (float steer : WALL_STEER_ANGLES) {
                if (isPathClear(player, baseTargetYaw + steer, probeStep, avoidNpcs)) {
                    shouldWalk = true;
                    chosenYaw  = baseTargetYaw + steer; // steer around the wall (no jitter)
                    break;
                }
            }
        }

        // Aim camera toward the chosen direction with a single smooth step.
        targetYaw = chosenYaw;
        smoothRotateCamera(player);

        if (shouldWalk) {
            // Speed-aware pulsed walking near the target.
            //
            // At high SkyBlock movement speeds the player can overshoot the visitor
            // in a single tick.  Once inside the "braking zone" we only press the
            // forward key every pulseStride ticks (proportional to speed), reducing
            // the average forward velocity enough to stop precisely at INTERACT_RADIUS.
            double dist = new Vec3d(player.getX(), player.getY(), player.getZ()).distanceTo(target);
            double brakingRadius = INTERACT_RADIUS + speedMult * 2.0;
            if (dist < brakingRadius) {
                int pulseStride = Math.max(1, (int) Math.ceil(speedMult));
                long ticks = client.world.getTime(); // world game-tick counter
                shouldWalk = (ticks % pulseStride == 0);
            }
        }

        // Suppress walking when the camera is still far from the chosen direction.
        // The forward key moves the player in the direction they are currently looking,
        // so pressing it while the camera is >MAX_WALK_YAW_ERROR_DEGREES off-target
        // would carry them in the wrong direction (e.g. away from the visitor or into
        // a wall after rounding the behind-point).
        if (shouldWalk) {
            float yawError = chosenYaw - player.getYaw();
            while (yawError >  180f) yawError -= 360f;
            while (yawError < -180f) yawError += 360f;
            if (Math.abs(yawError) > MAX_WALK_YAW_ERROR_DEGREES) {
                shouldWalk = false;
            }
        }

        // Only walk forward when there is solid ground ahead and no wall blocking the path.
        // Never trigger a jump – on Hypixel SkyBlock speed/jump buffs can reach level 10,
        // so autonomous jumping causes erratic movement near visitor NPCs.
        client.options.forwardKey.setPressed(shouldWalk);
        client.options.jumpKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
    }

    /**
     * Returns {@code true} if {@code state} is a stair or slab block.
     *
     * <p>Stair and slab blocks occupy only part of their block space and can be
     * stepped onto automatically by Minecraft's movement code, so the pathfinder
     * should treat them as walkable surfaces rather than walls.
     */
    private static boolean isStepBlock(BlockState state) {
        return state.getBlock() instanceof StairsBlock
                || state.getBlock() instanceof SlabBlock;
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
        boolean headBlocked;
        if (feetState.isAir()) {
            headBlocked = false; // step-down: head after descent is within the air at feetY
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
        long now = System.currentTimeMillis();
        // Cap the delta to SMOOTH_LOOK_MAX_DELTA_MS so a severe lag spike doesn't teleport the camera.
        float deltaMs = (lastSmoothLookTime == 0)
                ? SMOOTH_LOOK_INITIAL_DELTA_MS
                : Math.min(SMOOTH_LOOK_MAX_DELTA_MS, (float)(now - lastSmoothLookTime));
        // Always advance the timestamp so the next call computes an accurate delta.
        lastSmoothLookTime = now;

        // Scale the maximum step by actual elapsed time for frame-rate independence.
        float step = SMOOTH_LOOK_DEGREES_PER_SECOND * deltaMs / 1000.0f;

        float currentYaw   = player.getYaw();
        float currentPitch = player.getPitch();

        // Normalise yaw delta to [-180, 180] to take the shortest arc
        float yawDiff = targetYaw - currentYaw;
        while (yawDiff >  180f) yawDiff -= 360f;
        while (yawDiff < -180f) yawDiff += 360f;

        float pitchDiff = targetPitch - currentPitch;

        // Already on target – nothing to do.  Threshold is set above the tremor
        // amplitude so the camera does not oscillate around the aim point.
        if (Math.abs(yawDiff) < 1.0f && Math.abs(pitchDiff) < 1.0f) return;

        float newYaw   = currentYaw   + Math.max(-step, Math.min(step, yawDiff));
        float newPitch = currentPitch + Math.max(-step, Math.min(step, pitchDiff));
        newPitch = Math.max(-90f, Math.min(90f, newPitch));

        // Add a tiny random tremor to simulate the micro-vibration of a real
        // mouse player (delta-based, like genuine mouse input).
        float tremorYaw   = (random.nextFloat() * 2f - 1f) * SMOOTH_LOOK_TREMOR_AMPLITUDE;
        float tremorPitch = (random.nextFloat() * 2f - 1f) * SMOOTH_LOOK_TREMOR_AMPLITUDE * SMOOTH_LOOK_TREMOR_PITCH_SCALE;

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
        visitorsAccepted++;

        // Mid-run rescan: after accepting the 2nd visitor (V4), scan again so
        // that any 6th visitor who spawned at the far end of the line is detected.
        // The farthest-first sort inside scanForVisitors puts the new visitor
        // before the remaining closer ones: [V6, V3, V2, V1].
        if (visitorsAccepted == 2 && !midRunRescanPerformed && client.player != null) {
            midRunRescanPerformed = true;
            scanForVisitors(client.player);
            LOGGER.info("[JustFarming-Visitors] Mid-run rescan after 2nd visitor; {} visitor(s) found.", pendingVisitors.size());
        }

        if (!pendingVisitors.isEmpty()) {
            currentVisitor = pendingVisitors.remove(0);
            LOGGER.info("[JustFarming-Visitors] Moving to next visitor.");
            enterState(State.NAVIGATING);
        } else {
            // End-of-queue rescan: check for a late-arriving visitor when the
            // mid-run rescan was not triggered (fewer than 2 visitors initially).
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
}
