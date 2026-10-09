package myau.config.online;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import myau.Myau;
import myau.module.Module;
import myau.property.Property;
import myau.property.properties.DragProperty;

import java.util.ArrayList;

// Ported from OpenSkid (GPL-3.0), itself adapted from the MiauMinus
// online-config concept.
// Mirrors this client's config JSON shape (config/Myau/*.json). Every property
// read is isolated in its own try/catch so one bad value cannot break the rest
// of the apply.
public class OnlineConfigApplier {

    public static final class Result {
        public int applied;
        public int failed;
        public final ArrayList<String> changed = new ArrayList<String>();
        public final ArrayList<String> failedNames = new ArrayList<String>();
    }

    public Result apply(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty config data");
        }
        JsonElement parsed = new JsonParser().parse(json);
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IllegalArgumentException("Invalid config JSON: expected an object");
        }
        JsonObject root = parsed.getAsJsonObject();
        Result result = new Result();
        for (Module module : Myau.moduleManager.modules.values()) {
            JsonObject object = findModuleObject(root, module);
            if (object == null) {
                continue;
            }
            applyFlag(object, "toggled", module.getName(), result, new FlagWriter() {
                @Override
                public void write(boolean value) {
                    module.setEnabled(value);
                }
            });
            applyInt(object, "key", module.getName(), result, new IntWriter() {
                @Override
                public void write(int value) {
                    module.setKey(value);
                }
            });
            applyFlag(object, "hidden", module.getName(), result, new FlagWriter() {
                @Override
                public void write(boolean value) {
                    module.setHidden(value);
                }
            });

            ArrayList<Property<?>> list = Myau.propertyManager.properties.get(module.getClass());
            if (list == null) {
                continue;
            }
            for (Property<?> property : list) {
                if (!object.has(property.getName()) && !hasDragKeys(object, property)) {
                    continue;
                }
                String label = module.getName() + "." + property.getName();
                try {
                    if (property.read(object)) {
                        result.applied++;
                        result.changed.add(label);
                    } else {
                        result.failed++;
                        result.failedNames.add(label);
                    }
                } catch (Exception e) {
                    result.failed++;
                    result.failedNames.add(label);
                }
            }
        }
        return result;
    }

    private boolean hasDragKeys(JsonObject object, Property<?> property) {
        return property instanceof DragProperty
                && (object.has(property.getName() + "_x") || object.has(property.getName() + "_y"));
    }

    private JsonObject findModuleObject(JsonObject root, Module module) {
        JsonElement element = root.get(module.getName());
        if (element == null || !element.isJsonObject()) {
            element = root.get(module.getClass().getSimpleName());
        }
        if (element != null && element.isJsonObject()) {
            return element.getAsJsonObject();
        }
        return null;
    }

    private void applyFlag(JsonObject object, String key, String moduleName, Result result, FlagWriter writer) {
        if (!object.has(key)) {
            return;
        }
        try {
            JsonElement element = object.get(key);
            if (element != null && element.isJsonPrimitive()) {
                writer.write(element.getAsBoolean());
                result.applied++;
                result.changed.add(moduleName + "." + key);
            }
        } catch (Exception e) {
            result.failed++;
            result.failedNames.add(moduleName + "." + key);
        }
    }

    private void applyInt(JsonObject object, String key, String moduleName, Result result, IntWriter writer) {
        if (!object.has(key)) {
            return;
        }
        try {
            JsonElement element = object.get(key);
            if (element != null && element.isJsonPrimitive()) {
                writer.write(element.getAsInt());
                result.applied++;
                result.changed.add(moduleName + "." + key);
            }
        } catch (Exception e) {
            result.failed++;
            result.failedNames.add(moduleName + "." + key);
        }
    }

    private interface FlagWriter {
        void write(boolean value);
    }

    private interface IntWriter {
        void write(int value);
    }
}
