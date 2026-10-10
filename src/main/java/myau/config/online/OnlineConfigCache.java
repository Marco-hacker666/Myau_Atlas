package myau.config.online;

import com.google.gson.JsonObject;
import myau.Myau;
import myau.config.Config;
import myau.module.Module;
import myau.property.Property;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

// Ported from OpenSkid (GPL-3.0), itself adapted from the MiauMinus
// online-config concept.
// Local disk cache for fetched JSON configs. Nothing here touches the network
// and nothing applies on its own. Callers fetch, then apply explicitly.
public final class OnlineConfigCache {
    /* Next to this client's own configs (config/Myau/), so a shared file sits
       with the profiles it applies over. */
    private static final File DIR = new File("./config/Myau/online");

    private OnlineConfigCache() {
    }

    public static File dir() {
        if (!DIR.exists()) {
            DIR.mkdirs();
        }
        return DIR;
    }

    public static String sanitize(String name) {
        if (name == null) {
            return "shared";
        }
        String base = name.trim();
        int query = base.indexOf('?');
        if (query >= 0) {
            base = base.substring(0, query);
        }
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        if (base.toLowerCase(Locale.ROOT).endsWith(".json")) {
            base = base.substring(0, base.length() - 5);
        }
        base = base.replaceAll("[^A-Za-z0-9._-]+", "_");
        base = base.replaceAll("^\\.+", "");
        if (base.length() > 64) {
            base = base.substring(0, 64);
        }
        if (base.isEmpty() || base.equals("json")) {
            return "shared";
        }
        return base;
    }

    public static File fileFor(String name) {
        return new File(dir(), sanitize(name) + ".json");
    }

    public static boolean has(String name) {
        try {
            return fileFor(name).isFile();
        } catch (Exception e) {
            return false;
        }
    }

    public static void save(String name, String json) throws IOException {
        if (json == null || json.trim().isEmpty()) {
            throw new IOException("Refusing to cache empty config");
        }
        File file = fileFor(name);
        Writer writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
        try {
            writer.write(json);
        } finally {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static String load(String name) throws IOException {
        File file = fileFor(name);
        if (!file.isFile()) {
            throw new IOException("No cached config named \"" + sanitize(name) + "\"");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        InputStream in = new FileInputStream(file);
        try {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
        return out.toString("UTF-8");
    }

    public static List<OnlineConfigEntry> list() {
        File[] files = dir().listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name != null && name.toLowerCase(Locale.ROOT).endsWith(".json");
            }
        });
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File first, File second) {
                return Long.compare(second.lastModified(), first.lastModified());
            }
        });
        ArrayList<OnlineConfigEntry> entries = new ArrayList<OnlineConfigEntry>();
        for (File file : files) {
            String fileName = file.getName();
            String id = fileName.substring(0, fileName.length() - 5);
            entries.add(new OnlineConfigEntry(id, id, file.getPath(), file.lastModified(), file.length()));
        }
        return entries;
    }

    public static OnlineConfigEntry find(String name) {
        if (name == null) {
            return null;
        }
        String id = sanitize(name);
        for (OnlineConfigEntry entry : list()) {
            if (entry.getId().equalsIgnoreCase(id) || entry.getName().equalsIgnoreCase(name.trim())) {
                return entry;
            }
        }
        return null;
    }

    /** Writes the settings in use now, in the same shape the applier reads back. */
    public static File snapshotCurrent(String name) throws IOException {
        JsonObject object = new JsonObject();
        /* Myau keeps its modules in a typed map and its settings per class
           (Config.save does the same walk), so this follows that, not
           OpenSkid's allModules()/per-instance property map. */
        for (Module module : Myau.moduleManager.modules.values()) {
            JsonObject moduleObject = new JsonObject();
            moduleObject.addProperty("toggled", module.isEnabled());
            moduleObject.addProperty("key", module.getKey());
            moduleObject.addProperty("hidden", module.isHidden());
            ArrayList<Property<?>> list = Myau.propertyManager.properties.get(module.getClass());
            if (list != null) {
                for (Property<?> property : list) {
                    try {
                        property.write(moduleObject);
                    } catch (Exception ignored) {
                    }
                }
            }
            object.add(module.getName(), moduleObject);
        }
        String json = Config.gson.toJson(object);
        save(name, json);
        return fileFor(name);
    }
}
