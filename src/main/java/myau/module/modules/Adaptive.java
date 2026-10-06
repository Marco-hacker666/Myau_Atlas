package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.util.AdaptivePolicy;
import myau.util.Brain;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Learns which modules this server objects to, and turns the worst of them
 * down, over hours rather than minutes.
 *
 * Everything before this reacted. {@code FlagResponder} switched off whatever
 * had been named a few times in the last half minute; {@code AutoTune} tried a
 * setting for five minutes and kept it if the score went up. Both are honest
 * about the short run and both are badly wrong in the long run, for the same
 * reason: a half-hour of play is a handful of samples of a noisy process, and
 * acting on it confuses a bad fight with a bad setting.
 *
 * What is different here is only that the evidence is allowed to accumulate,
 * and that time is deliberately spent gathering the half of it that does not
 * arrive by itself.
 *
 * THE MEASUREMENT. For every kind of flag and every module, {@link Brain}
 * keeps how many arrived while that module was on and how many while it was
 * off, against how long each lasted. A module that adds nothing shows the same
 * rate either way, however many flags happen around it; one that causes flags
 * shows a higher rate on than off, and the difference is in flags per minute --
 * a number that can be traded against what the module is worth.
 *
 * THE COST. The off side never accumulates on its own. Something has to spend
 * time deliberately without a module, playing worse, to find out what it does.
 * That is what {@code probe-minutes} buys, it is the only way the comparison
 * can exist, and it is why this is slow: the answer is bought in minutes of
 * play, and there is no way to get it cheaper.
 *
 * ADAPTING. Nothing here knows what any module does. A module is a name with a
 * switch and possibly a number, and the kinds of flag are keys derived from
 * circumstance rather than a list. So a module added later, or a server that
 * begins objecting to something new, is handled the same as everything else --
 * it appears as a new name or a new key and starts accumulating evidence. That
 * is the whole of the claim: not that this is clever, but that it does not need
 * to be told anything to keep working.
 *
 * WHAT IT WILL NOT DO. It will not act below its confidence threshold, will not
 * touch a protected module, moves one thing at a time, writes every decision to
 * a file with the numbers behind it, and restores everything it changed when
 * switched off. A system that adjusts a configuration without being auditable
 * is not a smaller problem than the one it is solving.
 */
public class Adaptive extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File LOG_DIR = new File("./config/Myau/");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /** Flags per minute a module must add before it is worth acting on. */
    public final FloatProperty threshold = new FloatProperty("excess-threshold", 0.35F, 0.05F, 5.0F);
    /** Evidence required, as the confidence Brain reports. */
    public final FloatProperty minConfidence = new FloatProperty("min-confidence", 0.55F, 0.1F, 1.0F);
    /** Minutes to spend without a module to learn what it costs. */
    public final IntProperty probeMinutes = new IntProperty("probe-minutes", 8, 2, 60);
    /** Minutes between decisions, so one bad stretch cannot cascade. */
    public final IntProperty decideMinutes = new IntProperty("decide-minutes", 5, 1, 60);
    /**
     * Report conclusions without acting on them.
     *
     * This covers decisions only. Probing is not a decision -- it is the
     * measurement the decisions are made from, and a probe that does not
     * actually switch the module off measures nothing: the off side stays at
     * zero, the module never stops being the least understood one, and the
     * same probe repeats forever. That is exactly what happened the first
     * evening this ran. Probing therefore ignores this setting, and
     * {@code probe} is the switch for turning it off.
     */
    public final BooleanProperty dryRun = new BooleanProperty("dry-run", false);
    /** Spend time without modules to gather the comparison. */
    public final BooleanProperty probe = new BooleanProperty("probe", true);
    public final BooleanProperty chat = new BooleanProperty("chat", true);
    /**
     * Restrict probing to the modules worth the play time.
     *
     * Probing is the only expensive part of this: it costs minutes of playing
     * with something switched off, one module at a time, and those minutes come
     * out of the same budget as the game. Studying everything enabled means
     * spending the budget evenly over modules that could not possibly cause a
     * position correction, and reaching a conclusion about the ones that could
     * several days later than necessary.
     *
     * Modules outside this list are still recorded -- their coincidence with
     * every flag accumulates exactly as before, for free, from ordinary play.
     * They are simply never chosen as the thing to switch off. Passive evidence
     * is weaker, but it costs nothing, and for a module that is never going to
     * be the answer that is the right trade.
     *
     * Empty means probe anything eligible.
     */
    /** Lifetime flags of a kind before it can justify anything (plan step 10). */
    public final IntProperty minFlags = new IntProperty("min-flags", 8, 1, 200);
    /** No decision or probe this many minutes into a connection. */
    public final IntProperty warmupMinutes = new IntProperty("warmup-minutes", 3, 0, 30);
    /** Actions on one module until the server changes. */
    public final IntProperty maxActs = new IntProperty("max-acts", 2, 1, 10);
    /** Minutes with a module on, after acting on it, before acting on it again. */
    public final IntProperty reactMinutes = new IntProperty("react-minutes", 20, 5, 180);

    public final myau.property.properties.TextProperty focus =
            new myau.property.properties.TextProperty("focus",
                    "AimAssist,AutoClicker,Backtrack,BlockHit,ClickAssits,HitSelect,"
                            + "InvulnTiming,Velocity,WTap,KnockbackDelay,Reach,KillAura,"
                            + "LagRange,Blink,ServerLag,FakeLag,AntiDebuff,AutoSwap");

    /**
     * Never probed, never switched off, never counted as a suspect: the
     * instruments, and anything that cannot produce a server correction. The
     * only list in the system, and it says what must not be touched rather than
     * what may be -- so a module nobody thought about is eligible by default
     * instead of invisible by default.
     */
    private static final String[] PROTECTED = {
            "Adaptive", "Debug", "FlagDetector", "FlagResponder", "HitCheck",
            "LatencyCrosshair", "LatencyGovernor", "ServerFingerprint", "AutoTune",
            "ServerProfiles", "HUD", "ESP", "Chams", "BedESP", "ItemESP", "TargetESP",
            "Indicators", "Hotbar", "Animations", "RenderFixes", "Fullbright",
            "ClientSpoofer", "ResourceSpoofer", "AntiBot", "BedTracker", "ClickGUIModule"
    };

    private final Map<String, Boolean> restore = new HashMap<String, Boolean>();

    private long lastSample;
    private long lastDecision;
    private long probeUntil;
    private String probing;
    private int actions;
    private double probeStartedOff;
    private String currentServer = "";
    private final java.util.Set<String> unprobeable = new java.util.HashSet<String>();
    private File logTarget;
    /** Since the last action on each module (AdaptivePolicy.Epoch); cleared when the server changes. */
    private final Map<String, AdaptivePolicy.Epoch> epochs =
            new java.util.concurrent.ConcurrentHashMap<String, AdaptivePolicy.Epoch>();
    /** The last HOLD line written, so an unchanged reason is not written every round. */
    private String lastHold;
    /** The most recent decisions, newest last, for diagnostics. */
    private final java.util.ArrayDeque<String> decisions = new java.util.ArrayDeque<String>();

    public Adaptive() {
        super("Adaptive", false, false,
                "Learns which modules this server objects to and turns them down");
        /* The client saves its configuration on exit. A probe that is still
           running at that moment is a module switched off for measurement --
           and it would be written to the config as though it had been switched
           off on purpose, silently, permanently. Restoring first is not
           optional, and it cannot wait for onDisabled because quitting never
           calls it. This used to be a hook of its own, racing the config
           save's; Shutdown now runs RESTORE strictly before SAVE_CONFIG. */
        myau.management.Shutdown.register(myau.management.Shutdown.Stage.RESTORE, "Adaptive.restore",
                this::restoreAll);
        myau.management.Shutdown.register(myau.management.Shutdown.Stage.SAVE_STATE, "Brain", Brain::save);
    }

    /** Puts back everything switched off for measurement. */
    private void restoreAll() {
        for (Map.Entry<String, Boolean> entry : this.restore.entrySet()) {
            Module module = Myau.moduleManager.getModule(entry.getKey());
            if (module != null && module.isEnabled() != entry.getValue()) {
                module.setEnabled(entry.getValue());
            }
        }
        this.restore.clear();
        this.probing = null;
        /* Numbers turned down come back too (F-20). They used to be written
           straight into the settings, so this -- which only knew about
           switched-off modules -- left every knob where it had been turned. */
        releaseKnobs();
    }

    private static void releaseKnobs() {
        Module reach = Myau.moduleManager.modules.get(Reach.class);
        if (reach instanceof Reach) {
            ((Reach) reach).range.release(OWNER);
        }
        Module backtrack = Myau.moduleManager.modules.get(BackTrack.class);
        if (backtrack instanceof BackTrack) {
            ((BackTrack) backtrack).normalDelay.release(OWNER);
            ((BackTrack) backtrack).adaptiveDelay.release(OWNER);
        }
        Module delay = Myau.moduleManager.modules.get(KnockbackDelay.class);
        if (delay instanceof KnockbackDelay) {
            ((KnockbackDelay) delay).chance.release(OWNER);
        }
    }

    private static final String OWNER = "Adaptive";

    @Override
    public void onEnabled() {
        this.restore.clear();
        this.lastSample = System.currentTimeMillis();
        this.lastDecision = System.currentTimeMillis();
        this.probeUntil = 0L;
        this.probing = null;
        this.actions = 0;
        this.unprobeable.clear();
        this.logTarget = null;
        this.epochs.clear();
        this.lastHold = null;
        this.currentServer = serverKey();
        Brain.setServer(this.currentServer);
        Module tuner = Myau.moduleManager.modules.get(AutoTune.class);
        if (tuner != null && tuner.isEnabled()) {
            /* Both adjust the same numbers from different evidence, and each
               reads the other's change as the effect of its own. */
            say("&cAutoTune is on -- both tune the same settings, turn one off");
        }
        say(String.format("&7learning on &f%s&7 -- &f%d&7 flag kinds known",
                Brain.server(), Brain.kindCount()));
    }

    @Override
    public void onDisabled() {
        /* Anything switched off to measure it goes back on, and what was
           learned is written out. Ending an experiment must not silently
           become a configuration change. */
        restoreAll();
        Brain.save();
    }

    private static boolean isProtected(String name) {
        for (String entry : PROTECTED) {
            if (entry.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** Whether this module is one the focus list says is worth paying for. */
    private boolean inFocus(String name) {
        String raw = this.focus.getValue();
        if (raw == null || raw.trim().isEmpty()) {
            return true;
        }
        for (String entry : raw.split(",")) {
            if (entry.trim().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static double offMinutes(String module) {
        Brain.Exposure exposure = Brain.exposure(module);
        return exposure == null ? 0.0 : exposure.minutesOff;
    }

    private String serverKey() {
        /* Connected: that server. Not connected (switched on in a menu): the
           last server's file stays loaded on purpose -- no evidence accrues
           outside a world, and the next session's START switches it anyway. */
        String key = myau.management.ServerSession.currentServerKey();
        if (key == null) {
            key = myau.management.ServerSession.lastServerKey();
        }
        if (key != null) {
            return key;
        }
        ServerData data = mc.getCurrentServerData();
        return data == null || data.serverIP == null ? "singleplayer" : data.serverIP;
    }

    /**
     * The knowledge file is per server, and a network moves you between them
     * without this module being touched. Driven by ServerSession since
     * 2026-09-28; it used to poll the address every tick, which agreed with
     * nothing else about when the server had changed.
     */
    @EventTarget
    public void onSession(myau.events.SessionEvent event) {
        if (event.getType() == myau.events.SessionEvent.Type.START) {
            String key = event.getSession().serverKey();
            if (!key.equalsIgnoreCase(this.currentServer)) {
                /* What was decided about the last server is about the last
                   server: its cuts and switched-off modules go back, and the
                   per-module memory starts over. */
                endProbe();
                restoreAll();
                this.epochs.clear();
                this.lastHold = null;
                this.currentServer = key;
                Brain.setServer(key);
                log("SERVER  now " + key + " (" + Brain.kindCount() + " kinds known"
                        + (Brain.lastProblem().isEmpty() ? "" : "; file: " + Brain.lastProblem()) + ")");
            }
            Brain.beginSession();
        } else if (event.getType() == myau.events.SessionEvent.Type.END) {
            /* A disconnect is a natural point to write out what was learned. */
            Brain.save();
        }
    }

    /** Every module that could plausibly be a cause, and whether it is on. */
    private Map<String, Boolean> snapshotStates() {
        Map<String, Boolean> snapshot = new LinkedHashMap<String, Boolean>();
        for (Module module : Myau.moduleManager.modules.values()) {
            String name = module.getName();
            if (isProtected(name)) {
                continue;
            }
            snapshot.put(name, module.isEnabled());
        }
        return snapshot;
    }

    /** Called by FlagDetector for every violation it reports. */
    public static void observeFlag(String signature, int count) {
        Module module = Myau.moduleManager.modules.get(Adaptive.class);
        if (!(module instanceof Adaptive) || !module.isEnabled()) {
            return;
        }
        /* Called from the network thread. Building a fresh snapshot rather
           than reusing the field the client thread is also writing: sharing it
           meant two threads iterating and clearing the same map, which fails
           inside the packet handler rather than anywhere findable. */
        Brain.noteFlag(signature, count, ((Adaptive) module).snapshotStates());
        ((Adaptive) module).noteEpochFlag(signature, count);
    }

    /** Flags of the kind last acted on, while the module acted on is on: the evidence after. */
    private void noteEpochFlag(String signature, int count) {
        for (AdaptivePolicy.Epoch epoch : this.epochs.values()) {
            if (signature.equals(epoch.kind)) {
                Module target = Myau.moduleManager.getModule(epoch.module);
                if (target != null && target.isEnabled()) {
                    epoch.flagsOn += count;
                }
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE
                || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        long now = System.currentTimeMillis();
        /* The server switch is handled in onSession. */

        /* Time only counts while actually in a world. A client sitting in a
           menu accumulates minutes that no flag could ever have arrived in,
           and those minutes would dilute every rate in the model. */
        double minutes = (now - this.lastSample) / 60000.0;
        if (minutes > 0.0 && minutes < 1.0) {
            Brain.noteExposure(snapshotStates(), minutes);
            for (AdaptivePolicy.Epoch epoch : this.epochs.values()) {
                Module target = Myau.moduleManager.getModule(epoch.module);
                if (target != null && target.isEnabled()) {
                    epoch.minutesOn += minutes;
                }
            }
        }
        this.lastSample = now;

        if (this.probing != null && now >= this.probeUntil) {
            endProbe();
            return;
        }
        if (now - this.lastDecision < this.decideMinutes.getValue() * 60000L) {
            return;
        }
        this.lastDecision = now;
        Brain.save();
        decide();
    }

    /**
     * Act on the best-supported suspect, or else go and buy evidence about
     * whichever module is least understood.
     */
    private void decide() {
        if (this.probing != null) {
            return;
        }
        AdaptivePolicy.Settings settings = settings();
        AdaptivePolicy.Context context = context();
        List<Brain.Suspect> suspects = Brain.suspects(this.threshold.getValue());
        AdaptivePolicy.Outcome outcome = AdaptivePolicy.decide(suspects, settings, context);
        if (outcome.acts()) {
            Module module = Myau.moduleManager.getModule(outcome.chosen.module);
            if (module != null) {
                act(module, outcome.chosen, outcome);
                return;
            }
        }
        String hold = "HOLD    " + outcome.reason
                + (outcome.skipped.isEmpty() ? "" : "; passed over: " + outcome.skippedSummary());
        if (!hold.equals(this.lastHold)) {
            this.lastHold = hold;
            log(hold);
        }
        if (this.probe.getValue() && AdaptivePolicy.mayProbe(settings, context)) {
            beginProbe();
        }
    }

    private AdaptivePolicy.Settings settings() {
        AdaptivePolicy.Settings settings = new AdaptivePolicy.Settings();
        settings.minExcess = this.threshold.getValue();
        settings.minConfidence = this.minConfidence.getValue();
        settings.minSamples = this.minFlags.getValue();
        settings.warmupMs = this.warmupMinutes.getValue() * 60_000L;
        settings.maxActs = this.maxActs.getValue();
        settings.reactMinutes = this.reactMinutes.getValue();
        return settings;
    }

    private AdaptivePolicy.Context context() {
        return new AdaptivePolicy.Context() {
            @Override
            public long now() {
                return System.currentTimeMillis();
            }

            @Override
            public long sessionStartedAt() {
                myau.management.ServerSession.Session session = myau.management.ServerSession.current();
                return session == null ? 0L : session.startedAt();
            }

            @Override
            public String ineligible(String name) {
                Module module = Myau.moduleManager.getModule(name);
                if (module == null) {
                    return "unknown-module";
                }
                if (isProtected(name)) {
                    return "protected";
                }
                if (!module.isEnabled()) {
                    return "off";
                }
                if (!canCause(module)) {
                    return "cannot-cause";
                }
                if (settingsOnly(module)) {
                    return "settings-only";
                }
                return null;
            }

            @Override
            public AdaptivePolicy.Epoch epoch(String name) {
                return epochs.get(name);
            }
        };
    }

    /* ---- plausibility ---------------------------------------------------
       The 2026-09-23/24 log named Trajectories, Tracers and NoHurtCam as the
       causes of lagbacks -- modules that only draw. They were on during the
       same games as the real causes, so the coincidence counts were real; the
       conclusion was not. A module whose every handler is a render event
       cannot move the player or send a packet, and is never acted on. A
       module with no handlers at all works through mixins, which can change
       movement (KeepSprint does), so it stays a suspect -- except the few
       listed here that only change what is drawn. */
    private static final java.util.Set<String> RENDER_EVENTS = new java.util.HashSet<String>(
            java.util.Arrays.asList("Render2DEvent", "Render3DEvent", "RenderLivingEvent", "ResizeEvent"));
    private static final String[] COSMETIC = {"NoHurtCam", "ItemPhysics", "Animations", "Capes", "ViewClip",
            "HitParticleEffects"};
    private static final Map<Class<?>, Boolean> CAN_CAUSE =
            new java.util.concurrent.ConcurrentHashMap<Class<?>, Boolean>();

    /* Modules that are only settings read by others -- targeting rules,
       themes, colours, GUI choices. They change what other modules do, never
       how the player moves, and switching one off would change something
       unrelated to the evidence. (TargetFilter was the 10:06 dry-run pick.) */
    private static final String[] SETTINGS = {
            "TargetFilter", "Theme", "GuiModule", "ClickGUIModule", "Debug"
    };

    static boolean settingsOnly(Module module) {
        if (module instanceof ThemeStyle) {
            return true;
        }
        for (String name : SETTINGS) {
            if (name.equalsIgnoreCase(module.getName())) {
                return true;
            }
        }
        return false;
    }

    static boolean canCause(Module module) {
        Boolean known = CAN_CAUSE.get(module.getClass());
        if (known != null) {
            return known;
        }
        boolean result = true;
        for (String name : COSMETIC) {
            if (name.equalsIgnoreCase(module.getName())) {
                result = false;
            }
        }
        if (result) {
            boolean any = false;
            boolean nonRender = false;
            for (java.lang.reflect.Method method : module.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(EventTarget.class) && method.getParameterTypes().length == 1) {
                    any = true;
                    if (!RENDER_EVENTS.contains(method.getParameterTypes()[0].getSimpleName())) {
                        nonRender = true;
                    }
                }
            }
            result = !any || nonRender;
        }
        CAN_CAUSE.put(module.getClass(), result);
        return result;
    }

    /**
     * Turning a module down rather than off, where it has something to turn
     * down. The step is proportional to how much the evidence says it is
     * costing, so a module adding a tenth of a flag per minute is nudged and
     * one adding three is cut hard -- the alternative, a fixed step, either
     * takes all evening to help or overshoots on the first decision.
     */
    private void act(Module module, Brain.Suspect suspect, AdaptivePolicy.Outcome outcome) {
        double severity = Math.min(1.0, suspect.excess() / 2.0) * suspect.confidence;
        String detail;
        String oldValue;
        String newValue;

        Adjustable knob = knobFor(module);
        if (knob != null && severity < 0.8) {
            double from = knob.get();
            double to = from - (from - knob.floor) * (0.25 + 0.5 * severity);
            oldValue = String.format(java.util.Locale.ROOT, "%.2f", from);
            newValue = String.format(java.util.Locale.ROOT, "%.2f", Math.max(knob.floor, to));
            if (this.dryRun.getValue()) {
                detail = String.format("would set %s %.2f -> %.2f", knob.label, from, to);
            } else {
                knob.set(to);
                detail = String.format("set %s %.2f -> %.2f", knob.label, from, to);
            }
        } else {
            oldValue = "on";
            newValue = "off";
            if (this.dryRun.getValue()) {
                detail = "would disable " + module.getName();
            } else {
                this.restore.put(module.getName(), Boolean.TRUE);
                module.setEnabled(false);
                detail = "disabled " + module.getName();
            }
        }
        /* The evidence a second action on this module must rest on starts
           now -- in dry-run too, so the log shows what would really happen
           rather than the same line every round. */
        AdaptivePolicy.Epoch epoch = this.epochs.get(suspect.module);
        if (epoch == null) {
            epoch = new AdaptivePolicy.Epoch(suspect.module);
            this.epochs.put(suspect.module, epoch);
        }
        epoch.acted(suspect.kind, System.currentTimeMillis(), suspect.rateOff);
        this.actions++;

        /* Everything behind the decision, one line: what was observed, what
           it rests on, who is suspected and how surely, what changed and why. */
        long seenAgo = Math.max(0L, System.currentTimeMillis() - suspect.lastSeen) / 60000L;
        String record = String.format(java.util.Locale.ROOT,
                "DECIDE  %s | detector=FlagDetector kind=%s suspect=%s excess=%.2f/min on=%.2f off=%.2f"
                        + " min-on=%.1f min-off=%.1f flags-on=%.1f conf=%.0f%% n=%d recent=%.1f session=%d"
                        + " last-seen=%dm old=%s new=%s act=%d/%d reason=%s%s",
                detail, suspect.kind, suspect.module, suspect.excess(), suspect.rateOn, suspect.rateOff,
                suspect.minutesOn, suspect.minutesOff, suspect.flagsOn, suspect.confidence * 100.0,
                suspect.samples, suspect.recent, suspect.session, seenAgo, oldValue, newValue,
                epoch.acts, this.maxActs.getValue(), outcome.reason,
                outcome.skipped.isEmpty() ? "" : "; passed over: " + outcome.skippedSummary());
        log(record);
        synchronized (this.decisions) {
            this.decisions.addLast(record);
            while (this.decisions.size() > 20) {
                this.decisions.removeFirst();
            }
        }
        this.lastHold = null;
        say(String.format("&c%s &8(&f+%.2f&7 flags/min on &f%s&7, conf &f%.0f%%&8)",
                detail, suspect.excess(), suspect.kind, suspect.confidence * 100.0));
    }

    /** The most recent decisions, oldest first. */
    public List<String> recentDecisions() {
        synchronized (this.decisions) {
            return new ArrayList<String>(this.decisions);
        }
    }

    /**
     * Switch one module off for a while purely to find out what it is worth.
     *
     * Chosen as the one with the least time on its thinner side, which is the
     * measurement that most limits every conclusion. Nothing is learned about a
     * module that is never absent.
     */
    private void beginProbe() {
        List<String> candidates = new ArrayList<String>();
        for (Module module : Myau.moduleManager.modules.values()) {
            String name = module.getName();
            if (!isProtected(name) && module.isEnabled() && inFocus(name)
                    && !this.unprobeable.contains(name)) {
                candidates.add(name);
            }
        }
        String target = Brain.leastKnown(candidates);
        if (target == null) {
            return;
        }
        Module module = Myau.moduleManager.getModule(target);
        if (module == null || !module.isEnabled()) {
            return;
        }
        this.probing = target;
        this.probeUntil = System.currentTimeMillis() + this.probeMinutes.getValue() * 60000L;
        this.probeStartedOff = offMinutes(target);
        this.restore.put(target, Boolean.TRUE);
        module.setEnabled(false);
        Brain.Exposure exposure = Brain.exposure(target);
        log(String.format("PROBE   %s off for %dm (had on=%.1fm off=%.1fm)", target,
                this.probeMinutes.getValue(),
                exposure == null ? 0.0 : exposure.minutesOn,
                exposure == null ? 0.0 : exposure.minutesOff));
        say(String.format("&7measuring without &f%s&7 for &f%d&7 min", target,
                this.probeMinutes.getValue()));
    }

    private void endProbe() {
        String target = this.probing;
        this.probing = null;
        if (target == null) {
            return;
        }
        Boolean was = this.restore.remove(target);
        Module module = Myau.moduleManager.getModule(target);
        if (module != null && Boolean.TRUE.equals(was)) {
            module.setEnabled(true);
        }
        /* A probe that bought no evidence is a probe that will be chosen again
           immediately, and again after that. Whatever the reason -- the module
           refused to switch off, or the world was never entered -- it is set
           aside rather than retried in a loop. */
        if (offMinutes(target) - this.probeStartedOff < 0.5) {
            this.unprobeable.add(target);
            log("SKIP    " + target + " gained no off-time while probed; not probing it again");
        }
        Brain.Exposure exposure = Brain.exposure(target);
        log(String.format("PROBED  %s back on (on=%.1fm off=%.1fm)", target,
                exposure == null ? 0.0 : exposure.minutesOn,
                exposure == null ? 0.0 : exposure.minutesOff));
        say("&7done measuring &f" + target);
        Brain.save();
    }

    // ---- knobs --------------------------------------------------------

    /** A single number that makes a module less aggressive as it goes down. */
    private static final class Adjustable {
        final String label;
        final double floor;
        final boolean integral;
        private final java.util.function.Supplier<myau.property.Property<?>> target;

        Adjustable(String label, double floor, boolean integral,
                   java.util.function.Supplier<myau.property.Property<?>> target) {
            this.label = label;
            this.floor = floor;
            this.integral = integral;
            this.target = target;
        }

        /** Where this has it: its own override, else the player's value. */
        double get() {
            myau.property.Property<?> property = this.target.get();
            Object value = property.overrideOf(OWNER);
            if (value == null) {
                value = property.getBaseValue();
            }
            return ((Number) value).doubleValue();
        }

        /* A LEARNED override (plan step 7), not a write: never saved, and
           released when this module is switched off. */
        void set(double value) {
            double bounded = Math.max(this.floor, value);
            this.target.get().override(myau.property.Property.Source.LEARNED, OWNER, this.integral
                    ? (Object) Integer.valueOf((int) Math.round(bounded))
                    : (Object) Float.valueOf((float) bounded));
        }
    }

    /**
     * The one place that knows anything specific about a module, and it is
     * optional: a module without an entry here is simply switched off instead
     * of turned down, which is what happens to every module added later until
     * someone decides it deserves a gentler treatment.
     */
    private Adjustable knobFor(Module module) {
        if (module instanceof Reach) {
            final Reach reach = (Reach) module;
            return new Adjustable("Reach.range", 3.0, false, () -> reach.range);
        }
        if (module instanceof BackTrack) {
            final BackTrack backtrack = (BackTrack) module;
            return new Adjustable("BackTrack.delay", 50.0, true,
                    () -> backtrack.adaptive.getValue() ? backtrack.adaptiveDelay : backtrack.normalDelay);
        }
        if (module instanceof KnockbackDelay) {
            final KnockbackDelay delay = (KnockbackDelay) module;
            return new Adjustable("KnockbackDelay.chance", 0.0, true, () -> delay.chance);
        }
        return null;
    }

    // ---- output -------------------------------------------------------

    private void say(String message) {
        if (this.chat.getValue()) {
            ChatUtil.sendFormatted("&7[&bAdaptive&7] " + message);
        }
    }

    private void log(String line) {
        PrintWriter writer = null;
        try {
            if (!LOG_DIR.exists() && !LOG_DIR.mkdirs()) {
                return;
            }
            if (this.logTarget == null) {
                this.logTarget = new File(LOG_DIR, "adaptive.txt");
            }
            writer = new PrintWriter(new FileWriter(this.logTarget, true));
            writer.println(LINE_STAMP.format(new Date()) + "  [" + Brain.server() + "]  " + line);
            writer.flush();
        } catch (Exception ignored) {
            // A lost line must not end the run.
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    @Override
    public String[] getSuffix() {
        myau.management.ServerSession.Session session = myau.management.ServerSession.current();
        if (session != null && System.currentTimeMillis() - session.startedAt()
                < this.warmupMinutes.getValue() * 60_000L) {
            return new String[]{"warming up"};
        }
        if (this.probing != null) {
            long left = Math.max(0L, this.probeUntil - System.currentTimeMillis()) / 60000L;
            return new String[]{"probing " + this.probing + " " + left + "m"};
        }
        return new String[]{Brain.kindCount() + " kinds, " + this.actions + " acts"};
    }
}
