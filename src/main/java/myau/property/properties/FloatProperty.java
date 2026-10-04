package myau.property.properties;

import com.google.gson.JsonObject;
import myau.property.Property;

import java.util.function.BooleanSupplier;

public class FloatProperty extends Property<Float> {
    private final Float minimum;
    private final Float maximum;

    public FloatProperty(String name, Float value, Float minimum, Float maximum) {
        this(name, value, minimum, maximum, null);
    }

    public FloatProperty(String string, Float value, Float minimum, Float maximum, BooleanSupplier check) {
        super(string, value, floatV -> floatV >= minimum && floatV <= maximum, check);
        this.minimum = minimum;
        this.maximum = maximum;
    }

    @Override
    public String getValuePrompt() {
        return String.format("%s-%s", this.minimum, this.maximum);
    }

    @Override
    public String formatValue() {
        return String.format("&6%s", this.getValue());
    }

    @Override
    public boolean parseString(String string) {
        return this.setValue(Float.parseFloat(string));
    }

    @Override
    public boolean read(JsonObject jsonObject) {
        return this.setValue(jsonObject.get(this.getName()).getAsNumber().floatValue());
    }

    /* The base, never an override: a trial or a governed cut is not the
       player's choice and is not saved (Property, plan step 7). */
    @Override
    public void write(JsonObject jsonObject) {
        jsonObject.addProperty(this.getName(), this.getPersistedValue());
    }

    @Override
    protected boolean supportsOverride() {
        return true;
    }

    public Float getMinimum() {
        return minimum;
    }

    public Float getMaximum() {
        return maximum;
    }
}
