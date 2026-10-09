package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): range, time and distance gated auto blink.
//
// The hold rides Myau's shared blink (BlinkManager, lease SMART_BLINKER) rather
// than a private queue: the packets land in the same ordered queue as every
// other blink, the ledger names this module for them, and a hold that outlives
// its reason is ended by the PacketHolds lease. The manager decides what moves
// (what a client sends, replies excepted) as it does for Blink and NoFall; this
// module decides when to take the blink and when to give it back.
import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.RotationUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.Vec3;

public class SmartBlinker extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Normal", "Timed"});
    public final FloatProperty minRange = new FloatProperty("min-range", 2.0F, 0.0F, 6.0F);
    public final FloatProperty maxRange = new FloatProperty("max-range", 4.5F, 0.0F, 6.0F);
    public final IntProperty maxTime = new IntProperty("max-time", 500, 0, 5000, () -> this.mode.getValue() == 1);
    public final FloatProperty maxDistance = new FloatProperty("max-distance", 5.0F, 0.0F, 50.0F);
    public final IntProperty delay = new IntProperty("delay", 500, 0, 5000);
    public final BooleanProperty stopOnAttack = new BooleanProperty("stop-on-attack", true);
    public final BooleanProperty stopOnPlace = new BooleanProperty("stop-on-place", true);
    public final BooleanProperty stopOnHurt = new BooleanProperty("stop-on-hurt", true);
    public final BooleanProperty stopOnTP = new BooleanProperty("stop-on-tp", true);
    public final BooleanProperty blockAll = new BooleanProperty("block-all", false);

    private boolean blinking;
    private long blinkStartMs;
    private long lastReleaseMs;
    private Vec3 startPos;
    private Vec3 lastPos;
    private double moved;

    public SmartBlinker() {
        super("SmartBlinker", false, false, "Holds movement packets briefly when near a target.");
    }

    @Override
    public void onEnabled() {
        this.resetBlink();
        this.lastReleaseMs = 0L;
    }

    @Override
    public void onDisabled() {
        this.releaseBlink();
        this.resetBlink();
    }

    private void resetBlink() {
        this.blinking = false;
        this.blinkStartMs = 0L;
        this.startPos = null;
        this.lastPos = null;
        this.moved = 0.0D;
    }

    private void releaseBlink() {
        /* Gives the whole held queue back, in order, the moment a release
           condition fires. Returns false only while another module owns the
           blink, in which case there is nothing of this module's to release. */
        Myau.blinkManager.setBlinkState(false, BlinkModules.SMART_BLINKER);
        this.blinking = false;
        this.blinkStartMs = 0L;
        this.startPos = null;
        this.lastPos = null;
        this.moved = 0.0D;
        this.lastReleaseMs = System.currentTimeMillis();
    }

    private EntityLivingBase resolveTarget() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        if (killAura != null && killAura.isEnabled() && killAura.getTarget() != null) {
            EntityLivingBase aura = killAura.getTarget();
            if (aura != null && !aura.isDead && TeamUtil.isEntityLoaded(aura)) {
                return aura;
            }
            return null;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            return null;
        }
        EntityLivingBase best = null;
        double bestDist = 8.0D;
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityPlayer) || entity == mc.thePlayer) {
                continue;
            }
            EntityPlayer player = (EntityPlayer) entity;
            if (player.isDead || TeamUtil.isFriend(player) || TeamUtil.isSameTeam(player) || TeamUtil.isBot(player)) {
                continue;
            }
            double dist = mc.thePlayer.getDistanceToEntity(player);
            if (dist < bestDist) {
                bestDist = dist;
                best = player;
            }
        }
        return best;
    }

    private boolean inRange() {
        EntityLivingBase target = this.resolveTarget();
        if (target == null) {
            return false;
        }
        float lo = Math.min(this.minRange.getValue(), this.maxRange.getValue());
        float hi = Math.max(this.minRange.getValue(), this.maxRange.getValue());
        double dist = RotationUtil.distanceToEntity(target);
        return dist >= (double) lo && dist <= (double) hi;
    }

    private boolean shouldBlink() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return false;
        }
        if (this.stopOnHurt.getValue() && mc.thePlayer.hurtTime > 0) {
            return false;
        }
        if (!this.inRange()) {
            return false;
        }
        if (!this.blinking && this.lastReleaseMs > 0L
                && System.currentTimeMillis() - this.lastReleaseMs < (long) this.delay.getValue()) {
            return false;
        }
        if (this.blinking) {
            if (this.mode.getValue() == 1 && this.blinkStartMs > 0L
                    && System.currentTimeMillis() - this.blinkStartMs >= (long) this.maxTime.getValue()) {
                return false;
            }
            if (this.moved >= (double) this.maxDistance.getValue()) {
                return false;
            }
        }
        return true;
    }

    private boolean shouldHold(Packet<?> packet) {
        if (packet instanceof C00PacketKeepAlive || packet instanceof C01PacketChatMessage) {
            return false;
        }
        if (this.blockAll.getValue()) {
            return true;
        }
        return packet instanceof C03PacketPlayer;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (this.blinking) {
            /* Someone else taking the blink, or the lease ending it, means this
               module no longer holds anything: drop the state rather than keep
               reporting a hold it does not have. */
            if (!Myau.blinkManager.getBlinkingModule().equals(BlinkModules.SMART_BLINKER)) {
                this.resetBlink();
                return;
            }
            Vec3 now = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
            if (this.lastPos != null) {
                this.moved += this.lastPos.distanceTo(now);
            }
            this.lastPos = now;
            if (!this.shouldBlink()) {
                this.releaseBlink();
            }
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || !this.blinking || !this.stopOnAttack.getValue()) {
            return;
        }
        this.releaseBlink();
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (event.getType() == EventType.RECEIVE) {
            if (this.blinking && this.stopOnTP.getValue() && packet instanceof S08PacketPlayerPosLook) {
                this.releaseBlink();
            }
            return;
        }
        if (event.getType() != EventType.SEND) {
            return;
        }
        if (this.blinking) {
            if (this.stopOnAttack.getValue() && packet instanceof C02PacketUseEntity
                    && ((C02PacketUseEntity) packet).getAction() == C02PacketUseEntity.Action.ATTACK) {
                this.releaseBlink();
                return;
            }
            if (this.stopOnPlace.getValue() && packet instanceof C08PacketPlayerBlockPlacement) {
                this.releaseBlink();
                return;
            }
        }
        if (!this.shouldBlink()) {
            if (this.blinking) {
                this.releaseBlink();
            }
            return;
        }
        if (this.blinking) {
            return;
        }
        /* The blink starts on a packet of the kind this module means to hold --
           movement by default, anything with block-all -- because taking it
           holds every outgoing packet the manager holds, and a hold begun on a
           chat line or a keep-alive would be a hold for no reason. */
        if (!this.shouldHold(packet)) {
            return;
        }
        if (Myau.blinkManager.setBlinkState(true, BlinkModules.SMART_BLINKER)) {
            this.blinking = true;
            this.blinkStartMs = System.currentTimeMillis();
            this.startPos = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
            this.lastPos = this.startPos;
            this.moved = 0.0D;
        }
        /* false means another module already holds the blink (first come, first
           served) or an Arbiter catch is on: nothing is held for this module,
           and it does not pretend otherwise. */
    }

    @Override
    public String[] getSuffix() {
        if (this.blinking) {
            return new String[]{this.mode.getModeString() + " " + String.format("%.1f", this.moved)};
        }
        return new String[]{this.mode.getModeString()};
    }
}
