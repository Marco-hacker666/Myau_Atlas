package myau.script;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import myau.util.ChatUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

// Ported from OpenSkid (GPL-3.0). Note what this is: scripts are Java source
// compiled at runtime with the JDK compiler API and loaded through
// SecureScriptClassLoader. It is not a JavaScript engine.
//
// The donor's remote `load - "url"` include is deliberately omitted: there is
// no URL parameter, no fetch, no network anywhere in this file, by design.
//
// THREAT MODEL. Read before trusting a script.
// A malicious script CAN still: hang or slow the tick with an infinite loop
// or heavy allocation; spam local chat lines through print(); spawn threads
// (java.lang.Thread is not blocked); call System.exit(); read system
// properties; and escape the class denylist by reaching the parent loader
// via getClass().getClassLoader().getParent() plus Class.forName, because a
// URLClassLoader denylist is a speed bump, not a SecurityManager sandbox.
// A malicious script CANNOT through the intended API: touch live Minecraft,
// module, packet or file objects (it only ever sees Strings and booleans);
// mutate, replay or forge packets (it votes allow/cancel on a summary, the
// caller owns the real packet); or load code from the network (no fetch path
// exists and http tokens are rejected at the source check).
// Loading a script IS code execution. Only load files you wrote or read.
public class ScriptManager {
    private static final File SCRIPTS_DIR = new File("./config/Myau/scripts");
    private static final File CLASSES_DIR = new File(SCRIPTS_DIR, ".classes");
    private static final File STATE_FILE = new File("./config/Myau/scripts.json");
    private static final int MAX_ERROR_LINES = 6;

    private static final ScriptManager INSTANCE = new ScriptManager();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, LoadedScript> scripts = new LinkedHashMap<String, LoadedScript>();
    private final Map<String, Boolean> enabledState = new LinkedHashMap<String, Boolean>();

    // One loaded script. Registry struct, not scattered maps.
    private static final class LoadedScript {
        final String name;
        final File file;
        final Script instance;
        final SecureScriptClassLoader loader;
        boolean enabled;
        String lastError;

        LoadedScript(String name, File file, Script instance, SecureScriptClassLoader loader, boolean enabled) {
            this.name = name;
            this.file = file;
            this.instance = instance;
            this.loader = loader;
            this.enabled = enabled;
        }
    }

    private ScriptManager() {
        this.loadEnabledState();
    }

    public static ScriptManager getInstance() {
        return INSTANCE;
    }

    public synchronized List<String> getScriptNames() {
        return new ArrayList<String>(this.scripts.keySet());
    }

    public synchronized boolean isLoaded(String name) {
        return this.scripts.containsKey(name);
    }

    public synchronized boolean isEnabled(String name) {
        LoadedScript slot = this.scripts.get(name);
        return slot != null && slot.enabled;
    }

    public synchronized String getLastError(String name) {
        LoadedScript slot = this.scripts.get(name);
        return slot == null ? null : slot.lastError;
    }

    public synchronized List<String> availableScriptFiles() {
        List<String> out = new ArrayList<String>();
        File[] files = SCRIPTS_DIR.listFiles();
        if (files == null) {
            return out;
        }
        Arrays.sort(files);
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".java")) {
                out.add(this.stripExtension(file.getName()));
            }
        }
        return out;
    }

    public synchronized void loadAll() {
        this.ensureDirs();
        for (String name : this.availableScriptFiles()) {
            if (!this.scripts.containsKey(name)) {
                this.loadQuiet(name);
            }
        }
    }

    public synchronized void reloadAll() {
        this.ensureDirs();
        for (String name : new ArrayList<String>(this.scripts.keySet())) {
            this.reload(name);
        }
        for (String name : this.availableScriptFiles()) {
            if (!this.scripts.containsKey(name)) {
                this.loadQuiet(name);
            }
        }
    }

    public synchronized boolean load(String name) {
        String clean = this.cleanName(name);
        if (clean == null) {
            ChatUtil.sendFormatted("&7[&bScript&7]&r Invalid script name.");
            return false;
        }
        if (this.scripts.containsKey(clean)) {
            ChatUtil.sendFormatted("&7[&bScript&7]&r Already loaded (&o" + clean + "&r). Use reload.");
            return false;
        }
        return this.loadQuiet(clean);
    }

    public synchronized boolean unload(String name) {
        String clean = this.cleanName(name);
        LoadedScript slot = clean == null ? null : this.scripts.remove(clean);
        if (slot == null) {
            ChatUtil.sendFormatted("&7[&bScript&7]&r Not loaded (&o" + name + "&r).");
            return false;
        }
        try {
            slot.instance.onDisable();
        } catch (Throwable t) {
            // Unload must succeed even if the script throws.
        }
        try {
            slot.loader.close();
        } catch (Exception ignored) {
        }
        ChatUtil.sendFormatted("&7[&bScript&7]&r Unloaded (&o" + clean + "&r).");
        return true;
    }

    public synchronized boolean reload(String name) {
        String clean = this.cleanName(name);
        if (clean == null || !this.scripts.containsKey(clean)) {
            return this.load(name);
        }
        boolean wasEnabled = this.scripts.get(clean).enabled;
        this.unload(clean);
        boolean ok = this.loadQuiet(clean);
        if (ok && !wasEnabled) {
            this.setEnabled(clean, false);
        }
        return ok;
    }

    public synchronized boolean setEnabled(String name, boolean enabled) {
        String clean = this.cleanName(name);
        LoadedScript slot = clean == null ? null : this.scripts.get(clean);
        if (slot == null) {
            ChatUtil.sendFormatted("&7[&bScript&7]&r Not loaded (&o" + name + "&r).");
            return false;
        }
        slot.enabled = enabled;
        this.enabledState.put(clean, enabled);
        this.saveEnabledState();
        try {
            if (enabled) {
                slot.instance.onEnable();
            } else {
                slot.instance.onDisable();
            }
        } catch (Throwable t) {
            slot.lastError = this.shortError(t);
            ChatUtil.sendFormatted("&7[&bScript&7]&r Runtime error in (&o" + clean + "&r): " + slot.lastError);
        }
        ChatUtil.sendFormatted("&7[&bScript&7]&r " + (enabled ? "Enabled" : "Disabled") + " (&o" + clean + "&r).");
        return true;
    }

    /** Every loaded script that is on, by name. */
    public synchronized List<String> getEnabledScriptNames() {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, LoadedScript> entry : this.scripts.entrySet()) {
            if (entry.getValue().enabled) {
                out.add(entry.getKey());
            }
        }
        return out;
    }

    public synchronized int loadedCount() {
        return this.scripts.size();
    }

    /** Disables every loaded script without unloading it. */
    public synchronized void disableAll() {
        for (String name : new ArrayList<String>(this.scripts.keySet())) {
            if (this.scripts.get(name).enabled) {
                this.setEnabled(name, false);
            }
        }
    }

    // Event fan-out. Each script runs isolated in try/catch so one bad script
    // cannot break the tick or silence the rest.
    public void fireTick() {
        for (LoadedScript slot : this.snapshot()) {
            if (!slot.enabled) {
                continue;
            }
            try {
                slot.instance.onTick();
            } catch (Throwable t) {
                this.reportRuntime(slot, t);
            }
        }
    }

    public void fireChat(String message) {
        String copy = message == null ? "" : message;
        for (LoadedScript slot : this.snapshot()) {
            if (!slot.enabled) {
                continue;
            }
            try {
                slot.instance.onChat(copy);
            } catch (Throwable t) {
                this.reportRuntime(slot, t);
            }
        }
    }

    // True means every script voted allow. Any false asks the caller to cancel.
    public boolean firePacketSend(String packetClass, String packetSummary) {
        boolean allow = true;
        for (LoadedScript slot : this.snapshot()) {
            if (!slot.enabled) {
                continue;
            }
            try {
                if (!slot.instance.onPacketSend(packetClass, packetSummary)) {
                    allow = false;
                }
            } catch (Throwable t) {
                this.reportRuntime(slot, t);
            }
        }
        return allow;
    }

    public boolean firePacketReceive(String packetClass, String packetSummary) {
        boolean allow = true;
        for (LoadedScript slot : this.snapshot()) {
            if (!slot.enabled) {
                continue;
            }
            try {
                if (!slot.instance.onPacketReceive(packetClass, packetSummary)) {
                    allow = false;
                }
            } catch (Throwable t) {
                this.reportRuntime(slot, t);
            }
        }
        return allow;
    }

    private synchronized List<LoadedScript> snapshot() {
        return new ArrayList<LoadedScript>(this.scripts.values());
    }

    private boolean loadQuiet(String name) {
        this.ensureDirs();
        File file = new File(SCRIPTS_DIR, name + ".java");
        if (!this.isInsideScriptsDir(file)) {
            ChatUtil.sendFormatted("&7[&bScript&7]&r Rejected path escape (&o" + name + "&r).");
            return false;
        }
        if (!file.isFile()) {
            ChatUtil.sendFormatted("&7[&bScript&7]&r File not found (&o" + file.getPath() + "&r).");
            return false;
        }
        String source = this.readSource(file);
        if (source == null) {
            ChatUtil.sendFormatted("&7[&bScript&7]&r Could not read (&o" + name + "&r).");
            return false;
        }
        String rejected = this.rejectUnsafeSource(source);
        if (rejected != null) {
            ChatUtil.sendFormatted("&7[&bScript&7]&r Rejected (&o" + name + "&r): " + rejected);
            return false;
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            ChatUtil.sendFormatted("&7[&bScript&7]&r No Java compiler on this runtime (JRE instead of JDK). Cannot compile (&o" + name + "&r).");
            return false;
        }
        File outDir = new File(CLASSES_DIR, name);
        this.cleanDir(outDir);
        outDir.mkdirs();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null);
        boolean compiled;
        try {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(Arrays.asList(file));
            List<String> options = Arrays.asList(
                    "-d", outDir.getAbsolutePath(),
                    "-classpath", System.getProperty("java.class.path", ""),
                    "-source", "1.8",
                    "-target", "1.8"
            );
            compiled = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
        } catch (Exception e) {
            ChatUtil.sendFormatted("&7[&bScript&7]&r Compile failed (&o" + name + "&r): " + this.shortError(e));
            this.closeQuietly(fileManager);
            return false;
        } finally {
            this.closeQuietly(fileManager);
        }
        if (!compiled) {
            this.reportDiagnostics(name, diagnostics);
            return false;
        }
        SecureScriptClassLoader loader = null;
        try {
            loader = new SecureScriptClassLoader(new URL[]{outDir.toURI().toURL()}, Script.class.getClassLoader());
            Class<?> clazz = loader.loadClass(name);
            if (!Script.class.isAssignableFrom(clazz)) {
                ChatUtil.sendFormatted("&7[&bScript&7]&r Class (&o" + name + "&r) must extend myau.script.Script.");
                loader.close();
                return false;
            }
            Script instance = (Script) clazz.newInstance();
            instance.initName(name);
            boolean enabled = this.enabledState.containsKey(name) ? this.enabledState.get(name) : true;
            LoadedScript slot = new LoadedScript(name, file, instance, loader, enabled);
            this.scripts.put(name, slot);
            try {
                instance.onLoad();
                if (enabled) {
                    instance.onEnable();
                }
            } catch (Throwable t) {
                slot.lastError = this.shortError(t);
                ChatUtil.sendFormatted("&7[&bScript&7]&r Runtime error in (&o" + name + "&r): " + slot.lastError);
            }
            ChatUtil.sendFormatted("&7[&bScript&7]&r Loaded (&o" + name + "&r).");
            return true;
        } catch (ClassNotFoundException e) {
            ChatUtil.sendFormatted("&7[&bScript&7]&r Blocked unsafe reference in (&o" + name + "&r): " + e.getMessage());
            this.closeQuietly(loader);
            return false;
        } catch (Exception e) {
            ChatUtil.sendFormatted("&7[&bScript&7]&r Load failed (&o" + name + "&r): " + this.shortError(e));
            this.closeQuietly(loader);
            return false;
        }
    }

    // Source pre-checks. String matching is advisory: it stops accidents and
    // the donor's remote-include idiom, not a determined attacker.
    private String rejectUnsafeSource(String source) {
        if (source.contains("load - \"")) {
            return "remote load directives are not supported, vendor the code into the file";
        }
        if (source.contains("http://") || source.contains("https://")) {
            return "network addresses are not allowed in scripts";
        }
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ")) {
                return "package declarations are not allowed, ship the file in the default package";
            }
        }
        return null;
    }

    private void reportDiagnostics(String name, DiagnosticCollector<JavaFileObject> diagnostics) {
        ChatUtil.sendFormatted("&7[&bScript&7]&r Compile errors in (&o" + name + "&r):");
        int shown = 0;
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (shown >= MAX_ERROR_LINES) {
                break;
            }
            if (d.getKind() != Diagnostic.Kind.ERROR) {
                continue;
            }
            shown++;
            ChatUtil.sendFormatted("&7»&r line " + d.getLineNumber() + ": " + String.valueOf(d.getMessage(Locale.ROOT)));
        }
        if (shown == 0) {
            ChatUtil.sendFormatted("&7»&r unknown compile error, see log.");
        }
    }

    private void reportRuntime(LoadedScript slot, Throwable t) {
        slot.lastError = this.shortError(t);
        ChatUtil.sendFormatted("&7[&bScript&7]&r Runtime error in (&o" + slot.name + "&r): " + slot.lastError);
    }

    private String shortError(Throwable t) {
        String msg = t == null ? "unknown" : t.toString();
        if (msg.length() > 160) {
            msg = msg.substring(0, 160);
        }
        return msg;
    }

    private String readSource(File file) {
        StringBuilder out = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
            return out.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void ensureDirs() {
        if (!SCRIPTS_DIR.exists()) {
            SCRIPTS_DIR.mkdirs();
        }
        if (!CLASSES_DIR.exists()) {
            CLASSES_DIR.mkdirs();
        }
    }

    private boolean isInsideScriptsDir(File file) {
        try {
            String root = SCRIPTS_DIR.getCanonicalPath();
            String target = file.getCanonicalPath();
            return target.startsWith(root + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    private void cleanDir(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                this.cleanDir(file);
            }
            file.delete();
        }
    }

    private void closeQuietly(Object closeable) {
        if (closeable instanceof StandardJavaFileManager) {
            try {
                ((StandardJavaFileManager) closeable).close();
            } catch (Exception ignored) {
            }
        } else if (closeable instanceof SecureScriptClassLoader) {
            try {
                ((SecureScriptClassLoader) closeable).close();
            } catch (Exception ignored) {
            }
        }
    }

    private String cleanName(String name) {
        if (name == null) {
            return null;
        }
        String clean = name.trim();
        if (clean.endsWith(".java")) {
            clean = clean.substring(0, clean.length() - 5);
        }
        if (clean.isEmpty() || clean.contains("/") || clean.contains("\\") || clean.contains(".")) {
            return null;
        }
        return clean;
    }

    private String stripExtension(String fileName) {
        if (fileName.endsWith(".java")) {
            return fileName.substring(0, fileName.length() - 5);
        }
        return fileName;
    }

    // Enable state lives next to the normal config so it survives restarts
    // without touching Config. Keyed by script name.
    private void loadEnabledState() {
        if (!STATE_FILE.isFile()) {
            return;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(STATE_FILE));
            JsonElement parsed = new JsonParser().parse(reader);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        this.enabledState.put(entry.getKey(), entry.getValue().getAsBoolean());
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void saveEnabledState() {
        try {
            if (STATE_FILE.getParentFile() != null) {
                STATE_FILE.getParentFile().mkdirs();
            }
            JsonObject object = new JsonObject();
            for (Map.Entry<String, Boolean> entry : this.enabledState.entrySet()) {
                object.addProperty(entry.getKey(), entry.getValue());
            }
            for (String name : this.scripts.keySet()) {
                if (!object.has(name)) {
                    object.addProperty(name, this.scripts.get(name).enabled);
                }
            }
            PrintWriter writer = new PrintWriter(new FileWriter(STATE_FILE));
            writer.println(this.gson.toJson(object));
            writer.close();
        } catch (Exception ignored) {
        }
    }
}
