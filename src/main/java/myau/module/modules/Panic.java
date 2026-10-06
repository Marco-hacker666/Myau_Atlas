package myau.module.modules;

import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.TextProperty;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * One key that switches everything off, in the right order, and hands back
 * anything being withheld.
 *
 * The thing this client did not have. Twice in one evening a module misbehaved
 * badly enough to end in a disconnect, and on both occasions the only way to
 * stop it was to find the module in a menu while the screen was arguing with
 * the server about where the player was standing. A client that can hold a
 * second of movement needs a way to let go of it that does not involve
 * reading.
 *
 * SWITCHING OFF IS NOT ENOUGH ON ITS OWN, WHICH IS THE WHOLE REASON THIS IS A
 * MODULE RATHER THAN A LOOP. Three of the things that can be on are holding
 * outgoing packets at the moment the key is pressed, and disabling them in the
 * wrong order -- or disabling something else first and leaving them holding
 * for another few hundred milliseconds -- releases a backlog into a situation
 * that is already going wrong. So the packet holders are dealt with first and
 * explicitly: the blink queue is thrown away rather than sent, because in a
 * panic the held positions describe a journey nobody wants the server to be
 * told about, and the lag manager's delay is set to zero so that whatever it
 * still has leaves immediately at a normal rate. Only then does everything
 * else go off.
 *
 * WHAT IT LEAVES ALONE. Anything that only draws, and anything that only
 * measures. Switching off the menu, the HUD, the flag log and the hit log in
 * a panic would remove the display on the way out and the record of what
 * happened afterwards, which are the two things most wanted in the minute
 * after a panic. The keep list is the same one Adaptive uses to decide what it
 * may not experiment with, for the same reason: these are instruments, not
 * behaviour.
 *
 * IT DOES NOT RE-ENABLE ANYTHING. There is deliberately no undo. Whatever was
 * on when the key was pressed was, by hypothesis, part of the problem, and a
 * key that restores the exact state that just went wrong is a key that fires
 * twice by accident. The list of what it switched off is printed, so putting
 * back the wanted half is a matter of reading rather than remembering.
 */
public class Panic extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    /**
     * Modules a panic leaves running.
     *
     * Everything here either draws something or records something. None of it
     * sends a packet or changes how the player moves, so none of it can be
     * what the panic is about.
     */
    private static final String[] KEEP = {
            "Panic", "ClickGUI", "ClickGui", "HUD",
            "Debug", "FlagDetector", "FlagResponder", "HitCheck", "PacketLogger",
            "ServerFingerprint", "ServerProfiles", "Statistics", "Adaptive", "AutoTune",
            "LatencyCrosshair", "TargetHUD", "TargetESP", "ESP", "ESP2D", "Chams",
            "BedESP", "ItemESP", "ChestESP", "Tracers", "NameTags", "Indicators",
            "Hotbar", "Animations", "RenderFixes", "FullBright", "BlockOverlay",
            "Radar", "BedTracker", "BedwarUtils", "TeamHealthDisplay", "DynamicIsland",
            "WaterMark", "WaterMark2", "FPScounter", "Capes", "AntiBot",
            "ClientSpoofer", "ResourceSpoofer", "MouseRawInput", "Ambience"
    };

    /**
     * Extra names to keep, comma separated.
     *
     * Because the list above is a judgement about which modules are
     * instruments, and judgements about somebody else's setup are usually
     * slightly wrong.
     */
    public final TextProperty keepAlso = new TextProperty("keep-also", "");

    /** Whether to say in chat what was switched off. */
    public final BooleanProperty report = new BooleanProperty("report", true);

    /**
     * Whether to discard the blink queue rather than send it.
     *
     * On. A panic is not the moment to argue with the server about a second of
     * movement it never saw; the held positions are worth less than the
     * correction that sending them invites.
     */
    public final BooleanProperty dropHeld = new BooleanProperty("drop-held-packets", true);

    private boolean armed;

    public Panic() {
        super("Panic", false, false,
                "Switches every behaviour module off at once and releases anything being withheld");
    }

    /**
     * Runs the panic and switches itself back off.
     *
     * Done from the tick rather than from here, because this is called from
     * the key handler and the packet holders want to be released on the client
     * thread in a known state -- the same reason Blink defers its own stop by
     * a tick.
     */
    @Override
    public void onEnabled() {
        this.armed = true;
    }

    @EventTarget(whenDisabled = true)
    public void onTick(TickEvent event) {
        if (!this.armed || event.getType() != EventType.POST) {
            return;
        }
        this.armed = false;
        panic();
        /* A switch that stays on would make the second press a no-op, and the
           second press is the one somebody makes when the first did not appear
           to work. */
        this.setEnabled(false);
    }

    private void panic() {
        List<String> stopped = new ArrayList<String>();

        /* One. The packet holders, before anything else and in their own step.
           Whatever else is wrong, a queue that is still filling while the rest
           of this runs is a queue that gets released a moment later into a
           worse situation. */
        releaseHeld(stopped);

        /* Two. Everything that is not an instrument. */
        for (Module module : Myau.moduleManager.modules.values()) {
            if (module == null || !module.isEnabled() || keep(module)) {
                continue;
            }
            module.setEnabled(false);
            if (!stopped.contains(module.getName())) {
                stopped.add(module.getName());
            }
        }

        if (this.report.getValue()) {
            announce(stopped);
        }
    }

    /**
     * Gives back anything being withheld, before the modules holding it are
     * switched off.
     *
     * Order matters here and it is not obvious. Disabling Blink first would
     * make it release its queue through its own path on the way out, which is
     * exactly the burst a panic is trying to avoid; so the queue is emptied
     * first and the module is switched off afterwards with nothing left to
     * send.
     */
    private void releaseHeld(List<String> stopped) {
        try {
            if (Myau.blinkManager != null && Myau.blinkManager.isBlinking()) {
                if (this.dropHeld.getValue()) {
                    /* Movement dropped; held transaction replies still go
                       (hold-transactions, see BlinkManager.discardHeld). */
                    Myau.blinkManager.discardHeld();
                }
                Myau.blinkManager.setBlinkState(false, Myau.blinkManager.getBlinkingModule());
                stopped.add("Blink queue");
            }
        } catch (Exception ignored) {
            // Nothing useful to do; the rest of the panic still runs.
        }
        try {
            if (Myau.lagManager != null) {
                /* Zero means the manager stops holding. Note that its next
                   flush sends everything still queued in one pass -- there is
                   no rate limit on that path -- so this is a short burst, not
                   a gradual release. */
                Myau.lagManager.setDelay(0);
            }
        } catch (Exception ignored) {
            // As above.
        }
        /* FakeLag holds its own queue and empties it in onDisabled, which is
           the behaviour wanted here, so it is left to the loop below. It is
           named now so the report reads in the order things happened. */
        Module fakeLag = Myau.moduleManager.getModule("FakeLag");
        if (fakeLag != null && fakeLag.isEnabled()) {
            fakeLag.setEnabled(false);
            stopped.add("FakeLag");
        }
    }

    /**
     * Whether a module survives the panic, matched on either of its names.
     *
     * A module has two: the one it registers itself under and the one its
     * class is called, and in this client they frequently differ --
     * ClickGUIModule registers as "ClickGUI", GuiModule as "ClickGui" (and,
     * until it was removed on 2026-10-06, RiseClickGUIModule as "RiseClickGUI").
     * The first version of this list was
     * written by reading the file names, so all three of those entries matched
     * nothing and the first real panic switched off the menu. Which is a
     * particularly bad thing for a panic to do, since the menu is how anything
     * gets switched back on.
     *
     * Matching both names is the fix rather than correcting the three
     * spellings, because correcting them leaves the next person writing an
     * entry from a file listing with the same silent failure. A name that
     * matches nothing should be the exception, not the default.
     */
    private boolean keep(Module module) {
        String name = module.getName();
        String className = module.getClass().getSimpleName();
        for (String entry : KEEP) {
            if (entry.equalsIgnoreCase(name) || entry.equalsIgnoreCase(className)) {
                return true;
            }
        }
        String extra = this.keepAlso.getValue();
        if (extra == null || extra.trim().isEmpty()) {
            return false;
        }
        for (String entry : extra.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.equalsIgnoreCase(name) || trimmed.equalsIgnoreCase(className)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Prints what was switched off, in a few lines rather than one long one.
     *
     * The point of the list is that it is the only record of what to put back,
     * so it has to be readable rather than merely present.
     */
    private void announce(List<String> stopped) {
        if (stopped.isEmpty()) {
            ChatUtil.sendFormatted("&7[&cPanic&7] &fnothing was on");
            return;
        }
        ChatUtil.sendFormatted("&7[&cPanic&7] &fstopped &c" + stopped.size() + "&f:");
        StringBuilder line = new StringBuilder();
        for (String name : stopped) {
            if (line.length() > 0 && line.length() + name.length() > 60) {
                ChatUtil.sendFormatted("&8  " + line);
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append("&7, &8");
            }
            line.append(name);
        }
        if (line.length() > 0) {
            ChatUtil.sendFormatted("&8  " + line);
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{"bind a key"};
    }
}
