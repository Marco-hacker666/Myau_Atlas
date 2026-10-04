package myau.module.modules;

import myau.util.Ping;
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.Property;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.util.ChatUtil;
import myau.util.LatencyTiers;
import net.minecraft.client.Minecraft;

/**
 * Pulls the latency-sensitive settings back when the connection gets worse.
 *
 * The same numbers behave differently at different latencies: a backtrack
 * window that is comfortable at 150ms is a string of corrections at 340ms, and
 * this connection covers that whole span inside a single game. Tuning for the
 * average therefore tunes for neither -- it is either wasteful when the line is
 * good or reckless when it is not.
 *
 * Two things decide the tier, not one. Round trip time is the obvious input,
 * but a connection that swings between 50ms and 340ms is harder to act on than
 * a steady 300ms, because nothing sent can be timed against a moving target;
 * so a jittery line is treated as one tier worse than its average suggests.
 * The tier moves with hysteresis and only after it has settled (LatencyTiers,
 * plan step 8): a line sitting at a threshold no longer flaps.
 *
 * Settings are only ever moved downward from what the player chose, and only
 * as overrides (Property, plan step 7): the player's value stays the base, is
 * what gets saved, and is what comes back when the line recovers. Changing it
 * by hand while governed changes what the cut is taken from. Before, the
 * governor wrote the numbers directly and re-read "the player's choice" from
 * whatever the setting held while the line was good -- including an AutoTune
 * trial, or its own cut saved over a restart (F-24, F-26).
 */
public class LatencyGovernor extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String OWNER = "LatencyGovernor";

    public final IntProperty midPing = new IntProperty("mid-ping", 150, 50, 500);
    public final IntProperty highPing = new IntProperty("high-ping", 250, 80, 800);
    /** Mean deviation above which the line counts as unsteady. */
    public final IntProperty jitterLimit = new IntProperty("jitter", 60, 10, 400);
    /** How far below a threshold the average must fall to leave that tier. */
    public final IntProperty hysteresis = new IntProperty("hysteresis", 20, 0, 150);
    /** Seconds a different tier must be wanted before it is taken. */
    public final IntProperty settle = new IntProperty("settle-seconds", 10, 1, 30);

    public final BooleanProperty governBacktrack = new BooleanProperty("govern-backtrack", true);
    public final BooleanProperty governReach = new BooleanProperty("govern-reach", true);
    /** At the worst tier, modules that withhold outgoing packets are switched off. */
    public final BooleanProperty cutSendSideLag = new BooleanProperty("cut-send-lag", true);
    public final BooleanProperty chat = new BooleanProperty("chat", true);

    private static final int SAMPLES = 40;
    private final int[] pings = new int[SAMPLES];
    private int pingIndex;
    private int pingFilled;

    private final LatencyTiers tiers = new LatencyTiers();
    /** The tier applied; -1 before the first decision. */
    private int tier = -1;
    private int timer;

    /** Modules this switched off at the worst tier, to be given back (F-26). */
    private final java.util.Set<Class<?>> cut = new java.util.LinkedHashSet<Class<?>>();

    public LatencyGovernor() {
        super("LatencyGovernor", false, false,
                "Scales latency-sensitive settings down when the connection degrades");
        /* Overrides are never saved, but switched-off modules would be: give
           them back before the config is written on exit (F-26). */
        myau.management.Shutdown.register(myau.management.Shutdown.Stage.RESTORE, "LatencyGovernor.restore",
                this::restoreForExit);
    }

    private void restoreForExit() {
        if (!this.isEnabled()) {
            return;
        }
        releaseAll();
        giveBack();
    }

    /** Switches back on what the worst tier switched off. */
    private void giveBack() {
        for (Class<?> type : this.cut) {
            Module module = Myau.moduleManager.modules.get(type);
            if (module != null && !module.isEnabled()) {
                try {
                    module.setEnabled(true);
                } catch (Exception ignored) {
                    // One module failing to come back must not stop the others.
                }
            }
        }
        this.cut.clear();
    }

    private void resetSamples() {
        this.pingIndex = 0;
        this.pingFilled = 0;
    }

    @Override
    public void onEnabled() {
        resetSamples();
        this.tiers.reset();
        this.tier = -1;
        this.timer = 0;
    }

    @Override
    public void onDisabled() {
        /* Whatever was taken is given back, so switching the governor off never
           leaves another module quietly detuned -- or switched off (F-26). */
        releaseAll();
        this.tiers.reset();
        this.tier = -1;
        giveBack();
    }

    private int ping() {
        return Ping.own();
    }

    /**
     * START on another server: its samples describe another route, so they go
     * (a reconnect to the same server keeps them). END: the cuts were "for this
     * connection" and the connection is over -- the modules come back and the
     * tier is decided afresh when play resumes.
     */
    @EventTarget
    public void onSession(myau.events.SessionEvent event) {
        if (event.getType() == myau.events.SessionEvent.Type.START && event.serverChanged()) {
            resetSamples();
            releaseAll();
            this.tiers.reset();
            this.tier = -1;
        } else if (event.getType() == myau.events.SessionEvent.Type.END) {
            releaseAll();
            giveBack();
            this.tiers.reset();
            this.tier = -1;
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE
                || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (++this.timer < 20) {
            return;
        }
        this.timer = 0;

        int ping = ping();
        if (ping > 0) {
            this.pings[this.pingIndex] = ping;
            this.pingIndex = (this.pingIndex + 1) % SAMPLES;
            if (this.pingFilled < SAMPLES) {
                this.pingFilled++;
            }
        }
        /* Ten seconds of samples before the first decision: three, as it was,
           let the join's first readings (and the jitter between them) decide. */
        if (this.pingFilled < 10) {
            return;
        }

        int average = average();
        int jitter = meanDeviation(average);
        int next = this.tiers.update(average, jitter, this.midPing.getValue(), this.highPing.getValue(),
                this.jitterLimit.getValue(), this.hysteresis.getValue(), this.settle.getValue());
        if (next == this.tier) {
            /* Follow the base: the player may have changed it while governed. */
            if (this.tier > 0) {
                applyOverrides();
            }
            return;
        }
        int previous = this.tier;
        this.tier = next;
        applyOverrides();
        if (next >= 2 && previous < 2 && this.cutSendSideLag.getValue()) {
            /* These hold outgoing packets, which is the one thing that cannot
               be made to work on a line this bad -- the round trip is already
               the whole budget. */
            disable(LagRange.class, "LagRange");
            disable(Blink.class, "Blink");
            disable(ServerLag.class, "ServerLag");
            disable(FakeLag.class, "FakeLag");
        }
        if (this.chat.getValue()) {
            ChatUtil.sendFormatted(String.format(
                    "&7[&bLatencyGovernor&7] &f%s&7 &8(&fping %dms&7, jitter &f%dms&8)",
                    tierName(next), average, jitter));
        }
    }

    private int average() {
        int total = 0;
        for (int i = 0; i < this.pingFilled; i++) {
            total += this.pings[i];
        }
        return total / this.pingFilled;
    }

    private int meanDeviation(int average) {
        int total = 0;
        for (int i = 0; i < this.pingFilled; i++) {
            total += Math.abs(this.pings[i] - average);
        }
        return total / this.pingFilled;
    }

    private static String tierName(int tier) {
        return tier == 0 ? "clear" : (tier == 1 ? "holding back" : "minimum");
    }

    /** The overrides the applied tier calls for; none at tier 0 or below. */
    private void applyOverrides() {
        Module backtrackModule = Myau.moduleManager.modules.get(BackTrack.class);
        if (backtrackModule instanceof BackTrack) {
            BackTrack backtrack = (BackTrack) backtrackModule;
            if (this.tier > 0 && this.governBacktrack.getValue()) {
                float scale = this.tier == 1 ? 0.6F : 0.4F;
                govern(backtrack.normalDelay, scale);
                govern(backtrack.adaptiveDelay, scale);
            } else {
                backtrack.normalDelay.release(OWNER);
                backtrack.adaptiveDelay.release(OWNER);
            }
        }
        Module reachModule = Myau.moduleManager.modules.get(Reach.class);
        if (reachModule instanceof Reach) {
            Reach reach = (Reach) reachModule;
            if (this.tier > 0 && this.governReach.getValue()) {
                float cap = this.tier == 1 ? 3.05F : 3.0F;
                if (reach.range.getBaseValue() > cap) {
                    set(reach.range, cap);
                } else {
                    reach.range.release(OWNER);
                }
            } else {
                reach.range.release(OWNER);
            }
        }
    }

    /** A delay scaled down from the player's value, never below 50 ms and never up. */
    private void govern(IntProperty property, float scale) {
        int base = property.getBaseValue();
        int target = Math.min(base, Math.max(50, Math.round(base * scale)));
        if (target < base) {
            set(property, target);
        } else {
            property.release(OWNER);
        }
    }

    /** Lays the override only when it changes, so its timestamp means something. */
    private static void set(Property<?> property, Object value) {
        if (!value.equals(property.overrideOf(OWNER))) {
            property.override(Property.Source.GOVERNOR, OWNER, value);
        }
    }

    private void releaseAll() {
        Module backtrackModule = Myau.moduleManager.modules.get(BackTrack.class);
        if (backtrackModule instanceof BackTrack) {
            ((BackTrack) backtrackModule).normalDelay.release(OWNER);
            ((BackTrack) backtrackModule).adaptiveDelay.release(OWNER);
        }
        Module reachModule = Myau.moduleManager.modules.get(Reach.class);
        if (reachModule instanceof Reach) {
            ((Reach) reachModule).range.release(OWNER);
        }
    }

    private void disable(Class<?> type, String label) {
        Module module = Myau.moduleManager.modules.get(type);
        if (module != null && module.isEnabled()) {
            module.setEnabled(false);
            this.cut.add(type);
            if (this.chat.getValue()) {
                ChatUtil.sendFormatted("&7[&bLatencyGovernor&7] &cdisabled &f" + label
                        + "&7 for this connection");
            }
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.tier < 0 ? "sampling" : tierName(this.tier)};
    }
}
