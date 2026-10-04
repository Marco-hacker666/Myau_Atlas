package myau.property;

import myau.property.properties.IntProperty;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * A whole-number setting that is a range, drawn from each time it is used
 * (2026-09-28, item 5 of the Rise comparison; Rise's BoundsNumberValue).
 *
 * Randomising a setting used to be each module's own affair -- a min and a
 * max property, a clamp in verifyValue, a RandomUtil call that misbehaves
 * when min is above max. This is that once: two ordinary IntProperty
 * settings ("name-min", "name-max") that every menu already draws and the
 * config already saves, and a draw that is always inside them, whichever
 * way round the player left the two sliders.
 *
 * An existing pair can be wrapped (of) without renaming it, so a module
 * adopts this without losing anyone's saved values.
 */
public final class IntRange implements PropertyGroup {
    private static final Random RANDOM = new Random();

    private final IntProperty min;
    private final IntProperty max;
    /** Whether the two settings are this group's to register (false: fields of their own). */
    private final boolean owned;

    public IntRange(String name, int min, int max, int lowest, int highest) {
        this(name, min, max, lowest, highest, null);
    }

    public IntRange(String name, int min, int max, int lowest, int highest, BooleanSupplier visible) {
        this(new IntProperty(name + "-min", min, lowest, highest, visible),
                new IntProperty(name + "-max", max, lowest, highest, visible), true);
    }

    private IntRange(IntProperty min, IntProperty max, boolean owned) {
        this.min = min;
        this.max = max;
        this.owned = owned;
    }

    /** A range over two settings the module already has as fields. */
    public static IntRange of(IntProperty min, IntProperty max) {
        return new IntRange(min, max, false);
    }

    @Override
    public List<Property<?>> properties() {
        return this.owned ? Arrays.<Property<?>>asList(this.min, this.max) : Collections.<Property<?>>emptyList();
    }

    /** The lower end, whichever slider holds it. */
    public int low() {
        return Math.min(this.min.getValue(), this.max.getValue());
    }

    /** The upper end, whichever slider holds it. */
    public int high() {
        return Math.max(this.min.getValue(), this.max.getValue());
    }

    /** A value from low to high inclusive, uniformly. */
    public int random() {
        return random(RANDOM);
    }

    public int random(Random random) {
        int low = low();
        int high = high();
        return low == high ? low : low + random.nextInt(high - low + 1);
    }

    public boolean contains(int value) {
        return value >= low() && value <= high();
    }

    public IntProperty minProperty() {
        return this.min;
    }

    public IntProperty maxProperty() {
        return this.max;
    }

    /** "8-14", or "12" when both ends agree. */
    public String describe() {
        return low() == high() ? String.valueOf(low()) : low() + "-" + high();
    }
}
