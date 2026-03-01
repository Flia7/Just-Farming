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

    /** Time to wait after sending an interact packet before checking for a menu (ms). */
    private static final long INTERACT_WAIT_MS = 800;

    /** Time to wait after {@code /bazaar <item>} before checking for the screen (ms). */
    private static final long BAZAAR_WAIT_MS = 1500;

    /** Time to wait after clicking "Buy Instantly" before looking for a confirm button (ms). */
    private static final long BUY_WAIT_MS = 600;

    /** Minimum ms between consecutive entity-interact attempts. */
    private static final long INTERACT_COOLDOWN_MS = 1200;

    /** How long to scan for visitors before giving up and returning to the farm (ms). */
    private static final long SCAN_TIMEOUT_MS = 3000;

    /** Pause after {@code /warp garden} to allow the command to register (ms). */
    private static final long WARP_COMMAND_WAIT_MS = 1500;

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
        /** {@code /bazaar <item>} sent; waiting for the bazaar screen. */
        OPENING_BAZAAR,
        /** The bazaar screen is open; navigating to "Buy Instantly". */
        READING_BAZAAR,
        /** "Buy Instantly" clicked; waiting for a confirmation screen. */
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

    private final MinecraftClient client;
    private FarmingConfig config;

    // Visitor tracking
    private Entity       currentVisitor  = null;
    private final List<Entity> pendingVisitors = new ArrayList<>();

    // Item requirements extracted from the current visitor's menu
    private final List<VisitorRequirement> pendingRequirements = new ArrayList<>();
    private int requirementIndex = 0;

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
        enterState(State.DONE);
    }


    public void start() {
        LOGGER.info("[JustFarming-Visitors] Starting visitor routine.");
        pendingVisitors.clear();
        pendingRequirements.clear();
        requirementIndex  = 0;
        currentVisitor    = null;
        interactCooldownUntil = 0;
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
                long delay = config.visitorsTeleportDelay > 0
                        ? config.visitorsTeleportDelay : TELEPORT_WAIT_DEFAULT_MS;
                if (now - stateEnteredAt >= delay) {
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
                if (now - stateEnteredAt >= INTERACT_WAIT_MS) {
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
                    if (!pendingRequirements.isEmpty() && config.visitorsBuyFromBazaar) {
                        requirementIndex = 0;
                        openBazaarForCurrentRequirement();
                    } else {
                        startAcceptingOffer();
                    }
                }
            }

            case OPENING_BAZAAR -> {
                if (now - stateEnteredAt >= BAZAAR_WAIT_MS) {
                    if (client.currentScreen instanceof HandledScreen<?>) {
                        enterState(State.READING_BAZAAR);
                    } else {
                        LOGGER.warn("[JustFarming-Visitors] Bazaar screen did not open; skipping.");
                        nextRequirementOrAccept();
                    }
                }
            }

            case READING_BAZAAR -> {
                if (client.currentScreen instanceof HandledScreen<?> screen) {
                    if (tryClickBuyInstantly(screen)) {
                        enterState(State.CONFIRMING_PURCHASE);
                    } else {
                        player.closeHandledScreen();
                        nextRequirementOrAccept();
                    }
                } else {
                    nextRequirementOrAccept();
                }
            }

            case CONFIRMING_PURCHASE -> {
                if (now - stateEnteredAt >= BUY_WAIT_MS) {
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        tryClickConfirm(screen);
                        // Let the CLOSING_MENU state handle the screen close
                        enterState(State.CLOSING_MENU);
                    } else {
                        nextRequirementOrAccept();
                    }
                }
            }

            case ACCEPTING_OFFER -> {
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
                if (now - stateEnteredAt >= INTERACT_WAIT_MS) {
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        tryClickAcceptOffer(screen);
                        // Close after accepting
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
        LOGGER.info("[JustFarming-Visitors] -> {}", next);
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

    /** Rotate the player's camera to face {@code target}. */
    private void lookAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x;
        double dy = (target.y + 1.0) - eye.y; // aim at roughly head height
        double dz = target.z - eye.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float  yaw    = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float  pitch  = (float) -Math.toDegrees(Math.atan2(dy, distXZ));
        player.setYaw(yaw);
        player.setPitch(pitch);
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
        String itemName = pendingRequirements.get(requirementIndex).itemName;
        sendCommand("bazaar " + itemName);
        enterState(State.OPENING_BAZAAR);
        LOGGER.info("[JustFarming-Visitors] Opening bazaar for: {}", itemName);
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
            LOGGER.info("[JustFarming-Visitors] All visitors processed.");
            returnToFarm();
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
     * Scan the open screen for a confirmation slot ("Confirm", "Yes", "Buy")
     * and click it.
     *
     * @return {@code true} if a matching button was found and clicked.
     */
    private boolean tryClickConfirm(HandledScreen<?> screen) {
        return tryClickSlotWithName(screen, "Confirm", "Yes");
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
