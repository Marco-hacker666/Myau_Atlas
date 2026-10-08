package myau.property.properties;

import com.google.gson.JsonObject;
import myau.property.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

public class ModeProperty extends Property<Integer> {
    private String[] modes;
    /*
     * 2026-10-08: modes kept in the code but left out of the menus (hide), and
     * old names a saved config may still use (alias). A hidden mode still
     * loads from a config and still works; while it is the current value the
     * menu lists it too, so it can be switched away from.
     */
    private final Set<Integer> hidden = new HashSet<>();
    private final Map<String, String> aliases = new HashMap<>();

    public ModeProperty(String name, Integer value, String[] modes) {
        this(name, value, modes, null);
    }

    public ModeProperty(String name, Integer value, String[] modes, BooleanSupplier check) {
        super(name, value, check);
        this.modes = modes;
    }

    /** Leaves these modes out of the menus. */
    public ModeProperty hide(String... names) {
        for (String name : names) {
            for (int i = 0; i < this.modes.length; i++) {
                if (this.modes[i].equals(name)) {
                    this.hidden.add(i);
                }
            }
        }
        return this;
    }

    /** A config saying {@code old} means {@code now}. */
    public ModeProperty alias(String old, String now) {
        this.aliases.put(old.toLowerCase(), now);
        return this;
    }

    /** The indices the menus offer, in order: the visible modes and the current one. */
    public int[] visibleIndices() {
        List<Integer> out = new ArrayList<>();
        Integer current = this.getValue();
        for (int i = 0; i < this.modes.length; i++) {
            if (!this.hidden.contains(i) || current != null && current == i) {
                out.add(i);
            }
        }
        int[] array = new int[out.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = out.get(i);
        }
        return array;
    }

    @Override
    public String getValuePrompt() {
        int[] visible = this.visibleIndices();
        String[] names = new String[visible.length];
        for (int i = 0; i < visible.length; i++) {
            names[i] = this.modes[visible[i]];
        }
        return String.join(", ", names);
    }

    /** Picks the {@code index}-th entry of the menu (getValuePrompt's order). */
    public boolean setVisible(int index) {
        int[] visible = this.visibleIndices();
        return index >= 0 && index < visible.length && this.setValue(visible[index]);
    }

    public String getModeString() {
        int index = this.getValue();
        return index >= 0 && index < this.modes.length ? this.modes[index] : "";
    }

    @Override
    public String formatValue() {
        String index = this.getModeString();
        return index.isEmpty() ? "&4?" : String.format("&9%s", index);
    }

    @Override
    public boolean parseString(String string) {
        String renamed = this.aliases.get(string.toLowerCase());
        if (renamed != null) {
            string = renamed;
        }
        for (int i = 0; i < this.modes.length; i++) {
            if (string.equals(this.modes[i])) {
                return this.setValue(i);
            }
        }

        String valueStr = string.replace("_", "");
        for (int i = 0; i < this.modes.length; i++) {
            if (valueStr.equalsIgnoreCase(this.modes[i].replace("_", ""))) {
                return this.setValue(i);
            }
        }
        return false;
    }

    @Override
    public boolean read(JsonObject jsonObject) {
        return this.parseString(jsonObject.get(this.getName()).getAsString());
    }

    @Override
    public void write(JsonObject jsonObject) {
        jsonObject.addProperty(this.getName(), this.getModeString());
    }

    public void nextMode() {
        int next = this.getValue();
        for (int n = 0; n < this.modes.length; n++) {
            next = next + 1 >= this.modes.length ? 0 : next + 1;
            if (!this.hidden.contains(next)) {
                break;
            }
        }
        this.setValue(next);
    }

    public void previousMode() {
        int prev = this.getValue();
        for (int n = 0; n < this.modes.length; n++) {
            prev = prev - 1 < 0 ? this.modes.length - 1 : prev - 1;
            if (!this.hidden.contains(prev)) {
                break;
            }
        }
        this.setValue(prev);
    }

    public void setModes(String[] modes) {
        this.modes = modes;
        if (this.getValue() >= modes.length) {
            this.setValue(0);
        }
    }
}
