package myau.property.properties;

import com.google.gson.JsonObject;
import myau.property.Property;
import myau.util.KeyBindUtil;
import org.lwjgl.input.Keyboard;

import java.util.function.BooleanSupplier;

/**
 * An LWJGL key code held as a module setting.
 *
 * Distinct from a module's own keybind, which toggles the module: this is for
 * settings that need a key of their own, such as a hold-to-activate control.
 * Stored as a plain number so it round-trips through the config the same way
 * an IntProperty does.
 */
public class KeyProperty extends Property<Integer> {

    public KeyProperty(String name, Integer value) {
        this(name, value, null);
    }

    public KeyProperty(String name, Integer value, BooleanSupplier check) {
        super(name, value, v -> v != null && v >= 0, check);
    }

    public boolean isDown() {
        int key = this.getValue();
        return key != Keyboard.KEY_NONE && Keyboard.isKeyDown(key);
    }

    public String getKeyName() {
        int key = this.getValue();
        return key == Keyboard.KEY_NONE ? "None" : KeyBindUtil.getKeyName(key);
    }

    @Override
    public String getValuePrompt() {
        return "key";
    }

    @Override
    public String formatValue() {
        return String.format("&e%s", this.getKeyName());
    }

    @Override
    public boolean parseString(String string) {
        if ("none".equalsIgnoreCase(string)) {
            return this.setValue(Keyboard.KEY_NONE);
        }
        int key = Keyboard.getKeyIndex(string.toUpperCase());
        return key != Keyboard.KEY_NONE && this.setValue(key);
    }

    @Override
    public boolean read(JsonObject jsonObject) {
        return this.setValue(jsonObject.get(this.getName()).getAsNumber().intValue());
    }

    @Override
    public void write(JsonObject jsonObject) {
        jsonObject.addProperty(this.getName(), this.getValue());
    }
}
