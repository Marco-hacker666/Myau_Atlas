package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.util.ChatUtil;
import myau.util.SoundUtil;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.Map;

/**
 * Switches off whichever module the server keeps disagreeing with.
 *
 * {@link FlagDetector} already works out what the client was doing when a
 * correction arrived, and it already says so. Saying so is where it stopped:
 * reading the cause, deciding a module was responsible and turning it off was
 * left to a person watching chat mid-fight, which in practice meant several
 * games of suspicion before anything changed.
 *
 * The rule here is deliberately dull. A module is switched off only when it was
 * named in enough separate corrections inside one window -- not on a single
 * flag, which at this latency proves nothing -- and only modules that withhold
 * packets or place blocks are eligible, because those are the ones whose cause
 * the detector can actually establish. Everything else is left alone.
 *
 * Nothing is ever switched back on. A module that earned its way off stays off
 * until the player decides otherwise, and the announcement names both the
 * module and the count so the decision can be disagreed with.
 */
public class FlagResponder extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    /**
     * Modules never switched off automatically, whatever the counts say.
     *
     * Two kinds. The instruments, because switching off what is doing the
     * measuring ends the measurement rather than the problem -- and they act on
     * every flag by definition, so without this they would be the first to go.
     * And the purely visual ones, which cannot produce a server correction and
     * could only ever be named by coincidence.
     *
     * Everything else is eligible. The old version listed the eight modules it
     * knew how to blame and could act on nothing else; with the ledger naming
     * whoever actually withheld or sent a packet, the list that matters is the
     * one saying what must never be touched.
     */
    private static final String[] PROTECTED = {
            "Debug", "FlagDetector", "FlagResponder", "HitCheck", "LatencyCrosshair",
            "LatencyGovernor", "HUD", "ESP", "Chams", "BedESP", "ItemESP", "TargetESP",
            "Indicators", "Hotbar", "Animations", "RenderFixes", "Fullbright",
            "ClientSpoofer", "ResourceSpoofer", "AntiBot", "BedTracker", "ServerProfiles",
            /* The other tuners and instruments (2026-09-28): never a culprit. */
            "Adaptive", "AutoTune", "ServerFingerprint", "FightLog"
    };

    private static boolean isProtected(String name) {
        for (String entry : PROTECTED) {
            if (entry.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    public final IntProperty window = new IntProperty("window-seconds", 30, 5, 300);
    public final IntProperty threshold = new IntProperty("threshold", 5, 2, 50);
    /** Silence after acting, so one bad stretch cannot cascade. */
    public final IntProperty cooldown = new IntProperty("cooldown-seconds", 60, 5, 600);
    /** Report the decision without taking it, for deciding whether to trust it. */
    public final BooleanProperty dryRun = new BooleanProperty("dry-run", false);
    public final BooleanProperty chat = new BooleanProperty("chat", true);
    public final BooleanProperty sound = new BooleanProperty("sound", true);

    private final Map<String, Long> lastAction = new HashMap<String, Long>();
    private int disabled;
    private int checkTimer;

    public FlagResponder() {
        super("FlagResponder", false, false,
                "Turns off whichever module keeps being blamed for server corrections");
    }

    @Override
    public void onEnabled() {
        this.lastAction.clear();
        this.disabled = 0;
        this.checkTimer = 0;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE
                || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        /* Once a second is plenty: the window is measured in tens of seconds
           and the counts cannot change faster than corrections arrive. */
        if (++this.checkTimer < 20) {
            return;
        }
        this.checkTimer = 0;

        Module module = Myau.moduleManager.modules.get(FlagDetector.class);
        if (!(module instanceof FlagDetector) || !module.isEnabled()) {
            return;
        }
        FlagDetector detector = (FlagDetector) module;
        long windowMillis = this.window.getValue() * 1000L;
        long now = System.currentTimeMillis();

        /* Whatever the detector has been naming, rather than a list written
           here: a module added later, or one acting through a shared manager,
           is eligible on the same terms as the rest. */
        for (String name : detector.implicatedNames()) {
            if (isProtected(name)) {
                continue;
            }
            Module watched = Myau.moduleManager.getModule(name);
            if (watched == null || !watched.isEnabled()) {
                continue;
            }
            Long acted = this.lastAction.get(name);
            if (acted != null && now - acted < this.cooldown.getValue() * 1000L) {
                continue;
            }
            int count = detector.implicationsWithin(name, windowMillis);
            if (count < this.threshold.getValue()) {
                continue;
            }
            this.lastAction.put(name, now);
            act(watched, name, count);
        }
    }

    private void act(Module watched, String name, int count) {
        String verb;
        if (this.dryRun.getValue()) {
            verb = "&ewould disable";
        } else {
            /* The counts are cleared with the module: leaving them would let
               the same stretch of corrections justify acting again the moment
               the cooldown lapses. */
            watched.setEnabled(false);
            Module detector = Myau.moduleManager.modules.get(FlagDetector.class);
            if (detector instanceof FlagDetector) {
                ((FlagDetector) detector).clearImplications(name);
            }
            this.disabled++;
            verb = "&cdisabled";
        }
        if (this.chat.getValue()) {
            ChatUtil.sendFormatted(String.format(
                    "&7[&bFlagResponder&7] %s &f%s&7 &8(&f%d&7 flags in %ds&8)",
                    verb, name, count, this.window.getValue()));
        }
        if (this.sound.getValue()) {
            SoundUtil.playSound("note.pling");
        }
    }

    @Override
    public String[] getSuffix() {
        if (this.dryRun.getValue()) {
            return new String[]{"dry-run"};
        }
        return new String[]{this.disabled == 0 ? "watching" : this.disabled + " off"};
    }
}
