package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): jumps when hurt to reduce incoming knockback.
// STANDARD watches hurtTime, POLAR predicts the attacker's approach.
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.KnockbackEvent;
import myau.events.LoadWorldEvent;
import myau.events.MoveInputEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.util.KeyBindUtil;
import myau.util.RandomUtil;
import myau.util.RotationUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.potion.Potion;

public class JumpReset extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"STANDARD", "POLAR"});
    public final PercentProperty chance = new PercentProperty("chance", 100);
    public final FloatProperty range = new FloatProperty("range", 5.0F, 0.0F, 10.0F);
    public final IntProperty fov = new IntProperty("fov", 180, 0, 360);
    public final BooleanProperty mouseDown = new BooleanProperty("mouse-down", false);
    public final BooleanProperty movingForward = new BooleanProperty("moving-forward", true);
    public final BooleanProperty aimingOnPlayer = new BooleanProperty("aiming-on-player", true);
    public final BooleanProperty requireSprint = new BooleanProperty("require-sprint", true);
    public final FloatProperty exitRange = new FloatProperty("exit-range", 3.0F, 2.0F, 6.0F, () -> this.mode.getValue() == 1);
    public final IntProperty predictionTicks = new IntProperty("prediction-ticks", 2, 0, 5, () -> this.mode.getValue() == 1);
    public final IntProperty minInterval = new IntProperty("min-interval", 0, 0, 20);
    public final BooleanProperty reduce = new BooleanProperty("reduce", false);
    public final ModeProperty reduceMode = new ModeProperty("reduce-mode", 0, new String[]{"Linear", "Smooth"}, this.reduce::getValue);
    public final FloatProperty reduceFactor = new FloatProperty("reduce-factor", 0.6F, 0.0F, 1.0F, this.reduce::getValue);
    public final ModeProperty pauseWhen = new ModeProperty("pause-when", 2, new String[]{"Flag", "NoCombat", "Both", "None"});
    public final IntProperty flagTicks = new IntProperty("flag-ticks", 3, 0, 20, () -> this.pauseWhen.getValue() == 0 || this.pauseWhen.getValue() == 2);
    public final IntProperty combatTicks = new IntProperty("combat-ticks", 100, 0, 400, () -> this.pauseWhen.getValue() == 1 || this.pauseWhen.getValue() == 2);

    private boolean setJump;
    private boolean ignoreNext;
    private int lastHurtTime;
    private double lastFallDistance;
    private int ticksSinceFlag = 9999;
    private int ticksSinceAttack = 9999;
    private int ticksSinceReset = 9999;

    public JumpReset() {
        super("JumpReset", false, false, "Jumps when hurt to reduce incoming knockback.");
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (event.getType() == EventType.PRE) {
            if (this.mode.getValue() == 0) {
                this.tickStandard();
            }
        } else if (event.getType() == EventType.POST) {
            this.releaseJump();
        }
    }

    @EventTarget
    public void onKnockback(KnockbackEvent event) {
        if (!this.isEnabled() || this.mode.getValue() != 1 || mc.thePlayer == null) {
            return;
        }
        if (mc.thePlayer.onGround && event.getY() > 0.0D && this.canReset()) {
            this.pressJump();
            this.doReduce();
        }
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled() || this.mode.getValue() != 1 || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        EntityPlayer target = this.nearestTarget(6.0D);
        if (target != null && this.shouldPolarJump(target) && this.canReset()) {
            this.pressJump();
            this.doReduce();
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE) {
            return;
        }
        if (event.getPacket() instanceof S08PacketPlayerPosLook) {
            this.ticksSinceFlag = 0;
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        this.ticksSinceAttack = 0;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (this.ticksSinceFlag < 10000) {
            this.ticksSinceFlag++;
        }
        if (this.ticksSinceAttack < 10000) {
            this.ticksSinceAttack++;
        }
        if (this.ticksSinceReset < 10000) {
            this.ticksSinceReset++;
        }
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        this.resetState();
    }

    private void tickStandard() {
        boolean onGround = mc.thePlayer.onGround;
        if (onGround && this.lastFallDistance > 3.0D && !mc.thePlayer.capabilities.allowFlying) {
            this.ignoreNext = true;
        }
        int hurtTime = mc.thePlayer.hurtTime;
        if (hurtTime > this.lastHurtTime) {
            boolean mouseOk = KeyBindUtil.isKeyDown(-100) || !this.mouseDown.getValue();
            boolean forwardOk = KeyBindUtil.isKeyDown(mc.gameSettings.keyBindForward.getKeyCode()) || !this.movingForward.getValue();
            boolean aimOk = !this.aimingOnPlayer.getValue() || this.nearestTarget(this.range.getValue()) != null;
            boolean sprintOk = !this.requireSprint.getValue() || mc.thePlayer.isSprinting();
            boolean potionOk = !mc.thePlayer.isPotionActive(Potion.jump);
            if (!this.ignoreNext && onGround && mouseOk && forwardOk && aimOk && sprintOk && potionOk && this.canReset()) {
                this.pressJump();
                this.doReduce();
            }
            this.ignoreNext = false;
        }
        this.lastHurtTime = hurtTime;
        this.lastFallDistance = mc.thePlayer.fallDistance;
    }

    private boolean shouldPolarJump(EntityPlayer target) {
        if (!mc.thePlayer.onGround || !mc.thePlayer.isSprinting()) {
            return false;
        }
        double current = mc.thePlayer.getDistanceToEntity(target);
        double exit = this.exitRange.getValue();
        if (current > exit) {
            return false;
        }
        int ticks = this.predictionTicks.getValue();
        double selfX = mc.thePlayer.posX + mc.thePlayer.motionX * ticks;
        double selfZ = mc.thePlayer.posZ + mc.thePlayer.motionZ * ticks;
        double dx = target.posX - target.lastTickPosX;
        double dz = target.posZ - target.lastTickPosZ;
        double px = target.posX + dx * ticks - selfX;
        double pz = target.posZ + dz * ticks - selfZ;
        return px * px + pz * pz > exit * exit;
    }

    private boolean canReset() {
        if (this.shouldPause()) {
            return false;
        }
        if (this.ticksSinceReset < this.minInterval.getValue()) {
            return false;
        }
        return RandomUtil.nextInt(1, 100) <= this.chance.getValue();
    }

    private boolean shouldPause() {
        switch (this.pauseWhen.getValue()) {
            case 0:
                return this.ticksSinceFlag < this.flagTicks.getValue();
            case 1:
                return this.ticksSinceAttack > this.combatTicks.getValue();
            case 2:
                return this.ticksSinceFlag < this.flagTicks.getValue() || this.ticksSinceAttack > this.combatTicks.getValue();
            default:
                return false;
        }
    }

    private void doReduce() {
        if (!this.reduce.getValue() || mc.thePlayer == null) {
            return;
        }
        float factor = this.reduceFactor.getValue();
        float amount = this.reduceMode.getValue() == 1 ? 1.0F - factor : factor;
        amount = Math.max(0.0F, Math.min(1.0F, amount));
        mc.thePlayer.motionX *= amount;
        mc.thePlayer.motionZ *= amount;
    }

    private EntityPlayer nearestTarget(double maxRange) {
        List<EntityPlayer> inRange = mc.theWorld.loadedEntityList.stream()
                .filter(entity -> entity instanceof EntityPlayer)
                .map(entity -> (EntityPlayer) entity)
                .filter(entity -> this.isValidTarget(entity, maxRange))
                .sorted(Comparator.comparingDouble(RotationUtil::distanceToEntity))
                .collect(Collectors.toList());
        if (inRange.isEmpty()) {
            return null;
        }
        EntityPlayer best = inRange.get(0);
        if (this.aimingOnPlayer.getValue() && RotationUtil.angleToEntity(best) > this.fov.getValue() / 2.0F) {
            return null;
        }
        return best;
    }

    private boolean isValidTarget(EntityPlayer entity, double maxRange) {
        if (entity == mc.thePlayer || entity == mc.thePlayer.ridingEntity || entity.deathTime > 0) {
            return false;
        }
        if (RotationUtil.distanceToEntity(entity) > maxRange) {
            return false;
        }
        return !TeamUtil.isFriend(entity) && !TeamUtil.isSameTeam(entity) && !TeamUtil.isBot(entity);
    }

    private void pressJump() {
        this.setJump = true;
        this.ticksSinceReset = 0;
        KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), true);
    }

    private void releaseJump() {
        if (this.setJump && !KeyBindUtil.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode())) {
            this.setJump = false;
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
        }
    }

    private void resetState() {
        this.setJump = false;
        this.ignoreNext = false;
        this.lastHurtTime = 0;
        this.lastFallDistance = 0.0D;
        this.ticksSinceFlag = 9999;
        this.ticksSinceAttack = 9999;
        this.ticksSinceReset = 9999;
        if (mc.gameSettings != null && mc.gameSettings.keyBindJump != null
                && !KeyBindUtil.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode())) {
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
        }
    }

    @Override
    public void onEnabled() {
        this.resetState();
        if (mc.thePlayer != null) {
            this.lastHurtTime = mc.thePlayer.hurtTime;
            this.lastFallDistance = mc.thePlayer.fallDistance;
        }
    }

    @Override
    public void onDisabled() {
        this.resetState();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
