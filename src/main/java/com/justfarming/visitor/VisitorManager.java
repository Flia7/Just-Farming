package com.justfarming.visitor;

import com.justfarming.config.FarmingConfig;
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
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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

    /** Time to wait after {@code /bazaar <item>} before checking for the screen (ms). */
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
    private static final long POST_BAZAAR_WALK_DELAY_MS = 150;

    /**
     * Maximum camera rotation step per tick (degrees) for smooth look-at movement.
     * This replicates the natural feel of a player moving their mouse.
     */
    private static final float SMOOTH_LOOK_DEGREES_PER_TICK = 8.0f;

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
     * How far ahead (blocks) to probe the terrain when navigating.
     * Slightly more than half a block so we reliably detect edges and walls
     * before the player's centre reaches them.
     * At high SkyBlock movement speeds this value is scaled up dynamically.
     */
    private static final double PROBE_STEP = 0.6;

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

    private final MinecraftClient client;
    private FarmingConfig config;
    private final Random random = new Random();

    // Smooth camera rotation targets
    private float targetYaw   = 0f;
    private float targetPitch = 0f;

    // Visitor tracking
    private Entity       currentVisitor  = null;
    private final List<Entity> pendingVisitors = new ArrayList<>();

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
        enterState(State.DONE);
    }


    public void start() {
        LOGGER.info("[JustFarming-Visitors] Starting visitor routine.");
        pendingVisitors.clear();
        pendingRequirements.clear();
        requirementIndex  = 0;
        currentVisitor    = null;
        interactCooldownUntil = 0;
        postAccept        = false;
        postPurchase      = false;
        long base = config.visitorsTeleportDelay > 0
                ? config.visitorsTeleportDelay : TELEPORT_WAIT_DEFAULT_MS;
        teleportWaitMs = base + random.nextInt((int) TELEPORT_EXTRA_RANDOM_MS + 1);
        enterState(State.TELEPORTING);
        sendCommand("tptoplot barn");
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
                    LOGGER.info("[JustFarming-Visitors] Found {} visitor(s).",
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
                double dist = new Vec3d(player.getX(), player.getY(), player.getZ()).distanceTo(visitorPos);
                if (dist <= INTERACT_RADIUS) {
                    releaseMovementKeys();
                    if (now >= interactCooldownUntil) {
                        lookAt(player, visitorPos);
                        interactWithEntity(player, currentVisitor);
                        interactCooldownUntil = now + INTERACT_COOLDOWN_MS;
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
                    parseVisitorMenu(screen);
                    // Close the screen before opening the bazaar (or accepting)
                    player.closeHandledScreen();
                    enterState(State.CLOSING_MENU);
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
                if (now - stateEnteredAt >= currentActionDelay) {
                    if (client.currentScreen instanceof HandledScreen<?>) {
                        enterState(State.CLICKING_BAZAAR_ITEM);
                    } else {
                        LOGGER.warn("[JustFarming-Visitors] Bazaar screen did not open; skipping.");
                        nextRequirementOrAccept();
                    }
                }
            }

            case CLICKING_BAZAAR_ITEM -> {
                if (now - stateEnteredAt >= currentActionDelay) {
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        String itemName = pendingRequirements.get(requirementIndex).itemName;
                        if (tryClickItemByName(screen, itemName)) {
                            enterState(State.READING_BAZAAR);
                        } else {
                            LOGGER.warn("[JustFarming-Visitors] Could not find '{}' in bazaar; skipping.", itemName);
                            player.closeHandledScreen();
                            nextRequirementOrAccept();
                        }
                    } else {
                        nextRequirementOrAccept();
                    }
                }
            }

            case READING_BAZAAR -> {
                if (now - stateEnteredAt >= currentActionDelay) {
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        if (tryClickBuyInstantly(screen)) {
                            int amount = pendingRequirements.get(requirementIndex).amount;
                            amountToType  = String.valueOf(amount);
                            signTypingStep = 0;
                            enterState(State.ENTERING_AMOUNT);
                        } else {
                            player.closeHandledScreen();
                            nextRequirementOrAccept();
                        }
                    } else {
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
                        // Space digits so total typing takes at least 300 ms.
                        long perCharMs = Math.max(75L, 300L / amountToType.length());
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
                if (now - stateEnteredAt >= currentActionDelay) {
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        tryClickConfirm(screen);
                        // Press ESC to close the bazaar screen after confirming
                        player.closeHandledScreen();
                        postPurchase = true;
                        enterState(State.CLOSING_MENU);
                    } else {
                        nextRequirementOrAccept();
                    }
                }
            }

            case ACCEPTING_OFFER -> {
                if (now - stateEnteredAt < POST_BAZAAR_WALK_DELAY_MS) return;
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
                        interactCooldownUntil = now + INTERACT_COOLDOWN_MS;
                        enterState(State.WAITING_FOR_ACCEPT);
                    }
                } else {
                    walkToward(player, visitorPos);
                }
            }

            case WAITING_FOR_ACCEPT -> {
                if (now - stateEnteredAt >= currentActionDelay) {
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        tryClickAcceptOffer(screen);
                        // Close after accepting; mark that the next CLOSING_MENU
                        // should move to the next visitor, not re-enter accepting.
                        postAccept = true;
                        player.closeHandledScreen();
                        enterState(State.CLOSING_MENU);
                    } else {
                        nextVisitor();
                    }
                }
            }

            case RETURNING_TO_FARM -> {
                // Allow a small pause so the warp command registers
                if (now - stateEnteredAt >= WARP_COMMAND_WAIT_MS) {
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

    /** Populate {@link #pendingVisitors} with NPC entities in range. */
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
                            if (config.visitorBlacklist != null && config.visitorBlacklist.contains(name)) {
                                LOGGER.info("[JustFarming-Visitors] Skipping blacklisted visitor: {}", name);
                                return false;
                            }
                            return true;
                        })
                .forEach(pendingVisitors::add);
    }

    private void walkToward(ClientPlayerEntity player, Vec3d target) {
        if (client.options == null || client.world == null) return;
        lookAt(player, target);

        // Compute how many times faster the player is moving compared to vanilla.
        // This covers Speed-effect buffs, armour bonuses, and other SkyBlock modifiers
        // that all ultimately manifest as a higher MOVEMENT_SPEED attribute value.
        double speedMult = getSpeedMultiplier(player);

        // Scale the probe-step with speed so we look further ahead when moving fast,
        // giving enough reaction distance to stop before hitting a wall.
        double probeStep = Math.min(PROBE_STEP * speedMult, 5.0);

        double yawRad   = Math.toRadians(player.getYaw());
        double stepX    = -Math.sin(yawRad) * probeStep;
        double stepZ    =  Math.cos(yawRad) * probeStep;
        double nextX    = player.getX() + stepX;
        double nextZ    = player.getZ() + stepZ;
        int    feetY    = (int) Math.floor(player.getY());

        net.minecraft.util.math.BlockPos floorPos =
                new net.minecraft.util.math.BlockPos(
                        (int) Math.floor(nextX), feetY - 1, (int) Math.floor(nextZ));
        net.minecraft.util.math.BlockPos feetPos =
                new net.minecraft.util.math.BlockPos(
                        (int) Math.floor(nextX), feetY, (int) Math.floor(nextZ));
        net.minecraft.util.math.BlockPos headPos =
                new net.minecraft.util.math.BlockPos(
                        (int) Math.floor(nextX), feetY + 1, (int) Math.floor(nextZ));

        boolean floorAhead = !client.world.getBlockState(floorPos).isAir();
        // Stop walking if there is a solid block at feet or head level ahead (wall).
        boolean wallAhead  = !client.world.getBlockState(feetPos).isAir()
                          || !client.world.getBlockState(headPos).isAir();

        boolean shouldWalk = floorAhead && !wallAhead;
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
     * {@link #targetYaw}/{@link #targetPitch} at a fixed rate of
     * {@link #SMOOTH_LOOK_DEGREES_PER_TICK} degrees per tick, replicating the
     * feel of a player naturally moving their mouse.
     */
    private void smoothRotateCamera(ClientPlayerEntity player) {
        float currentYaw   = player.getYaw();
        float currentPitch = player.getPitch();

        // Normalise yaw delta to [-180, 180] to take the shortest arc
        float yawDiff = targetYaw - currentYaw;
        while (yawDiff >  180f) yawDiff -= 360f;
        while (yawDiff < -180f) yawDiff += 360f;

        float pitchDiff = targetPitch - currentPitch;

        // Already on target – nothing to do
        if (Math.abs(yawDiff) < 0.1f && Math.abs(pitchDiff) < 0.1f) return;

        float step = SMOOTH_LOOK_DEGREES_PER_TICK;
        float newYaw   = currentYaw   + Math.max(-step, Math.min(step, yawDiff));
        float newPitch = currentPitch + Math.max(-step, Math.min(step, pitchDiff));
        newPitch = Math.max(-90f, Math.min(90f, newPitch));

        player.setYaw(newYaw);
        player.setPitch(newPitch);
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
        currentVisitor = null;
        if (!pendingVisitors.isEmpty()) {
            currentVisitor = pendingVisitors.remove(0);
            LOGGER.info("[JustFarming-Visitors] Moving to next visitor.");
            enterState(State.NAVIGATING);
        } else {
            // Re-scan in case a new (6th) visitor appeared after we started processing
            if (client.player != null) {
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
        sendCommand("warp garden");
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
