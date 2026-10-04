package myau.module.modules;

import com.google.common.base.CaseFormat;
import lombok.Getter;
import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.*;
import myau.management.RotationState;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.property.properties.*;
import myau.util.*;
import myau.util.rotation.Rotation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.DataWatcher.WatchableObject;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.EntitySilverfish;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityBat;
import net.minecraft.entity.passive.EntitySquid;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S1CPacketEntityMetadata;
import net.minecraft.util.MathHelper;
import net.minecraft.util.*;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.world.WorldSettings.GameType;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;

public class KillAura extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final DecimalFormat df = new DecimalFormat("+0.0;-0.0", new DecimalFormatSymbols(Locale.US));
    private static final long ROTATION_UPDATE_INTERVAL_MS = 50;
    public final ModeProperty cpsMode;
    public final ModeProperty mode;
    public final ModeProperty sort;
    public ModeProperty autoBlock;
    /* Read by NoSlow ("No Attack" with SWAP autoblock): the block tick on which no hit goes out. */
    public final IntProperty attackTick = new IntProperty("AttackTick", 0, 1, 5, () -> this.autoBlock.getValue() == 6);
    public final BooleanProperty autoBlockRequirePress;
    public final FloatProperty autoBlockCPS;
    public final FloatProperty autoBlockRange;
    public final FloatProperty swingRange;
    public final FloatProperty attackRange;
    public final IntProperty fov;
    public final IntProperty minCPS;
    public final IntProperty maxCPS;
    public final IntProperty switchDelay;
    /**
     * How much of the round trip to lead the target by, as a multiplier.
     *
     * Zero reproduces the previous behaviour exactly, which is what makes this
     * measurable: {@link HitCheck} already reports the hit rate, so the
     * question "does leading help on this connection" can be answered by
     * playing a game each way rather than argued about. One is the full round
     * trip; below one is cautious.
     */
    public final FloatProperty aimLead = new FloatProperty("AimLead", 0.0F, 0.0F, 1.5F);
    /** Blocks of lead allowed on any axis, so a bad velocity cannot fling it. */
    public final FloatProperty aimLeadCap = new FloatProperty("AimLeadCap", 1.2F, 0.2F, 3.0F,
            () -> this.aimLead.getValue() > 0.0F);
    public final ModeProperty rotations;
    public final FloatProperty deadZoneSize;
    public final FloatProperty maxTurnSpeed;
    public final FloatProperty minTurnSpeed;
    public final FloatProperty acceleration;
    public final FloatProperty deceleration;
    public final BooleanProperty useOvershoot;
    public final FloatProperty overshootStrength;
    public final FloatProperty overshootRecovery;
    public final FloatProperty noiseStrength;
    public final BooleanProperty visualizeAim;
    public final BooleanProperty smoothBack;
    public final ModeProperty moveFix;
    public final PercentProperty smoothing;
    public final IntProperty ravenSmoothing;
    public final IntProperty ravenPredictTicks;
    public final IntProperty ravenYawRandom;
    public final IntProperty angleStep;
    public final ModeProperty aimMode;
    public final IntProperty aimMinSpeed;
    public final IntProperty aimMaxSpeed;
    /* SMOOTHSTEP: aim at the point of the hitbox nearest the crosshair
       (RotationEngine.nearestOnBox) rather than its middle. Already on the
       target, the aim stays put instead of being dragged to the centre. */
    public final BooleanProperty multipoint;
    /* SMOOTHSTEP: the most the turn may grow from one tick to the next. A
       hand gets up to speed; it does not go from still to 70 degrees a tick. */
    public final IntProperty turnAccel;
    public final BooleanProperty throughWalls;
    public final BooleanProperty requirePress;
    public final BooleanProperty allowMining;
    public final BooleanProperty weaponsOnly;
    public final BooleanProperty allowTools;
    public final BooleanProperty inventoryCheck;
    public final BooleanProperty botCheck;
    public final BooleanProperty players;
    public final BooleanProperty bosses;
    public final BooleanProperty mobs;
    public final BooleanProperty animals;
    public final BooleanProperty golems;
    public final BooleanProperty silverfish;
    public final BooleanProperty teams;
    public final ModeProperty showTarget;
    public final ModeProperty debugLog;
    public final BooleanProperty randomize;
    public final FloatProperty randomizeRange;
    public final FloatProperty yRandomizeStrength;
    public final FloatProperty liquidBounceHorizontalSpeed;
    public final FloatProperty liquidBounceVerticalSpeed;
    public final FloatProperty liquidBounceSmoothFactor;
    public final BooleanProperty liquidBouncePredict;
    public final FloatProperty liquidBouncePredictSize;
    public final BooleanProperty liquidBounceRandomize;
    public final FloatProperty liquidBounceRandomizeRange;
    public final FloatProperty liquidBounceHorizontalSearch;
    public final FloatProperty liquidBounceBodyPointMin;
    public final FloatProperty liquidBounceBodyPointMax;
    /* Rotations "Advanced": Rise 6.9.5's aim model, util/AdvancedAim
       (2026-09-28, docs/PAID-CLIENT-GAP.md). Names follow Rise's settings;
       defaults and ranges are Rise's. */
    public final FloatProperty advGravity = new FloatProperty("Adv-Gravity", 9.0F, 1.0F, 20.0F, this::isAdvanced);
    public final FloatProperty advWind = new FloatProperty("Adv-Wind", 6.0F, 0.0F, 10.0F, this::isAdvanced);
    public final FloatProperty advDampedDistance = new FloatProperty("Adv-DampedDistance", 12.0F, 1.0F, 45.0F, this::isAdvanced);
    public final FloatProperty advMaxStep = new FloatProperty("Adv-MaxStep", 15.0F, 3.0F, 60.0F, this::isAdvanced);
    public final IntProperty advOvershootChance = new IntProperty("Adv-OvershootChance", 77, 0, 100, this::isAdvanced);
    public final FloatProperty advOvershootScale = new FloatProperty("Adv-OvershootScale", 0.0F, 0.0F, 0.6F, this::isAdvanced);
    public final FloatProperty advOvershootMax = new FloatProperty("Adv-OvershootMax", 17.0F, 2.0F, 45.0F, this::isAdvanced);
    public final FloatProperty advGaussian = new FloatProperty("Adv-Gaussian", 0.0F, 0.0F, 0.6F, this::isAdvanced);
    public final IntProperty advAccuracy = new IntProperty("Adv-Accuracy", 40, 40, 100, this::isAdvanced);
    public final FloatProperty advMinStep = new FloatProperty("Adv-MinStep", 0.0F, 0.0F, 8.0F, this::isAdvanced);
    public final FloatProperty advPrediction = new FloatProperty("Adv-Prediction", 1.0F, 0.0F, 3.5F, this::isAdvanced);
    public final FloatProperty advDeadzone = new FloatProperty("Adv-Deadzone", 1.0F, 0.25F, 4.5F, this::isAdvanced);
    public final FloatProperty advAnchor = new FloatProperty("Adv-Anchor", 0.0F, 0.0F, 0.7F, this::isAdvanced);
    public final IntProperty advHoldTicks = new IntProperty("Adv-HoldTicks", 2, 0, 8, this::isAdvanced);
    public final FloatProperty advCruiseFloor = new FloatProperty("Adv-CruiseFloor", 1.0F, 0.0F, 5.0F, this::isAdvanced);
    public final FloatProperty advPaceJitter = new FloatProperty("Adv-PaceJitter", 0.0F, 0.0F, 0.8F, this::isAdvanced);
    public final IntProperty advBurstChance = new IntProperty("Adv-BurstChance", 21, 0, 100, this::isAdvanced);
    public final FloatProperty advBurstStrength = new FloatProperty("Adv-BurstStrength", 0.0F, 0.0F, 1.0F, this::isAdvanced);
    public final BooleanProperty advFlickGuard = new BooleanProperty("Adv-FlickGuard", true, this::isAdvanced);
    public final FloatProperty advFlickMax = new FloatProperty("Adv-FlickMax", 29.0F, 4.0F, 60.0F,
            () -> this.isAdvanced() && this.advFlickGuard.getValue());
    /* A click that finds no one is still a click: swung, as a hand misses. */
    public final BooleanProperty advSwing = new BooleanProperty("Adv-Swing", true, this::isAdvanced);
    public final IntProperty advAimReaction = new IntProperty("Adv-AimReaction", 180, 30, 450, this::isAdvanced);
    public final IntProperty advAimReactionJitter = new IntProperty("Adv-AimReactionJitter", 44, 0, 220, this::isAdvanced);
    public final IntProperty advTriggerReaction = new IntProperty("Adv-TriggerReaction", 95, 0, 300, this::isAdvanced);
    public final IntProperty advTriggerReactionJitter = new IntProperty("Adv-TriggerReactionJitter", 30, 0, 140, this::isAdvanced);
    /* After the knockback planner (Rise's "Knockback Displacement"), Advanced
       only. SAFE turns the yaw toward the hazard only as far as the look still
       lands on the target; FULL turns all the way and lets the hit go on range
       alone, as Rise does -- the server then sees a hit with the yaw pointing
       away from the target. */
    public final ModeProperty kbDisplace = new ModeProperty("KBDisplace", 0, new String[]{"OFF", "SAFE", "FULL"},
            this::isAdvanced);
    /* The CPS settings as one range (2026-09-28): same two settings, same keys. */
    private myau.property.IntRange cps;
    /* This tick's Advanced rotation was turned for knockback (FULL). */
    private boolean kbDisplacing;
    private final myau.util.AdvancedAim advancedAim = new myau.util.AdvancedAim();
    private final myau.util.AdvancedAim.Settings advancedSettings = new myau.util.AdvancedAim.Settings();
    /* Set at the start of the tick's update: another action (a placement, a
       dig, an inventory click) already went out this tick (TickActions). */
    private boolean otherActionsThisTick;
    private final int[] clickPattern = {16, 22, 14, 46, 18, 8, 8, 63, 25, 25, 12, 39, 26, 18, 6, 62, 26, 18, 21, 40, 26, 8, 16, 46, 26, 20, 15, 50, 25, 10, 11, 43, 25, 11, 37, 39, 25, 12, 18, 54, 25, 25, 15, 41, 27, 9, 1, 66, 26, 17, 21, 48, 27, 8, 6, 62, 28, 19, 13, 47, 26, 7, 14, 53, 27, 16, 29, 38, 27, 8, 6, 60, 27, 22, 19, 45, 26, 10, 10, 62, 25, 20, 28, 22, 26, 19, 11, 57, 26, 16, 32, 36, 26, 9, 9, 66, 27, 19, 27, 38, 26, 9, 10, 61, 26, 25, 15, 34, 26, 20, 10, 52, 26, 22, 28, 29, 27, 8, 3, 63, 26, 21, 27, 38, 26, 10, 11, 38, 27, 15, 31, 39, 25, 13, 10, 45, 27, 14, 27, 40, 26, 10, 6, 51, 26, 18, 31, 27, 27, 11, 14, 47, 26, 23, 21, 35, 26, 12, 13, 41, 26, 15, 31, 36, 27, 16, 9, 44, 27, 14, 30, 39, 25, 14, 10, 46, 28, 10, 24, 45, 26, 7, 5, 46, 26, 20, 6, 50, 26, 8, 6, 51, 26, 17, 20, 40, 27, 25, 1, 32, 26, 20, 9, 46, 25, 15, 12, 30, 26, 11, 25, 46, 27, 13, 10, 36, 27, 20, 15, 41, 26, 8, 6, 41, 26, 12, 29, 44, 26, 13, 11, 44, 26, 12, 27, 36, 26, 23, 4, 39, 26, 24, 12, 47, 26, 9, 2, 65, 26, 16, 27, 34, 26, 25, 0, 53, 26, 16, 3, 47, 27, 16, 10, 41, 26, 18, 25, 38, 26, 11, 10, 50, 27, 20, 20, 29, 26, 11, 7, 66, 26, 20, 18, 31, 26, 21, 21, 28, 26, 21, 29, 25, 27, 15, 12, 43, 28, 11, 31, 32, 27, 23, 0, 49, 27, 20, 30, 30, 25, 32, 0, 50, 26, 12, 25, 34, 27, 11, 11, 44, 27, 23, 26, 25, 27, 16, 11, 46, 26, 13, 32, 35, 28, 9, 5, 48, 26, 21, 29, 37, 26, 10, 7, 48, 27, 20, 21, 41, 24, 7, 18, 46, 25, 22, 22, 33, 25, 10, 5, 59, 26, 21, 19, 29, 26, 11, 10, 46, 25, 22, 29, 31, 25, 11, 12, 50, 24, 20, 28, 40, 25, 10, 4, 56, 25, 16, 36, 30, 24, 10, 9, 63, 25, 22, 22, 32, 25, 9, 8, 58, 27, 10, 43, 30, 26, 8, 3, 60, 26, 24, 14, 42, 26, 12, 9, 49, 25, 11, 32, 38, 27, 8, 8, 50, 26, 20, 26, 32, 25, 10, 4, 66, 25, 18, 28, 24, 26, 10, 8, 54, 25, 16, 32, 34, 24, 9, 12, 54, 25, 18, 18, 41, 28, 9, 16, 50, 28, 15, 21, 46, 27, 9, 8, 49, 26, 21, 18, 36, 26, 15, 10, 54, 27, 22, 27, 32, 25, 9, 15, 48, 28, 19, 26, 35, 27, 9, 13, 48, 25, 21, 23, 33, 27, 8, 3, 65, 26, 19, 23, 39, 25, 9, 13, 44, 26, 25, 19, 35, 26, 14, 6, 63, 27, 15, 23, 32, 28, 8, 2, 65, 26, 19, 24, 34, 27, 12, 0, 49, 26, 21, 34, 34, 26, 8, 9, 60, 26, 23, 19, 34, 26, 10, 5, 59, 26, 12, 36, 39, 26, 11, 11, 44, 26, 25, 5, 47, 25, 9, 10, 49, 27, 19, 24, 31, 26, 10, 4, 60, 27, 25, 9, 41, 26, 20, 7, 54, 24, 11, 35, 35, 26, 9, 5, 67, 26, 17, 19, 43, 26, 24, 17, 39, 25, 16, 11, 45, 25, 9, 3, 60, 25, 25, 16, 37, 28, 9, 5, 55, 26, 15, 12, 49, 25, 17, 8, 39, 25, 15, 16, 48, 25, 12, 9, 37, 25, 17, 31, 38, 27, 8, 8, 62, 26, 23, 14, 38, 27, 16, 10, 45, 26, 13, 25, 42, 25, 9, 8, 57, 27, 12, 36, 38, 27, 13, 11, 30, 27, 21, 24, 47, 25, 10, 6, 54, 26, 13, 28, 42, 25, 10, 5, 47, 26, 21, 22, 44, 26, 10, 8, 50, 28, 17, 26, 33, 26, 10, 14, 55, 27, 14, 30, 29, 25, 13, 1, 70, 26, 14, 30, 26, 27, 12, 14, 67, 25, 21, 4, 33, 25, 11, 5, 48, 26, 21, 21, 39, 25, 11, 1, 55, 26, 11, 29, 32, 26, 12, 10, 50, 27, 16, 26, 36, 27, 23, 3, 57, 27, 11, 23, 37, 26, 9, 16, 37, 26, 16, 38, 37, 26, 9, 2, 60, 27, 22, 16, 38, 27, 9, 5, 53, 26, 14, 33, 30, 25, 13, 11, 46, 25, 23, 22, 43, 24, 10, 13, 51, 25, 21, 25, 35, 27, 8, 16, 48, 25, 21, 19, 42, 25, 12, 12, 49, 26, 21, 18, 42, 25, 12, 13, 51, 27, 16, 25, 37, 26, 11, 12, 47, 27, 21, 13, 39, 27, 5, 9, 61, 25, 24, 11, 39, 26, 10, 9, 52, 26, 15, 33, 28, 38, 0, 9, 55, 26, 14, 39, 24, 25, 10, 9, 52, 27, 13, 29, 36, 25, 12, 9, 49, 25, 22, 30, 26, 26, 10, 2, 66, 27, 17, 30, 31, 26, 14, 7, 64, 28, 16, 31, 28, 24, 13, 14, 54, 25, 12, 29, 35, 27, 10, 8, 49, 27, 18, 26, 38, 25, 8, 14, 46, 26, 23, 15, 36, 26, 11, 5, 61, 27, 23, 8, 42, 25, 9, 10, 57, 26, 11, 29, 37, 25, 11, 9, 56, 27, 11, 32, 35, 26, 12, 6, 62, 27, 20, 33, 27, 27, 10, 14, 50, 27, 17, 28, 40, 25, 9, 8, 46, 26, 23, 16, 44, 26, 11, 13, 47, 28, 19, 19, 36, 26, 8, 7, 55, 26, 15, 24, 39, 26, 12, 9, 56, 26, 15, 28, 36, 25, 10, 10, 51, 25, 17, 32, 36, 25, 9, 7, 58, 26, 11, 31, 32, 26, 7, 14, 57, 26, 13, 22, 25, 24, 9, 14, 42, 26, 12, 27, 31, 25, 9, 2, 62, 27, 23, 12, 33, 26, 8, 18, 46, 25, 24, 14, 33, 24, 10, 14, 50, 25, 20, 21, 38, 26, 9, 1, 61, 25, 11, 30, 35, 26, 10, 10, 53, 25, 18, 22, 35, 25, 8, 4, 44, 25, 25, 21, 37, 24, 13, 6, 35, 27, 11, 34, 32, 25, 9, 10, 51, 26, 17, 18, 31, 24, 11, 8, 53, 26, 16, 30, 35, 26, 8, 10, 60, 25, 11, 32, 29, 25, 22, 2, 53, 26, 16, 30, 33, 27, 9, 11, 57, 25, 13, 32, 30, 25, 14, 10, 67, 24, 21, 29, 35, 27, 8, 12, 70, 26, 14, 19, 42, 27, 22, 0, 57, 27, 12, 31, 33, 25, 9, 12, 62, 27, 23, 14, 43, 25, 11, 2, 71, 28, 12, 33, 31, 27, 8, 12, 71, 26, 15, 23, 42, 28, 9, 8, 63, 26, 22, 22, 37, 27, 7, 4, 78, 27, 20, 26, 34, 25, 9, 15, 64, 27, 21, 23, 32, 26, 12, 11, 77, 25, 11, 32, 29, 26, 9, 15, 63, 27, 19, 23, 38, 26, 10, 15, 57, 26, 14, 37, 14, 26, 18, 6, 67, 26, 13, 31, 33, 26, 19, 1, 60, 27, 25, 22, 24, 27, 22, 2, 55, 26, 13, 25, 34, 26, 24, 0, 68, 25, 20, 22, 31, 25, 11, 4, 80, 24, 22, 22, 29, 26, 16, 8, 81, 25, 11, 22, 38, 27, 10, 11, 50, 27, 18, 35, 32, 26, 10, 5, 76, 26, 23, 22, 30, 24, 21, 8, 67, 27, 24, 16, 42, 27, 8, 3};
    private final TimerUtil timer = new TimerUtil();
    private final Random random = new Random();
    public AttackData target = null;
    private Rotation serverRotation = new Rotation(0, 0);
    private boolean isSmoothBacking = false;
    private boolean wantsToDisable = false;
    /* SmoothBack for every rotating mode (2026-10-04): the aura turned the
       sent look and has not handed it back to the camera yet. Until then only
       LiquidBounce/Advanced had a turn back, and only while a target was
       still around; losing it, or Silent/Legit/Hypixel at any time, sent the
       camera's yaw in the very next packet -- up to 180 degrees in a tick. */
    private boolean returning;
    private final RotationEngine backEngine = new RotationEngine();
    private float lastSentYaw;
    private float lastSentPitch;
    private boolean sentKnown;
    /* The turn back is over (or given up): this setEnabled(false) is final. */
    private boolean closing;
    private Vec3 currentAimVec = null;
    private int patternIndex = 0;
    private int switchTick = 0;
    private boolean hitRegistered = false;
    private boolean blockingState = false;
    private boolean isBlocking = false;
    private boolean fakeBlockState = false;
    private int hypixel3Asw = 0;
    private boolean blinkReset = false;
    private long attackDelayMS = 0L;
    int blockTick = 0;
    private int lastTickProcessed;
    private long lastRotationUpdateTime = 0;

    public KillAura() {
        super("KillAura", false, false, "Kill +999999 Aura");
        this.lastTickProcessed = 0;

        // 新增CPS模式属性
        this.cpsMode = new ModeProperty("CPS Mode", 0, new String[]{"Normal", "Record"});

        this.mode = new ModeProperty("Mode", 1, new String[]{"Single", "Switch"});
        this.sort = new ModeProperty("Sort", 1, new String[]{"Distance", "Health", "HurtTime", "FOV"});
        this.autoBlock = new ModeProperty("auto-block", 3, new String[]{"NONE", "VANILLA", "SPOOF", "HYPIXEL", "BLINK", "INTERACT", "SWAP", "LEGIT", "FAKE", "Morden"});
        this.autoBlockCPS = new FloatProperty("AutoBlockCPS", 8.0F, 1.0F, 10.0F);
        this.autoBlockRequirePress = new BooleanProperty("AutoBlockRequirePress", false);
        this.autoBlockRange = new FloatProperty("AutoBlockRange", 6.0F, 3.0F, 8.0F);
        this.swingRange = new FloatProperty("SwingRange", 3.5F, 3.0F, 6.0F);
        this.attackRange = new FloatProperty("AttackRange", 3.0F, 3.0F, 6.0F);
        this.fov = new IntProperty("FOV", 360, 30, 360);
        this.minCPS = new IntProperty("MinCPS", 14, 1, 20);
        this.maxCPS = new IntProperty("MaxCPS", 14, 1, 20);
        this.cps = myau.property.IntRange.of(this.minCPS, this.maxCPS);
        this.switchDelay = new IntProperty("SwitchDelay", 150, 0, 1000);
        this.rotations = new ModeProperty("Rotations", 2, new String[]{"NONE", "Legit", "Silent", "LockView", "LiquidBounce", "Hypixel", "Advanced"});
        this.deadZoneSize = new FloatProperty("DeadZone", 0.5F, 0.0F, 2.0F, () -> rotations.getValue() == 4);
        this.maxTurnSpeed = new FloatProperty("MaxSpeed", 25.0F, 5.0F, 180.0F, () -> rotations.getValue() == 4);
        this.minTurnSpeed = new FloatProperty("MinSpeed", 5.0F, 1.0F, 90.0F, () -> rotations.getValue() == 4);
        this.acceleration = new FloatProperty("Acceleration", 2.5F, 0.1F, 10.0F, () -> rotations.getValue() == 4);
        this.deceleration = new FloatProperty("Deceleration", 1.5F, 0.1F, 10.0F, () -> rotations.getValue() == 4);
        this.useOvershoot = new BooleanProperty("Overshoot", true, () -> rotations.getValue() == 4);
        this.overshootStrength = new FloatProperty("OverStr", 5.0F, 0.0F, 20.0F, () -> rotations.getValue() == 4 && useOvershoot.getValue());
        this.overshootRecovery = new FloatProperty("OverRecov", 0.2F, 0.01F, 1.0F, () -> rotations.getValue() == 4 && useOvershoot.getValue());
        this.noiseStrength = new FloatProperty("Noise", 0.2F, 0.0F, 2.0F, () -> rotations.getValue() == 4);
        this.randomize = new BooleanProperty("Randomize", true, () -> rotations.getValue() == 4);
        this.randomizeRange = new FloatProperty("RandomRange", 0.4F, 0.0F, 1.0F, () -> rotations.getValue() == 4 && randomize.getValue());
        this.yRandomizeStrength = new FloatProperty("YRandomize", 0.3F, 0.0F, 1.0F, () -> rotations.getValue() == 4 && randomize.getValue());
        this.visualizeAim = new BooleanProperty("VisualizeAim", true, () -> rotations.getValue() == 4);
        this.smoothBack = new BooleanProperty("SmoothBack", true,
                () -> rotations.getValue() != 0 && rotations.getValue() != 3);
        this.moveFix = new ModeProperty("MoveFix", 1, new String[]{"NONE", "Silent", "Strict"});
        this.smoothing = new PercentProperty("Smoothing", 0);
        this.ravenSmoothing = new IntProperty("HypixelSmoothing", 0, 0, 10, () -> this.rotations.getValue() == 5);
        this.ravenPredictTicks = new IntProperty("HypixelPredict", 0, 0, 5, () -> this.rotations.getValue() == 5);
        this.ravenYawRandom = new IntProperty("HypixelYawRandom", 0, 0, 5, () -> this.rotations.getValue() == 5);
        this.angleStep = new IntProperty("AngleStep", 90, 30, 180);
        this.aimMode = new ModeProperty("AimMode", 1, new String[]{"LEGACY", "SMOOTHSTEP"});
        this.aimMinSpeed = new IntProperty("MinTurnSpeed", 12, 1, 180, () -> this.aimMode.getValue() == 1);
        this.aimMaxSpeed = new IntProperty("MaxTurnSpeed", 60, 1, 180);
        this.multipoint = new BooleanProperty("Multipoint", true, () -> this.aimMode.getValue() == 1);
        this.turnAccel = new IntProperty("TurnAccel", 30, 1, 180);
        this.throughWalls = new BooleanProperty("ThroughWalls", true);
        this.requirePress = new BooleanProperty("RequirePress", false);
        this.allowMining = new BooleanProperty("AllowMining", true);
        this.weaponsOnly = new BooleanProperty("WeaponsOnly", true);
        this.allowTools = new BooleanProperty("AllowTools", false, this.weaponsOnly::getValue);
        this.inventoryCheck = new BooleanProperty("InventoryCheck", true);
        this.botCheck = new BooleanProperty("BotCheck", true);
        this.players = new BooleanProperty("Players", true);
        this.bosses = new BooleanProperty("Bosses", false);
        this.mobs = new BooleanProperty("Mobs", false);
        this.animals = new BooleanProperty("Animals", false);
        this.golems = new BooleanProperty("Golems", false);
        this.silverfish = new BooleanProperty("Silverfish", false);
        this.teams = new BooleanProperty("Teams", true);
        this.showTarget = new ModeProperty("ShowTarget", 0, new String[]{"NONE", "Default"});
        this.debugLog = new ModeProperty("Debug", 0, new String[]{"NONE", "Health"});

        this.liquidBounceHorizontalSpeed = new FloatProperty("LB-HSpeed", 180.0F, 1.0F, 180.0F, () -> rotations.getValue() == 4);
        this.liquidBounceVerticalSpeed = new FloatProperty("LB-VSpeed", 180.0F, 1.0F, 180.0F, () -> rotations.getValue() == 4);
        this.liquidBounceSmoothFactor = new FloatProperty("LB-Smooth", 0.5F, 0.1F, 1.0F, () -> rotations.getValue() == 4);
        this.liquidBouncePredict = new BooleanProperty("LB-Predict", true, () -> rotations.getValue() == 4);
        this.liquidBouncePredictSize = new FloatProperty("LB-PredictSize", 1.0F, 0.0F, 3.0F, () -> rotations.getValue() == 4 && liquidBouncePredict.getValue());
        this.liquidBounceRandomize = new BooleanProperty("LB-Randomize", true, () -> rotations.getValue() == 4);
        this.liquidBounceRandomizeRange = new FloatProperty("LB-RandomRange", 0.5F, 0.0F, 1.0F, () -> rotations.getValue() == 4 && liquidBounceRandomize.getValue());
        this.liquidBounceHorizontalSearch = new FloatProperty("LB-HSearch", 0.5F, 0.0F, 1.0F, () -> rotations.getValue() == 4);
        this.liquidBounceBodyPointMin = new FloatProperty("LB-BodyMin", 0.1F, 0.0F, 1.0F, () -> rotations.getValue() == 4);
        this.liquidBounceBodyPointMax = new FloatProperty("LB-BodyMax", 0.9F, 0.0F, 1.0F, () -> rotations.getValue() == 4);

        /* Shown only where they do something (2026-10-04, menu tidy): the
           Legit/Silent/LockView aim settings for those modes, LEGACY's two for
           LEGACY, the CPS range for Normal CPS. MaxTurnSpeed and TurnAccel
           also drive SmoothBack's turn back in every rotating mode. */
        java.util.function.BooleanSupplier plainAim = () -> rotations.getValue() >= 1 && rotations.getValue() <= 3;
        this.aimMode.when(plainAim);
        this.aimMinSpeed.when(plainAim);
        this.multipoint.when(plainAim);
        java.util.function.BooleanSupplier turnBack = () -> plainAim.getAsBoolean() && aimMode.getValue() == 1
                || rotations.getValue() != 0 && rotations.getValue() != 3 && smoothBack.getValue();
        this.aimMaxSpeed.when(turnBack);
        this.turnAccel.when(turnBack);
        this.smoothing.when(() -> plainAim.getAsBoolean() && aimMode.getValue() == 0);
        this.angleStep.when(() -> plainAim.getAsBoolean() && aimMode.getValue() == 0);
        this.minCPS.when(() -> cpsMode.getValue() == 0);
        this.maxCPS.when(() -> cpsMode.getValue() == 0);
        this.moveFix.when(() -> rotations.getValue() != 0);
    }

    private long getAttackDelay() {
        if (this.isBlocking) {
            return (long) (1000.0F / this.autoBlockCPS.getValue());
        } else {
            if (this.cpsMode.getValue() == 1) {
                /* The recording is four intervals to a click (each group of
                   four sums to 78-131ms, 102 on average: 9.8 CPS). Taken one
                   entry at a time, as until 2026-10-04, every "click" was a
                   quarter of one and the aura ran near 20 CPS. */
                long delay = 0L;
                for (int i = 0; i < 4; i++) {
                    delay += clickPattern[(patternIndex + i) % clickPattern.length];
                }
                return delay;
            } else {
                return 1000L / this.cps.random();
            }
        }
    }

    private boolean performAttack(float yaw, float pitch) {
        if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
            if (this.isPlayerBlocking() && this.autoBlock.getValue() != 1) {
                return false;
            } else if (this.attackDelayMS > 0L) {
                return false;
            } else if (this.otherActionsThisTick) {
                /* Not in the same tick as someone else's placement, dig or
                   inventory click: no hand does both (TickActions). */
                myau.management.TickActions.refuse();
                return false;
            } else {
                if ((this.rotations.getValue() == 4 || this.rotations.getValue() == 6)
                        && !(this.kbDisplacing && this.isBoxInAttackRange(this.target.getBox()))
                        && !this.aimedAt(yaw, pitch)) {
                    return false;
                }

                /* Added to what is left, not set: the aura can only act on a
                   tick, so a click due 12ms into the next tick has to come
                   12ms sooner after it. Setting it threw that remainder away
                   every time, and any delay of 51-100ms -- 10 to 19 CPS --
                   came out as exactly one hit every two ticks: a flat 10 CPS
                   whatever MinCPS/MaxCPS said (2026-10-04, simulated: 10-16
                   gave 10.0 with every gap 2 ticks; this gives 12.8, gaps of
                   1 and 2). The remainder is at most one tick: the countdown
                   above stops at zero or just under. */
                this.attackDelayMS += this.getAttackDelay();
                mc.thePlayer.swingItem();

                if (this.cpsMode.getValue() == 1) {
                    patternIndex = (patternIndex + 4) % clickPattern.length;
                }

                /* The swing above is a miss-swing if this refuses: a hand that is
                   not on the target swings at the air. It used to refuse only
                   when the box was out of range AND the ray missed, so with the
                   box in range a hit went out wherever the look was -- Silent's
                   capped turn often still behind a strafing target -- and the
                   server threw it away (Grim judges an attack by the look in the
                   movement packet after it). NONE was not checked at all. */
                if (this.rotations.getValue() != 4 && this.rotations.getValue() != 6
                        && !this.aimedAt(yaw, pitch)) {
                    return false;
                }
                ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
                /* Fired where vanilla fires it (MixinPlayerControllerMP: after
                   the slot sync, before the attack packet). The aura sends
                   its own C02 and never went through attackEntity, so ten
                   modules that react to attacks -- Criticals, MoreKB,
                   SprintReset, BlockHit, BackTrack, AntiBot's need-hit,
                   TimerRange, Hitflick, HitParticleEffects, Disabler -- did
                   nothing for any hit the aura made. */
                myau.event.EventManager.call(new myau.events.AttackEvent(this.target.getEntity()));
                PacketUtil.sendPacket(new C02PacketUseEntity(this.target.getEntity(), Action.ATTACK));
                if (mc.playerController.getCurrentGameType() != GameType.SPECTATOR) {
                    PlayerUtil.attackEntity(this.target.getEntity());
                }
                this.hitRegistered = true;
                return true;
            }
        } else {
            return false;
        }
    }

    /**
     * Whether a ray along this rotation -- the one this tick's movement packet
     * carries, sent right after the attack -- reaches the target's real hitbox
     * within attack range, or the eyes are already inside it. Vanilla's pick
     * does exactly this (box grown by getCollisionBorderSize, 0.1), and so do
     * Grim's hitbox check and Raven Alter's aura, which only points the mouse
     * at the target when that ray lands.
     *
     * The real box, not getBox(): that one is moved ahead by AimLead, which is
     * fine for aiming but not for judging a hit -- the server resolves the hit
     * against where the target was on this client's screen (Grim compensates
     * for latency), not against a predicted spot.
     */
    private boolean aimedAt(float yaw, float pitch) {
        EntityLivingBase entity = this.target.getEntity();
        double border = entity.getCollisionBorderSize();
        AxisAlignedBB real = entity.getEntityBoundingBox().expand(border, border, border);
        if (real.isVecInside(mc.thePlayer.getPositionEyes(1.0F))) {
            return true;
        }
        return RotationUtil.rayTrace(real, yaw, pitch, this.attackRange.getValue()) != null;
    }

    private void sendUseItem() {
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        this.startBlock(mc.thePlayer.getHeldItem());
    }

    private void startBlock(ItemStack itemStack) {
        PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(itemStack));
        mc.thePlayer.setItemInUse(itemStack, itemStack.getMaxItemUseDuration());
        this.blockingState = true;
    }

    private void stopBlock() {
        PacketUtil.sendPacket(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
        mc.thePlayer.stopUsingItem();
        this.blockingState = false;
    }

    /**
     * The right click after a hit, as vanilla sends it with the crosshair on
     * the entity: INTERACT_AT (where on the box), INTERACT, then the use.
     *
     * Traced against the real box within attack range, the way aimedAt is
     * (2026-10-04). It used to trace the AimLead-shifted box out to 8 blocks
     * and give the hit point relative to the entity's real position, so with
     * AimLead on the point could lie outside the hitbox, and the click could
     * name an entity no vanilla crosshair would be on. And when that trace
     * missed it sent nothing at all, so no block started; vanilla, with the
     * crosshair off the entity, just uses the item.
     */
    private void interactAttack(float yaw, float pitch) {
        if (this.target == null) {
            return;
        }
        EntityLivingBase entity = this.target.getEntity();
        double border = entity.getCollisionBorderSize();
        AxisAlignedBB real = entity.getEntityBoundingBox().expand(border, border, border);
        MovingObjectPosition mop = RotationUtil.rayTrace(real, yaw, pitch, this.attackRange.getValue());
        if (mop == null) {
            this.sendUseItem();
            return;
        }
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        PacketUtil.sendPacket(new C02PacketUseEntity(entity,
                new Vec3(mop.hitVec.xCoord - entity.posX, mop.hitVec.yCoord - entity.posY, mop.hitVec.zCoord - entity.posZ)));
        PacketUtil.sendPacket(new C02PacketUseEntity(entity, Action.INTERACT));
        PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
        mc.thePlayer.setItemInUse(mc.thePlayer.getHeldItem(), mc.thePlayer.getHeldItem().getMaxItemUseDuration());
        this.blockingState = true;
    }

    private boolean canAttack() {
        if (this.inventoryCheck.getValue() && mc.currentScreen instanceof GuiContainer) {
            return false;
        } else if (!(Boolean) this.weaponsOnly.getValue()
                || ItemUtil.hasRawUnbreakingEnchant()
                || this.allowTools.getValue() && ItemUtil.isHoldingTool()) {
            if (((IAccessorPlayerControllerMP) mc.playerController).getIsHittingBlock()) {
                return false;
            } else if ((ItemUtil.isEating() || ItemUtil.isUsingBow()) && PlayerUtil.isUsingItem()) {
                return false;
            } else {
                AutoHeal autoHeal = (AutoHeal) Myau.moduleManager.modules.get(AutoHeal.class);
                if (autoHeal.isEnabled() && autoHeal.isSwitching()) {
                    return false;
                } else {
                    BedNuker bedNuker = (BedNuker) Myau.moduleManager.modules.get(BedNuker.class);
                    if (bedNuker.isEnabled() && bedNuker.isReady()) {
                        return false;
                    } else if (Myau.moduleManager.modules.get(Scaffold.class).isEnabled()) {
                        return false;
                    } else if (this.requirePress.getValue()) {
                        return PlayerUtil.isAttacking();
                    } else {
                        return !this.allowMining.getValue() || mc.objectMouseOver == null
                                || !mc.objectMouseOver.typeOfHit.equals(MovingObjectType.BLOCK) || !PlayerUtil.isAttacking();
                    }
                }
            }
        } else {
            return false;
        }
    }

    private boolean canAutoBlock() {
        if (!ItemUtil.isHoldingSword()) {
            return false;
        } else {
            return !this.autoBlockRequirePress.getValue() || PlayerUtil.isUsingItem();
        }
    }

    private boolean hasValidTarget() {
        return mc.theWorld
                .loadedEntityList
                .stream()
                .anyMatch(
                        entity -> entity instanceof EntityLivingBase
                                && this.isValidTarget((EntityLivingBase) entity)
                                && this.isInBlockRange((EntityLivingBase) entity)
                );
    }

    private boolean isValidTarget(EntityLivingBase entityLivingBase) {
        /* Still in this world. Was loadedEntityList.contains -- a scan of every
           entity, for every entity, every tick (2026-10-04). A client entity
           taken out of the world is set dead; one from the world before a
           switch has the old world. */
        if (entityLivingBase.isDead || entityLivingBase.worldObj != mc.theWorld) {
            return false;
        } else if (entityLivingBase != mc.thePlayer && entityLivingBase != mc.thePlayer.ridingEntity) {
            if (entityLivingBase == mc.getRenderViewEntity() || entityLivingBase == mc.getRenderViewEntity().ridingEntity) {
                return false;
            } else if (entityLivingBase.deathTime > 0) {
                return false;
            } else if (RotationUtil.angleToEntity(entityLivingBase) > this.fov.getValue().floatValue()) {
                return false;
            } else if (!this.throughWalls.getValue() && RotationUtil.rayTrace(entityLivingBase) != null) {
                return false;
            } else if (entityLivingBase instanceof EntityOtherPlayerMP) {
                if (!this.players.getValue()) {
                    return false;
                } else if (!TargetFilter.accepts((EntityPlayer) entityLivingBase)) {
                    /* The shared filter, so this module and the latency
                       modules agree about who counts. With TargetFilter off it
                       answers exactly as the friend check below does. */
                    return false;
                } else if (TeamUtil.isFriend((EntityPlayer) entityLivingBase)) {
                    return false;
                } else {
                    return (!this.teams.getValue() || !TeamUtil.isSameTeam((EntityPlayer) entityLivingBase)) && (!this.botCheck.getValue() || !TeamUtil.isBot((EntityPlayer) entityLivingBase));
                }
            } else if (entityLivingBase instanceof EntityDragon || entityLivingBase instanceof EntityWither) {
                return this.bosses.getValue();
            } else if (!(entityLivingBase instanceof EntityMob) && !(entityLivingBase instanceof EntitySlime)) {
                if (entityLivingBase instanceof EntityAnimal
                        || entityLivingBase instanceof EntityBat
                        || entityLivingBase instanceof EntitySquid
                        || entityLivingBase instanceof EntityVillager) {
                    return this.animals.getValue();
                } else if (!(entityLivingBase instanceof EntityIronGolem)) {
                    return false;
                } else {
                    return this.golems.getValue() && (!this.teams.getValue() || !TeamUtil.hasTeamColor(entityLivingBase));
                }
            } else if (!(entityLivingBase instanceof EntitySilverfish)) {
                return this.mobs.getValue();
            } else {
                return this.silverfish.getValue() && (!this.teams.getValue() || !TeamUtil.hasTeamColor(entityLivingBase));
            }
        } else {
            return false;
        }
    }

    private boolean isInRange(EntityLivingBase entityLivingBase) {
        return this.isInBlockRange(entityLivingBase) || this.isInSwingRange(entityLivingBase) || this.isInAttackRange(entityLivingBase);
    }

    private boolean isInBlockRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.autoBlockRange.getValue();
    }

    private boolean isInSwingRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.swingRange.getValue();
    }

    private boolean isBoxInSwingRange(AxisAlignedBB axisAlignedBB) {
        return RotationUtil.distanceToBox(axisAlignedBB) <= (double) this.swingRange.getValue();
    }

    private boolean isInAttackRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.attackRange.getValue();
    }

    private boolean isBoxInAttackRange(AxisAlignedBB axisAlignedBB) {
        return RotationUtil.distanceToBox(axisAlignedBB) <= (double) this.attackRange.getValue();
    }

    /** Ticks of lead to apply, from the measured round trip. */
    public double leadTicks() {
        float strength = this.aimLead.getValue();
        if (strength <= 0.0F) {
            return 0.0;
        }
        int ping = myau.util.Ping.own();
        if (ping <= 0) {
            /* No usable reading means no idea how far ahead to aim, and
               guessing here aims at nothing in particular. */
            return 0.0;
        }
        return Math.min(10.0, ping / 50.0) * strength;
    }

    private boolean isPlayerTarget(EntityLivingBase entityLivingBase) {
        return entityLivingBase instanceof EntityPlayer && TeamUtil.isTarget((EntityPlayer) entityLivingBase);
    }

    /**
     * Whether an attack on the current target would be refused outright for
     * invulnerability. Absent or disabled, the module answers yes and nothing
     * about the existing behaviour changes.
     */
    private boolean invulnAllowsAttack() {
        InvulnTiming timing = InvulnTiming.instance();
        return timing == null || this.target == null
                || timing.allowAttack(this.target.getEntity());
    }

    /** A target that cannot be hurt is worth leaving early, switch delay or not. */
    private boolean invulnWantsSwitch() {
        InvulnTiming timing = InvulnTiming.instance();
        return timing != null && timing.isEnabled() && timing.redirect.getValue()
                && this.target != null && !timing.canDamage(this.target.getEntity());
    }

    private int findEmptySlot(int currentSlot) {
        for (int i = 0; i < 9; i++) {
            if (i != currentSlot && mc.thePlayer.inventory.getStackInSlot(i) == null) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (i != currentSlot) {
                ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
                if (stack != null && !stack.hasDisplayName()) {
                    return i;
                }
            }
        }
        return Math.floorMod(currentSlot - 1, 9);
    }

    private int findSwordSlot(int currentSlot) {
        for (int i = 0; i < 9; i++) {
            if (i != currentSlot) {
                ItemStack item = mc.thePlayer.inventory.getStackInSlot(i);
                if (item != null && item.getItem() instanceof ItemSword) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Another module has just sent a hit on the target this tick (Velocity's
     * knockback reduce, which runs before this module): no second attack in
     * the same tick. The countdown at the top of PRE takes 50 off, leaving
     * just over zero, so this tick is skipped and the next one is free.
     */
    public void noteExternalAttack() {
        this.attackDelayMS = Math.max(this.attackDelayMS, 51L);
    }

    public EntityLivingBase getTarget() {
        return this.target != null ? this.target.getEntity() : null;
    }

    public boolean isAttackAllowed() {
        Scaffold scaffold = (Scaffold) Myau.moduleManager.modules.get(Scaffold.class);
        if (scaffold.isEnabled()) {
            return false;
        } else if (!this.weaponsOnly.getValue()
                || ItemUtil.hasRawUnbreakingEnchant()
                || this.allowTools.getValue() && ItemUtil.isHoldingTool()) {
            return !this.requirePress.getValue() || KeyBindUtil.isKeyDown(mc.gameSettings.keyBindAttack.getKeyCode());
        } else {
            return false;
        }
    }

    public boolean shouldAutoBlock() {
        if (this.isPlayerBlocking() && this.isBlocking) {
            return !mc.thePlayer.isInWater() && !mc.thePlayer.isInLava() && (this.autoBlock.getValue() == 3
                    || this.autoBlock.getValue() == 4
                    || this.autoBlock.getValue() == 5
                    || this.autoBlock.getValue() == 6
                    || this.autoBlock.getValue() == 7);
        } else {
            return false;
        }
    }

    public boolean isBlocking() {
        return this.fakeBlockState && ItemUtil.isHoldingSword();
    }

    public boolean isPlayerBlocking() {
        return (mc.thePlayer.isUsingItem() || this.blockingState) && ItemUtil.isHoldingSword();
    }

    @EventTarget(value = Priority.LOW, whenDisabled = true)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() == EventType.POST) {
            /* After the movement packet: what the server last got. */
            this.lastSentYaw = event.getYaw();
            this.lastSentPitch = event.getPitch();
            this.sentKnown = true;
        }
        if (event.getType() == EventType.POST && this.blinkReset) {
            this.blinkReset = false;
            Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
            Myau.blinkManager.setBlinkState(true, BlinkModules.AUTO_BLOCK);
        }
        if (this.isEnabled() || this.wantsToDisable) {
            if (event.getType() == EventType.PRE) {
                if (this.wantsToDisable) {
                    /* Switched off with the sent look away from the camera:
                       turn it back first, from the look last sent. */
                    if (this.smoothBack.getValue() && !event.isRotated() && this.stepBack(event)) {
                        return;
                    }
                    this.wantsToDisable = false;
                    this.returning = false;
                    this.closing = true;
                    this.setEnabled(false);
                    this.closing = false;
                    return;
                }
                boolean rotatedBefore = event.isRotated();
                if (this.attackDelayMS > 0L) {
                    this.attackDelayMS -= 50L;
                }
                /* Set again below by the Advanced branch; never carried over. */
                this.kbDisplacing = false;
                /* Before this module's own blocking packets of the tick. */
                this.otherActionsThisTick = myau.management.TickActions.any(
                        myau.management.TickActions.USE | myau.management.TickActions.DIG
                                | myau.management.TickActions.INVENTORY);
                boolean attack = this.target != null && this.canAttack() && this.invulnAllowsAttack();
                boolean block = attack && this.canAutoBlock();
                if (!block) {
                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                    this.isBlocking = false;
                    this.fakeBlockState = false;
                    this.blockTick = 0;
                    this.hypixel3Asw = 0;
                }
                if (attack) {
                    boolean swap = false;
                    boolean blocked = false;
                    if (block) {
                        switch (this.autoBlock.getValue()) {
                            case 0:
                                if (PlayerUtil.isUsingItem()) {
                                    this.isBlocking = true;
                                    if (!this.isPlayerBlocking() && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                        swap = true;
                                    }
                                } else {
                                    this.isBlocking = false;
                                    if (this.isPlayerBlocking() && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                        this.stopBlock();
                                    }
                                }
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.fakeBlockState = false;
                                break;
                            case 1:
                                if (this.hasValidTarget()) {
                                    if (!this.isPlayerBlocking() && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                        swap = true;
                                    }
                                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = true;
                                    this.fakeBlockState = false;
                                } else {
                                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = false;
                                    this.fakeBlockState = false;
                                }
                                break;
                            case 2:
                                if (this.hasValidTarget()) {
                                    int item = ((IAccessorPlayerControllerMP) mc.playerController).getCurrentPlayerItem();
                                    if (Myau.playerStateManager.digging || Myau.playerStateManager.placing || mc.thePlayer.inventory.currentItem != item || this.isPlayerBlocking() && this.blockTick != 0 || this.attackDelayMS > 0L && this.attackDelayMS <= 50L) {
                                        this.blockTick = 0;
                                    } else {
                                        int slot = this.findEmptySlot(item);
                                        PacketUtil.sendPacket(new C09PacketHeldItemChange(slot));
                                        PacketUtil.sendPacket(new C09PacketHeldItemChange(item));
                                        swap = true;
                                        this.blockTick = 1;
                                    }
                                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = true;
                                    this.fakeBlockState = false;
                                } else {
                                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = false;
                                    this.fakeBlockState = false;
                                }
                                break;
                            case 3:
                                if (this.hasValidTarget()) {
                                    if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                        switch (this.blockTick) {
                                            case 0:
                                                if (!this.isPlayerBlocking()) {
                                                    swap = true;
                                                }
                                                blocked = true;
                                                this.blockTick = 1;
                                                break;
                                            case 1:
                                                if (this.isPlayerBlocking()) {
                                                    if (Myau.moduleManager.modules.get(NoSlow.class).isEnabled()) {
                                                        int randomSlot = new Random().nextInt(9);
                                                        while (randomSlot == mc.thePlayer.inventory.currentItem) {
                                                            randomSlot = new Random().nextInt(9);
                                                        }
                                                        PacketUtil.sendPacket(new C09PacketHeldItemChange(randomSlot));
                                                        PacketUtil.sendPacket(new C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem));
                                                    }
                                                    this.stopBlock();
                                                    attack = false;
                                                }
                                                if (this.attackDelayMS <= 50L) {
                                                    this.blockTick = 0;
                                                }
                                                break;
                                            default:
                                                this.blockTick = 0;
                                        }
                                    }
                                    this.isBlocking = true;
                                    this.fakeBlockState = true;
                                } else {
                                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = false;
                                    this.fakeBlockState = false;
                                }
                                break;
                            case 4:
                                if (this.hasValidTarget()) {
                                    if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                        switch (this.blockTick) {
                                            case 0:
                                                if (!this.isPlayerBlocking()) {
                                                    swap = true;
                                                }
                                                this.blinkReset = true;
                                                this.blockTick = 1;
                                                break;
                                            case 1:
                                                if (this.isPlayerBlocking()) {
                                                    this.stopBlock();
                                                    attack = false;
                                                }
                                                if (this.attackDelayMS <= 50L) {
                                                    this.blockTick = 0;
                                                }
                                                break;
                                            default:
                                                this.blockTick = 0;
                                        }
                                    }
                                    this.isBlocking = true;
                                    this.fakeBlockState = true;
                                } else {
                                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = false;
                                    this.fakeBlockState = false;
                                }
                                break;
                            case 5:
                                if (this.hasValidTarget()) {
                                    int item = ((IAccessorPlayerControllerMP) mc.playerController).getCurrentPlayerItem();
                                    if (mc.thePlayer.inventory.currentItem == item && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                        switch (this.blockTick) {
                                            case 0:
                                                if (!this.isPlayerBlocking()) {
                                                    swap = true;
                                                }
                                                this.blinkReset = true;
                                                this.blockTick = 1;
                                                break;
                                            case 1:
                                                if (this.isPlayerBlocking()) {
                                                    int slot = this.findEmptySlot(item);
                                                    PacketUtil.sendPacket(new C09PacketHeldItemChange(slot));
                                                    ((IAccessorPlayerControllerMP) mc.playerController).setCurrentPlayerItem(slot);
                                                    attack = false;
                                                }
                                                if (this.attackDelayMS <= 50L) {
                                                    this.blockTick = 0;
                                                }
                                                break;
                                            default:
                                                this.blockTick = 0;
                                        }
                                    }
                                    this.isBlocking = true;
                                    this.fakeBlockState = true;
                                } else {
                                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = false;
                                    this.fakeBlockState = false;
                                }
                                break;
                            case 6:
                                if (this.hasValidTarget()) {
                                    int item = ((IAccessorPlayerControllerMP) mc.playerController).getCurrentPlayerItem();
                                    if (mc.thePlayer.inventory.currentItem == item && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                        switch (this.blockTick) {
                                            case 0:
                                                int slot = this.findSwordSlot(item);
                                                if (slot != -1) {
                                                    if (!this.isPlayerBlocking()) {
                                                        swap = true;
                                                    }
                                                    this.blockTick = 1;
                                                }
                                                break;
                                            case 1:
                                                int swordsSlot = this.findSwordSlot(item);
                                                if (swordsSlot == -1) {
                                                    this.blockTick = 0;
                                                } else if (!this.isPlayerBlocking()) {
                                                    swap = true;
                                                } else if (this.attackDelayMS <= 50L) {
                                                    PacketUtil.sendPacket(new C09PacketHeldItemChange(swordsSlot));
                                                    ((IAccessorPlayerControllerMP) mc.playerController).setCurrentPlayerItem(swordsSlot);
                                                    this.startBlock(mc.thePlayer.inventory.getStackInSlot(swordsSlot));
                                                    attack = false;
                                                    this.blockTick = 0;
                                                }
                                                break;
                                            default:
                                                this.blockTick = 0;
                                        }
                                        Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                        this.isBlocking = true;
                                        this.fakeBlockState = true;
                                        break;
                                    }
                                }
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                                break;
                            case 7:
                                if (this.hasValidTarget()) {
                                    if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                        switch (this.blockTick) {
                                            case 0:
                                                if (!this.isPlayerBlocking()) {
                                                    swap = true;
                                                }
                                                this.blockTick = 1;
                                                break;
                                            case 1:
                                                if (this.isPlayerBlocking()) {
                                                    this.stopBlock();
                                                    attack = false;
                                                }
                                                if (this.attackDelayMS <= 50L) {
                                                    this.blockTick = 0;
                                                }
                                                break;
                                            default:
                                                this.blockTick = 0;
                                        }
                                    }
                                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = true;
                                    this.fakeBlockState = false;
                                } else {
                                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = false;
                                    this.fakeBlockState = false;
                                }
                                break;
                            case 9:
                                // Hypixel3 (ported from Cryptix KillAura): 3-tick blink-batched block -> attack -> block cycle
                                if (this.hasValidTarget()) {
                                    Myau.blinkManager.setBlinkState(true, BlinkModules.AUTO_BLOCK);
                                    if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                        switch (this.hypixel3Asw) {
                                            case 0:
                                                if (this.isPlayerBlocking()) {
                                                    this.stopBlock();
                                                }
                                                attack = false;
                                                this.hypixel3Asw = 1;
                                                break;
                                            case 1:
                                                if (this.isPlayerBlocking()) {
                                                    this.stopBlock();
                                                }
                                                attack = false;
                                                this.hypixel3Asw = 2;
                                                break;
                                            case 2:
                                                if (!this.isPlayerBlocking()) {
                                                    swap = true;
                                                }
                                                blocked = true;
                                                this.hypixel3Asw = 0;
                                                break;
                                            default:
                                                this.hypixel3Asw = 0;
                                        }
                                    } else {
                                        attack = false;
                                    }
                                    this.isBlocking = true;
                                    this.fakeBlockState = true;
                                } else {
                                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = false;
                                    this.fakeBlockState = false;
                                    this.hypixel3Asw = 0;
                                }
                                break;
                            case 8:
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = this.hasValidTarget();
                                if (PlayerUtil.isUsingItem() && !this.isPlayerBlocking() && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    swap = true;
                                }
                        }
                    }
                    boolean attacked = false;
                    if ((this.rotations.getValue() == 4 || this.rotations.getValue() == 6) && this.smoothBack.getValue() && (this.target == null || !this.isBoxInSwingRange(this.target.getBox()))) {
                        Rotation currentRot = this.serverRotation;
                        Rotation playerRot = new Rotation(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
                        if (Math.abs(MathHelper.wrapAngleTo180_float(currentRot.yaw - playerRot.yaw)) > 1.0F || Math.abs(MathHelper.wrapAngleTo180_float(currentRot.pitch - playerRot.pitch)) > 1.0F) {
                            this.isSmoothBacking = true;
                            Rotation nextRot = getSmoothBackRotation(currentRot, playerRot);

                            float[] fixed = RotationUtil.gcd(new float[]{nextRot.yaw, nextRot.pitch}, new float[]{currentRot.yaw, currentRot.pitch});
                            nextRot = new Rotation(fixed[0], fixed[1]);

                            this.serverRotation = nextRot;
                            event.setRotation(nextRot.yaw, nextRot.pitch, 1);
                            if (this.moveFix.getValue() != 0) {
                                event.setPervRotation(nextRot.yaw, 1);
                            }
                        } else {
                            this.isSmoothBacking = false;
                            this.serverRotation = playerRot;
                        }
                    }
                    if (this.target != null && this.isBoxInSwingRange(this.target.getBox())) {
                        if (this.rotations.getValue() == 4) {
                            Rotation currentRot = this.serverRotation;
                            if (Float.isNaN(currentRot.yaw) || Float.isNaN(currentRot.pitch)) {
                                currentRot = new Rotation(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
                            }

                            Rotation nextRot = updateLiquidBounceRotation(currentRot);

                            float[] fixed = RotationUtil.gcd(new float[]{nextRot.yaw, nextRot.pitch}, new float[]{currentRot.yaw, currentRot.pitch});
                            nextRot = new Rotation(fixed[0], fixed[1]);

                            this.serverRotation = nextRot;
                            updateRenderAimPosition(nextRot);
                            event.setRotation(nextRot.yaw, nextRot.pitch, 1);
                            if (this.moveFix.getValue() != 0) {
                                event.setPervRotation(nextRot.yaw, 1);
                            }
                            mc.thePlayer.rotationYawHead = nextRot.yaw;
                            mc.thePlayer.renderYawOffset = nextRot.yaw;
                            if (attack) {
                                attacked = this.performAttack(nextRot.yaw, nextRot.pitch);
                            }
                        } else if (this.rotations.getValue() == 6) {
                            /* Rise's Advanced model, from the rotation last sent. */
                            float[] next = this.advancedRotation(event.getYaw(), event.getPitch());
                            this.serverRotation = new Rotation(next[0], next[1]);
                            event.setRotation(next[0], next[1], 1);
                            if (this.moveFix.getValue() != 0) {
                                event.setPervRotation(next[0], 1);
                            }
                            mc.thePlayer.rotationYawHead = next[0];
                            mc.thePlayer.renderYawOffset = next[0];
                            if (attack) {
                                attacked = this.advancedAttack(next[0], next[1]);
                            }
                        } else if (this.rotations.getValue() == 5) {
                            // Raven BS rotations (ported 1:1 from Raven BS KillAura: getRotations -> fixRotation -> getRotationsSmoothed)
                            Rotation currentRot = this.serverRotation;
                            if (Float.isNaN(currentRot.yaw) || Float.isNaN(currentRot.pitch)) {
                                currentRot = new Rotation(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
                            }
                            float[] raw = this.getRavenRotations(this.target.getEntity());
                            float[] fixedRot = this.ravenFixRotation(raw[0], raw[1], currentRot.yaw, currentRot.pitch);
                            float[] smoothed = this.getRavenRotationsSmoothed(fixedRot, currentRot.yaw, currentRot.pitch);
                            float finalYaw = smoothed[0];
                            float finalPitch = smoothed[1];
                            if (finalPitch > 90) finalPitch = 90;
                            if (finalPitch < -90) finalPitch = -90;
                            this.serverRotation = new Rotation(finalYaw, finalPitch);
                            event.setRotation(finalYaw, finalPitch, 1);
                            mc.thePlayer.rotationYawHead = finalYaw;
                            mc.thePlayer.renderYawOffset = finalYaw;
                            if (this.moveFix.getValue() != 0) {
                                event.setPervRotation(finalYaw, 1);
                            }
                            if (attack) {
                                attacked = this.performAttack(event.getNewYaw(), event.getNewPitch());
                            }
                        } else if (this.rotations.getValue() >= 1) {
                            float[] rotations = this.aimMode.getValue() == 1
                                    ? this.stepTowards(event.getYaw(), event.getPitch())
                                    : RotationUtil.getRotationsToBox(
                                            this.target.getBox(),
                                            event.getYaw(),
                                            event.getPitch(),
                                            (float) this.angleStep.getValue() + RandomUtil.nextFloat(-5.0F, 5.0F),
                                            (float) this.smoothing.getValue() / 100.0F
                                    );
                            /* How far this tick's correction actually moved. It
                               falls to zero once the aim has caught up, which is
                               what the tremor below is scaled by: noise larger
                               than the smoother's one-degree dead zone can never
                               be corrected, so applying it at full strength on a
                               settled aim leaves it oscillating forever instead
                               of holding still. */
                            float stepYaw = MathHelper.wrapAngleTo180_float(rotations[0] - event.getYaw());
                            float stepPitch = rotations[1] - event.getPitch();
                            float activity = Math.min(1.0F,
                                    (Math.abs(stepYaw) + Math.abs(stepPitch)) / 6.0F);
                            this.aimPhase += 0.35F + RandomUtil.nextFloat(-0.05F, 0.05F);
                            float randomYaw = this.tremor(0.0F, 2.5F, activity);
                            float randomPitch = this.tremor(1.9F, 1.5F, activity);
                            float finalYaw = rotations[0] + randomYaw;
                            float finalPitch = rotations[1] + randomPitch;

                            // GCD FIX
                            float[] fixed = RotationUtil.gcd(new float[]{finalYaw, finalPitch}, new float[]{event.getYaw(), event.getPitch()});
                            finalYaw = fixed[0];
                            finalPitch = fixed[1];

                            if (finalPitch > 90) finalPitch = 90;
                            if (finalPitch < -90) finalPitch = -90;
                            event.setRotation(finalYaw, finalPitch, 1);
                            if (this.rotations.getValue() == 3) {
                                Myau.rotationManager.setRotation(finalYaw, finalPitch, 1, true);
                            } else {
                                mc.thePlayer.rotationYawHead = finalYaw;
                                mc.thePlayer.renderYawOffset = finalYaw;
                            }
                            if (this.moveFix.getValue() != 0 || this.rotations.getValue() == 3) {
                                event.setPervRotation(finalYaw, 1);
                            }
                            if (attack) {
                                attacked = this.performAttack(event.getNewYaw(), event.getNewPitch());
                            }
                        } else {
                            if (attack) {
                                attacked = this.performAttack(event.getNewYaw(), event.getNewPitch());
                            }
                        }
                    }
                    if (this.rotations.getValue() == 6
                            && (this.target == null || !this.isBoxInSwingRange(this.target.getBox()))) {
                        /* Out of reach: the next landing waits a reaction again. */
                        this.advancedAim.lostTarget();
                    }
                    if (swap) {
                        if (attacked) {
                            this.interactAttack(event.getNewYaw(), event.getNewPitch());
                        } else {
                            this.sendUseItem();
                        }
                    }
                    if (blocked) {
                        Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                        Myau.blinkManager.setBlinkState(true, BlinkModules.AUTO_BLOCK);
                    }
                }
                if (!rotatedBefore && event.isRotated()) {
                    /* LockView turns the camera itself: nothing to return. */
                    this.returning = this.rotations.getValue() != 3;
                } else if (this.returning) {
                    /* Another module took the look this tick, or it is back. */
                    if (rotatedBefore || !this.smoothBack.getValue() || !this.stepBack(event)) {
                        this.returning = false;
                    }
                }
            }
        }
    }

    // ── Raven BS rotation port (RotationUtils.getRotations(Entity, NONE) + getRotationsPredicated) ─────
    private float[] getRavenRotations(EntityLivingBase entity) {
        double posX = entity.posX;
        double posZ = entity.posZ;
        int ticks = this.ravenPredictTicks.getValue();
        if (ticks > 0) {
            double dX = entity.posX - entity.lastTickPosX;
            double dZ = entity.posZ - entity.lastTickPosZ;
            for (int i = 0; i < ticks; i++) {
                posX += dX;
                posZ += dZ;
            }
        }
        double deltaX = posX - mc.thePlayer.posX;
        double deltaZ = posZ - mc.thePlayer.posZ;
        double deltaY = entity.posY + entity.getEyeHeight() * 0.9 - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        float yaw = mc.thePlayer.rotationYaw + MathHelper.wrapAngleTo180_float(
                (float) (Math.atan2(deltaZ, deltaX) * 57.295780181884766) - 90.0f - mc.thePlayer.rotationYaw);
        float pitch = MathHelper.clamp_float(mc.thePlayer.rotationPitch + MathHelper.wrapAngleTo180_float(
                (float) (-(Math.atan2(deltaY, MathHelper.sqrt_double(deltaX * deltaX + deltaZ * deltaZ)) * 57.295780181884766)) - mc.thePlayer.rotationPitch) + 3.0f, -90.0f, 90.0f);
        return new float[]{yaw, pitch};
    }

    private int ravenRandInt(int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + this.random.nextInt(max - min + 1);
    }

    // ── Raven BS RotationUtils.fixRotation (sensitivity GCD, randomYawFactor=0) ─
    private float[] ravenFixRotation(float targetYaw, float targetPitch, float yaw, float pitch) {
        float n5 = targetYaw - yaw;
        float abs = Math.abs(n5);
        float n7 = targetPitch - pitch;
        float n8 = mc.gameSettings.mouseSensitivity * 0.6f + 0.2f;
        double n9 = n8 * n8 * n8 * 1.2;
        float n10 = (float) (Math.round((double) n5 / n9) * n9);
        float n11 = (float) (Math.round((double) n7 / n9) * n9);
        targetYaw = yaw + n10;
        targetPitch = pitch + n11;
        if (abs >= 1.0f) {
            int factor = this.ravenYawRandom.getValue();
            if (factor != 0) {
                int n13 = factor * 100 + ravenRandInt(-30, 30);
                targetYaw += ravenRandInt(-n13, n13) / 100.0f;
            }
        } else if (abs <= 0.04f) {
            targetYaw += (abs > 0.0f) ? 0.01f : -0.01f;
        }
        return new float[]{targetYaw, MathHelper.clamp_float(targetPitch, -90.0f, 90.0f)};
    }

    // ── Raven BS KillAura.getRotationsSmoothed (+ inlined unwrapYaw) ──────────
    private float[] getRavenRotationsSmoothed(float[] rotations, float serverYaw, float serverPitch) {
        float unwrappedYaw = serverYaw + ((((rotations[0] - serverYaw + 180f) % 360f) + 360f) % 360f - 180f);
        float deltaYaw = unwrappedYaw - serverYaw;
        float deltaPitch = rotations[1] - serverPitch;

        float yawSmoothing = (float) this.ravenSmoothing.getValue();
        float pitchSmoothing = yawSmoothing;

        float strafe = mc.thePlayer.moveStrafing;
        if (strafe < 0 && deltaYaw < 0 || strafe > 0 && deltaYaw > 0) {
            yawSmoothing = Math.max(1f, yawSmoothing / 2f);
        }

        float motionY = (float) mc.thePlayer.motionY;
        if (motionY > 0 && deltaPitch > 0 || motionY < 0 && deltaPitch < 0) {
            pitchSmoothing = Math.max(1f, pitchSmoothing / 2f);
        }

        serverYaw += deltaYaw / Math.max(1f, yawSmoothing);
        serverPitch += deltaPitch / Math.max(1f, pitchSmoothing);

        return new float[]{serverYaw, serverPitch};
    }

    private Rotation updateLiquidBounceRotation(Rotation current) {
        if (this.target == null || System.currentTimeMillis() - this.lastRotationUpdateTime < ROTATION_UPDATE_INTERVAL_MS) {
            return current;
        }

        this.lastRotationUpdateTime = System.currentTimeMillis();
        EntityLivingBase targetEntity = this.target.getEntity();

        AxisAlignedBB bb = targetEntity.getEntityBoundingBox().expand(targetEntity.getCollisionBorderSize(), targetEntity.getCollisionBorderSize(), targetEntity.getCollisionBorderSize());
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);

        Vec3 targetPoint = searchCenterPoint(bb, eyes);

        if (targetPoint == null) {
            targetPoint = new Vec3(
                    (bb.minX + bb.maxX) / 2.0,
                    (bb.minY + bb.maxY) / 2.0,
                    (bb.minZ + bb.maxZ) / 2.0
            );
        }

        if (this.liquidBouncePredict.getValue()) {
            targetPoint = applyPrediction(targetPoint, targetEntity);
        }

        double diffX = targetPoint.xCoord - eyes.xCoord;
        double diffY = targetPoint.yCoord - eyes.yCoord;
        double diffZ = targetPoint.zCoord - eyes.zCoord;
        double dist = MathHelper.sqrt_double(diffX * diffX + diffZ * diffZ);

        float targetYaw = (float) (Math.atan2(diffZ, diffX) * 180.0D / Math.PI) - 90.0F;
        float targetPitch = (float) (-(Math.atan2(diffY, dist) * 180.0D / Math.PI));

        targetYaw = MathHelper.wrapAngleTo180_float(targetYaw);
        targetPitch = MathHelper.wrapAngleTo180_float(targetPitch);

        return limitAngleChange(current, new Rotation(targetYaw, targetPitch));
    }

    private Vec3 searchCenterPoint(AxisAlignedBB bb, Vec3 eyes) {
        double scanRange = Math.max(this.attackRange.getValue(), this.swingRange.getValue());
        double attackRange = this.attackRange.getValue();
        double throughWallsRange = this.throughWalls.getValue() ? attackRange : 0;

        double minBody = this.liquidBounceBodyPointMin.getValue();
        double maxBody = this.liquidBounceBodyPointMax.getValue();
        double hMin = 0.0;
        double hMax = this.liquidBounceHorizontalSearch.getValue();

        Vec3 bestPoint = null;
        double bestScore = Double.MAX_VALUE;

        for (double x = hMin; x <= hMax; x += 0.25) {
            for (double y = minBody; y <= maxBody; y += 0.25) {
                for (double z = hMin; z <= hMax; z += 0.25) {
                    Vec3 point = new Vec3(
                            bb.minX + (bb.maxX - bb.minX) * x,
                            bb.minY + (bb.maxY - bb.minY) * y,
                            bb.minZ + (bb.maxZ - bb.minZ) * z
                    );

                    double distance = eyes.distanceTo(point);

                    if (distance > scanRange) {
                        continue;
                    }

                    boolean visible = isVisible(eyes, point);
                    if (!visible && distance > throughWallsRange) {
                        continue;
                    }

                    double score = distance;
                    if (distance > attackRange) {
                        score += 10.0;
                    }
                    if (!visible) {
                        score += 5.0;
                    }

                    if (this.liquidBounceRandomize.getValue()) {
                        score += random.nextDouble() * this.liquidBounceRandomizeRange.getValue() * 5.0;
                    }

                    if (score < bestScore) {
                        bestScore = score;
                        bestPoint = point;
                    }
                }
            }
        }

        return bestPoint;
    }

    private boolean isVisible(Vec3 from, Vec3 to) {
        if (this.throughWalls.getValue()) {
            return true;
        }
        return mc.theWorld.rayTraceBlocks(from, to, false, true, false) == null;
    }

    private Vec3 applyPrediction(Vec3 point, EntityLivingBase entity) {
        double predictX = entity.posX + entity.motionX * this.liquidBouncePredictSize.getValue();
        double predictY = entity.posY + entity.motionY * this.liquidBouncePredictSize.getValue() * 0.5;
        double predictZ = entity.posZ + entity.motionZ * this.liquidBouncePredictSize.getValue();

        double offsetX = point.xCoord - entity.posX;
        double offsetY = point.yCoord - entity.posY;
        double offsetZ = point.zCoord - entity.posZ;

        return new Vec3(predictX + offsetX, predictY + offsetY, predictZ + offsetZ);
    }

    private Rotation limitAngleChange(Rotation current, Rotation target) {
        float maxHorizontalChange = this.liquidBounceHorizontalSpeed.getValue();
        float maxVerticalChange = this.liquidBounceVerticalSpeed.getValue();
        float smoothFactor = this.liquidBounceSmoothFactor.getValue();

        float yawDiff = MathHelper.wrapAngleTo180_float(target.yaw - current.yaw);
        float pitchDiff = MathHelper.wrapAngleTo180_float(target.pitch - current.pitch);

        yawDiff = MathHelper.clamp_float(yawDiff, -maxHorizontalChange, maxHorizontalChange);
        pitchDiff = MathHelper.clamp_float(pitchDiff, -maxVerticalChange, maxVerticalChange);

        yawDiff *= smoothFactor;
        pitchDiff *= smoothFactor;

        return new Rotation(
                current.yaw + yawDiff,
                MathHelper.clamp_float(current.pitch + pitchDiff, -90.0f, 90.0f)
        );
    }

    private void updateRenderAimPosition(Rotation rotation) {
        if (rotation == null || mc.thePlayer == null) {
            this.currentAimVec = null;
            return;
        }
        float yawRad = rotation.yaw * (float) Math.PI / 180.0F;
        float pitchRad = rotation.pitch * (float) Math.PI / 180.0F;
        double lookX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);
        Vec3 eyePos = mc.thePlayer.getPositionEyes(1.0F);
        double renderDistance = Math.max(0.1, mc.thePlayer.getDistanceToEntity(this.target.getEntity()) * 0.5);
        renderDistance = Math.min(renderDistance, 4.0);
        this.currentAimVec = new Vec3(
                eyePos.xCoord + lookX * renderDistance,
                eyePos.yCoord + lookY * renderDistance,
                eyePos.zCoord + lookZ * renderDistance
        );
    }

    /**
     * One tick of turning the sent look back to the camera, from the look last
     * sent, through the shared engine (speed noise, a bowed path, easing in;
     * Rotations module) at this module's turn speeds. False once within a
     * degree: the camera's own look then goes out unchanged.
     */
    private boolean stepBack(UpdateEvent event) {
        float camYaw = mc.thePlayer.rotationYaw;
        float camPitch = mc.thePlayer.rotationPitch;
        float fromYaw = event.getYaw();
        float fromPitch = event.getPitch();
        if (Math.abs(MathHelper.wrapAngleTo180_float(camYaw - fromYaw)) <= 1.0F
                && Math.abs(camPitch - fromPitch) <= 1.0F) {
            return false;
        }
        float[] next = this.backEngine.step(fromYaw, fromPitch, camYaw, camPitch,
                Math.max(20.0F, this.aimMaxSpeed.getValue()), this.turnAccel.getValue(), 0, false,
                java.util.EnumSet.allOf(RotationEngine.Feature.class));
        this.serverRotation = new Rotation(next[0], next[1]);
        event.setRotation(next[0], next[1], 1);
        if (this.moveFix.getValue() != 0) {
            event.setPervRotation(next[0], 1);
        }
        mc.thePlayer.rotationYawHead = next[0];
        mc.thePlayer.renderYawOffset = next[0];
        return true;
    }

    private Rotation getSmoothBackRotation(Rotation current, Rotation target) {
        float yawDiff = MathHelper.wrapAngleTo180_float(target.yaw - current.yaw);
        float pitchDiff = MathHelper.wrapAngleTo180_float(target.pitch - current.pitch);
        float speed = Math.max(5.0f, Math.abs(yawDiff) * 0.3f);
        float yawStep = MathHelper.clamp_float(yawDiff, -speed, speed);
        float pitchStep = MathHelper.clamp_float(pitchDiff, -speed, speed);
        return new Rotation(current.yaw + yawStep, current.pitch + pitchStep);
    }

    @EventTarget(whenDisabled = true)
    public void onTick(TickEvent event) {
        if (this.isEnabled() || this.wantsToDisable) {
            switch (event.getType()) {
                case PRE:
                    if (this.target == null && !this.isSmoothBacking && !this.wantsToDisable) {
                        this.serverRotation = new Rotation(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
                        this.currentAimVec = null;
                    }
                    ArrayList<EntityLivingBase> validTargets = new ArrayList<>();
                    for (Entity entity : mc.theWorld.loadedEntityList) {
                        if (entity instanceof EntityLivingBase
                                && this.isValidTarget((EntityLivingBase) entity)
                                && this.isInRange((EntityLivingBase) entity)) {
                            validTargets.add((EntityLivingBase) entity);
                        }
                    }
                    /* A target still in swing range but out of attack range is
                       given up only for someone in attack range (2026-10-04).
                       It used to be given up every tick, which in Switch mode,
                       after a hit, moved to the next player every tick. Only
                       the switch delay running out advances Switch. */
                    boolean switchDue = this.timer.hasTimeElapsed(this.switchDelay.getValue().longValue());
                    boolean needsNewTarget = false;
                    if (this.target == null
                            || !this.isValidTarget(this.target.getEntity())
                            || !this.isBoxInSwingRange(this.target.getBox())
                            || !this.isBoxInAttackRange(this.target.getBox())
                            && validTargets.stream().anyMatch(this::isInAttackRange)
                            || switchDue
                            || this.invulnWantsSwitch()) {
                        needsNewTarget = true;
                        this.timer.reset();
                    }
                    if (validTargets.isEmpty()) {
                        this.target = null;
                        this.currentAimVec = null;
                    } else {
                        if (validTargets.stream().anyMatch(this::isInSwingRange)) {
                            validTargets.removeIf(entityLivingBase -> !this.isInSwingRange(entityLivingBase));
                        }
                        if (validTargets.stream().anyMatch(this::isInAttackRange)) {
                            validTargets.removeIf(entityLivingBase -> !this.isInAttackRange(entityLivingBase));
                        }
                        if (validTargets.stream().anyMatch(this::isPlayerTarget)) {
                            validTargets.removeIf(entityLivingBase -> !this.isPlayerTarget(entityLivingBase));
                        }
                        validTargets.sort(
                                (entityLivingBase1, entityLivingBase2) -> {
                                    /* Someone who can be hurt outranks every
                                       other consideration: the alternative is
                                       spending the swing on a refusal. */
                                    InvulnTiming timing = InvulnTiming.instance();
                                    if (timing != null && timing.isEnabled() && timing.redirect.getValue()) {
                                        int free1 = timing.canDamage(entityLivingBase1) ? 0 : 1;
                                        int free2 = timing.canDamage(entityLivingBase2) ? 0 : 1;
                                        if (free1 != free2) {
                                            return Integer.compare(free1, free2);
                                        }
                                    }
                                    int sortBase = 0;
                                    switch (this.sort.getValue()) {
                                        case 1:
                                            sortBase = Float.compare(TeamUtil.getHealthScore(entityLivingBase1), TeamUtil.getHealthScore(entityLivingBase2));
                                            break;
                                        case 2:
                                            sortBase = Integer.compare(entityLivingBase1.hurtResistantTime, entityLivingBase2.hurtResistantTime);
                                            break;
                                        case 3:
                                            sortBase = Float.compare(
                                                    RotationUtil.angleToEntity(entityLivingBase1),
                                                    RotationUtil.angleToEntity(entityLivingBase2)
                                            );
                                    }
                                    return sortBase != 0
                                            ? sortBase
                                            : Double.compare(RotationUtil.distanceToEntity(entityLivingBase1), RotationUtil.distanceToEntity(entityLivingBase2));
                                }
                        );
                        if (needsNewTarget) {
                            if (this.mode.getValue() == 1 && this.hitRegistered && switchDue) {
                                this.hitRegistered = false;
                                this.switchTick++;
                            }
                            if (this.mode.getValue() == 0 || this.switchTick >= validTargets.size()) {
                                this.switchTick = 0;
                            }
                            EntityLivingBase newTarget = validTargets.get(this.switchTick);
                            boolean targetChanged = this.target == null || this.target.getEntity() != newTarget;
                            if (targetChanged) {
                                InvulnTiming timing = InvulnTiming.instance();
                                /* Only counted when the old target was the one
                                   that could not be hurt and the new one can --
                                   an ordinary switch is not this module's doing. */
                                if (timing != null && timing.isEnabled() && this.target != null
                                        && !timing.canDamage(this.target.getEntity())
                                        && timing.canDamage(newTarget)) {
                                    timing.noteRedirect();
                                }
                                this.target = new AttackData(newTarget, this);
                            }
                        }
                    }
                    if (this.target != null) {
                        this.target.update();
                    }
                    break;
                case POST:
                    if (this.isPlayerBlocking() && !mc.thePlayer.isBlocking()) {
                        mc.thePlayer.setItemInUse(mc.thePlayer.getHeldItem(), mc.thePlayer.getHeldItem().getMaxItemUseDuration());
                    }
            }
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onPacket(PacketEvent event) {
        if (this.isEnabled() && !event.isCancelled()) {
            if (event.getPacket() instanceof C07PacketPlayerDigging) {
                C07PacketPlayerDigging packet = (C07PacketPlayerDigging) event.getPacket();
                if (packet.getStatus() == C07PacketPlayerDigging.Action.RELEASE_USE_ITEM) {
                    this.blockingState = false;
                }
            }
            if (event.getPacket() instanceof C09PacketHeldItemChange) {
                this.blockingState = false;
                if (this.isBlocking) {
                    mc.thePlayer.stopUsingItem();
                }
            }
            if (this.debugLog.getValue() == 1 && this.isAttackAllowed()) {
                if (event.getPacket() instanceof S06PacketUpdateHealth) {
                    float packet = ((S06PacketUpdateHealth) event.getPacket()).getHealth() - mc.thePlayer.getHealth();
                    if (packet != 0.0F && this.lastTickProcessed != mc.thePlayer.ticksExisted) {
                        this.lastTickProcessed = mc.thePlayer.ticksExisted;
                        ChatUtil.sendFormatted(
                                String.format(
                                        "%sHealth: %s&l%s&r (&otick: %d&r)&r",
                                        Myau.clientName,
                                        packet > 0.0F ? "&a" : "&c",
                                        df.format(packet),
                                        mc.thePlayer.ticksExisted
                                )
                        );
                    }
                }
                if (event.getPacket() instanceof S1CPacketEntityMetadata) {
                    S1CPacketEntityMetadata packet = (S1CPacketEntityMetadata) event.getPacket();
                    if (packet.getEntityId() == mc.thePlayer.getEntityId()) {
                        for (WatchableObject watchableObject : packet.func_149376_c()) {
                            if (watchableObject.getDataValueId() == 6) {
                                float diff = (Float) watchableObject.getObject() - mc.thePlayer.getHealth();
                                if (diff != 0.0F && this.lastTickProcessed != mc.thePlayer.ticksExisted) {
                                    this.lastTickProcessed = mc.thePlayer.ticksExisted;
                                    ChatUtil.sendFormatted(
                                            String.format(
                                                    "%sHealth: %s&l%s&r (&otick: %d&r)&r",
                                                    Myau.clientName,
                                                    diff > 0.0F ? "&a" : "&c",
                                                    df.format(diff),
                                                    mc.thePlayer.ticksExisted
                                            )
                                    );
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @EventTarget(whenDisabled = true)
    public void onMove(MoveInputEvent event) {
        if (this.isEnabled() || this.wantsToDisable) {
            boolean isSilent = this.rotations.getValue() == 2;
            boolean isLiquidBounce = this.rotations.getValue() == 4;
            boolean isRavenBS = this.rotations.getValue() == 5;
            boolean isAdvanced = this.rotations.getValue() == 6;
            if (this.moveFix.getValue() != 0 && (isSilent || isLiquidBounce || isRavenBS || isAdvanced)) {
                if (RotationState.isActived() && RotationState.getPriority() == 1.0F && MoveUtil.isForwardPressed()) {
                    MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
                }
            }
            if (this.shouldAutoBlock()) {
                mc.thePlayer.movementInput.jump = false;
            }
        }
    }

    @EventTarget(whenDisabled = true)
    public void onRender3D(Render3DEvent event) {
        try {
            if (mc.getRenderManager() == null || mc.getRenderViewEntity() == null) return;
            IAccessorRenderManager renderManagerAccessor = (IAccessorRenderManager) mc.getRenderManager();
            double viewerX = renderManagerAccessor.getRenderPosX();
            double viewerY = renderManagerAccessor.getRenderPosY();
            double viewerZ = renderManagerAccessor.getRenderPosZ();
            if (this.isEnabled() && target != null) {
                if (this.showTarget.getValue() != 0
                        && TeamUtil.isEntityLoaded(this.target.getEntity())
                        && this.isAttackAllowed()) {
                    Color color = new Color(-1);
                    if (this.showTarget.getValue() == 1) {
                        if (this.target.getEntity().hurtTime > 0) {
                            color = new Color(16733525);
                        } else {
                            color = new Color(5635925);
                        }
                    }
                    ThemeStyle themed = ThemeStyle.active(TargetColors.class);
                    if (themed != null) {
                        color = themed.color(color, this.target.getEntity(), -1);
                    }
                    RenderUtil.enableRenderState();
                    RenderUtil.drawEntityBox(this.target.getEntity(), color.getRed(), color.getGreen(), color.getBlue());
                    RenderUtil.disableRenderState();
                }
            }
            if ((this.isEnabled() || this.wantsToDisable) && this.visualizeAim.getValue() && this.currentAimVec != null) {
                double x = this.currentAimVec.xCoord;
                double y = this.currentAimVec.yCoord;
                double z = this.currentAimVec.zCoord;
                x -= viewerX;
                y -= viewerY;
                z -= viewerZ;
                GlStateManager.pushMatrix();
                GlStateManager.enableBlend();
                GlStateManager.disableDepth();
                GlStateManager.disableTexture2D();
                GlStateManager.disableLighting();
                GlStateManager.depthMask(false);
                Color inner = new Color(0, 255, 255, 204);
                Color outer = new Color(0, 255, 0, 128);
                float aimWidth = 2.0F;
                ThemeStyle themed = ThemeStyle.active(TargetColors.class);
                if (themed != null) {
                    /* TargetColors: the aim point in the line colour, its cage a step along the cycle. */
                    inner = themed.line(inner, null, 0);
                    outer = themed.line(outer, null, 3);
                    aimWidth = themed.width();
                }
                GlStateManager.color(inner.getRed() / 255.0F, inner.getGreen() / 255.0F, inner.getBlue() / 255.0F,
                        inner.getAlpha() / 255.0F);
                double size = 0.08;
                AxisAlignedBB bb = new AxisAlignedBB(x - size, y - size, z - size, x + size, y + size, z + size);
                RenderGlobal.drawSelectionBoundingBox(bb);
                GlStateManager.color(outer.getRed() / 255.0F, outer.getGreen() / 255.0F, outer.getBlue() / 255.0F,
                        outer.getAlpha() / 255.0F);
                GL11.glLineWidth(aimWidth);
                RenderGlobal.drawOutlinedBoundingBox(bb, outer.getRed(), outer.getGreen(), outer.getBlue(), outer.getAlpha());
                GlStateManager.depthMask(true);
                GlStateManager.enableTexture2D();
                GlStateManager.enableDepth();
                GlStateManager.disableBlend();
                GlStateManager.popMatrix();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @EventTarget(whenDisabled = true)
    public void onLeftClick(LeftClickMouseEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        } else {
            if (this.isEnabled() && this.target != null && this.canAttack()) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget(whenDisabled = true)
    public void onRightClick(RightClickMouseEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        } else {
            if (this.isEnabled() && this.target != null && this.canAttack()) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget(whenDisabled = true)
    public void onHitBlock(HitBlockEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        } else {
            if (this.isEnabled() && this.target != null && this.canAttack()) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget(whenDisabled = true)
    public void onCancelUse(CancelUseEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        }
    }

    @Override
    public void onEnabled() {
        if (!this.wantsToDisable) {
            this.target = null;
            this.switchTick = 0;
            this.hitRegistered = false;
            this.attackDelayMS = 0L;
            this.blockTick = 0;
            /* Config.load enables modules at startup, before there is a
               player. The NPE here used to abort the rest of the config load,
               so every module after this one lost its settings. onTick
               re-seeds the rotation once a player exists. */
            if (mc.thePlayer != null) {
                this.serverRotation = new Rotation(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
            }
            this.currentAimVec = null;
            this.isSmoothBacking = false;
            this.lastRotationUpdateTime = 0;
            this.patternIndex = 0;
            this.advancedAim.reset();
            this.returning = false;
            this.backEngine.reset();
        }
    }

    // ── Rotations "Advanced" (Rise 6.9.5's model, util/AdvancedAim) ─────

    private boolean isAdvanced() {
        return this.rotations != null && this.rotations.getValue() == 6;
    }

    private myau.util.AdvancedAim.Settings advancedSettings() {
        myau.util.AdvancedAim.Settings s = this.advancedSettings;
        s.gravity = this.advGravity.getValue();
        s.wind = this.advWind.getValue();
        s.dampedDistance = this.advDampedDistance.getValue();
        s.maxStep = this.advMaxStep.getValue();
        s.overshootChance = this.advOvershootChance.getValue();
        s.overshootScale = this.advOvershootScale.getValue();
        s.overshootMax = this.advOvershootMax.getValue();
        s.gaussian = this.advGaussian.getValue();
        s.accuracy = this.advAccuracy.getValue();
        s.minStep = this.advMinStep.getValue();
        s.prediction = this.advPrediction.getValue();
        s.deadzone = this.advDeadzone.getValue();
        s.anchor = this.advAnchor.getValue();
        s.holdTicks = this.advHoldTicks.getValue();
        s.cruiseFloor = this.advCruiseFloor.getValue();
        s.paceJitter = this.advPaceJitter.getValue();
        s.burstChance = this.advBurstChance.getValue();
        s.burstStrength = this.advBurstStrength.getValue();
        s.flickGuard = this.advFlickGuard.getValue();
        s.flickMax = this.advFlickMax.getValue();
        s.aimReaction = this.advAimReaction.getValue();
        s.aimReactionJitter = this.advAimReactionJitter.getValue();
        s.triggerReaction = this.advTriggerReaction.getValue();
        s.triggerReactionJitter = this.advTriggerReactionJitter.getValue();
        return s;
    }

    /**
     * One tick of the Advanced model: where to look (a predicted point the
     * eye follows after a reaction time), what to turn toward (the nearest
     * point of the box while it is in reach, as Rise picks it), and the hand's
     * step there, on the mouse grid.
     */
    private float[] advancedRotation(float yaw, float pitch) {
        myau.util.AdvancedAim.Settings s = this.advancedSettings();
        EntityLivingBase entity = this.target.getEntity();
        AxisAlignedBB box = this.target.getBox();
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        this.advancedAim.observeMotion(
                new Vec3(entity.posX - entity.lastTickPosX, entity.posY - entity.lastTickPosY, entity.posZ - entity.lastTickPosZ),
                new Vec3(mc.thePlayer.posX - mc.thePlayer.lastTickPosX, mc.thePlayer.posY - mc.thePlayer.lastTickPosY,
                        mc.thePlayer.posZ - mc.thePlayer.lastTickPosZ));
        Vec3 wanted = this.advancedAim.predictedPoint(entity.posX, entity.posY, entity.posZ, entity.height,
                entity.getEntityBoundingBox(), eyes, s);
        Vec3 aim = this.advancedAim.aimPoint(wanted, entity.getEntityBoundingBox(),
                mc.thePlayer.getDistanceToEntity(entity), System.currentTimeMillis(), s);
        Vec3 point = this.advancedPick(box, aim, eyes, yaw);
        float[] want = RotationEngine.rotationsTo(eyes, point, yaw);
        this.kbDisplacing = false;
        if (this.kbDisplace.getValue() != 0 && this.attackDelayMS <= 50L
                && myau.util.KnockbackPlanner.hasKnockbackSource(mc.thePlayer,
                        net.minecraft.enchantment.EnchantmentHelper.getKnockbackModifier(mc.thePlayer))
                && !myau.util.KnockbackPlanner.fallingCrit(mc.thePlayer)) {
            myau.util.KnockbackPlanner.Plan plan = myau.util.KnockbackPlanner.plan(mc.theWorld, entity);
            if (plan != null) {
                float planYaw = yaw + MathHelper.wrapAngleTo180_float(plan.yaw - yaw);
                if (this.kbDisplace.getValue() == 2) {
                    want[0] = planYaw;
                    this.kbDisplacing = true;
                } else {
                    float safe = this.hittableYawToward(box, eyes, yaw, want[1], planYaw);
                    if (!Float.isNaN(safe)) {
                        want[0] = safe;
                    }
                }
            }
        }
        boolean onTarget = RotationUtil.rayTrace(box, yaw, pitch, this.attackRange.getValue() + 0.15) != null;
        float[] step = this.advancedAim.step(yaw, pitch, want[0], want[1], entity.getEntityId(), onTarget, s);
        return RotationUtil.gcd(step, new float[]{yaw, pitch});
    }

    /**
     * The point to turn toward: the box's nearest point while a look at it
     * lands, else the landing point nearest the eyes (closest to the aim
     * point on a tie), else the aim point itself.
     */
    private Vec3 advancedPick(AxisAlignedBB box, Vec3 aim, Vec3 eyes, float nearYaw) {
        double range = this.attackRange.getValue();
        Vec3 nearest = myau.util.AdvancedAim.nearestInside(box, eyes);
        float[] toNearest = RotationEngine.rotationsTo(eyes, nearest, nearYaw);
        if (RotationUtil.rayTrace(box, toNearest[0], toNearest[1], range) != null) {
            return nearest;
        }
        float[] toAim = RotationEngine.rotationsTo(eyes, aim, nearYaw);
        Vec3 best = null;
        double bestScore = Double.MAX_VALUE;
        for (Vec3 candidate : myau.util.AdvancedAim.candidates(box, aim, eyes)) {
            float[] look = RotationEngine.rotationsTo(eyes, candidate, nearYaw);
            if (RotationUtil.rayTrace(box, look[0], look[1], range) == null) {
                continue;
            }
            double score = candidate.squareDistanceTo(eyes)
                    + (Math.abs(MathHelper.wrapAngleTo180_float(look[0] - toAim[0])) + Math.abs(look[1] - toAim[1])) * 1.0E-5;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best == null ? aim : best;
    }

    /**
     * SAFE knockback steering: the yaw nearest planYaw that still looks into
     * the box -- the box's yaw span from the eyes, shrunk by 15%, with the
     * plan clamped into it. NaN when no such look lands.
     */
    private float hittableYawToward(AxisAlignedBB box, Vec3 eyes, float nearYaw, float pitch, float planYaw) {
        float centre = RotationEngine.rotationsTo(eyes,
                new Vec3((box.minX + box.maxX) / 2.0, eyes.yCoord, (box.minZ + box.maxZ) / 2.0), nearYaw)[0];
        float low = 0.0F;
        float high = 0.0F;
        double[] xs = {box.minX, box.maxX};
        double[] zs = {box.minZ, box.maxZ};
        for (double x : xs) {
            for (double z : zs) {
                float corner = MathHelper.wrapAngleTo180_float(
                        RotationEngine.rotationsTo(eyes, new Vec3(x, eyes.yCoord, z), nearYaw)[0] - centre);
                low = Math.min(low, corner);
                high = Math.max(high, corner);
            }
        }
        float offset = MathHelper.clamp_float(MathHelper.wrapAngleTo180_float(planYaw - centre), low * 0.85F, high * 0.85F);
        float yaw = centre + offset;
        return RotationUtil.rayTrace(box, yaw, pitch, this.attackRange.getValue()) != null ? yaw : Float.NaN;
    }

    /**
     * The hit, after a reaction time from the look landing on the target. A
     * click that is due while the look is off it is swung at the air.
     */
    private boolean advancedAttack(float yaw, float pitch) {
        myau.util.AdvancedAim.Settings s = this.advancedSettings();
        boolean onTarget = RotationUtil.rayTrace(this.target.getBox(), yaw, pitch, this.attackRange.getValue()) != null
                || this.kbDisplacing && this.isBoxInAttackRange(this.target.getBox());
        if (this.advancedAim.triggerReady(this.target.getEntity().getEntityId(), onTarget,
                System.currentTimeMillis(), s)) {
            return this.performAttack(yaw, pitch);
        }
        if (!onTarget && this.advSwing.getValue() && this.attackDelayMS <= 0L
                && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing
                && !this.isPlayerBlocking()) {
            mc.thePlayer.swingItem();
            this.attackDelayMS += this.getAttackDelay();
        }
        return false;
    }

    @Override
    public void onDisabled() {
        /* Released before the smooth-back branch, not after it. That branch
           returns early, and it used to skip all of this: the autoblock blink
           kept holding movement for the whole turn back, and a pending
           blinkReset switched the blink on again after the module was off. */
        this.blinkReset = false;
        Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
        this.blockingState = false;
        this.isBlocking = false;
        this.fakeBlockState = false;
        if (!this.closing && this.rotations.getValue() != 0 && this.rotations.getValue() != 3 && this.smoothBack.getValue()
                && mc.thePlayer != null && this.sentKnown
                && (Math.abs(MathHelper.wrapAngleTo180_float(this.lastSentYaw - mc.thePlayer.rotationYaw)) > 1.0F
                || Math.abs(this.lastSentPitch - mc.thePlayer.rotationPitch) > 1.0F)) {
            this.wantsToDisable = true;
            this.setEnabled(true);
            return;
        }
        this.returning = false;
        this.wantsToDisable = false;
        this.currentAimVec = null;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }

    @Getter
    public static class AttackData {
        private final EntityLivingBase entity;
        private final KillAura killAura;
        private AxisAlignedBB box;
        private double x;
        private double y;
        private double z;

        /* Smoothed blocks per tick. Raw frame-to-frame deltas are useless for
           this: another player's position arrives in packets and jumps, so the
           instantaneous delta alternates between most of a block and nothing.
           An exponential average over a few ticks is what a human's aim is
           actually tracking. */
        private double velX;
        private double velY;
        private double velZ;
        private boolean sampled;

        public AttackData(EntityLivingBase entityLivingBase, KillAura killAura) {
            this.entity = entityLivingBase;
            this.killAura = killAura;
            update();
        }

        public void update() {
            if (this.sampled) {
                double dx = entity.posX - this.x;
                double dy = entity.posY - this.y;
                double dz = entity.posZ - this.z;
                /* A jump of more than two blocks in a tick is a teleport or a
                   correction, not movement, and feeding it in would throw the
                   lead across the map. */
                if (Math.abs(dx) < 2.0 && Math.abs(dy) < 2.0 && Math.abs(dz) < 2.0) {
                    this.velX += (dx - this.velX) * 0.35;
                    this.velY += (dy - this.velY) * 0.35;
                    this.velZ += (dz - this.velZ) * 0.35;
                }
            }
            this.sampled = true;

            double collisionBorderSize = entity.getCollisionBorderSize();
            this.box = entity.getEntityBoundingBox().expand(collisionBorderSize, collisionBorderSize, collisionBorderSize);

            /* Aim where the target will be when the packet lands, not where it
               was when the packet that said so left.
               
               At 230ms the server resolves an attack against a position about
               four or five ticks ahead of the one this client is looking at, so
               aiming at what is on screen is aiming at where the opponent was.
               Against someone strafing at full speed that is most of a block --
               which is most of a hitbox, and most of the misses.
               
               The offset is applied to the box itself rather than to the
               rotation, so the range check, the raytrace and the aim all agree
               about where the target is. Offsetting only the rotation would aim
               at a point the range check then rejects. */
            double lead = this.killAura == null ? 0.0 : this.killAura.leadTicks();
            if (lead > 0.0) {
                double cap = this.killAura.aimLeadCap.getValue();
                double offsetX = clampLead(this.velX * lead, cap);
                double offsetY = clampLead(this.velY * lead, cap);
                double offsetZ = clampLead(this.velZ * lead, cap);
                this.box = this.box.offset(offsetX, offsetY, offsetZ);
            }

            this.x = entity.posX;
            this.y = entity.posY;
            this.z = entity.posZ;
        }

        private static double clampLead(double value, double cap) {
            return Math.max(-cap, Math.min(cap, value));
        }
    }
    /**
     * One tick of travel towards the target, measured in degrees rather than
     * in a fraction of what is left.
     *
     * The older path multiplies the remaining angle by a constant each tick,
     * which halves the error forever without ever removing it -- and the
     * smoother it feeds treats anything under a degree as zero, so the aim
     * parks just short of the target and stays there. A fixed number of
     * degrees per tick has a finish line: whatever the distance, it arrives,
     * and how long that takes can be predicted, which is what matters when the
     * server is a third of a second behind.
     *
     * Speed is shaped by distance rather than held constant so the turn does
     * not start and stop abruptly: near maximum while sweeping across, easing
     * down to the minimum over the last stretch. The minimum is a floor, never
     * zero, which is what guarantees arrival.
     */
    private final RotationEngine aimEngine = new RotationEngine();

    private float[] stepTowards(float currentYaw, float currentPitch) {
        float[] full;
        if (this.multipoint.getValue()) {
            /* The same vertical band the centre aim uses (5-75% of the
               height), and a margin off the sides for the target to move in. */
            AxisAlignedBB aim = RotationEngine.aimBox(this.target.getBox(), 0.15, 0.05, 0.75);
            Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
            full = RotationEngine.rotationsTo(eyes,
                    RotationEngine.nearestOnBox(aim, eyes, currentYaw, currentPitch), currentYaw);
        } else {
            full = RotationUtil.getRotationsToBox(
                    this.target.getBox(), currentYaw, currentPitch, 180.0F, 0.0F);
        }

        float deltaYaw = MathHelper.wrapAngleTo180_float(full[0] - currentYaw);
        float deltaPitch = full[1] - currentPitch;
        float distance = (float) Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
        if (distance < 0.05F) {
            return new float[]{full[0], full[1]};
        }

        float min = Math.min(this.aimMinSpeed.getValue(), this.aimMaxSpeed.getValue());
        float max = Math.max(this.aimMinSpeed.getValue(), this.aimMaxSpeed.getValue());

        // Smoothstep over the first 60 degrees: 0 at the target, 1 far away.
        float t = Math.min(1.0F, distance / 60.0F);
        float shaped = t * t * (3.0F - 2.0F * t);
        float step = min + (max - min) * shaped;
        step *= 1.0F + RandomUtil.nextFloat(-0.08F, 0.08F);

        /* The step, capped by how fast the turn may grow, on the mouse grid. */
        return this.aimEngine.step(currentYaw, currentPitch, full[0], full[1], step,
                this.turnAccel.getValue(), 0, false);
    }

    /** Advancing phase for the aim tremor below. */
    private float aimPhase = 0.0F;

    /**
     * Hand tremor rather than white noise.
     *
     * Independent random offsets each tick cannot be tracked or corrected --
     * they simply move the aim somewhere new every time. Two sines of
     * different periods produce a wandering offset that changes slowly enough
     * for the smoother to follow, which is both what a real hand does and what
     * lets the aim settle. Amplitude is scaled by how far the correction is
     * still travelling, so a converged aim carries almost none.
     */
    private float tremor(float phaseOffset, float amplitude, float activity) {
        double p = this.aimPhase + phaseOffset;
        double wave = Math.sin(p) + Math.sin(p * 2.7 + 1.3) * 0.3;
        return (float) (wave * amplitude * activity);
    }
}
