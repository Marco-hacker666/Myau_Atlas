package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;

/**
 * One switch for the diagnostic side of the client, with the individual tools
 * behind it.
 *
 * The instruments accumulated one at a time, each answering a question that had
 * come up that evening, and each arrived as its own entry in the module list.
 * That is five toggles to find and set before a test run and five to put back
 * afterwards, next to the modules that actually play the game -- which is how a
 * diagnostic gets left on for a week, or left off during the one game that
 * would have explained something.
 *
 * They are still separate modules: each has its own settings, its own state and
 * its own log, and merging that into a single class would trade five small
 * things that work for one large one that might. What changes is that they are
 * no longer reached individually. This module owns their enabled state, they
 * are taken out of the menu, and the sections below are what is switched.
 *
 * Because it owns them, a section turned off here also turns the module off,
 * including one left enabled in a saved config from before this existed.
 */
public class Debug extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    /** Server position corrections, classified and attributed. */
    public final BooleanProperty flags = new BooleanProperty("flags", true);
    /** Which swings landed, and why the rest did not. */
    public final BooleanProperty hits = new BooleanProperty("hits", true);
    /** One line per fight: hits, combos, knockback each way, the outcome. */
    public final BooleanProperty fights = new BooleanProperty("fights", true);
    /** Where the server still thinks you are aiming. */
    public final BooleanProperty aimLag = new BooleanProperty("aim-lag", false);
    /** Scales latency-sensitive settings down as the connection degrades. */
    public final BooleanProperty governor = new BooleanProperty("governor", false);

    /** Switch off whichever module the corrections keep naming. */
    public final BooleanProperty autoDisable = new BooleanProperty("auto-disable", true);
    /** Say what it would do without doing it. */
    public final BooleanProperty autoDisableDryRun = new BooleanProperty("auto-disable-dry-run", false,
            this.autoDisable::getValue);
    /** Flags naming one module inside the window before it is switched off. */
    public final IntProperty severity = new IntProperty("severity", 5, 2, 30,
            this.autoDisable::getValue);
    public final IntProperty window = new IntProperty("window-seconds", 30, 5, 300,
            this.autoDisable::getValue);

    /** Write every section's output to config/Myau as well as to the screen. */
    public final BooleanProperty logFiles = new BooleanProperty("log-files", true);
    public final BooleanProperty chat = new BooleanProperty("chat", true);

    private int timer;
    private boolean applied;

    public Debug() {
        super("Debug", false, false,
                "One switch for the diagnostic tools, with each behind its own section");
    }

    @Override
    public void onEnabled() {
        this.applied = false;
        this.timer = 0;
    }

    @Override
    public void onDisabled() {
        /* Everything this module turned on goes off with it, so leaving the
           panel is the same as never having opened it. */
        set(FlagDetector.class, false);
        set(HitCheck.class, false);
        set(FightLog.class, false);
        set(LatencyCrosshair.class, false);
        set(LatencyGovernor.class, false);
        set(FlagResponder.class, false);
    }

    private static void set(Class<?> type, boolean wanted) {
        Module module = Myau.moduleManager.modules.get(type);
        if (module != null && module.isEnabled() != wanted) {
            module.setEnabled(wanted);
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        /* Applied once on the first tick so a fresh world starts in the state
           the panel describes, then only often enough to notice a section being
           changed in the menu. Every call is guarded by the current state, so
           nothing is written unless it differs. */
        if (this.applied && ++this.timer < 20) {
            return;
        }
        this.timer = 0;
        this.applied = true;
        apply();
    }

    private void apply() {
        set(FlagDetector.class, this.flags.getValue());
        set(HitCheck.class, this.hits.getValue());
        set(FightLog.class, this.fights.getValue());
        set(LatencyCrosshair.class, this.aimLag.getValue());
        set(LatencyGovernor.class, this.governor.getValue());

        /* Nothing to respond to without the detector that produces the
           attributions, so the section follows it rather than standing alone. */
        boolean responder = this.autoDisable.getValue() && this.flags.getValue();
        set(FlagResponder.class, responder);

        Module module = Myau.moduleManager.modules.get(FlagResponder.class);
        if (module instanceof FlagResponder) {
            FlagResponder found = (FlagResponder) module;
            found.threshold.setValue(this.severity.getValue());
            found.window.setValue(this.window.getValue());
            found.dryRun.setValue(this.autoDisableDryRun.getValue());
            found.chat.setValue(this.chat.getValue());
        }
        Module detector = Myau.moduleManager.modules.get(FlagDetector.class);
        if (detector instanceof FlagDetector) {
            ((FlagDetector) detector).logFile.setValue(this.logFiles.getValue());
            ((FlagDetector) detector).chat.setValue(this.chat.getValue());
        }
        Module hitCheck = Myau.moduleManager.modules.get(HitCheck.class);
        if (hitCheck instanceof HitCheck) {
            ((HitCheck) hitCheck).logFile.setValue(this.logFiles.getValue());
        }
    }

    /** A one-line answer to "what is actually being watched right now". */
    public void report() {
        StringBuilder sb = new StringBuilder("&7[&bDebug&7] ");
        sb.append(this.flags.getValue() ? "&aflags " : "&8flags ");
        sb.append(this.hits.getValue() ? "&ahits " : "&8hits ");
        sb.append(this.aimLag.getValue() ? "&aaim-lag " : "&8aim-lag ");
        sb.append(this.governor.getValue() ? "&agovernor " : "&8governor ");
        sb.append(this.autoDisable.getValue() ? "&aauto-disable" : "&8auto-disable");
        ChatUtil.sendFormatted(sb.toString());
    }

    @Override
    public String[] getSuffix() {
        int on = 0;
        if (this.flags.getValue()) {
            on++;
        }
        if (this.hits.getValue()) {
            on++;
        }
        if (this.aimLag.getValue()) {
            on++;
        }
        if (this.governor.getValue()) {
            on++;
        }
        if (this.autoDisable.getValue()) {
            on++;
        }
        return new String[]{on + "/5"};
    }
}
