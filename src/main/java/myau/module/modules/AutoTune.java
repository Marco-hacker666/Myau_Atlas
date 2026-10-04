package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.Property;
import myau.util.ChatUtil;
import myau.util.TuneExperiment;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Finds settings by trying them, rather than by being told them.
 *
 * Every number in this client was chosen by someone guessing, copying a config,
 * or reasoning from how the protocol ought to behave. None of those survive
 * contact with one particular connection: the reach that is safe here, the
 * backtrack window this line can carry, the click rate that lands rather than
 * looks fast, are all properties of a route and a server rather than of the
 * game. They cannot be reasoned to. They can be measured.
 *
 * Two instruments already write the numbers that matter. {@link FlagDetector}
 * counts corrections per minute; {@link HitCheck} counts the fraction of swings
 * that dealt damage. Those are the two halves of the only question worth
 * asking -- is this configuration landing more hits without being caught more
 * often.
 *
 * THE EXPERIMENT (plan step 11, 2026-09-28). One setting at a time, one
 * candidate value at a time, measured as four blocks of play --
 * current, candidate, current, candidate -- and kept only if the candidate
 * beat the current value in both pairs and by the margin on average
 * ({@link TuneExperiment}). The old loop kept anything that out-scored one
 * earlier five-minute block by any amount, which mostly kept luck (F-21).
 * A kept value is COMMITTED: written as the setting's base (source TUNED),
 * saved with the configuration, and logged with the evidence. Everything
 * short of that is an override (plan step 7) and never saved.
 *
 * The instruments are read, not reset: each block takes the counters' change
 * over it. The old loop cleared FlagDetector's and HitCheck's session counters
 * at every trial, so their own displays never showed a session (C6). A block
 * the counters were reset in (a join), with too little fighting, or with the
 * value masked by something of higher precedence (LatencyGovernor) is played
 * again, a limited number of times.
 *
 * SCORING. Hits are worth having and corrections are worth avoiding, but not at
 * any exchange rate: score = hit% - flags/min x flag-weight. The weight is a
 * setting because the right exchange rate is a judgement about risk, not a fact
 * about the game -- and every block is written with its parts.
 *
 * WHAT IT WILL NOT DO. It only moves settings it was given, one at a time; only
 * tunes a setting that is in use (BackTrack has two delays and uses one); never
 * changes the configuration without a validated result; and writes every block
 * and every result to autotune.txt with its trial id.
 */
public class AutoTune extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File LOG_DIR = new File("./config/Myau/");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss");
    private static final String OWNER = "AutoTune";

    /** Minutes of play in each block; an experiment is four blocks. */
    public final IntProperty trialMinutes = new IntProperty("trial-minutes", 5, 1, 60);
    /** Judged swings a block needs before its numbers mean anything. */
    public final IntProperty minSwings = new IntProperty("min-swings", 25, 5, 200);
    /**
     * How many points of hit rate one flag per minute is worth giving up. High
     * values tune for safety, low values for damage.
     */
    public final FloatProperty flagWeight = new FloatProperty("flag-weight", 6.0F, 0.0F, 40.0F);
    /** Fraction of the range a first step moves. */
    public final FloatProperty stepFraction = new FloatProperty("step", 0.12F, 0.02F, 0.5F);
    /** Score points the candidate must win by, on average over both pairs. */
    public final FloatProperty margin = new FloatProperty("margin", 2.0F, 0.0F, 20.0F);
    /** Blocks that may be played again (too little fighting, a join, masked) before the experiment expires. */
    public final IntProperty maxReplays = new IntProperty("max-replays", 3, 0, 10);

    public final BooleanProperty tuneReach = new BooleanProperty("tune-reach", true);
    public final BooleanProperty tuneBacktrack = new BooleanProperty("tune-backtrack", true);
    public final BooleanProperty tuneAura = new BooleanProperty("tune-aura", false);
    public final BooleanProperty chat = new BooleanProperty("chat", true);
    public final BooleanProperty logFile = new BooleanProperty("log-file", true);

    private final List<Knob> knobs = new ArrayList<Knob>();
    private int knobIndex = -1;
    private Knob knob;
    private TuneExperiment experiment;
    private int nextId = 1;
    private int committed;
    private int finished;

    private long blockStart;
    private int blockFlags;
    private int blockSwings;
    private int blockHits;
    private File logTarget;

    /** One numeric setting under test, and how far it is currently willing to move. */
    private static final class Knob {
        final String label;
        final Property<?> property;
        final double min;
        final double max;
        final boolean integral;
        /** Whether the setting is the one its module uses right now. */
        final BooleanSupplier active;
        double step;
        int direction = 1;
        /** Set by the player mid-trial: left alone until AutoTune is switched off and on. */
        boolean byHand;

        Knob(String label, Property<?> property, double min, double max, boolean integral,
             BooleanSupplier active, double step) {
            this.label = label;
            this.property = property;
            this.min = min;
            this.max = max;
            this.integral = integral;
            this.active = active;
            this.step = step;
        }

        /** The player's value (the base). */
        double base() {
            Object value = this.property.getBaseValue();
            return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
        }

        Object typed(double value) {
            double clamped = Math.max(this.min, Math.min(this.max, value));
            return this.integral ? (Object) Integer.valueOf((int) Math.round(clamped))
                    : (Object) Float.valueOf((float) clamped);
        }

        /* An override, never a write (plan step 7): the player's value stays the
           base and is what is saved. Laid for the current-value blocks too, so
           "is the value tried the one in effect" means the same in every block. */
        void apply(double value) {
            this.property.override(Property.Source.TRIAL, OWNER, typed(value));
        }

        void release() {
            this.property.release(OWNER);
        }

        boolean inEffect() {
            return OWNER.equals(this.property.getSourceOwner());
        }
    }

    public AutoTune() {
        super("AutoTune", false, false,
                "Tries settings and keeps the ones that measure better");
        /* Quitting never calls onDisabled: whatever block is running is let go
           before the config is written, so only committed values are saved. */
        myau.management.Shutdown.register(myau.management.Shutdown.Stage.RESTORE, "AutoTune.release",
                this::releaseForExit);
    }

    private void releaseForExit() {
        if (!this.isEnabled()) {
            return;
        }
        releaseAll();
    }

    private void releaseAll() {
        for (Knob each : this.knobs) {
            each.release();
        }
    }

    @Override
    public void onEnabled() {
        this.knobs.clear();
        this.knobIndex = -1;
        this.knob = null;
        this.experiment = null;
        this.committed = 0;
        this.finished = 0;
        this.logTarget = null;
        collectKnobs();
        if (this.knobs.isEmpty()) {
            say("&cnothing to tune -- enable a section, or the module that owns it");
            this.setEnabled(false);
            return;
        }
        say("&7tuning &f" + this.knobs.size() + "&7 settings, &f" + this.trialMinutes.getValue()
                + "&7 min per block, four blocks per candidate");
        startNext();
    }

    @Override
    public void onDisabled() {
        /* An experiment that ends unfinished is undone; committed values stay
           -- they are the configuration now, with their evidence in the log. */
        if (this.experiment != null && !this.experiment.finished()) {
            log("ABANDON " + this.experiment + " (switched off)");
        }
        releaseAll();
        this.experiment = null;
        this.knob = null;
    }

    /** The settings this is allowed to move, and the range each may move in. */
    private void collectKnobs() {
        double fraction = this.stepFraction.getValue();

        if (this.tuneReach.getValue()) {
            Module module = Myau.moduleManager.modules.get(Reach.class);
            if (module instanceof Reach && module.isEnabled()) {
                Reach reach = (Reach) module;
                add("Reach.range", reach.range, 3.0, 3.4, false, () -> true, fraction);
            }
        }
        if (this.tuneBacktrack.getValue()) {
            Module module = Myau.moduleManager.modules.get(BackTrack.class);
            if (module instanceof BackTrack && module.isEnabled()) {
                final BackTrack backtrack = (BackTrack) module;
                /* Only the delay in use is tuned (F-22): trials of the other
                   were noise, and could still be "kept". */
                add("BackTrack.normal-delay", backtrack.normalDelay, 50, 250, true,
                        () -> !backtrack.adaptive.getValue(), fraction);
                add("BackTrack.adaptive-delay", backtrack.adaptiveDelay, 50, 250, true,
                        () -> backtrack.adaptive.getValue(), fraction);
            }
        }
        if (this.tuneAura.getValue()) {
            Module module = Myau.moduleManager.modules.get(KillAura.class);
            if (module instanceof KillAura && module.isEnabled()) {
                KillAura aura = (KillAura) module;
                add("KillAura.AttackRange", aura.attackRange, 3.0, 3.4, false, () -> true, fraction);
                add("KillAura.SwitchDelay", aura.switchDelay, 0, 600, true, () -> true, fraction);
            }
        }
    }

    private void add(String label, Property<?> property, double min, double max,
                     boolean integral, BooleanSupplier active, double fraction) {
        if (!(property.getBaseValue() instanceof Number)) {
            return;
        }
        double step = Math.max(integral ? 1.0 : 0.01, (max - min) * fraction);
        this.knobs.add(new Knob(label, property, min, max, integral, active, step));
    }

    // ---- the loop -----------------------------------------------------

    /** Begins an experiment on the next setting in use. */
    private void startNext() {
        this.experiment = null;
        this.knob = null;
        for (int tried = 0; tried < this.knobs.size(); tried++) {
            this.knobIndex = (this.knobIndex + 1) % this.knobs.size();
            Knob next = this.knobs.get(this.knobIndex);
            if (!next.active.getAsBoolean() || next.byHand) {
                continue;
            }
            double from = next.base();
            double candidate = from + next.step * next.direction;
            if (candidate < next.min || candidate > next.max) {
                /* Against the edge in this direction; turn around. */
                next.direction = -next.direction;
                candidate = from + next.step * next.direction;
            }
            candidate = ((Number) next.typed(candidate)).doubleValue();
            if (candidate == from) {
                continue;
            }
            this.knob = next;
            this.experiment = new TuneExperiment(this.nextId++, next.label, from, candidate,
                    this.margin.getValue(), this.maxReplays.getValue());
            log(String.format(Locale.ROOT, "START   %s step %.3f margin %.1f", this.experiment, next.step,
                    this.margin.getValue()));
            say(String.format("&7trying &f%s &7%.3f &8->&f %.3f &8(4 blocks)", next.label, from, candidate));
            beginBlock();
            return;
        }
        say("&7no setting in use to tune");
    }

    /** Lays the block's value and takes the instruments' readings at its start. */
    private void beginBlock() {
        this.knob.apply(this.experiment.valueNow());
        this.blockStart = System.currentTimeMillis();
        FlagDetector detector = detector();
        HitCheck hitCheck = hitCheck();
        this.blockFlags = detector == null ? 0 : detector.violationCount();
        this.blockSwings = hitCheck == null ? 0 : hitCheck.judgedSwings();
        this.blockHits = hitCheck == null ? 0 : hitCheck.landedHits();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE
                || mc.thePlayer == null || mc.theWorld == null || this.experiment == null) {
            return;
        }
        if (this.knob.base() != this.experiment.current) {
            /* The player set this one by hand (the menu and commands set the
               base). The trial on top kept its own value in effect, so the
               slider moved and the number did not (BackTrack adaptive-delay,
               2026-10-02); the block would then have been replayed as
               "masked", and a COMMIT would have overwritten the choice. */
            this.knob.byHand = true;
            say("&7" + this.knob.label + " &fchanged by hand &8-- left alone until AutoTune is toggled");
            this.experiment.expire("changed by the player");
            finish();
            return;
        }
        if (System.currentTimeMillis() - this.blockStart < this.trialMinutes.getValue() * 60000L) {
            return;
        }
        endBlock();
    }

    /**
     * Another server makes the comparison meaningless: the experiment expires.
     * A new connection to the same server resets the instruments, so the
     * block in progress starts over.
     */
    @EventTarget
    public void onSession(myau.events.SessionEvent event) {
        if (event.getType() != myau.events.SessionEvent.Type.START || this.experiment == null) {
            return;
        }
        if (event.serverChanged()) {
            this.experiment.expire("server changed");
            finish();
        } else {
            beginBlock();
        }
    }

    private FlagDetector detector() {
        Module module = Myau.moduleManager.modules.get(FlagDetector.class);
        return module instanceof FlagDetector && module.isEnabled() ? (FlagDetector) module : null;
    }

    private HitCheck hitCheck() {
        Module module = Myau.moduleManager.modules.get(HitCheck.class);
        return module instanceof HitCheck && module.isEnabled() ? (HitCheck) module : null;
    }

    private void endBlock() {
        FlagDetector detector = detector();
        HitCheck hitCheck = hitCheck();
        if (detector == null || hitCheck == null) {
            say("&cFlagDetector and HitCheck must both be on -- pausing");
            this.setEnabled(false);
            return;
        }
        String block = this.experiment.state().name();
        double value = this.experiment.valueNow();
        int flags = detector.violationCount() - this.blockFlags;
        int swings = hitCheck.judgedSwings() - this.blockSwings;
        int hits = hitCheck.landedHits() - this.blockHits;
        double minutes = Math.max(0.5, (System.currentTimeMillis() - this.blockStart) / 60000.0);

        String thin = null;
        if (!this.knob.inEffect()) {
            thin = "masked: " + this.knob.property.describe();
        } else if (flags < 0 || swings < 0 || hits < 0) {
            thin = "instruments were reset during the block";
        } else if (swings < this.minSwings.getValue()) {
            thin = swings + " swings, " + this.minSwings.getValue() + " needed";
        }
        if (thin != null) {
            this.experiment.blockThin();
            log(String.format(Locale.ROOT, "REPLAY  #%d %s value=%.3f (%s)", this.experiment.id, block, value, thin));
        } else {
            double hitRate = 100.0 * hits / swings;
            double flagsPerMinute = flags / minutes;
            double score = hitRate - flagsPerMinute * this.flagWeight.getValue();
            this.experiment.blockScored(score, swings);
            log(String.format(Locale.ROOT,
                    "BLOCK   #%d %s value=%.3f swings=%d hits=%.1f%% flags=%d (%.2f/min over %.1f min) score=%.1f",
                    this.experiment.id, block, value, swings, hitRate, flags, flagsPerMinute, minutes, score));
        }
        if (this.experiment.finished()) {
            finish();
        } else {
            beginBlock();
        }
    }

    /** Carries out the experiment's result and moves on to the next setting. */
    private void finish() {
        TuneExperiment done = this.experiment;
        Knob tuned = this.knob;
        if (done == null || tuned == null) {
            return;
        }
        tuned.release();
        this.finished++;
        switch (done.state()) {
            case COMMITTED:
                /* The one path from an experiment to the configuration. */
                tuned.property.setBase(Property.Source.TUNED, tuned.typed(done.candidate));
                this.committed++;
                log(String.format(Locale.ROOT, "COMMIT  #%d %s %.3f -> %.3f: %s", done.id, done.label,
                        done.current, done.candidate, done.reason()));
                say(String.format("&a+ &f%s &7%.3f &8->&f %.3f &8(%s)", done.label, done.current,
                        done.candidate, done.reason()));
                break;
            case REJECTED:
                if (tuned.direction > 0) {
                    /* One direction failed; the other has not been tried. */
                    tuned.direction = -1;
                } else {
                    /* Both failed: at a local best for now, so look more finely. */
                    tuned.direction = 1;
                    tuned.step = Math.max(tuned.integral ? 1.0 : 0.005, tuned.step * 0.5);
                }
                log(String.format(Locale.ROOT, "REJECT  #%d %s stays %.3f: %s (next step %.3f)", done.id,
                        done.label, done.current, done.reason(), tuned.step));
                say(String.format("&c- &f%s &7stays &f%.3f &8(%s)", done.label, done.current, done.reason()));
                break;
            default:
                log(String.format(Locale.ROOT, "EXPIRE  #%d %s stays %.3f: %s", done.id, done.label,
                        done.current, done.reason()));
                break;
        }
        startNext();
    }

    // ---- output -------------------------------------------------------

    private void say(String message) {
        if (this.chat.getValue()) {
            ChatUtil.sendFormatted("&7[&bAutoTune&7] " + message);
        }
    }

    private void log(String line) {
        if (!this.logFile.getValue()) {
            return;
        }
        PrintWriter writer = null;
        try {
            if (!LOG_DIR.exists() && !LOG_DIR.mkdirs()) {
                return;
            }
            if (this.logTarget == null) {
                this.logTarget = new File(LOG_DIR, "autotune.txt");
            }
            writer = new PrintWriter(new FileWriter(this.logTarget, true));
            writer.println(LINE_STAMP.format(new Date()) + "  " + line);
            writer.flush();
        } catch (Exception ignored) {
            // Losing a log line must not end the experiment.
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    @Override
    public String[] getSuffix() {
        if (this.experiment == null) {
            return new String[]{"idle"};
        }
        long left = this.trialMinutes.getValue() * 60000L - (System.currentTimeMillis() - this.blockStart);
        return new String[]{"#" + this.experiment.id + " " + this.experiment.state().name().toLowerCase(Locale.ROOT)
                + " " + Math.max(0, left / 1000) + "s, " + this.committed + "/" + this.finished + " kept"};
    }
}
