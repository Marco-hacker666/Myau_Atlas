package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): blocking engine with block-timing modes,
// APS cadence, smart unblock on self-hurt and NoSlow cooperation.
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.util.ItemUtil;
import myau.util.KeyBindUtil;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.server.S19PacketEntityStatus;

public class AutoBlock extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int[] APS_VALUES = {3, 5, 7, 10, 14};

    public final ModeProperty mode = new ModeProperty("mode", 2,
            new String[]{"Vanilla", "Legit", "Hypixel", "Smart", "Hold"});
    public final ModeProperty apsMode = new ModeProperty("aps-mode", 0,
            new String[]{"3APS", "5APS", "7APS", "10APS", "14APS"},
            () -> this.mode.getValue() == 2);

    public final BooleanProperty requireKillAura = new BooleanProperty("require-killaura", true);
    public final BooleanProperty requireRightClick = new BooleanProperty("require-right-click", false);
    public final BooleanProperty manualLeftClick = new BooleanProperty("manual-left-click", false);

    public final BooleanProperty smartUnblock = new BooleanProperty("smart-unblock", false);
    public final PercentProperty smartUnblockChance = new PercentProperty("smart-unblock-chance", 100,
            () -> this.smartUnblock.getValue());
    public final IntProperty smartUnblockTicks = new IntProperty("smart-unblock-ticks", 8, 0, 15,
            () -> this.smartUnblock.getValue());

    public final BooleanProperty allowNoSlow = new BooleanProperty("allow-noslow", true);
    public final BooleanProperty onlyUnblockWithoutNoSlow = new BooleanProperty("only-unblock-without-noslow", true,
            () -> this.allowNoSlow.getValue());
    public final BooleanProperty disableNoSlowInRange = new BooleanProperty("disable-noslow-in-range", true,
            () -> this.allowNoSlow.getValue());
    public final FloatProperty noSlowDisableRange = new FloatProperty("noslow-disable-range", 3.5F, 0.0F, 8.0F,
            () -> this.allowNoSlow.getValue() && this.disableNoSlowInRange.getValue());

    public final FloatProperty fov = new FloatProperty("fov", 360.0F, 1.0F, 360.0F);
    public final FloatProperty targetRange = new FloatProperty("target-range", 5.0F, 1.0F, 8.0F);

    private final TimerUtil apsTimer = new TimerUtil();
    private int unblockTicks = 0;
    private boolean blocking = false;

    public AutoBlock() {
        super("AutoBlock", false, false, "Automatically blocks with your sword during combat.");
    }

    @Override
    public void onEnabled() {
        this.unblockTicks = 0;
        this.blocking = false;
        this.apsTimer.reset();
    }

    @Override
    public void onDisabled() {
        this.unblockTicks = 0;
        this.blocking = false;
        this.apsTimer.reset();
        if (mc.thePlayer != null) {
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE) {
            return;
        }
        if (!this.smartUnblock.getValue() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (!(event.getPacket() instanceof S19PacketEntityStatus)) {
            return;
        }
        S19PacketEntityStatus packet = (S19PacketEntityStatus) event.getPacket();
        if (packet.getOpCode() != 2) {
            return;
        }
        try {
            if (!mc.thePlayer.equals(packet.getEntity(mc.theWorld))) {
                return;
            }
        } catch (Exception ignored) {
            return;
        }
        if (Math.random() * 100.0 > this.smartUnblockChance.getValue()) {
            return;
        }
        this.unblockTicks = this.smartUnblockTicks.getValue();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (!ItemUtil.isHoldingSword()) {
            this.releaseBlock();
            return;
        }

        if (this.manualLeftClick.getValue() && !mc.gameSettings.keyBindAttack.isKeyDown()) {
            this.releaseBlock();
            return;
        }
        if (this.requireRightClick.getValue() && !mc.gameSettings.keyBindUseItem.isKeyDown()) {
            this.releaseBlock();
            return;
        }

        EntityLivingBase target = this.resolveTarget();
        if (this.requireKillAura.getValue() && target == null) {
            this.releaseBlock();
            return;
        }
        if (target == null || target.isDead
                || mc.thePlayer.getDistanceToEntity(target) > this.targetRange.getValue()
                || !this.inFov(target)) {
            this.releaseBlock();
            return;
        }

        if (this.unblockTicks > 0) {
            this.unblockTicks--;
            this.releaseBlock();
            return;
        }

        boolean noSlowLive = this.noSlowLive();
        boolean preferHold = noSlowLive && this.onlyUnblockWithoutNoSlow.getValue();
        if (this.disableNoSlowInRange.getValue() && noSlowLive
                && mc.thePlayer.getDistanceToEntity(target) <= this.noSlowDisableRange.getValue()) {
            preferHold = true;
        }

        switch (this.mode.getValue()) {
            case 0:
            case 4:
                this.startBlock();
                break;
            case 1:
                if (mc.thePlayer.hurtTime > 0) {
                    this.startBlock();
                } else {
                    this.releaseBlock();
                }
                break;
            case 2:
                if (preferHold) {
                    this.startBlock();
                    break;
                }
                if (this.apsTimer.hasTimeElapsed(this.apsIntervalMs())) {
                    if (this.blocking) {
                        this.releaseBlock();
                    } else {
                        this.startBlock();
                    }
                    this.apsTimer.reset();
                }
                break;
            case 3:
                if (target.hurtTime >= 8 && target.hurtTime <= 10) {
                    this.releaseBlock();
                } else {
                    this.startBlock();
                }
                break;
            default:
                this.releaseBlock();
                break;
        }
    }

    private EntityLivingBase resolveTarget() {
        try {
            KillAura killAura = (KillAura) Myau.moduleManager.getModule(KillAura.class);
            if (killAura != null && killAura.isEnabled() && killAura.getTarget() != null) {
                return killAura.getTarget();
            }
        } catch (Exception ignored) {
        }
        if (mc.objectMouseOver != null && mc.objectMouseOver.entityHit instanceof EntityLivingBase) {
            return (EntityLivingBase) mc.objectMouseOver.entityHit;
        }
        return null;
    }

    private boolean noSlowLive() {
        if (!this.allowNoSlow.getValue()) {
            return false;
        }
        try {
            NoSlow noSlow = (NoSlow) Myau.moduleManager.getModule(NoSlow.class);
            return noSlow != null && noSlow.isEnabled() && ItemUtil.isHoldingSword();
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean inFov(EntityLivingBase entity) {
        float maxAngle = this.fov.getValue() / 2.0F;
        if (maxAngle >= 180.0F) {
            return true;
        }
        double dx = entity.posX - mc.thePlayer.posX;
        double dz = entity.posZ - mc.thePlayer.posZ;
        float targetYaw = (float) (Math.atan2(-dx, dz) * 180.0D / Math.PI);
        float diff = targetYaw - mc.thePlayer.rotationYaw;
        while (diff > 180.0F) {
            diff -= 360.0F;
        }
        while (diff <= -180.0F) {
            diff += 360.0F;
        }
        return Math.abs(diff) <= maxAngle;
    }

    private long apsIntervalMs() {
        int idx = this.apsMode.getValue();
        if (idx < 0 || idx >= APS_VALUES.length) {
            idx = 0;
        }
        return 1000L / APS_VALUES[idx];
    }

    private void startBlock() {
        this.blocking = true;
        KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
    }

    private void releaseBlock() {
        if (!this.blocking && mc.thePlayer == null) {
            return;
        }
        this.blocking = false;
        if (mc.thePlayer != null) {
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
