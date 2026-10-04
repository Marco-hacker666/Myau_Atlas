package myau.module.modules;

import myau.util.Ping;
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.EntityLivingBase;

/**
 * Works out when a target can actually be damaged again, and spends the swings
 * that would have been wasted on someone who can.
 *
 * A hit in 1.8 opens a ten tick window during which the same entity refuses
 * every weaker blow. The client keeps attacking through it anyway: the hit log
 * from one game shows five attacks on one player inside a single second, four
 * of which could not have done anything. Those are not near misses -- they were
 * refused before the server considered range, aim or anything else.
 *
 * The timer the client holds is not the server's. It starts counting when the
 * hurt animation packet arrives, half a round trip after the server started
 * counting, and the attack sent in reply takes the other half to get back. So
 * the client's figure runs roughly a full round trip BEHIND -- at 230ms it
 * claims four or five ticks of invulnerability that, by the time a packet could
 * arrive, are already spent. Read literally it does not cause wasted swings so
 * much as missed openings: it holds fire through a window that is already open.
 *
 * The compensation is therefore subtracted, not added, and what remains is an
 * estimate of the timer as it will stand when the packet lands. A safety margin
 * keeps the estimate on the cautious side of zero, and the whole adjustment can
 * be scaled back to nothing for a connection where the ping figure itself is
 * not to be trusted.
 *
 * Nothing here sends a packet, holds one back, or alters a rotation. It answers
 * two questions for {@link KillAura} -- can this target be hurt, and is there a
 * better one -- and the decisions stay where they already were.
 */
public class InvulnTiming extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();
    /** Ticks of invulnerability a hit opens, as 1.8 defines it. */
    private static final float WINDOW = 10.0F;

    /**
     * How much of the round trip to subtract. 1.0 is the full correction; lower
     * values suit a connection whose reported ping is unreliable, and 0 turns
     * the prediction off while leaving the rest of the module working.
     */
    public final FloatProperty compensation = new FloatProperty("ping-compensation", 1.0F, 0.0F, 1.5F);
    /** Kept on the cautious side of zero, in ticks. */
    public final FloatProperty safety = new FloatProperty("safety-ticks", 0.5F, 0.0F, 4.0F);
    /** Prefer a target that can be hurt over one that cannot. */
    public final BooleanProperty redirect = new BooleanProperty("redirect", true);
    /** Hold fire rather than attack a target that cannot be hurt. */
    public final BooleanProperty holdFire = new BooleanProperty("hold-fire", true);
    /**
     * A ceiling on consecutive held attacks. However wrong the estimate gets,
     * the aura cannot be talked into silence for longer than this.
     */
    public final IntProperty maxHold = new IntProperty("max-hold-ticks", 6, 1, 20,
            this.holdFire::getValue);
    public final BooleanProperty hud = new BooleanProperty("suffix", true);

    private int held;
    private int savedSwings;
    private int redirects;

    public InvulnTiming() {
        super("InvulnTiming", false, false,
                "Times attacks to the end of the target's invulnerability window");
    }

    /** The live instance, or null when it was never registered. */
    public static InvulnTiming instance() {
        Module module = Myau.moduleManager.modules.get(InvulnTiming.class);
        return module instanceof InvulnTiming ? (InvulnTiming) module : null;
    }

    @Override
    public void onEnabled() {
        this.held = 0;
        this.savedSwings = 0;
        this.redirects = 0;
    }

    private int ping() {
        return Ping.own();
    }

    /** The round trip expressed in ticks, which is the unit the timer is in. */
    private float lagTicks() {
        int ping = ping();
        if (ping <= 0) {
            return 0.0F;
        }
        /* Clamped to the window: a reading longer than the window itself says
           nothing useful, and would make every target look damageable. */
        return Math.min(WINDOW, ping / 50.0F) * this.compensation.getValue();
    }

    /**
     * The target's invulnerability timer as it is expected to stand when a
     * packet sent now arrives. Zero or less means the hit can register.
     */
    public float predict(EntityLivingBase target) {
        if (target == null) {
            return 0.0F;
        }
        return target.hurtTime - lagTicks() + this.safety.getValue();
    }

    public boolean canDamage(EntityLivingBase target) {
        if (!this.isEnabled() || target == null) {
            return true;
        }
        return predict(target) <= 0.0F;
    }

    /**
     * Whether an attack on this target should go out now. Held attacks are
     * counted so that a stuck estimate releases instead of silencing the aura,
     * and the count is cleared the moment one goes through.
     */
    public boolean allowAttack(EntityLivingBase target) {
        if (!this.isEnabled() || !this.holdFire.getValue() || target == null) {
            return true;
        }
        if (canDamage(target)) {
            this.held = 0;
            return true;
        }
        if (this.held >= this.maxHold.getValue()) {
            /* The estimate has been saying no for longer than it can be right
               about. Let the swing out and start over rather than trusting it
               further. */
            this.held = 0;
            return true;
        }
        this.held++;
        this.savedSwings++;
        return false;
    }

    /** Recorded by KillAura when the estimate caused a target change. */
    public void noteRedirect() {
        this.redirects++;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE
                || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        /* Nothing to do per tick; the counter is only reset so that a fight
           ending does not leave a stale hold behind. */
        if (mc.thePlayer.isDead) {
            this.held = 0;
        }
    }

    @Override
    public String[] getSuffix() {
        if (!this.hud.getValue()) {
            return new String[0];
        }
        return new String[]{this.savedSwings + "/" + this.redirects};
    }
}
