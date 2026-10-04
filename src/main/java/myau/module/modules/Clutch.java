package myau.module.modules;

import myau.util.Ping;
import myau.util.AsyncLog;
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.enums.BlinkModules;
import myau.events.AttackEvent;
import myau.events.LoadWorldEvent;
import myau.events.MoveInputEvent;
import myau.events.PacketEvent;
import myau.events.RightClickMouseEvent;
import myau.events.UpdateEvent;
import myau.management.Arbiter;
import myau.management.RotationState;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.KeyProperty;
import myau.property.properties.ModeProperty;
import myau.util.BlockUtil;
import myau.util.ChatUtil;
import myau.util.MoveUtil;
import myau.util.RandomUtil;
import myau.util.RotationEngine;
import myau.util.RotationUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.BlockTNT;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldSettings.GameType;
import org.lwjgl.input.Keyboard;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Catches a fall with a block: knocked off a bridge, or walked off an edge
 * over the void.
 *
 * 2026-09-25 REWRITE. The previous version (a port of Raven bS's script) was
 * measured in a BedFight session with its debug output on, and failed in
 * three distinct ways, each visible in the log:
 *
 *   IT LOOKED FOR THE WRONG THING. It chose an existing solid block near where
 *   the player was heading and placed against one of its faces. The new block
 *   then sat beside the path rather than under it: three blocks placed at
 *   y84, y79 and y73 on one fall, each against a wall, and the player fell
 *   past all three into the void. It also only searched one to four blocks
 *   below the feet, so once knocked a few blocks under the bridge it found
 *   nothing at all -- "no aim" from fall 2 to fall 29, repeatedly, then death.
 *
 *   IT PLACED A TICK LATE. It aimed from the position before this tick's
 *   movement and clicked on the next tick, by which time the server had the
 *   position after it. Falling at one to three blocks a tick, the look
 *   direction it had validated no longer pointed at the face from where the
 *   server thought the player was: placements refused with nobody near
 *   (REJECT, body 14), and pulled back.
 *
 *   IT TRIGGERED ON FALL DISTANCE. Knockback throws the player up first, and
 *   the fall distance only starts counting at the top of the arc, which is
 *   the moment the bridge starts getting away.
 *
 * So this works the other way round, in the order a person who clutches well
 * thinks in:
 *
 *   WHERE WILL I BE. The fall is simulated tick by tick against the world's
 *   collision boxes (the idea Raven's auto-clutch uses for its trigger). That
 *   one prediction decides whether to act -- the void below, or after a hit a
 *   drop worse than safe-drop -- and where a block would have to be.
 *
 *   WHICH CELL CATCHES ME. Candidate cells are the ones under the predicted
 *   footprint whose top the feet actually cross, highest first: a block there
 *   ends the fall; a block anywhere else is decoration.
 *
 *   WHAT CAN I CLICK TO PUT ONE THERE. Any solid neighbour's facing side,
 *   including the underside of the block above (so a block can be hung under
 *   the bridge once already below it), reachable and unobstructed from where
 *   the eyes will be when the click is sent. With no neighbour at all, a short
 *   chain is built toward the cell from the nearest support, but only when
 *   there is time for every block of it.
 *
 *   HOW FAST MUST I TURN. Rotation speed comes from the time left: the angle
 *   still to cover over the ticks before the feet pass the cell (the planning
 *   rule LiquidBounce's MLG uses), clamped between speed and max-speed. An
 *   easy catch turns gently; a late one turns as fast as allowed.
 *
 *   CLICK AFTER THE MOVE IS SENT. The placement goes out in the POST update,
 *   after the movement packet carrying this tick's rotation, and is checked by
 *   a ray from the position that packet reported. The server validates the
 *   click against exactly what was just computed -- LiquidBounce does the same
 *   on old versions ("post move").
 *
 * Every attempt is counted, and the suffix shows saves out of attempts, so
 * whether a change helped is a number rather than an impression.
 *
 * SECOND PASS, same day, from the first BedFight session with it (3/5 in a
 * practice match, then 1/2 in a real one with 6 of its 8 blocks refused):
 *
 *   THE MOVEMENT DID NOT MATCH THE LOOK. The rotation was only put on the
 *   packet: movement in the air was still worked out from the camera's yaw,
 *   while the server, which knows only the yaw it is sent, works it out from
 *   that. Every other module here that turns the player registers its yaw
 *   for movement as well (setPervRotation) and remaps the keys to the
 *   nearest matching direction; this one never did, so the global MoveFix
 *   had nothing to fix with. Both attempts that had blocks refused came
 *   with a triple LAGBACK of 2.3 blocks, "exceeds travel", Clutch active --
 *   the server discarding the movement of the whole catch.
 *
 *   IT KEPT CLICKING THROUGH A CORRECTION. After a setback the server holds
 *   the player where it put them until the client confirms, and the
 *   corrections still in flight arrive one after another; every placement
 *   sent in that window was judged from a position the client no longer
 *   had. Now a correction (the confirming C06 sent outside the tick) stops
 *   clicks for a round trip, a refused block (S23 air on a block this placed)
 *   stops them for one too, and three refusals end the clicking for that
 *   attempt: past that, each block is only another flag.
 *
 *   "NOTHING TO BUILD FROM" SAID NOTHING. The two losses in practice left
 *   only that line. Every attempt is now traced tick by tick to
 *   config/Myau/clutch-<stamp>.txt -- position, motion, catch levels, the
 *   plan or the reason there is none (out of reach and by how much, faces
 *   turned away, view blocked, a chain too slow for the time left), what
 *   stopped each click, corrections and refusals -- and chat gets the
 *   reason once it has lasted two ticks.
 *
 * THIRD PASS, ideas from Vape 4.21's clutch (read, not copied: it plans a
 * whole placement sequence once and verifies it in a full simulation; this
 * keeps re-planning every tick and takes only the three ideas that fit):
 *
 *   FIGHT THE PUSH. When the estimated drift is four blocks or more, Vape
 *   turns the player's movement back and holds forward. Here, after a hit
 *   that carries the player away from where they last stood, the keys are
 *   held toward that spot for the rest of the fall, remapped to the yaw the
 *   server is sent; the prediction includes the push, so the catch cells
 *   are where the steered player will actually be. When steering alone gets
 *   the player back onto something, no block is placed at all.
 *
 *   TURN BEFORE THE FACE IS THERE. Vape's simulation starts the rotation for
 *   a face that becomes clickable a few ticks later. Here, when nothing can
 *   be clicked from where the eyes are, the plan is also made from where
 *   they will be one to four ticks down the fall, and the turn starts now;
 *   with nothing at all, the look goes back to the block last stood on,
 *   where the supports are.
 *
 *   LET GO AFTER LANDING. Vape freezes movement for a few ticks after a fast
 *   clutch; here, three ticks after a steered save, or the held key walks
 *   the player off the one block that caught them.
 *
 * FOURTH PASS, same day, at the user's word (BedFight hands out ladders):
 *
 *   A LADDER WHEN NO BLOCK WILL DO. Vape's fallback, rebuilt for 1.8's own
 *   rules: a ladder only needs the body to enter its cell, at any speed, so
 *   it catches falls past a pillar or an island's side that a block could
 *   only catch by being under the feet in time. Then the player is pressed
 *   into it, climbs, and steps off onto the block it hangs on. See
 *   planLadder for why the click waits until the body is clear of the cell.
 *
 *   REAL BY DEFAULT, AND THE VIEW HANDED BACK. As Vape does: the camera is
 *   turned, which is what a server that watches rotations expects to see,
 *   and after the catch it is turned back to where it looked (reset-angle),
 *   unless the mouse takes it first. Meanwhile the keys keep meaning what
 *   they meant against that view.
 *
 *   ONE BLOCK A TICK. place-interval now defaults to 1, as Vape places; to be
 *   measured on Pika.
 */
public class Clutch extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    /** Ticks of fall simulated: three seconds covers well over a hundred blocks. */
    private static final int HORIZON = 60;
    /** A catch must happen within this many ticks of now to be planned for. */
    private static final int PLAN_TICKS = 16;
    private static final double FACE_INSET = 0.1;
    private static final int FACE_GRID = 4;
    /** Above every combat rotation (they use up to 6): this one keeps the player alive. */
    private static final int ROTATION_PRIORITY = 7;
    /** Refusals in one attempt after which it stops clicking: the server is not taking them. */
    private static final int MAX_REFUSALS = 3;
    /** Ticks a reason must last before it goes to chat; the file gets every tick. */
    private static final int PROBLEM_TICKS = 2;
    /** Lines kept for one attempt's trace: a three-second fall writes about two hundred. */
    private static final int TRACE_LIMIT = 800;
    /** How far ahead of the fall a face is looked for, to start turning before it can be clicked. */
    private static final int PRE_AIM_TICKS = 4;
    /** Ticks without movement after a steered save: long enough for the landing to stick. */
    private static final int SETTLE_TICKS = 3;
    /** Steering stops this close, horizontally, to where the player last stood. */
    private static final double STEER_STOP = 0.3;
    /** Beyond this, horizontally, the last standing place is no guide to anything. */
    private static final double ANCHOR_RANGE = 10.0;
    /** A hurt this recent is what the player is flying from: knockback, not a jump. */
    private static final long KNOCKBACK_MILLIS = 1000L;
    /** Ticks of the fall searched for a cell to hang a ladder in. */
    private static final int LADDER_TICKS = 12;
    /** Climbing is given up this long after the ladder went up: the player has control again. */
    private static final int CLIMB_TICKS = 60;
    private static final File LOG_DIR = new File("./config/Myau/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss.SSS");

    public final ModeProperty trigger = new ModeProperty("trigger", 2, new String[]{"AUTO", "HOLD", "BOTH"});
    public final KeyProperty holdKey = new KeyProperty("hold-key", Keyboard.KEY_NONE,
            () -> this.trigger.getValue() != 0);
    /* REAL turns the camera, as a player would; SILENT only the packets. */
    public final ModeProperty mode = new ModeProperty("mode", 1, new String[]{"SILENT", "REAL"});
    /** REAL: after the catch, turn the camera back to where it looked before. */
    public final BooleanProperty resetAngle = new BooleanProperty("reset-angle", true, () -> this.mode.getValue() == 1);
    /* SILENT: movement is worked out from the yaw the server is sent, with the
       keys remapped to the nearest direction that still goes where the player
       meant -- the server can only check movement against that yaw. */
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT"},
            () -> this.mode.getValue() == 0);

    public final FloatProperty reach = new FloatProperty("reach", 4.5F, 3.0F, 5.0F);
    /* Only blocks within this cone of where the player looked when the catch
       began. A catch that swings the view round behind the player is the
       loudest turn it makes; 360 is no limit. */
    public final IntProperty fov = new IntProperty("fov", 360, 30, 360);
    /* Only the sides of blocks: never the top of one below (which puts the
       block under the feet a level lower) or the bottom of one above. */
    public final BooleanProperty onlySideways = new BooleanProperty("only-sideways", false);
    /** Degrees a tick when there is time to spare. */
    public final IntProperty speed = new IntProperty("speed", 30, 5, 180);
    /** Degrees a tick when the catch is about to be missed. */
    public final IntProperty maxSpeed = new IntProperty("max-speed", 60, 10, 180);
    public final IntProperty snapback = new IntProperty("snapback-speed", 20, 1, 100);
    /* Ticks the view stays where the catch left it before turning back. A
       hand does not start back the instant the block is down. */
    public final IntProperty snapbackDelay = new IntProperty("snapback-delay", 0, 0, 20);
    /* Pressing jump while the view is being turned back finishes the turn at
       once, so the jump goes where the player is looking. */
    public final BooleanProperty snapOnJump = new BooleanProperty("snap-on-jump", false);
    /* Switch Clutch off once a catch is over and the view is back. */
    public final BooleanProperty disableAfter = new BooleanProperty("disable-after", false);
    public final IntProperty randomization = new IntProperty("rotation-random", 15, 0, 60);
    /* humanize (2026-10-04): the spot clicked on a face drawn from a normal
       distribution, a new one every block, instead of always the edge
       nearest the crosshair (scaled by rotation-random); and the turn handed
       to the shared engine's NOISE and CURVE (amounts in the Rotations
       module), the tick that lands on the target left exact. Off: exactly
       the turn of before. */
    public final BooleanProperty humanize = new BooleanProperty("humanize", true);
    /* The engine's EASE as well: slows the last stretch of a turn, which
       costs ticks a catch may not have. Off by default for that reason. */
    public final BooleanProperty ease = new BooleanProperty("ease", false, this.humanize::getValue);

    /** Without a hit, how far to fall toward the void before acting: a small hop is not a fall. */
    public final FloatProperty edgeFall = new FloatProperty("edge-fall", 1.0F, 0.0F, 10.0F);
    /* Only falls with nothing under them. Catching a drop onto ground costs
       blocks, turns and clicks the server can refuse, to save a few hearts:
       the user asked for it off (2026-09-25). */
    public final BooleanProperty voidOnly = new BooleanProperty("void-only", true);
    /** After a hit, drops longer than this are caught even where there is ground below. */
    public final FloatProperty safeDrop = new FloatProperty("safe-drop", 5.0F, 2.0F, 30.0F,
            () -> !this.voidOnly.getValue());
    /* Not unless at least this many blocks of air are under the feet. 0 is
       off. Over the void there is no bottom, so this only tells drops apart. */
    public final IntProperty minHeight = new IntProperty("min-height", 0, 0, 20);
    public final IntProperty combatWindow = new IntProperty("combat-window", 2500, 0, 8000);

    /* Ticks between placements. Vanilla holds right click to one per four
       ticks; a fast clicker manages one per two; Vape's clutch places one a
       tick, which halves the time a chain needs. 1 is being tried on Pika
       (2026-09-25): if placements start coming back refused, go to 2. */
    public final IntProperty placeInterval = new IntProperty("place-interval", 1, 0, 6);
    /* Early turn (2026-10-04). A click goes out before the movement packet
       that carries its look, so the look the server already has when the
       click arrives is last tick's; 57% of the 10-04 20:35 clicks were aimed
       somewhere that look did not reach, 105 of them after a turn of more
       than 90 degrees in that same tick (places-20261004-203527). A turn
       bigger than this, when the fall leaves a tick to spare, is made a tick
       early and clicked along next tick; a turn this size or smaller, or one
       with no tick to spare, is turned and clicked in the same tick as
       before, so the one-block-a-tick chain keeps its pace. 180 = off. */
    /* safe-mode switches both parts on (the click along the look already
       sent, and the early turn); off, the catch runs exactly as the 10-03
       07:40 jar: turn and click in the same tick. */
    public final BooleanProperty safeMode = new BooleanProperty("safe-mode", false);
    public final IntProperty earlyTurn = new IntProperty("early-turn", 45, 10, 180, this.safeMode::getValue);
    public final BooleanProperty chain = new BooleanProperty("chain", true);
    public final IntProperty chainLength = new IntProperty("chain-length", 3, 1, 6, this.chain::getValue);
    /* Never build above the layer last stood on: knocked upward, the highest
       crossed cell is above the bridge and the catch becomes a step up. Lower
       layers stay allowed, so a fall past the bridge is still caught. Vape
       catches at the height last stood on (fallTargetY). */
    public final BooleanProperty keepY = new BooleanProperty("keep-y", false);
    /* After a catch, if the landing would be on the edge of the block with the
       middle of the feet over air, place the block under the middle too --
       Vape's landing extension. One per catch. */
    public final BooleanProperty extraBlock = new BooleanProperty("extra-block", true);
    /* Hold the movement key toward where the player last stood while the hit
       carries them away, and let go for a moment after landing on the block. */
    public final BooleanProperty counterKnockback = new BooleanProperty("counter-knockback", true);
    /* Ticks after the hit before the push back starts. A server checking
       that knockback was taken looks at the first ticks after it, and no
       hand answers a hit in less than a few: pressing back from the tick of
       the hit is what set the 2026-09-25 Pika lagbacks off. */
    public final IntProperty counterDelay = new IntProperty("counter-delay", 6, 0, 15, this.counterKnockback::getValue);
    /* Turn toward a face that is not clickable yet but will be in a few ticks,
       and with nothing at all to aim at, toward where the player last stood. */
    public final BooleanProperty preAim = new BooleanProperty("pre-aim", true);
    /* With no block that can catch the fall in time, hang a ladder in a cell
       the body will pass through, then press into it and climb. */
    public final BooleanProperty autoLadder = new BooleanProperty("auto-ladder", true);

    public final BooleanProperty autoSwitch = new BooleanProperty("auto-switch", true);
    public final BooleanProperty switchBack = new BooleanProperty("switch-back", true);
    /* Switch only when the blocks in hand run out mid-catch; holding
       something else when the catch starts, use nothing. */
    public final BooleanProperty onDepletion = new BooleanProperty("only-on-depletion", false,
            this.autoSwitch::getValue);
    public final BooleanProperty swing = new BooleanProperty("swing", true);
    public final BooleanProperty pauseClicker = new BooleanProperty("pause-autoclicker", true);
    /** Chat as it happens, and every attempt traced tick by tick to config/Myau/clutch-<stamp>.txt. */
    public final ModeProperty log = new ModeProperty("log", 2, new String[]{"OFF", "FILE", "CHAT+FILE"});

    /** One way to put a block where it is wanted. */
    private static final class Plan {
        final BlockPos cell;
        final BlockPos support;
        final EnumFacing side;
        final float yaw;
        final float pitch;
        final double cost;
        /** Tick the feet reach the new block's top, or -1 for a chain step. */
        final int catchTick;
        /** A ladder hung in the cell rather than a block put there. */
        boolean ladder;

        Plan(BlockPos cell, BlockPos support, EnumFacing side, float yaw, float pitch, double cost, int catchTick) {
            this.cell = cell;
            this.support = support;
            this.side = side;
            this.yaw = yaw;
            this.pitch = pitch;
            this.cost = cost;
            this.catchTick = catchTick;
        }
    }

    /* The fall as simulated this tick: path.get(0) is where the player will be
       after this tick's movement, which is also where the server will think
       they are when the POST update clicks. */
    private final List<AxisAlignedBB> path = new ArrayList<AxisAlignedBB>();
    private AxisAlignedBB pathStart;
    private int landingTick;
    private double predictedFall;

    private boolean active;
    private Plan plan;

    /** The rotation the server has been, or is about to be, given. */
    private float sentYaw;
    private float sentPitch;
    private boolean hasSent;
    private boolean resetting;

    private int cooldown;
    private int lastHurtTime;
    private long lastHurtAt;
    private int lastHurtTick = -1000;
    /* The turn so far -- last tick's step, for the rise to this one, and a
       speed factor that drifts rather than being drawn fresh each tick --
       kept by the engine shared with the other modules that turn. */
    private final RotationEngine engine = new RotationEngine();
    /** Ticks still to wait before turning back (snapback-delay). */
    private int backWait;
    /** The attempt began with blocks in hand (only-on-depletion). */
    private boolean startedWithBlocks;
    private int previousSlot = -1;
    private boolean slotSwapped;
    private boolean clickerPaused;

    /* One attempt: from the moment a catch is wanted until the player is on
       the ground again, dead, or somewhere else. */
    private boolean attempt;
    private int attemptPlaced;
    private int attemptRefused;
    private int attemptTick;
    private double attemptStartY;
    /** The latest reason nothing could be done, for the line that closes the attempt. */
    private String attemptProblem;
    /* The reason as it runs: chat hears of it once it has lasted PROBLEM_TICKS. */
    private String problemKey;
    private int problemRun;
    private String saidProblem;
    private final Set<BlockPos> placed = new HashSet<BlockPos>();
    /** extra-block: the cell under the middle of the landing this tick plans for, and whether this catch had it. */
    private BlockPos extraTarget;
    private boolean extraDone;
    /** Last tick's position, to notice being moved by the server rather than falling. */
    private Vec3 lastPosition;
    /** Where the player last stood: what a catch steers back toward and looks back at. */
    private Vec3 lastGround;
    /** This tick: whether movement is steered, and toward which world yaw. */
    private boolean steering;
    private float steerYaw;
    private boolean attemptSteered;
    /** Ticks left with no movement input, after a steered save. */
    private int settleTicks;
    /** How many ticks ahead the plan was made from: 0 is from where the player is now. */
    private int planLead;
    /** Where the steering heads for: the last standing place, or the ladder. */
    private double steerX;
    private double steerZ;

    /* The ladder this attempt hung, if any, and the climb out of it. */
    private BlockPos ladderCell;
    private EnumFacing ladderSide;
    private int ladderTick;
    private boolean rodeLadder;

    /* REAL: where the camera looked before the catch took it. */
    private float viewYaw;
    private float viewPitch;
    private int saves;
    private int attempts;
    /* The last save, until the server has had time to refuse what it stood on. */
    private final Set<BlockPos> savedOn = new HashSet<BlockPos>();
    private int savedAt = -1000;
    /* This player's own swings: a sprinting swing slows the client by 0.4
       whether or not the server counts the hit, and the server slows its
       copy only when it does. */
    private int lastAttackTick = -1000;
    /** The tick of the last correction from the server. */
    private int lastCorrectedTick = -1000;
    private boolean lastAttackSprinting;

    /* Server corrections. The update runs inside EntityPlayerSP.onUpdate; a
       C06 sent outside it is the client confirming a teleport, which is the
       exact moment the server's position and rotation for the player change. */
    private boolean inUpdate;
    private int tick;
    /** Ticks left with no clicks, after a correction or a refusal. */
    private int pauseTicks;
    /** Recently placed blocks and the tick each went down, for matching refusals. */
    private final Map<BlockPos, Integer> placedAt = new ConcurrentHashMap<BlockPos, Integer>();
    /** Placed blocks the server set back to air, handed over from the network thread. */
    private final ConcurrentLinkedQueue<BlockPos> refused = new ConcurrentLinkedQueue<BlockPos>();
    /**
     * Support faces whose click onPlayerRightClick answered false, and the
     * tick each may be tried again (2026-10-02). The plan is rebuilt every
     * tick and a failed face is usually still the cheapest, so without this
     * the same refused click repeated for the rest of the fall.
     */
    private final Map<String, Integer> failedFaces = new HashMap<String, Integer>();
    private static final int FAILED_FACE_TICKS = 10;
    /** A click was sent this tick (in PRE): no turn until the next tick. */
    private boolean holdThisTick;
    /* The cell an early turn was aimed at last tick: this tick's click along
       that look may place it even if this tick's plan moved on. */
    private BlockPos aimedCell;
    private int aimedTick;
    /* Set while the click along the held look is tried: its "why not" notes
       would repeat every tick, and the click after the turn writes its own. */
    private boolean quietNotes;
    /* Yaw steps, for Grim DuplicateRotPlace (its memory is server-side, so
       these are kept across enables): the last non-zero one sent, and the one
       that was last when a block was placed. */
    private float lastTurn;
    private float placedTurn = -1.0F;
    /* humanize: the preferred spot on the next face (face coordinates 0-1). */
    private final Random noise = new Random();
    private double aimU = 0.5;
    private double aimV = 0.5;

    /* Why nothing could be aimed at this tick, counted over every face tried. */
    private int seenSupports;
    private int facesAway;
    private int facesFar;
    private int facesBlocked;
    private double nearestFace;
    private int chainNeed;
    private int chainLeft;

    private final List<String> trace = new ArrayList<String>();
    private File logTarget;
    private String lastNote;

    public Clutch() {
        super("Clutch", false, false, "Catches a fall with a block: knocked off a bridge, or off an edge over the void");
    }

    @Override
    public void onEnabled() {
        this.sentYaw = mc.thePlayer != null ? mc.thePlayer.rotationYaw : 0.0F;
        this.sentPitch = mc.thePlayer != null ? mc.thePlayer.rotationPitch : 0.0F;
        this.hasSent = false;
        this.resetting = false;
        this.active = false;
        this.plan = null;
        this.cooldown = 0;
        this.attempt = false;
        this.inUpdate = false;
        this.pauseTicks = 0;
        this.placedAt.clear();
        this.refused.clear();
        this.failedFaces.clear();
        this.logTarget = null;
    }

    @Override
    public void onDisabled() {
        Arbiter.setCatching(getName(), false);
        Arbiter.setViewHeld(getName(), false);
        stopPlacing();
        this.active = false;
        this.plan = null;
        this.steering = false;
        this.settleTicks = 0;
        this.ladderCell = null;
        abandonAttempt("module turned off");
    }

    @EventTarget(whenDisabled = true)
    public void onLoadWorld(LoadWorldEvent event) {
        /* A new world is a new fall, and the old attempt's blocks are gone. */
        abandonAttempt("world changed");
        this.active = false;
        this.plan = null;
        this.placed.clear();
        this.placedAt.clear();
        this.refused.clear();
        this.failedFaces.clear();
        this.pauseTicks = 0;
        this.hasSent = false;
        this.resetting = false;
        this.lastGround = null;
        this.steering = false;
        this.settleTicks = 0;
        this.ladderCell = null;
    }

    // --------------------------------------------------------------- logging

    /** Chat, when the log setting says so, and always the attempt's trace. */
    private void say(String message) {
        trace(message.replaceAll("&[0-9a-fklmnor]", ""));
        if (this.log.getValue() == 2) {
            ChatUtil.sendFormatted("&7[&bClutch&7] " + message);
        }
    }

    /**
     * One line of the attempt's trace, kept until the attempt ends and then
     * written in one go -- a file opened every tick of a fall would be the
     * slowest thing this module does.
     */
    private void trace(String line) {
        if (this.log.getValue() == 0) {
            return;
        }
        if (this.trace.size() < TRACE_LIMIT) {
            this.trace.add(LINE_STAMP.format(new Date())
                    + (this.attempt ? String.format(" t%-3d ", this.attemptTick) : "      ") + line);
        }
        if (!this.attempt) {
            flushTrace();
        }
    }

    /** A trace line for why the click did not happen, once per change. */
    private void note(String line) {
        if (this.quietNotes) {
            return;
        }
        if (!line.equals(this.lastNote)) {
            this.lastNote = line;
            trace("  post: " + line);
        }
    }

    private void flushTrace() {
        if (this.trace.isEmpty()) {
            return;
        }
        if (this.logTarget == null) {
            this.logTarget = new File(LOG_DIR, "clutch-" + FILE_STAMP.format(new Date()) + ".txt");
        }
        /* Written on AsyncLog's thread: a file opened mid-tick is a stall. */
        List<String> lines = new ArrayList<String>(this.trace);
        lines.add("");
        AsyncLog.append(this.logTarget, lines);
        this.trace.clear();
    }

    /** Ends an attempt that neither landed nor was lost, so its trace still gets written. */
    private void abandonAttempt(String why) {
        if (this.attempt) {
            this.attempt = false;
            trace("ended unresolved: " + why);
        }
        Arbiter.setCatching(getName(), false);
    }

    private static String cell(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private String state() {
        return String.format("pos %.2f %.2f %.2f v %+.2f %+.2f %+.2f fall %.1f",
                mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ,
                mc.thePlayer.motionX, mc.thePlayer.motionY, mc.thePlayer.motionZ, mc.thePlayer.fallDistance)
                + held();
    }

    /**
     * What else was going on this tick that the server's view of the player
     * depends on: a swing of this player's own, and outgoing packets some
     * module is holding back. Empty when nothing was.
     */
    private String held() {
        StringBuilder text = new StringBuilder();
        if (this.lastAttackTick == this.tick) {
            text.append(this.lastAttackSprinting ? " | swung (sprinting)" : " | swung");
        }
        int blinked = Myau.blinkManager == null ? 0 : Myau.blinkManager.blinkedPackets.size();
        if (blinked > 0) {
            text.append(" | blink ").append(Myau.blinkManager.getBlinkingModule()).append(' ').append(blinked).append('p');
        }
        Module fakeLag = Myau.moduleManager.modules.get(FakeLag.class);
        if (fakeLag instanceof FakeLag && ((FakeLag) fakeLag).heldCount() > 0) {
            text.append(" | fakelag ").append(((FakeLag) fakeLag).heldCount()).append('p');
        }
        return text.toString();
    }

    /**
     * While a catch is placing, the player's own right click (vanilla's, in
     * the same tick, before Clutch's) would be a second placement between two
     * movement packets: Grim MultiPlace, and AirLiquidPlace against a block
     * the server has not applied yet (22:08:51 and 22:09:19, right click held
     * with FastPlace while towering). active is last tick's decision, which is
     * exactly the tick whose plan clicks next.
     */
    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (this.active) {
            event.setCancelled(true);
            return;
        }
        if (this.hasSent) {
            /* Handing the view back after a catch, and the player clicks: the
               player has taken the view, as when the mouse moves. Their click
               uses the look at the start of this tick, and the hand-back would
               have sent the next step of its turn in the same tick's packet --
               every such block flagged (Grim RotationPlace x6 + DuplicateRotPlace,
               2026-10-02 22:54:29, FastPlace towering right after a catch, the
               snapback turning ~12 degrees a tick). Not sending a rotation this
               tick leaves the camera's, which is the look the click used. */
            this.resetting = false;
            this.hasSent = false;
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        /* The tick counter moves in PRE, and swings are made before it. */
        this.lastAttackTick = this.tick + 1;
        this.lastAttackSprinting = mc.thePlayer.isSprinting();
    }

    private int ping() {
        return Ping.own();
    }

    /** A round trip in ticks: how long anything the server says takes to be answered. */
    private int roundTripTicks() {
        int ping = ping();
        return ping <= 0 ? 4 : MathHelper.clamp_int((ping + 49) / 50, 2, 8);
    }

    // ----------------------------------------------------------- corrections

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (event.getType() == EventType.SEND) {
            if (packet instanceof C03PacketPlayer.C06PacketPlayerPosLook && !this.inUpdate) {
                corrected((C03PacketPlayer.C06PacketPlayerPosLook) packet);
            }
            return;
        }
        /* Network thread: only hand the position over. */
        if (packet instanceof S23PacketBlockChange && !this.placedAt.isEmpty()) {
            S23PacketBlockChange change = (S23PacketBlockChange) packet;
            BlockPos pos = change.getBlockPosition();
            if (pos != null && change.getBlockState() != null
                    && change.getBlockState().getBlock() == Blocks.air && this.placedAt.containsKey(pos)) {
                this.refused.add(pos);
            }
        }
    }

    /**
     * The server moved the player and the client has just confirmed it. The
     * confirmation carries the camera's rotation, so that is now the rotation
     * the server has; and until the corrections still in flight have landed,
     * a click is judged from a position the client does not have.
     */
    private void corrected(C03PacketPlayer.C06PacketPlayerPosLook packet) {
        this.lastCorrectedTick = this.tick;
        this.sentYaw = packet.getYaw();
        this.sentPitch = packet.getPitch();
        int ticks = roundTripTicks() + 1;
        this.pauseTicks = Math.max(this.pauseTicks, ticks);
        if (this.attempt) {
            say(String.format("&ecorrected by the server &8(no clicks for %dt) &7%.2f %.2f %.2f",
                    ticks, packet.getPositionX(), packet.getPositionY(), packet.getPositionZ()));
        }
    }

    /** Blocks the server took back: nothing placed against them will stand either. */
    private void drainRefusals() {
        BlockPos pos;
        while ((pos = this.refused.poll()) != null) {
            Integer at = this.placedAt.remove(pos);
            if (at == null) {
                continue;
            }
            this.placed.remove(pos);
            if (pos.equals(this.ladderCell)) {
                this.ladderCell = null;
            }
            if (this.savedOn.contains(pos) && this.tick - this.savedAt <= 40) {
                /* Refused after the landing: it was on a block the server
                   never had, and it will move the player off it. */
                this.savedOn.clear();
                this.saves--;
                say(String.format("&cthat save did not stand &8(the server refused %s, %dt after the landing)",
                        cell(pos), this.tick - this.savedAt));
            }
            if (this.attempt) {
                this.attemptRefused++;
                this.pauseTicks = Math.max(this.pauseTicks, roundTripTicks());
                say(String.format("&crefused &7%s &8(placed %dt ago%s)", cell(pos), this.tick - at,
                        this.attemptRefused >= MAX_REFUSALS ? ", clicking stopped for this fall" : ""));
            }
        }
        if (!this.placedAt.isEmpty()) {
            Iterator<Map.Entry<BlockPos, Integer>> it = this.placedAt.entrySet().iterator();
            while (it.hasNext()) {
                if (this.tick - it.next().getValue() > 100) {
                    it.remove();
                }
            }
        }
    }

    /**
     * Last of all the input handlers, so the global MoveFix, which remaps the
     * keys the player is holding, cannot undo the steering. The steered keys
     * are chosen against the yaw movement is worked out from -- the one the
     * server is sent -- so what the server re-simulates is a key press it
     * could have seen.
     */
    @EventTarget(Priority.LOWEST)
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        if (this.steering) {
            press(this.steerYaw);
            return;
        }
        if (this.settleTicks > 0) {
            mc.thePlayer.movementInput.moveForward = 0.0F;
            mc.thePlayer.movementInput.moveStrafe = 0.0F;
            return;
        }
        if (!MoveUtil.isForwardPressed() || !RotationState.isActived()
                || RotationState.getPriority() != ROTATION_PRIORITY) {
            return;
        }
        if (this.mode.getValue() == 1 && this.hasSent) {
            /* REAL: the camera was turned by the catch, not by the player, so
               the keys still mean what they meant before it turned. */
            press(MoveUtil.adjustYaw(this.viewYaw, MoveUtil.getForwardValue(), MoveUtil.getLeftValue()));
        } else if (this.mode.getValue() == 0 && this.moveFix.getValue() == 1) {
            MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
        }
    }

    /**
     * Keys that move the player toward this world yaw, against the yaw
     * movement is worked out from: the nearest of the eight directions keys
     * can make (sin 22.5 degrees), at the strength vanilla gives keys -- less
     * when sneaking.
     */
    private static void press(float worldYaw) {
        float moveYaw = RotationState.isActived() ? RotationState.getSmoothedYaw() : mc.thePlayer.rotationYaw;
        float relative = MathHelper.wrapAngleTo180_float(worldYaw - moveYaw) * 0.017453292F;
        float forward = MathHelper.cos(relative);
        float strafe = -MathHelper.sin(relative);
        float strength = mc.thePlayer.movementInput.sneak ? 0.3F : 1.0F;
        mc.thePlayer.movementInput.moveForward = forward > 0.38F ? strength : forward < -0.38F ? -strength : 0.0F;
        mc.thePlayer.movementInput.moveStrafe = strafe > 0.38F ? strength : strafe < -0.38F ? -strength : 0.0F;
    }

    /** Steers toward a point, horizontally; false when already there. */
    private boolean steerTo(double x, double z) {
        double dx = x - mc.thePlayer.posX;
        double dz = z - mc.thePlayer.posZ;
        if (dx * dx + dz * dz < STEER_STOP * STEER_STOP) {
            return false;
        }
        this.steerX = x;
        this.steerZ = z;
        this.steerYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        return true;
    }

    /**
     * Into the ladder once it is up: toward a point just inside the plate, so
     * the body is pushed against it. On a 1.8 ladder the fall is held to 0.15
     * a tick and the fall distance cleared, and pressing into it -- colliding
     * sideways -- is what climbs (0.2 a tick up); at the top the body clears
     * the block the ladder hangs on and steps onto it. Without the press the
     * player slides out of the bottom of a one-block ladder in seven ticks.
     * Given up when the player lands, falls well below it, drifts off, holds
     * keys away from it, or after CLIMB_TICKS.
     */
    private boolean climbing() {
        if (this.ladderCell == null || !this.attempt || mc.thePlayer.onGround) {
            return false;
        }
        double faceX = this.ladderCell.getX() + 0.5 - this.ladderSide.getFrontOffsetX() * 0.8;
        double faceZ = this.ladderCell.getZ() + 0.5 - this.ladderSide.getFrontOffsetZ() * 0.8;
        double dx = faceX - mc.thePlayer.posX;
        double dz = faceZ - mc.thePlayer.posZ;
        if (this.tick - this.ladderTick > CLIMB_TICKS || dx * dx + dz * dz > 2.5 * 2.5
                || mc.thePlayer.posY < this.ladderCell.getY() - 1.5) {
            /* Missed it, knocked off it, or stuck on it: this ladder is done
               with, and the fall may need another. */
            this.ladderCell = null;
            this.rodeLadder = false;
            return false;
        }
        if (this.rodeLadder && keysAway(dx, dz)) {
            return false;
        }
        return steerTo(faceX, faceZ);
    }

    /**
     * Whether to steer this tick, and where: back toward where the player
     * last stood, while they are away from it. A hit sends the player off at
     * several tenths of a block a tick and the air gives back two or three
     * hundredths; held for the ten ticks a knockback arc lasts, that is most
     * of a block less to cover, which is often the whole difference between
     * a support within reach and none.
     */
    private boolean decideSteer() {
        if (!this.counterKnockback.getValue() || this.lastGround == null) {
            return false;
        }
        double dx = this.lastGround.xCoord - mc.thePlayer.posX;
        double dz = this.lastGround.zCoord - mc.thePlayer.posZ;
        if (dx * dx + dz * dz > ANCHOR_RANGE * ANCHOR_RANGE) {
            return false;
        }
        /* Started only against a hit's push. Without one the player chose to
           be going this way -- a jump across a gap reads as a fall into the
           void until the far side is reached -- and so they did with the keys
           held away from where they stood. Once started, it holds for the fall. */
        if (!this.attemptSteered) {
            boolean hitRecently = System.currentTimeMillis() - this.lastHurtAt < KNOCKBACK_MILLIS;
            boolean carriedAway = mc.thePlayer.motionX * dx + mc.thePlayer.motionZ * dz < 0.0;
            if (!hitRecently || !carriedAway || keysAway(dx, dz)) {
                return false;
            }
            if (this.tick - this.lastHurtTick < this.counterDelay.getValue()) {
                /* Not yet: the knockback is taken whole first. */
                return false;
            }
        }
        return steerTo(this.lastGround.xCoord, this.lastGround.zCoord);
    }

    /**
     * Whether the keys held point away from this direction -- turned by the
     * view the player chose, which in REAL is not the camera the catch turned.
     */
    private boolean keysAway(double dx, double dz) {
        if (!MoveUtil.isForwardPressed()) {
            return false;
        }
        float view = this.mode.getValue() == 1 && this.hasSent ? this.viewYaw : mc.thePlayer.rotationYaw;
        float yaw = MoveUtil.adjustYaw(view, MoveUtil.getForwardValue(), MoveUtil.getLeftValue()) * 0.017453292F;
        return -MathHelper.sin(yaw) * dx + MathHelper.cos(yaw) * dz < 0.0;
    }

    // ------------------------------------------------------------ prediction

    /**
     * Simulates the fall from the current position and motion, the way the
     * game moves a player in the air: the air's push from the keys, then move
     * by the motion against the collision boxes axis by axis, then gravity and
     * drag. The player's own keys are left out -- two hundredths of a block a
     * tick -- but steering is not: it is held for the whole fall, so leaving
     * it out would put every catch cell a block too far out.
     */
    private void predict(boolean steer) {
        this.path.clear();
        this.landingTick = -1;
        AxisAlignedBB box = mc.thePlayer.getEntityBoundingBox();
        this.pathStart = box;
        double mx = mc.thePlayer.motionX;
        double my = mc.thePlayer.motionY;
        double mz = mc.thePlayer.motionZ;
        double apex = box.minY;
        /* The air's push for a key: speedInAir, three tenths more sprinting. */
        double push = steer ? (mc.thePlayer.isSprinting() ? 0.026 : 0.02) : 0.0;
        for (int t = 0; t < HORIZON; t++) {
            if (push > 0.0) {
                double dx = this.steerX - (box.minX + box.maxX) / 2.0;
                double dz = this.steerZ - (box.minZ + box.maxZ) / 2.0;
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance > STEER_STOP) {
                    mx += dx / distance * push;
                    mz += dz / distance * push;
                }
            }
            double ox = mx;
            double oy = my;
            double oz = mz;
            List<AxisAlignedBB> hits = mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, box.addCoord(mx, my, mz));
            for (AxisAlignedBB hit : hits) {
                my = hit.calculateYOffset(box, my);
            }
            box = box.offset(0.0, my, 0.0);
            for (AxisAlignedBB hit : hits) {
                mx = hit.calculateXOffset(box, mx);
            }
            box = box.offset(mx, 0.0, 0.0);
            for (AxisAlignedBB hit : hits) {
                mz = hit.calculateZOffset(box, mz);
            }
            box = box.offset(0.0, 0.0, mz);
            this.path.add(box);
            apex = Math.max(apex, box.minY);
            if (oy != my && oy < 0.0) {
                this.landingTick = t;
                break;
            }
            if (box.minY < -64.0) {
                break;
            }
            mx = ox != mx ? 0.0 : mx * 0.91;
            mz = oz != mz ? 0.0 : mz * 0.91;
            my = oy != my ? 0.0 : (my - 0.08) * 0.98;
        }
        double already = mc.thePlayer.motionY < 0.0 ? mc.thePlayer.fallDistance : 0.0;
        AxisAlignedBB end = this.path.isEmpty() ? box : this.path.get(this.path.size() - 1);
        this.predictedFall = already + (apex - end.minY);
    }

    private boolean intoVoid() {
        return this.landingTick < 0;
    }

    /**
     * The tick at which the feet reach the top of this cell while over it, or
     * -1 if the fall passes it by. Checked against both ends of each step, so
     * a fast fall cannot skip a column between two samples.
     */
    private static int catchTick(List<AxisAlignedBB> path, AxisAlignedBB start, BlockPos cell, int limit) {
        double top = cell.getY() + 1.0;
        AxisAlignedBB previous = start;
        for (int t = 0; t < Math.min(limit, path.size()); t++) {
            AxisAlignedBB current = path.get(t);
            if (previous.minY >= top - 1.0E-3 && current.minY <= top) {
                return overColumn(previous, cell) || overColumn(current, cell) ? t : -1;
            }
            previous = current;
        }
        return -1;
    }

    private static boolean overColumn(AxisAlignedBB box, BlockPos cell) {
        return box.maxX > cell.getX() && box.minX < cell.getX() + 1
                && box.maxZ > cell.getZ() && box.minZ < cell.getZ() + 1;
    }

    private static boolean intersects(AxisAlignedBB box, BlockPos cell) {
        return box.intersectsWith(new AxisAlignedBB(cell, cell.add(1, 1, 1)));
    }

    // --------------------------------------------------------------- trigger

    private boolean holdActive() {
        return this.holdKey.getValue() != Keyboard.KEY_NONE && this.holdKey.isDown();
    }

    /**
     * Whether this fall should be caught. The void always; after a hit, any
     * drop worse than safe-drop too, even while still rising from the hit --
     * that rise is the time a catch needs. Without a hit, a big drop onto
     * ground is taken to be deliberate.
     */
    private boolean wanted() {
        if (mc.thePlayer.onGround || mc.thePlayer.capabilities.isFlying || mc.thePlayer.isInWater()
                || mc.thePlayer.isInLava() || mc.thePlayer.isOnLadder() || mc.thePlayer.isRiding()) {
            return false;
        }
        int mode = this.trigger.getValue();
        if (mode == 1) {
            return holdActive();
        }
        boolean hit = System.currentTimeMillis() - this.lastHurtAt < this.combatWindow.getValue();
        /* Without a hit, not while right click is held: that is someone
           bridging by hand, and taking the rotation from under them would get
           their own placements refused. */
        boolean edge = mc.thePlayer.fallDistance >= this.edgeFall.getValue()
                && !mc.gameSettings.keyBindUseItem.isKeyDown();
        boolean auto = intoVoid()
                ? hit || edge
                : !this.voidOnly.getValue() && hit && this.predictedFall > this.safeDrop.getValue();
        if (auto && this.minHeight.getValue() > 0 && airBelow(this.minHeight.getValue()) < this.minHeight.getValue()) {
            auto = false;
        }
        return mode == 0 ? auto : auto || holdActive();
    }

    // ------------------------------------------------------------- inventory

    /**
     * A block that will hold a player: solid, not something that falls, not
     * something that opens. The held stack if it qualifies, so a player
     * already holding blocks is not switched off them; otherwise the biggest.
     */
    private int pickBlockSlot() {
        int current = mc.thePlayer.inventory.currentItem;
        if (usable(mc.thePlayer.inventory.getStackInSlot(current))) {
            return current;
        }
        int best = -1;
        int bestCount = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (usable(stack) && stack.stackSize > bestCount) {
                best = slot;
                bestCount = stack.stackSize;
            }
        }
        return best;
    }

    private static boolean usable(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || !(stack.getItem() instanceof ItemBlock)) {
            return false;
        }
        Block block = ((ItemBlock) stack.getItem()).getBlock();
        /* A full cube by its bounds rather than isFullCube(), which is false
           for glass -- the commonest bridging block there is. */
        boolean full = block.getBlockBoundsMinX() <= 0.0 && block.getBlockBoundsMaxX() >= 1.0
                && block.getBlockBoundsMinY() <= 0.0 && block.getBlockBoundsMaxY() >= 1.0
                && block.getBlockBoundsMinZ() <= 0.0 && block.getBlockBoundsMaxZ() >= 1.0;
        return full && block.getMaterial().isSolid() && BlockUtil.isSolid(block)
                && !BlockUtil.isInteractable(block)
                && !(block instanceof BlockFalling) && !(block instanceof BlockTNT);
    }

    private static boolean isLadder(ItemStack stack) {
        return stack != null && stack.stackSize > 0 && stack.getItem() == Item.getItemFromBlock(Blocks.ladder);
    }

    private int pickLadderSlot() {
        int current = mc.thePlayer.inventory.currentItem;
        if (isLadder(mc.thePlayer.inventory.getStackInSlot(current))) {
            return current;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (isLadder(mc.thePlayer.inventory.getStackInSlot(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private void selectSlot(int slot) {
        if (slot < 0 || mc.thePlayer.inventory.currentItem == slot || !this.autoSwitch.getValue()) {
            return;
        }
        if (this.previousSlot < 0 && this.switchBack.getValue()) {
            this.previousSlot = mc.thePlayer.inventory.currentItem;
        }
        mc.thePlayer.inventory.currentItem = slot;
        this.slotSwapped = true;
    }

    private void startPlacing() {
        if (this.pauseClicker.getValue() && !this.clickerPaused) {
            Module clicker = Myau.moduleManager.getModule(AutoClicker.class);
            if (clicker != null && clicker.isEnabled()) {
                clicker.setEnabled(false);
                this.clickerPaused = true;
            }
        }
    }

    private void stopPlacing() {
        if (this.slotSwapped && this.previousSlot >= 0 && this.previousSlot < 9 && mc.thePlayer != null) {
            mc.thePlayer.inventory.currentItem = this.previousSlot;
        }
        this.slotSwapped = false;
        this.previousSlot = -1;
        if (this.clickerPaused) {
            Module clicker = Myau.moduleManager.getModule(AutoClicker.class);
            if (clicker != null) {
                clicker.setEnabled(true);
            }
            this.clickerPaused = false;
        }
    }

    // ------------------------------------------------------------- planning

    private Vec3 eyesAt(AxisAlignedBB box) {
        return new Vec3((box.minX + box.maxX) / 2.0, box.minY + mc.thePlayer.getEyeHeight(), (box.minZ + box.maxZ) / 2.0);
    }

    /** The vanilla look vector, with the game's own sine table, so the ray is the server's ray. */
    private static Vec3 look(float yaw, float pitch) {
        float cosYaw = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
        float sinYaw = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
        float cosPitch = -MathHelper.cos(-pitch * 0.017453292F);
        float sinPitch = MathHelper.sin(-pitch * 0.017453292F);
        return new Vec3(sinYaw * cosPitch, sinPitch, cosYaw * cosPitch);
    }

    private MovingObjectPosition ray(Vec3 eyes, float yaw, float pitch) {
        Vec3 dir = look(yaw, pitch);
        double r = this.reach.getValue();
        return mc.theWorld.rayTraceBlocks(eyes, eyes.addVector(dir.xCoord * r, dir.yCoord * r, dir.zCoord * r),
                false, false, false);
    }

    private double rotationCost(float yaw, float pitch) {
        return Math.abs(MathHelper.wrapAngleTo180_float(yaw - this.sentYaw)) + Math.abs(pitch - this.sentPitch);
    }

    private static boolean clickable(BlockPos pos) {
        return !BlockUtil.isReplaceable(pos) && !BlockUtil.isInteractable(pos);
    }

    /**
     * The cheapest look, from these eyes, that puts a block into this cell by
     * clicking one of its solid neighbours -- including the underside of the
     * block above it. The ray is traced for every candidate, so a face hidden
     * behind another block is never chosen.
     */
    private Plan aim(BlockPos cell, Vec3 eyes, int catchTick) {
        Plan best = null;
        for (EnumFacing toSupport : EnumFacing.values()) {
            if (this.onlySideways.getValue() && toSupport.getAxis() == EnumFacing.Axis.Y) {
                continue;
            }
            BlockPos support = cell.offset(toSupport);
            if (!clickable(support) || faceFailed(support, toSupport.getOpposite())) {
                continue;
            }
            this.seenSupports++;
            Plan candidate = aimFace(cell, support, toSupport.getOpposite(), eyes, catchTick,
                    best == null ? Double.MAX_VALUE : best.cost);
            if (candidate != null && inFov(candidate)) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * The cheapest look, from these eyes, that hits this one face of the
     * support, if it is cheaper than the bound: sampled over the face, each
     * sample ray-traced. Counts why not, for the diagnosis.
     */
    private Plan aimFace(BlockPos cell, BlockPos support, EnumFacing side, Vec3 eyes, int catchTick, double bound) {
        Plan best = null;
        /* Chosen by turn plus, with humanize, a few degrees for each unit of
           face away from this block's drawn spot; the plan keeps the bare turn
           as its cost, which is what the timing is judged by. */
        double bestScore = Double.MAX_VALUE;
        double spread = spreadWeight();
        double reachSq = this.reach.getValue() * this.reach.getValue();
        {
            /* The face must face the eyes, or no ray reaches it. */
            double nx = side.getFrontOffsetX();
            double ny = side.getFrontOffsetY();
            double nz = side.getFrontOffsetZ();
            double planeX = nx > 0 ? support.getX() + 1 : nx < 0 ? support.getX() : Double.NaN;
            double planeY = ny > 0 ? support.getY() + 1 : ny < 0 ? support.getY() : Double.NaN;
            double planeZ = nz > 0 ? support.getZ() + 1 : nz < 0 ? support.getZ() : Double.NaN;
            if ((!Double.isNaN(planeX) && (eyes.xCoord - planeX) * nx <= 0.0)
                    || (!Double.isNaN(planeY) && (eyes.yCoord - planeY) * ny <= 0.0)
                    || (!Double.isNaN(planeZ) && (eyes.zCoord - planeZ) * nz <= 0.0)) {
                this.facesAway++;
                return null;
            }
            boolean inReach = false;
            double closest = Double.MAX_VALUE;
            boolean traced = false;
            boolean seen = false;
            for (int i = 0; i <= FACE_GRID; i++) {
                double u = FACE_INSET + (1.0 - 2.0 * FACE_INSET) * i / FACE_GRID;
                for (int j = 0; j <= FACE_GRID; j++) {
                    double v = FACE_INSET + (1.0 - 2.0 * FACE_INSET) * j / FACE_GRID;
                    double px = !Double.isNaN(planeX) ? planeX : support.getX() + u;
                    double py = !Double.isNaN(planeY) ? planeY : support.getY() + (Double.isNaN(planeX) ? v : u);
                    double pz = !Double.isNaN(planeZ) ? planeZ : support.getZ() + v;
                    double dx = px - eyes.xCoord;
                    double dy = py - eyes.yCoord;
                    double dz = pz - eyes.zCoord;
                    double distanceSq = dx * dx + dy * dy + dz * dz;
                    if (distanceSq > reachSq) {
                        closest = Math.min(closest, distanceSq);
                        continue;
                    }
                    inReach = true;
                    float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
                    float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                    yaw = this.sentYaw + MathHelper.wrapAngleTo180_float(yaw - this.sentYaw);
                    double cost = rotationCost(yaw, pitch);
                    if (cost >= bound) {
                        continue;
                    }
                    double score = cost + spread * Math.hypot(u - this.aimU, v - this.aimV);
                    if (score >= bestScore) {
                        continue;
                    }
                    traced = true;
                    MovingObjectPosition hit = ray(eyes, yaw, pitch);
                    if (hit == null || hit.typeOfHit != MovingObjectType.BLOCK
                            || !support.equals(hit.getBlockPos()) || hit.sideHit != side) {
                        continue;
                    }
                    seen = true;
                    bestScore = score;
                    best = new Plan(cell, support, side, yaw, pitch, cost, catchTick);
                }
            }
            if (!inReach) {
                this.facesFar++;
                this.nearestFace = Math.min(this.nearestFace, Math.sqrt(closest));
            } else if (traced && !seen) {
                this.facesBlocked++;
            }
        }
        return best;
    }

    /** The cell a 1.8 player is "on a ladder" in: the column of the centre, the layer of the feet. */
    private static BlockPos ladderCellOf(AxisAlignedBB box) {
        return new BlockPos(MathHelper.floor_double((box.minX + box.maxX) / 2.0), MathHelper.floor_double(box.minY),
                MathHelper.floor_double((box.minZ + box.maxZ) / 2.0));
    }

    /**
     * A ladder for a fall no block can catch in time. 1.8 decides "on a
     * ladder" from the cell holding the centre's column and the feet's layer,
     * at the start of each tick's move -- so a ladder in the cell the player
     * is in after tick t holds them from tick t+1, whatever the speed: no top
     * to cross, as a block needs, only a cell to enter. It needs a normal
     * cube behind it (so not glass), which is what a pillar or an island's
     * side offers a player falling down past it.
     *
     * The click must come while the body is still out of the cell. 1.8 checks
     * the new ladder against the bodies around it using the ladder class's
     * shared bounds, which hold whichever ladder was last looked at -- or a
     * full cube if none has been -- not this one's plate; a body anywhere in
     * the cell can get the click refused, on the client and on the server.
     * So for the cell entered at tick t the last click is at the last tick k
     * before that the body is clear of it, the turn has k+1 ticks, and the
     * aim is taken from where the eyes will be then.
     *
     * The earliest cell that can be turned to in time wins: the sooner the
     * catch, the less there is to climb.
     */
    private Plan planLadder() {
        Plan best = null;
        int bestTick = Integer.MAX_VALUE;
        float turn = this.maxSpeed.getValue();
        BlockPos previous = null;
        for (int t = 1; t < Math.min(LADDER_TICKS, this.path.size()) && t <= bestTick; t++) {
            BlockPos cell = ladderCellOf(this.path.get(t));
            if (cell.equals(previous) || !BlockUtil.isReplaceable(cell)) {
                previous = cell;
                continue;
            }
            previous = cell;
            int last = -1;
            for (int k = t - 1; k >= 0; k--) {
                if (!intersects(this.path.get(k), cell)) {
                    last = k;
                    break;
                }
            }
            if (last < 0) {
                continue;
            }
            Vec3 eyes = eyesAt(this.path.get(last));
            for (EnumFacing side : EnumFacing.Plane.HORIZONTAL) {
                BlockPos support = cell.offset(side.getOpposite());
                if (!mc.theWorld.getBlockState(support).getBlock().isNormalCube() || BlockUtil.isInteractable(support)
                        || faceFailed(support, side)) {
                    continue;
                }
                Plan candidate = aimFace(cell, support, side, eyes, last + 1, Double.MAX_VALUE);
                if (candidate == null || candidate.cost > (last + 1) * turn) {
                    continue;
                }
                if (t < bestTick || candidate.cost < best.cost) {
                    candidate.ladder = true;
                    best = candidate;
                    bestTick = t;
                }
            }
        }
        return best;
    }

    private static boolean supported(BlockPos cell) {
        for (EnumFacing facing : EnumFacing.values()) {
            if (clickable(cell.offset(facing))) {
                return true;
            }
        }
        return false;
    }

    /** Distance from the eyes to the nearest block that could be clicked, within a radius. */
    private static double nearestBlock(Vec3 eyes, int radius) {
        double best = Double.MAX_VALUE;
        int cx = MathHelper.floor_double(eyes.xCoord);
        int cy = MathHelper.floor_double(eyes.yCoord);
        int cz = MathHelper.floor_double(eyes.zCoord);
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int y = cy - radius; y <= cy + radius; y++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!clickable(pos)) {
                        continue;
                    }
                    double dx = Math.max(Math.max(x - eyes.xCoord, eyes.xCoord - (x + 1)), 0.0);
                    double dy = Math.max(Math.max(y - eyes.yCoord, eyes.yCoord - (y + 1)), 0.0);
                    double dz = Math.max(Math.max(z - eyes.zCoord, eyes.zCoord - (z + 1)), 0.0);
                    best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
                }
            }
        }
        return best;
    }

    private void resetDiagnosis() {
        this.seenSupports = 0;
        this.facesAway = 0;
        this.facesFar = 0;
        this.facesBlocked = 0;
        this.nearestFace = Double.MAX_VALUE;
        this.chainNeed = 0;
        this.chainLeft = 0;
    }

    /**
     * Why no face could be clicked this tick, as {key, words}: the key says
     * which reason leads, for chat, and the words carry the numbers.
     */
    private String[] diagnose(Vec3 eyes) {
        if (this.seenSupports == 0 && this.chainNeed == 0) {
            double nearest = nearestBlock(eyes, 6);
            return new String[]{"none", nearest == Double.MAX_VALUE
                    ? "no block within 6 of the fall"
                    : String.format("no block next to the fall (nearest %.1f from the eyes)", nearest)};
        }
        List<String> parts = new ArrayList<String>();
        String key = null;
        if (this.facesFar > 0) {
            key = "far";
            parts.add(String.format("out of reach (nearest face %.2f, reach %.1f)",
                    this.nearestFace, this.reach.getValue()));
        }
        if (this.facesBlocked > 0) {
            key = key != null ? key : "blocked";
            parts.add(this.facesBlocked + " face" + (this.facesBlocked == 1 ? "" : "s") + " blocked from view");
        }
        if (this.chainNeed > 0) {
            key = key != null ? key : "chain";
            parts.add(String.format("a chain needs %dt, the fall leaves %dt", this.chainNeed, this.chainLeft));
        }
        if (this.facesAway > 0) {
            key = key != null ? key : "away";
            parts.add(this.facesAway + " face" + (this.facesAway == 1 ? "" : "s") + " turned away");
        }
        if (parts.isEmpty()) {
            return new String[]{"none", "nothing to build from within reach"};
        }
        StringBuilder words = new StringBuilder();
        for (String part : parts) {
            words.append(words.length() == 0 ? "" : ", ").append(part);
        }
        return new String[]{key, words.toString()};
    }

    /** Columns under the predicted footprint over the next few ticks. */
    private List<int[]> footprint(int ticks) {
        List<int[]> columns = new ArrayList<int[]>();
        Set<Long> seen = new HashSet<Long>();
        int limit = Math.min(ticks, this.path.size());
        for (int t = 0; t < limit; t++) {
            AxisAlignedBB box = this.path.get(t);
            for (int x = MathHelper.floor_double(box.minX); x <= MathHelper.floor_double(box.maxX - 1.0E-7); x++) {
                for (int z = MathHelper.floor_double(box.minZ); z <= MathHelper.floor_double(box.maxZ - 1.0E-7); z++) {
                    if (seen.add(((long) x << 32) ^ (z & 0xFFFFFFFFL))) {
                        columns.add(new int[]{x, z});
                    }
                }
            }
        }
        return columns;
    }

    /**
     * The catch cells, highest first: under the footprint, not inside the
     * player where the click will be sent from, and actually crossed by the
     * feet. Each level down is a block's worth more fall, so the first level
     * with any cell wins.
     *
     * The click is sent in PRE, before this tick's move (2026-10-03), so "where
     * the click is sent from" is the box now (pathStart), and the levels start
     * under the feet now: a cell crossed during this tick's move can still be
     * caught, the block going down before the move does.
     */
    private List<List<BlockPos>> catchCells() {
        List<List<BlockPos>> levels = new ArrayList<List<BlockPos>>();
        if (this.path.isEmpty()) {
            return levels;
        }
        AxisAlignedBB now = this.pathStart;
        int feet = MathHelper.floor_double(now.minY + 1.0E-3);
        int top = Math.min(feet - 1, keepYTop());
        List<int[]> columns = footprint(PLAN_TICKS);
        for (int level = top; level >= feet - 5; level--) {
            List<BlockPos> cells = new ArrayList<BlockPos>();
            for (int[] column : columns) {
                BlockPos cell = new BlockPos(column[0], level, column[1]);
                if (!BlockUtil.isReplaceable(cell) || intersects(now, cell)) {
                    continue;
                }
                if (catchTick(this.path, this.pathStart, cell, PLAN_TICKS) >= 0) {
                    cells.add(cell);
                }
            }
            if (!cells.isEmpty()) {
                levels.add(cells);
            }
        }
        return levels;
    }

    /**
     * keep-y: the highest layer a block may go on -- the layer stood on
     * before this fall. No limit when keep-y is off or nothing was stood on.
     */
    private int keepYTop() {
        return this.keepY.getValue() && this.lastGround != null
                ? MathHelper.floor_double(this.lastGround.yCoord - 1.0E-3) : Integer.MAX_VALUE;
    }

    /**
     * extra-block: the cell under the middle of the predicted landing, on the
     * layer the landing is on -- when this catch has placed a block next to it
     * and the landing would be on that block's edge with the middle of the
     * feet over air. Null otherwise. Vape extends a long clutch the same way
     * (its "extensionTarget": the landing column at the target's height).
     */
    private BlockPos extraCell() {
        if (!this.extraBlock.getValue() || !this.attempt || this.extraDone || this.attemptPlaced <= 0
                || mc.thePlayer.onGround || this.landingTick < 0 || this.landingTick >= this.path.size()) {
            return null;
        }
        AxisAlignedBB land = this.path.get(this.landingTick);
        int level = MathHelper.floor_double(land.minY - 1.0E-3);
        BlockPos cell = new BlockPos(MathHelper.floor_double((land.minX + land.maxX) / 2.0), level,
                MathHelper.floor_double((land.minZ + land.maxZ) / 2.0));
        if (!BlockUtil.isReplaceable(cell) || intersects(this.pathStart, cell)
                || catchTick(this.path, this.pathStart, cell, PLAN_TICKS) < 0) {
            return null;
        }
        /* Only beside a block this catch placed: ground that was already
           there is not this module's to extend. */
        for (BlockPos pos : this.placed) {
            if (pos.getY() == level && Math.abs(pos.getX() - cell.getX()) <= 1
                    && Math.abs(pos.getZ() - cell.getZ()) <= 1) {
                return cell;
            }
        }
        return null;
    }

    /**
     * The cheapest direct catch, from the eyes "lead" ticks ahead: the click
     * goes out in PRE of that tick, before its move, so the feet may cross the
     * cell during that very move (crossing == lead) -- the block is there
     * first. A cell crossed in an earlier move is behind.
     */
    private Plan planCatch(List<List<BlockPos>> levels, Vec3 eyes, int lead) {
        for (List<BlockPos> cells : levels) {
            Plan best = null;
            for (BlockPos cell : cells) {
                int crossing = catchTick(this.path, this.pathStart, cell, PLAN_TICKS);
                if (crossing < lead) {
                    continue;
                }
                Plan candidate = aim(cell, eyes, crossing);
                if (candidate != null && (best == null || candidate.cost < best.cost)) {
                    best = candidate;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    /**
     * No catch cell can be clicked into: build toward one. A search outward
     * from the catch cells through empty cells on the same level finds the
     * nearest cell that does have a support; placing it gives its neighbour
     * one next time. Only chains short enough to finish before the feet pass
     * the level are started, since half a bridge catches nothing.
     */
    private Plan planChain(List<List<BlockPos>> levels, Vec3 eyes, int lead) {
        if (!this.chain.getValue()) {
            return null;
        }
        int interval = Math.max(1, this.placeInterval.getValue());
        /* The box the clicks go out from: now, before this tick's move. */
        AxisAlignedBB next = this.pathStart;
        for (List<BlockPos> cells : levels) {
            int deadline = PLAN_TICKS;
            for (BlockPos cell : cells) {
                deadline = Math.min(deadline, catchTick(this.path, this.pathStart, cell, PLAN_TICKS));
            }
            Map<BlockPos, Integer> depth = new HashMap<BlockPos, Integer>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<BlockPos>();
            for (BlockPos cell : cells) {
                depth.put(cell, 0);
                queue.add(cell);
            }
            Plan best = null;
            int bestDepth = Integer.MAX_VALUE;
            while (!queue.isEmpty()) {
                BlockPos cell = queue.poll();
                int d = depth.get(cell);
                if (d > bestDepth) {
                    break;
                }
                if (d > 0) {
                    /* Every block of the chain, this one included, has to go
                       down before the feet reach the level: d + 1 clicks, the
                       first in PRE of tick "lead", the last (the catch cell)
                       in PRE of tick lead + d * interval, which may be the
                       tick whose move crosses the level. */
                    if (d * interval + lead > deadline) {
                        int need = d * interval + lead;
                        if ((this.chainNeed == 0 || need < this.chainNeed) && supported(cell)) {
                            this.chainNeed = need;
                            this.chainLeft = deadline;
                        }
                        continue;
                    }
                    Plan candidate = aim(cell, eyes, -1);
                    if (candidate != null && (best == null || d < bestDepth || candidate.cost < best.cost)) {
                        best = candidate;
                        bestDepth = d;
                    }
                }
                if (d >= this.chainLength.getValue()) {
                    continue;
                }
                for (EnumFacing facing : EnumFacing.Plane.HORIZONTAL) {
                    BlockPos neighbour = cell.offset(facing);
                    if (!depth.containsKey(neighbour) && BlockUtil.isReplaceable(neighbour)
                            && !intersects(next, neighbour)) {
                        depth.put(neighbour, d + 1);
                        queue.add(neighbour);
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    // -------------------------------------------------------------- rotation

    /**
     * An angular step toward the target, capped per tick. The cap is what the
     * time left demands -- the angle to cover spread over the ticks before
     * the catch -- kept between speed and max-speed; and, as a hand does, the
     * turn gets up to that over a tick or two instead of starting at it, with
     * its speed drifting rather than jumping about. The step is whole mouse
     * counts at the player's sensitivity, as every turn a mouse makes is.
     */
    private void stepRotation(float targetYaw, float targetPitch, float step) {
        if (this.holdThisTick) {
            /* A click went out this tick: the movement packet after it has to
               carry the rotation it was aimed with (see onUpdate). */
            return;
        }
        Set<RotationEngine.Feature> features = EnumSet.noneOf(RotationEngine.Feature.class);
        if (this.humanize.getValue()) {
            features.add(RotationEngine.Feature.NOISE);
            features.add(RotationEngine.Feature.CURVE);
            if (this.ease.getValue()) {
                features.add(RotationEngine.Feature.EASE);
            }
        }
        float[] next = this.engine.step(this.sentYaw, this.sentPitch, targetYaw, targetPitch, step,
                Math.max(this.speed.getValue(), this.maxSpeed.getValue() * 0.5F), this.randomization.getValue(), true,
                features);
        float turn = Math.abs(MathHelper.wrapAngleTo180_float(next[0] - this.sentYaw));
        for (int guard = 0; guard < 3 && turn > 2.0F
                && (Math.abs(turn - this.placedTurn) < 1.0E-3F
                || myau.management.PlaceRotations.wouldDuplicate(next[0])); guard++) {
            /* Grim DuplicateRotPlace flags a placement whose yaw step equals the
               one at the previous judged placement (22:09:20, x=0.0). One mouse
               count more breaks the tie. Checked against the server's memory
               as the sent packets build it (PlaceRotations, 2026-10-04): this
               module's own placedTurn missed placements whose packet had no
               look, the player's own clicks, and looks it did not send
               (19:55:31, x=0.0). */
            double gcd = RotationUtil.gcd();
            next[0] += Math.signum(MathHelper.wrapAngleTo180_float(next[0] - this.sentYaw))
                    * (float) (gcd > 0.0 ? gcd : 0.01);
            turn = Math.abs(MathHelper.wrapAngleTo180_float(next[0] - this.sentYaw));
        }
        if (turn > 0.0F) {
            this.lastTurn = turn;
        }
        this.sentYaw = next[0];
        this.sentPitch = next[1];
    }

    /**
     * humanize: the spot the next block is clicked at, in face coordinates,
     * from a normal distribution around the middle of the face (sd 0.18),
     * kept inside the sampled part of it.
     */
    private void drawAimSpot() {
        double lo = FACE_INSET;
        double hi = 1.0 - FACE_INSET;
        this.aimU = Math.max(lo, Math.min(hi, 0.5 + this.noise.nextGaussian() * 0.18));
        this.aimV = Math.max(lo, Math.min(hi, 0.5 + this.noise.nextGaussian() * 0.18));
    }

    /** Degrees of turn worth one unit of face away from the drawn spot: 6 at rotation-random 25. */
    private double spreadWeight() {
        return this.humanize.getValue() ? this.randomization.getValue() * 0.24 : 0.0;
    }

    /** Within fov of where the player looked when the catch began. */
    private boolean inFov(Plan plan) {
        if (this.fov.getValue() >= 360) {
            return true;
        }
        float yaw = this.hasSent ? this.viewYaw : mc.thePlayer.rotationYaw;
        float pitch = this.hasSent ? this.viewPitch : mc.thePlayer.rotationPitch;
        return RotationEngine.angle(plan.yaw, plan.pitch, yaw, pitch) <= this.fov.getValue() / 2.0F;
    }

    /** Blocks of air straight under the feet, counted up to limit. */
    private int airBelow(int limit) {
        BlockPos feet = new BlockPos(mc.thePlayer.posX, mc.thePlayer.getEntityBoundingBox().minY - 0.01, mc.thePlayer.posZ);
        int air = 0;
        while (air < limit && feet.getY() - air >= 0 && BlockUtil.isReplaceable(feet.down(air))) {
            air++;
        }
        return feet.getY() - air < 0 ? limit : air;
    }

    private float stepFor(Plan plan) {
        if (plan.catchTick < 0) {
            /* A chain has a deadline of its own blocks after this one. */
            return this.maxSpeed.getValue();
        }
        float needed = (float) plan.cost / Math.max(1, plan.catchTick);
        return Math.max(this.speed.getValue(), Math.min(this.maxSpeed.getValue(), needed * 1.25F));
    }

    private void applyRotation(UpdateEvent event) {
        this.hasSent = true;
        if (this.mode.getValue() == 1) {
            /* The camera follows across the frames of the tick rather than
               jumping at its start -- which is a 20 Hz stutter of the whole
               view -- and is there by the next tick. While it is handed back
               the mouse still works, and moving it takes the view over. */
            Myau.rotationManager.setRotation(this.sentYaw, this.sentPitch, ROTATION_PRIORITY, !this.resetting);
        }
        event.setRotation(this.sentYaw, this.sentPitch, ROTATION_PRIORITY);
        /* The yaw movement is worked out from. Registered at this priority
           even with move-fix off, so a combat module's cannot slip in under
           a packet that carries this one. */
        event.setPervRotation(this.mode.getValue() == 1 || this.moveFix.getValue() != 0
                ? this.sentYaw : mc.thePlayer.rotationYaw, ROTATION_PRIORITY);
    }

    // ------------------------------------------------------------------ tick

    @EventTarget(Priority.HIGH)
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (event.getType() == EventType.POST) {
            this.inUpdate = false;
            return;
        }
        this.inUpdate = true;
        this.tick++;
        if (mc.thePlayer.hurtTime > this.lastHurtTime) {
            this.lastHurtAt = System.currentTimeMillis();
            this.lastHurtTick = this.tick;
        }
        this.lastHurtTime = mc.thePlayer.hurtTime;
        if (this.resetting && this.mode.getValue() == 1
                && (Math.abs(MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - this.sentYaw)) > 0.5F
                || Math.abs(mc.thePlayer.rotationPitch - this.sentPitch) > 0.5F)) {
            /* REAL: the mouse moved while the camera was being turned back.
               The player has taken the view; stop handing it back to them. */
            this.resetting = false;
            this.hasSent = false;
        }
        if (!this.hasSent) {
            this.sentYaw = mc.thePlayer.rotationYaw;
            this.sentPitch = mc.thePlayer.rotationPitch;
            this.engine.reset();
        }
        if (this.cooldown > 0) {
            this.cooldown--;
        }
        if (this.pauseTicks > 0) {
            this.pauseTicks--;
        }
        if (this.settleTicks > 0) {
            this.settleTicks--;
        }
        drainRefusals();
        if (mc.thePlayer.onGround) {
            this.lastGround = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        }

        /* The click is made at the end of this PRE, after this tick's turn
           (see post()). History: until 2026-10-02 it went out in POST, after
           the movement packet -- Grim "Post" and "RotationPlace post-flying";
           then at the start of the next PRE with that tick's turn held, which
           cost a tick per block. */
        this.holdThisTick = false;

        predict(false);
        /* extra-block wants this tick even when the fall itself no longer does. */
        BlockPos extra = extraCell();
        this.extraTarget = extra;
        boolean want = mc.currentScreen == null && !scaffolding() && (wanted() || extra != null);
        updateAttempt(want);
        /* Tell the modules that hold packets or change movement (see Arbiter). */
        Arbiter.setCatching(getName(), this.attempt);
        Arbiter.setViewHeld(getName(), this.hasSent && this.mode.getValue() == 1);
        List<String> yielded = Arbiter.drainYielded();
        if (this.attempt && !yielded.isEmpty()) {
            trace("    stood aside for the catch: " + String.join(", ", yielded));
        }
        this.plan = null;
        this.planLead = 0;
        if (this.attempt && this.ladderCell != null && !this.rodeLadder && mc.thePlayer.isOnLadder()
                && ladderCellOf(mc.thePlayer.getEntityBoundingBox()).equals(this.ladderCell)) {
            this.rodeLadder = true;
            say("&acaught on the ladder &8(climbing)");
        }
        if (this.attempt && this.tick - this.lastCorrectedTick <= 1) {
            /* The server is moving the player itself, a tick at a time, along
               its own copy of the fall (Pika, after a lagback: an S08 every
               tick until it lets go). Nothing done here reaches it -- clicks
               are judged from where it is about to put the player, keys and
               turns only add to what it is correcting. Hands off until it
               stops; then the catch goes on from wherever that is. */
            this.steering = false;
            trace(state() + " | the server is moving the player: hands off");
            holdRotation(event);
            return;
        }
        /* Whether to act is decided on the fall as it would go untouched; how,
           on the fall as it will go with the steering held. On a ladder the
           fall is not wanted at all, but the climb still has to be pressed. */
        this.steering = climbing() || (want && decideSteer());
        if (this.steering) {
            this.attemptSteered = true;
            predict(true);
        }

        if (!want) {
            if (this.attempt) {
                trace(state() + " | not needed now" + (this.landingTick >= 0
                        ? String.format(" (lands in %dt, %.1f fall)", this.landingTick, this.predictedFall) : ""));
            }
            this.active = false;
            this.aimedCell = null;
            stopPlacing();
            /* Ease the view back instead of dropping it, so the server does
               not see the aim vanish in a single tick. */
            if (this.hasSent) {
                /* REAL moved the camera itself, so "back" is where it looked
                   before the catch; SILENT never moved it. */
                boolean real = this.mode.getValue() == 1;
                if (real && !this.resetAngle.getValue()) {
                    this.hasSent = false;
                    if (this.disableAfter.getValue() && this.attemptPlaced > 0) {
                        this.setEnabled(false);
                    }
                    return;
                }
                float backYaw = real ? this.sentYaw + MathHelper.wrapAngleTo180_float(this.viewYaw - this.sentYaw)
                        : mc.thePlayer.rotationYaw;
                float backPitch = real ? this.viewPitch : mc.thePlayer.rotationPitch;
                boolean jump = this.snapOnJump.getValue() && mc.gameSettings.keyBindJump.isKeyDown();
                if (!this.resetting) {
                    this.backWait = this.snapbackDelay.getValue();
                }
                this.resetting = true;
                if (this.backWait > 0 && !jump) {
                    this.backWait--;
                    holdRotation(event);
                    return;
                }
                if (jump && !this.holdThisTick) {
                    float[] back = RotationEngine.quantize(this.sentYaw, this.sentPitch, backYaw, backPitch);
                    this.sentYaw = back[0];
                    this.sentPitch = back[1];
                } else {
                    stepRotation(backYaw, backPitch, this.snapback.getValue());
                }
                applyRotation(event);
                if (Math.abs(MathHelper.wrapAngleTo180_float(this.sentYaw - backYaw)) < 0.5F
                        && Math.abs(this.sentPitch - backPitch) < 0.5F) {
                    this.resetting = false;
                    this.hasSent = false;
                    /* A turn that placed nothing -- pre-aim, a fall that
                       sorted itself out -- is not the catch it waits for. */
                    if (this.disableAfter.getValue() && this.attemptPlaced > 0) {
                        this.setEnabled(false);
                    }
                }
            }
            return;
        }
        this.active = true;
        this.resetting = false;
        BlinkModules blinker = Myau.blinkManager == null ? BlinkModules.NONE : Myau.blinkManager.getBlinkingModule();
        if (blinker != BlinkModules.NONE) {
            /* AntiVoid, Blink and the rest hold every packet, the clicks
               included, and the server judges them late from a position it
               is about to be told to forget (09:43:57 Blink, 12:27:22
               AntiVoid). A catch needs the server current: let the held
               movement go first. New blinks are refused while the catch is
               on (Arbiter); Blink switches itself off when it loses the
               queue. */
            int held = Myau.blinkManager.blinkedPackets.size();
            Myau.blinkManager.setBlinkState(false, blinker);
            say(String.format("&e%s was holding %d packets &8(released so the clicks count)", blinker, held));
        }

        int blockSlot = pickBlockSlot();
        int ladderSlot = this.autoLadder.getValue() ? pickLadderSlot() : -1;
        if (!this.autoSwitch.getValue() || this.onDepletion.getValue() && !this.startedWithBlocks) {
            /* Only what is already in hand. */
            ItemStack held = mc.thePlayer.inventory.getCurrentItem();
            blockSlot = usable(held) ? mc.thePlayer.inventory.currentItem : -1;
            ladderSlot = ladderSlot >= 0 && isLadder(held) ? mc.thePlayer.inventory.currentItem : -1;
        }
        if (blockSlot < 0 && ladderSlot < 0) {
            problem("hotbar", this.autoSwitch.getValue() ? "no blocks in the hotbar"
                    : "not holding blocks (auto-switch is off)");
            holdRotation(event);
            return;
        }
        selectSlot(blockSlot >= 0 ? blockSlot : ladderSlot);
        startPlacing();

        if (this.path.isEmpty()) {
            return;
        }
        /* The eyes the click goes out from: now, before this tick's move. */
        Vec3 eyes = eyesAt(this.pathStart);
        if (extra == null && this.steering && !intoVoid() && this.predictedFall <= this.safeDrop.getValue()) {
            /* Steering alone puts the player back on something: no block needed. */
            trace(state() + String.format(" | steering lands in %dt, %.1f fall", this.landingTick, this.predictedFall)
                    + steerText());
            holdRotation(event);
            return;
        }
        List<List<BlockPos>> levels = blockSlot < 0 ? new ArrayList<List<BlockPos>>()
                : extra != null ? Collections.singletonList(Collections.singletonList(extra)) : catchCells();
        resetDiagnosis();
        String[] why = null;
        if (levels.isEmpty()) {
            why = new String[]{"cells", blockSlot >= 0 ? "no cell under the fall within " + PLAN_TICKS + " ticks"
                    : "no blocks in the hotbar"};
        } else {
            this.plan = planCatch(levels, eyes, 0);
            if (this.plan == null) {
                this.plan = planChain(levels, eyes, 0);
            }
        }
        if (this.plan == null && !levels.isEmpty()) {
            why = diagnose(eyes);
            /* Nothing clickable from here: look for a face that will be, a few
               ticks down the fall, and start turning toward it now. */
            for (int lead = 1; this.preAim.getValue() && this.plan == null
                    && lead <= PRE_AIM_TICKS && lead < this.path.size(); lead++) {
                /* PRE of tick "lead": after lead moves. */
                Vec3 ahead = eyesAt(this.path.get(lead - 1));
                this.plan = planCatch(levels, ahead, lead);
                if (this.plan == null) {
                    this.plan = planChain(levels, ahead, lead);
                }
                if (this.plan != null) {
                    this.planLead = lead;
                }
            }
        }
        if (this.plan == null && ladderSlot >= 0 && this.ladderCell == null && extra == null) {
            /* No block can catch this fall in time: hang a ladder in its way. */
            this.plan = planLadder();
            if (this.plan != null) {
                selectSlot(ladderSlot);
            }
        }
        if (this.plan == null && extra != null) {
            /* The extra block cannot be reached: the catch already holds. */
            this.extraDone = true;
        }
        if (this.plan == null) {
            trace(state() + " | " + levels(levels) + " | none: " + why[1]
                    + (ladderSlot >= 0 && this.ladderCell == null ? ", no ladder cell either" : "") + steerText());
            problem(why[0], why[1]);
            lookBack(event, eyes);
            return;
        }
        this.problemKey = null;
        this.problemRun = 0;
        /* Early turn, 1: a click along the look already sent, if it reaches a
           wanted cell from where the server has the eyes. It costs no tick --
           the click would go out this tick either way -- and needs no turn:
           holdThisTick keeps that look for this tick's packet, so the look
           before the click and the one after it are the same. */
        boolean safe = this.safeMode.getValue();
        if (safe) {
            this.quietNotes = true;
            try {
                post();
            } finally {
                this.quietNotes = false;
            }
        }
        boolean clickedHeld = this.holdThisTick;
        /* Early turn, 2: a big turn with a tick to spare is made now and
           clicked along next tick (by 1), aimed from where the eyes will be
           then. "A tick to spare" is the planners' own test with the click a
           tick later (lead 1): the catch cell is crossed no sooner, the chain
           still ends in time. With no tick to spare, the turn and the click
           share this tick as before. */
        boolean early = false;
        if (safe && !clickedHeld && this.planLead == 0 && !this.plan.ladder && this.earlyTurn.getValue() < 180
                && this.path.size() > 1 && RotationEngine.angle(this.sentYaw, this.sentPitch,
                this.plan.yaw, this.plan.pitch) > this.earlyTurn.getValue()) {
            Vec3 next = eyesAt(this.path.get(0));
            /* The same kind of block on the same layer, a tick later: never a
               direct catch traded for a chain, nor a layer further down. */
            Plan later = planCatch(levels, next, 1);
            if (later == null && this.plan.catchTick < 0) {
                later = planChain(levels, next, 1);
            }
            if (later != null && later.cell.getY() == this.plan.cell.getY()) {
                this.plan = later;
                early = true;
            }
        }
        float step = stepFor(this.plan);
        stepRotation(this.plan.yaw, this.plan.pitch, step);
        applyRotation(event);
        this.aimedCell = early ? this.plan.cell : null;
        this.aimedTick = this.tick;
        trace(String.format("%s | %s | %s %s on %s %s%s cost %.0f step %.0f | look %.1f/%.1f -> %.1f/%.1f%s%s%s",
                state(), levels(levels), this.plan.ladder ? "ladder" : this.plan.catchTick < 0 ? "chain" : "catch",
                cell(this.plan.cell),
                cell(this.plan.support), this.plan.side.getName(),
                this.plan.catchTick < 0 ? "" : " t" + this.plan.catchTick, this.plan.cost, step,
                this.sentYaw, this.sentPitch, this.plan.yaw, this.plan.pitch,
                this.planLead > 0 ? " | pre-aim from " + this.planLead + "t ahead (now: " + why[1] + ")" : "",
                this.pauseTicks > 0 ? " | paused " + this.pauseTicks + "t" : "", steerText())
                + (extra != null ? " | extra block under the feet" : "")
                + (clickedHeld ? " | clicked along the look already sent" : early ? " | early turn, click next tick" : ""));
        /* The click, now: after this tick's turn, before this tick's movement
           packet. The server -- Grim's RotationPlace "post-flying" as much as a
           vanilla server -- judges it by the rotation that packet carries,
           from where the player is now, which is exactly the ray post() casts.
           That is also vanilla's own order (the click uses the look about to
           be sent) and Vape's, and it places as soon as the turn lands: one
           block a tick, where clicking a tick later with the turn held made
           it one in two. Not after a click along the held look (one click a
           tick), nor after an early turn (its click is next tick's). */
        if (!clickedHeld && !early) {
            post();
        }
    }

    private String steerText() {
        return this.steering ? String.format(" | steer %.0f", MathHelper.wrapAngleTo180_float(this.steerYaw)) : "";
    }

    /**
     * With nothing to aim at, turn toward the block the player last stood on.
     * The supports are behind a player who has been knocked off them, and a
     * face that turns up in a tick or two is then a small turn away instead
     * of half a circle -- which at the rotation speeds a server will take is
     * two or three ticks of a fall that may not have them.
     */
    private void lookBack(UpdateEvent event, Vec3 eyes) {
        if (!this.preAim.getValue() || this.lastGround == null) {
            holdRotation(event);
            return;
        }
        double dx = MathHelper.floor_double(this.lastGround.xCoord) + 0.5 - eyes.xCoord;
        double dy = MathHelper.floor_double(this.lastGround.yCoord - 0.5) + 0.5 - eyes.yCoord;
        double dz = MathHelper.floor_double(this.lastGround.zCoord) + 0.5 - eyes.zCoord;
        if (dx * dx + dz * dz > ANCHOR_RANGE * ANCHOR_RANGE) {
            holdRotation(event);
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        yaw = this.sentYaw + MathHelper.wrapAngleTo180_float(yaw - this.sentYaw);
        stepRotation(yaw, pitch, Math.max(this.speed.getValue(), this.maxSpeed.getValue() / 2.0F));
        applyRotation(event);
    }

    /** The catch levels as y, cell count and the tick the feet cross the first: "L84x3@2". */
    private String levels(List<List<BlockPos>> levels) {
        StringBuilder text = new StringBuilder();
        for (List<BlockPos> cells : levels) {
            int first = PLAN_TICKS;
            for (BlockPos cell : cells) {
                int t = catchTick(this.path, this.pathStart, cell, PLAN_TICKS);
                if (t >= 0) {
                    first = Math.min(first, t);
                }
            }
            text.append(text.length() == 0 ? "" : " ")
                    .append('L').append(cells.get(0).getY()).append('x').append(cells.size()).append('@').append(first);
        }
        return text.toString();
    }

    /**
     * With nothing to aim at this tick, keep sending the rotation already
     * sent. Letting it lapse for a tick would have the server see the real
     * view, and the next step would then start from an angle it never had.
     */
    private void holdRotation(UpdateEvent event) {
        if (this.hasSent) {
            applyRotation(event);
        }
    }

    /**
     * The click for this tick's plan, at the end of this tick's PRE: after
     * this tick's turn, before this tick's move and its movement packet (see
     * onUpdate). The ray is traced from where the player is now -- where the
     * server has them -- along the rotation that packet will carry, so the
     * server checks exactly this. Only a block
     * that goes where it is wanted is placed: into a catch cell of the fall as
     * it is now, or the planned chain cell.
     */
    private void post() {
        if (!this.active || this.plan == null || this.cooldown > 0) {
            return;
        }
        if (this.attemptRefused >= MAX_REFUSALS) {
            note("no click: " + this.attemptRefused + " blocks refused this fall");
            return;
        }
        if (this.pauseTicks > 0) {
            note("no click: waiting out a correction or refusal");
            return;
        }
        ItemStack held = mc.thePlayer.inventory.getCurrentItem();
        if (this.plan.ladder ? !isLadder(held) : !usable(held)) {
            note("no click: not holding " + (this.plan.ladder ? "a ladder" : "blocks"));
            return;
        }
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        MovingObjectPosition hit = ray(eyes, this.sentYaw, this.sentPitch);
        if (hit == null || hit.typeOfHit != MovingObjectType.BLOCK) {
            note("turning: the look reaches no block yet");
            return;
        }
        if (!clickable(hit.getBlockPos())) {
            note("turning: the look is on " + cell(hit.getBlockPos()) + ", which cannot be built on");
            return;
        }
        BlockPos cell = hit.getBlockPos().offset(hit.sideHit);
        if (faceFailed(hit.getBlockPos(), hit.sideHit)) {
            note("no click: " + cell(hit.getBlockPos()) + " " + hit.sideHit.getName() + " was just refused by the client");
            return;
        }
        if (this.plan.ladder) {
            postLadder(held, hit, cell);
            return;
        }
        if (!BlockUtil.isReplaceable(cell) || intersects(mc.thePlayer.getEntityBoundingBox(), cell)) {
            note("turning: the look would place at " + cell(cell) + ", which is taken or inside the player");
            return;
        }
        boolean wanted = cell.equals(this.plan.cell)
                || this.tick - this.aimedTick == 1 && cell.equals(this.aimedCell);
        if (!wanted) {
            /* A partly turned look can still land somewhere useful: under the
               fall as it now stands -- but not above the keep-y layer (until
               2026-10-03 this let a steep look onto the top of a catch block
               put one block up on every jump). */
            predict(this.steering);
            wanted = cell.getY() <= keepYTop() && catchTick(this.path, this.pathStart, cell, PLAN_TICKS) >= 0;
        }
        if (!wanted) {
            note("turning: the look would place at " + cell(cell) + ", not under the fall");
            return;
        }
        if (!((ItemBlock) held.getItem()).canPlaceBlockOnSide(mc.theWorld, hit.getBlockPos(),
                hit.sideHit, mc.thePlayer, held)) {
            note("no click: the game would not place at " + cell(cell));
            return;
        }
        /* From here a C08 goes out whatever onPlayerRightClick answers. */
        this.holdThisTick = true;
        this.placedTurn = this.lastTurn;
        if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held,
                hit.getBlockPos(), hit.sideHit, hit.hitVec)) {
            this.lastNote = null;
            this.attemptPlaced++;
            this.placed.add(cell);
            this.placedAt.put(cell, this.tick);
            if (this.swing.getValue()) {
                mc.thePlayer.swingItem();
            }
            if (mc.playerController.getCurrentGameType() != GameType.CREATIVE) {
                this.cooldown = this.placeInterval.getValue();
            }
            boolean extra = cell.equals(this.extraTarget);
            if (extra) {
                this.extraDone = true;
            }
            drawAimSpot();
            say("&aplaced &7" + cell(cell) + " &8on " + cell(hit.getBlockPos()) + " " + hit.sideHit.getName()
                    + (extra ? " (extra, under the feet)"
                    : this.plan.catchTick < 0 ? " (chain)" : " (catch in " + this.plan.catchTick + "t)"));
        } else {
            failFace(hit.getBlockPos(), hit.sideHit);
            note("no click: the client refused the placement at " + cell(cell));
        }
    }

    private static String faceKey(BlockPos support, EnumFacing side) {
        return support.getX() + "," + support.getY() + "," + support.getZ() + "," + side.ordinal();
    }

    /** A click on this face was answered false: leave it out of the next plans for a while. */
    private void failFace(BlockPos support, EnumFacing side) {
        this.failedFaces.put(faceKey(support, side), this.tick + FAILED_FACE_TICKS);
        this.plan = null;
    }

    private boolean faceFailed(BlockPos support, EnumFacing side) {
        Integer until = this.failedFaces.get(faceKey(support, side));
        if (until == null) {
            return false;
        }
        if (this.tick >= until) {
            this.failedFaces.remove(faceKey(support, side));
            return false;
        }
        return true;
    }

    /**
     * The ladder click. Unlike a block it may go where the body is; what
     * matters is that the plate is clear of it, that the face clicked is a
     * side, and that the cell is one the body will be in at the start of a
     * tick -- the planned one, or any on the fall as it now stands.
     */
    private void postLadder(ItemStack held, MovingObjectPosition hit, BlockPos cell) {
        if (!hit.sideHit.getAxis().isHorizontal() || !BlockUtil.isReplaceable(cell)
                || !mc.theWorld.getBlockState(hit.getBlockPos()).getBlock().isNormalCube()) {
            note("turning: the look is not on the side of a solid block");
            return;
        }
        if (intersects(mc.thePlayer.getEntityBoundingBox(), cell)) {
            /* See planLadder: the check is against the class's shared bounds. */
            note("no click: the body is already in " + cell(cell));
            return;
        }
        boolean wanted = cell.equals(this.plan.cell);
        if (!wanted) {
            predict(this.steering);
            for (int t = 1; t < Math.min(LADDER_TICKS, this.path.size()) && !wanted; t++) {
                wanted = ladderCellOf(this.path.get(t)).equals(cell);
            }
        }
        if (!wanted) {
            note("turning: a ladder at " + cell(cell) + " would not be in the fall's way");
            return;
        }
        if (!((ItemBlock) held.getItem()).canPlaceBlockOnSide(mc.theWorld, hit.getBlockPos(),
                hit.sideHit, mc.thePlayer, held)) {
            note("no click: the game would not hang a ladder at " + cell(cell));
            return;
        }
        this.holdThisTick = true;
        this.placedTurn = this.lastTurn;
        if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held,
                hit.getBlockPos(), hit.sideHit, hit.hitVec)) {
            this.lastNote = null;
            this.attemptPlaced++;
            this.placedAt.put(cell, this.tick);
            this.ladderCell = cell;
            this.ladderSide = hit.sideHit;
            this.ladderTick = this.tick;
            if (this.swing.getValue()) {
                mc.thePlayer.swingItem();
            }
            if (mc.playerController.getCurrentGameType() != GameType.CREATIVE) {
                this.cooldown = this.placeInterval.getValue();
            }
            say("&aladder &7" + cell(cell) + " &8on " + cell(hit.getBlockPos()) + " " + hit.sideHit.getName());
        } else {
            failFace(hit.getBlockPos(), hit.sideHit);
            note("no click: the client refused the ladder at " + cell(cell));
        }
    }

    private boolean scaffolding() {
        Module scaffold = Myau.moduleManager.getModule(Scaffold.class);
        return scaffold != null && scaffold.isEnabled();
    }

    // -------------------------------------------------------------- attempts

    /**
     * Remembers why this attempt could not act. Chat hears of a reason once it
     * has lasted PROBLEM_TICKS -- the first tick off an edge nearly always has
     * the bridge's side turned away, and that is not news -- and only once.
     */
    private void problem(String key, String detail) {
        if (!this.attempt) {
            return;
        }
        this.attemptProblem = detail;
        this.problemRun = key.equals(this.problemKey) ? this.problemRun + 1 : 1;
        this.problemKey = key;
        if (this.problemRun == PROBLEM_TICKS && !key.equals(this.saidProblem)) {
            this.saidProblem = key;
            say("&e" + detail);
        }
    }

    /**
     * Opens an attempt when a catch is first wanted, and closes it when the
     * player lands, dies, or is moved somewhere else: saved if they are
     * standing on a block this attempt placed.
     */
    private void updateAttempt(boolean want) {
        if (want && !this.attempt) {
            this.attempt = true;
            this.attempts++;
            this.attemptPlaced = 0;
            this.attemptRefused = 0;
            this.attemptTick = 0;
            this.attemptSteered = false;
            this.ladderCell = null;
            this.rodeLadder = false;
            this.startedWithBlocks = usable(mc.thePlayer.inventory.getCurrentItem());
            if (!this.hasSent) {
                /* The view before this catch; not re-taken while an earlier
                   catch is still handing the camera back. */
                this.viewYaw = mc.thePlayer.rotationYaw;
                this.viewPitch = mc.thePlayer.rotationPitch;
            }
            this.attemptProblem = null;
            this.problemKey = null;
            this.problemRun = 0;
            this.saidProblem = null;
            this.lastNote = null;
            this.attemptStartY = mc.thePlayer.posY;
            this.lastPosition = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
            this.placed.clear();
            this.extraTarget = null;
            this.extraDone = false;
            drawAimSpot();
            long sinceHit = System.currentTimeMillis() - this.lastHurtAt;
            int sinceSwing = this.tick - this.lastAttackTick;
            trace(String.format("ATTEMPT %d | %s | %s | %s | ping %d | reach %.1f interval %d | %s | stood %s",
                    this.attempts, intoVoid() ? "void" : String.format("drop %.1f", this.predictedFall),
                    sinceHit < this.combatWindow.getValue() ? "hit " + sinceHit + "ms ago" : "no hit",
                    sinceSwing <= 20 ? "swung " + sinceSwing + "t ago" + (this.lastAttackSprinting ? " sprinting" : "")
                            : "no swing",
                    ping(), this.reach.getValue(), this.placeInterval.getValue(), state(),
                    this.lastGround == null ? "unknown" : String.format("%.2f %.2f %.2f (%.1f away)",
                            this.lastGround.xCoord, this.lastGround.yCoord, this.lastGround.zCoord,
                            Math.sqrt(Math.pow(this.lastGround.xCoord - mc.thePlayer.posX, 2)
                                    + Math.pow(this.lastGround.zCoord - mc.thePlayer.posZ, 2)))));
            say("&7catching " + (intoVoid() ? "a fall into the void"
                    : String.format("a %.1f block drop", this.predictedFall)));
            return;
        }
        if (!this.attempt) {
            return;
        }
        this.attemptTick++;
        /* Pika does not always kill a player who falls into the void: it moves
           them back to their spawn. A jump of more than eight blocks in one
           tick is that, and is a fall that was not caught. */
        Vec3 now = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        boolean moved = this.lastPosition != null && now.squareDistanceTo(this.lastPosition) > 64.0;
        this.lastPosition = now;
        if (mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0.0F || mc.thePlayer.posY < -30.0 || moved
                || Math.abs(mc.thePlayer.posY - this.attemptStartY) > 80.0) {
            this.attempt = false;
            this.ladderCell = null;
            say("&clost &7after " + this.attemptPlaced + " block" + (this.attemptPlaced == 1 ? "" : "s")
                    + (this.attemptRefused > 0 ? ", " + this.attemptRefused + " refused" : "")
                    + (this.attemptProblem != null ? " &8(" + this.attemptProblem + ")" : "")
                    + (moved ? " &8[moved by the server]" : ""));
            return;
        }
        if (mc.thePlayer.onGround) {
            this.attempt = false;
            BlockPos under = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY - 0.5, mc.thePlayer.posZ);
            boolean ours = false;
            AxisAlignedBB feet = mc.thePlayer.getEntityBoundingBox().offset(0.0, -0.5, 0.0);
            for (BlockPos pos : this.placed) {
                if (pos.equals(under) || intersects(feet, pos)) {
                    ours = true;
                    break;
                }
            }
            boolean ladder = !ours && this.rodeLadder;
            this.ladderCell = null;
            if (ladder) {
                this.saves++;
                this.settleTicks = SETTLE_TICKS;
                say(String.format("&asaved &7by a ladder, climbed out %.1f below the start",
                        this.attemptStartY - mc.thePlayer.posY));
            } else if (ours) {
                this.saves++;
                this.savedOn.clear();
                this.savedOn.addAll(this.placed);
                this.savedAt = this.tick;
                if (this.attemptSteered) {
                    /* Still holding a key toward the bridge, the player would
                       walk straight off a one-block landing. */
                    this.settleTicks = SETTLE_TICKS;
                }
                say(String.format("&asaved &7with %d block%s, %.1f below the start%s%s", this.attemptPlaced,
                        this.attemptPlaced == 1 ? "" : "s", this.attemptStartY - mc.thePlayer.posY,
                        this.attemptRefused > 0 ? ", " + this.attemptRefused + " refused" : "",
                        this.attemptSteered ? " &8(steered)" : ""));
            } else {
                say("&7landed on ground that was already there" + (this.attemptSteered ? " &8(steered back)" : ""));
                /* Not a catch, and not a failure either: the attempt did not count. */
                this.attempts--;
            }
        }
    }

    @Override
    public String[] getSuffix() {
        String score = this.saves + "/" + this.attempts;
        if (this.trigger.getValue() != 0 && this.holdKey.getValue() != Keyboard.KEY_NONE) {
            return new String[]{this.mode.getModeString(), this.holdKey.getKeyName(), score};
        }
        return new String[]{this.mode.getModeString(), score};
    }
}
