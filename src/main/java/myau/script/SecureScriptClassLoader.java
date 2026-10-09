package myau.script;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

// Ported from OpenSkid (GPL-3.0). Child-first for script classes so each load
// gets a fresh copy and unload really drops it. Shared parent type only for
// the API.
//
// This is a speed bump, not a sandbox. See ScriptManager's threat model.
public class SecureScriptClassLoader extends URLClassLoader {
    private static final List<String> DENIED_PREFIXES = Collections.unmodifiableList(Arrays.asList(
            "java.lang.reflect.",
            "java.lang.Process",
            "java.lang.Runtime",
            "java.lang.Thread",
            "java.lang.System",
            "java.io.",
            "java.nio.",
            "java.net.",
            "net.minecraft.",
            "net.minecraftforge.",
            "myau.mixin.",
            "myau.module.",
            "myau.management.",
            "myau.config.",
            "myau.command.",
            "myau.anticheat.",
            "myau.event.",
            "myau.events.",
            "me.ksyz.",
            "de.florianmichael.",
            "org.lwjgl."
    ));

    public SecureScriptClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name != null) {
            for (String denied : DENIED_PREFIXES) {
                if (name.startsWith(denied)) {
                    throw new ClassNotFoundException("Blocked for scripts: " + name);
                }
            }
            // Core JDK and the script API itself must come from the parent so
            // casts to myau.script.Script keep working across reloads.
            if (name.startsWith("java.") || name.equals("myau.script.Script")) {
                return super.loadClass(name, resolve);
            }
            // Script's own classes first, so reload picks up the new bytes.
            try {
                Class<?> found = findClass(name);
                if (resolve) {
                    resolveClass(found);
                }
                return found;
            } catch (ClassNotFoundException expected) {
                return super.loadClass(name, resolve);
            }
        }
        return super.loadClass(name, resolve);
    }
}
