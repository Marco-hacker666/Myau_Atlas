package myau.property;

import myau.property.properties.FloatProperty;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * A decimal setting that is a range, drawn from each time it is used
 * (2026-09-28); see IntRange, which this mirrors.
 */
public final class FloatRange implements PropertyGroup {
    private static final Random RANDOM = new Random();

    private final FloatProperty min;
    private final FloatProperty max;
    private final boolean owned;

    public FloatRange(String name, float min, float max, float lowest, float highest) {
        this(name, min, max, lowest, highest, null);
    }

    public FloatRange(String name, float min, float max, float lowest, float highest, BooleanSupplier visible) {
        this(new FloatProperty(name + "-min", min, lowest, highest, visible),
                new FloatProperty(name + "-max", max, lowest, highest, visible), true);
    }

    private FloatRange(FloatProperty min, FloatProperty max, boolean owned) {
        this.min = min;
        this.max = max;
        this.owned = owned;
    }

    /** A range over two settings the module already has as fields. */
    public static FloatRange of(FloatProperty min, FloatProperty max) {
        return new FloatRange(min, max, false);
    }

    @Override
    public List<Property<?>> properties() {
        return this.owned ? Arrays.<Property<?>>asList(this.min, this.max) : Collections.<Property<?>>emptyList();
    }

    public float low() {
        return Math.min(this.min.getValue(), this.max.getValue());
    }

    public float high() {
        return Math.max(this.min.getValue(), this.max.getValue());
    }

    /** A value from low to high, uniformly. */
    public float random() {
        return random(RANDOM);
    }

    public float random(Random random) {
        float low = low();
        return low + random.nextFloat() * (high() - low);
    }

    /** The point t of the way from low to high (t from 0 to 1). */
    public float lerp(float t) {
        return low() + (high() - low()) * Math.max(0.0F, Math.min(1.0F, t));
    }

    public boolean contains(float value) {
        return value >= low() && value <= high();
    }

    public FloatProperty minProperty() {
        return this.min;
    }

    public FloatProperty maxProperty() {
        return this.max;
    }

    /** "2.8-3.2", or "3.0" when both ends agree. */
    public String describe() {
        return low() == high() ? String.format(Locale.ROOT, "%.2f", low())
                : String.format(Locale.ROOT, "%.2f-%.2f", low(), high());
    }
}
