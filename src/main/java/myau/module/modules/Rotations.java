package myau.module.modules;

import myau.Myau;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;

/**
 * The settings of the shared rotation engine (util/RotationEngine), in one
 * place (2026-10-04).
 *
 * The engine turns the player one tick at a time. A module asks it for the
 * features it can live with -- NOISE, CURVE, EASE -- and the amounts come
 * from here, so every module that opts in turns the same way. Switched off,
 * no feature applies anywhere and every module turns exactly as it did
 * before these existed.
 *
 *   speed-noise  how much the speed of a turn wanders: a normal
 *                distribution (sd this % of the step) around a speed a
 *                little under the cap, smoothed from tick to tick. Replaces
 *                the uniform, only-slower drift.
 *   curve        how far a turn bows off its straight line: sd this % of
 *                the step, along a smooth random walk (curve-smooth is how
 *                much of the bow carries over each tick). The tick that lands
 *                on the target is never bowed.
 *   ease         slow down over the last ease-zone degrees, to no less than
 *                ease-floor a tick, instead of stopping dead at full speed.
 *                Costs ticks: a module whose timing is tight asks for it
 *                only by its own option.
 *
 * WHICH MODULES ASK, as of now: Clutch (with its humanize on: NOISE and
 * CURVE, and EASE with its own ease option). KillAura, AutoBlockIn and
 * Scaffold still turn their own way.
 */
public class Rotations extends Module {

    public final IntProperty speedNoise = new IntProperty("speed-noise", 12, 0, 40);
    public final IntProperty curve = new IntProperty("curve", 8, 0, 30);
    public final IntProperty curveSmooth = new IntProperty("curve-smooth", 70, 0, 95);
    public final BooleanProperty ease = new BooleanProperty("ease", true);
    public final FloatProperty easeZone = new FloatProperty("ease-zone", 25.0F, 5.0F, 90.0F);
    public final FloatProperty easeFloor = new FloatProperty("ease-floor", 5.0F, 1.0F, 30.0F);

    public Rotations() {
        super("Rotations", true, false,
                "Shared rotation engine settings: speed noise, curved turns and easing for the modules that use it");
    }

    /** The settings in force, or null when the module is off or not there (tests). */
    public static Rotations active() {
        try {
            Rotations rotations = (Rotations) Myau.moduleManager.modules.get(Rotations.class);
            return rotations != null && rotations.isEnabled() ? rotations : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
