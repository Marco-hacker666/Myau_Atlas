package myau.property;

import com.google.gson.JsonObject;
import myau.module.Module;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * A module setting.
 *
 * VALUE OWNERSHIP (plan step 7, 2026-09-28; docs/ARCH-AUDIT-2026-09-28.md).
 * A value has one base -- what the player chose, or what a config file said --
 * and any number of temporary overrides laid on top by the modules that tune
 * other modules. getValue() is the effective value: the highest override if
 * there is one, else the base. Only the base is ever saved.
 *
 * Before this, AutoTune, Adaptive and LatencyGovernor all wrote each other's
 * numbers (Reach.range, BackTrack's delays) straight into setValue. Nothing
 * could tell a trial from a choice: each read the others' writes as the
 * player's baseline, a trial or a governed cut was saved as the configuration
 * on exit, and loading a profile could be undone by a tuner's revert running
 * in the middle of the load (F-07, F-09, F-20, F-24, F-26). Now setValue is the
 * player's (or the file's) and override/release are the tuners', and
 * describe() answers "where did this value come from".
 *
 * Nothing changes for a property nobody overrides: setValue sets the value,
 * getValue returns it, write saves it.
 */
public abstract class Property<T> {

    /** Where a value came from, lowest precedence first. */
    public enum Source {
        /** As declared in the module. */
        DEFAULT,
        /** Read from a config file. */
        PROFILE,
        /** Committed by AutoTune after a validated experiment (plan step 11). Saved. */
        TUNED,
        /** Set by the player (GUI, command) or by the module itself. */
        USER,
        /** Adaptive's long-run conclusion. Not saved. */
        LEARNED,
        /** An AutoTune trial. Not saved. */
        TRIAL,
        /**
         * LatencyGovernor's cut on a bad connection. Highest: it is a safety
         * limit, and a trial must not step over it. Not saved.
         */
        GOVERNOR;

        public boolean isOverride() {
            return this.ordinal() >= LEARNED.ordinal();
        }
    }

    private static final class Layer<T> {
        final Source source;
        final String owner;
        final T value;
        final long at;

        Layer(Source source, String owner, T value) {
            this.source = source;
            this.owner = owner;
            this.value = value;
            this.at = System.currentTimeMillis();
        }
    }

    private final String name;
    private final T type;
    private final Predicate<T> validator;
    private final BooleanSupplier visibleChecker;
    /** The effective value. Volatile: read from the network thread too. */
    private volatile T value;
    /** The chosen value: the one that is saved. */
    private volatile T base;
    private volatile Source baseSource = Source.DEFAULT;
    private volatile long baseAt;
    /** Overrides, in the order they were laid. Guarded by this. */
    private final List<Layer<T>> layers = new ArrayList<Layer<T>>(2);
    private Module owner;

    protected Property(String name, Object value, BooleanSupplier visibleChecker) {
        this(name, value, null, visibleChecker);
    }

    protected Property(String name, Object value, Predicate<T> predicate, BooleanSupplier visibleChecker) {
        this.name = name;
        this.type = (T) value;
        this.validator = predicate;
        this.visibleChecker = visibleChecker;
        this.value = (T) value;
        this.base = (T) value;
        this.owner = null;
    }

    public String getName() {
        return this.name;
    }

    /* How the menus present this setting (2026-10-04, ModuleDocs). None of it
       is saved or matched against: the config key stays getName(). */
    private String label;
    private String group;
    private String help;
    private BooleanSupplier alsoVisible;

    /** The name shown: one set by the docs, else the key written out as words. */
    public String getLabel() {
        return this.label != null ? this.label : prettify(this.name);
    }

    public Property<T> setLabel(String label) {
        this.label = label;
        return this;
    }

    /** The heading this setting sits under in the menu; null for none. */
    public String getGroup() {
        return this.group;
    }

    public Property<T> setGroup(String group) {
        this.group = group;
        return this;
    }

    private String helpEn;

    /** What the setting does, in the menu's language, for the hint line; null when not written. */
    public String getHelp() {
        return myau.module.ModuleDocs.isEnglish() ? this.helpEn : this.help;
    }

    public Property<T> setHelp(String help) {
        this.help = help;
        return this;
    }

    public Property<T> setHelpEn(String help) {
        this.helpEn = help;
        return this;
    }

    /** Shown only while this also holds, on top of the constructor's condition. */
    public Property<T> when(BooleanSupplier condition) {
        this.alsoVisible = condition;
        return this;
    }

    /**
     * A key as words: "release-every" -> "Release every", "AutoBlockCPS" ->
     * "Auto block CPS", "LB-HSpeed" -> "LB H speed", "hud_x" -> "Hud x". The
     * keys were written in four styles by as many authors; the menu shows one.
     */
    public static String prettify(String key) {
        if (key == null || key.isEmpty()) {
            return key;
        }
        StringBuilder words = new StringBuilder();
        char[] c = key.replace('_', ' ').replace('-', ' ').toCharArray();
        for (int i = 0; i < c.length; i++) {
            char ch = c[i];
            boolean boundary = i > 0 && Character.isUpperCase(ch) && c[i - 1] != ' '
                    && (Character.isLowerCase(c[i - 1]) || Character.isDigit(c[i - 1])
                    || i + 1 < c.length && Character.isLowerCase(c[i + 1]));
            if (boundary) {
                words.append(' ');
            }
            words.append(ch);
        }
        String[] parts = words.toString().trim().split(" +");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String w = parts[i];
            if (w.isEmpty()) {
                continue;
            }
            /* An acronym, or a lone capital ("H" of HSpeed): kept as written. */
            boolean acronym = w.equals(w.toUpperCase()) && !w.matches("\\d+") && Character.isLetter(w.charAt(0));
            String shown = acronym ? w : i == 0 ? Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase()
                    : w.toLowerCase();
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(shown);
        }
        return out.toString();
    }

    public abstract String getValuePrompt();

    public boolean isVisible() {
        return (this.visibleChecker == null || this.visibleChecker.getAsBoolean())
                && (this.alsoVisible == null || this.alsoVisible.getAsBoolean());
    }

    /** The effective value: the highest override, else the base. */
    public T getValue() {
        return this.value;
    }

    public abstract String formatValue();

    /**
     * Sets the base -- the player's or the config file's choice.
     *
     * While an override is on, the effective value does not change; the new
     * base is what remains when the override is released, and what is saved.
     */
    public boolean setValue(Object object) {
        return setBase(myau.config.Config.loading ? Source.PROFILE : Source.USER, object);
    }

    /** Sets the base, saying where it came from (a base source: not an override). */
    public boolean setBase(Source source, Object object) {
        if (source == null || source.isOverride()) {
            throw new IllegalArgumentException("not a base source: " + source);
        }
        if (this.validator != null && !this.validator.test((T) object)) {
            return false;
        }
        synchronized (this) {
            this.base = (T) object;
            this.baseSource = source;
            this.baseAt = System.currentTimeMillis();
            if (this.layers.isEmpty()) {
                this.value = (T) object;
            }
        }
        if (this.owner != null) {
            this.owner.verifyValue(this.name);
        }
        return true;
    }

    // ------------------------------------------------------------ overrides

    /** Whether this kind of property can carry overrides (its write() saves the base). */
    protected boolean supportsOverride() {
        return false;
    }

    /**
     * Lays (or replaces) {@code owner}'s override. Returns false if the value
     * is out of range, exactly as setValue would.
     */
    public boolean override(Source source, String owner, Object object) {
        if (source == null || !source.isOverride()) {
            throw new IllegalArgumentException("not an override source: " + source);
        }
        if (!supportsOverride()) {
            throw new UnsupportedOperationException(getClass().getSimpleName() + " " + this.name
                    + " cannot be overridden");
        }
        if (this.validator != null && !this.validator.test((T) object)) {
            return false;
        }
        synchronized (this) {
            removeLayer(owner);
            this.layers.add(new Layer<T>(source, owner, (T) object));
            recompute();
        }
        return true;
    }

    /** Removes {@code owner}'s override, if any. */
    public void release(String owner) {
        synchronized (this) {
            if (removeLayer(owner)) {
                recompute();
            }
        }
    }

    /** {@code owner}'s override value, or null if it has none. */
    public synchronized T overrideOf(String owner) {
        for (Layer<T> layer : this.layers) {
            if (layer.owner.equals(owner)) {
                return layer.value;
            }
        }
        return null;
    }

    public synchronized boolean isOverridden() {
        return !this.layers.isEmpty();
    }

    /** The chosen value -- what is saved -- whatever overrides are on. */
    public T getBaseValue() {
        return this.base;
    }

    /** What write() saves: always the base, never an override. */
    protected T getPersistedValue() {
        return this.base;
    }

    /** Where the effective value comes from. */
    public synchronized Source getSource() {
        Layer<T> top = top();
        return top == null ? this.baseSource : top.source;
    }

    /** The module whose override is in effect; null when the base is. */
    public synchronized String getSourceOwner() {
        Layer<T> top = top();
        return top == null ? null : top.owner;
    }

    /**
     * One line on where the value came from, for logs and diagnostics:
     * "2.9 (TRIAL by AutoTune 12s ago; base 3.1 PROFILE)".
     */
    public synchronized String describe() {
        long now = System.currentTimeMillis();
        Layer<T> top = top();
        if (top == null) {
            return this.value + " (" + this.baseSource
                    + (this.baseAt == 0L ? "" : " " + (now - this.baseAt) / 1000L + "s ago") + ")";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(this.value).append(" (").append(top.source).append(" by ").append(top.owner)
                .append(' ').append((now - top.at) / 1000L).append("s ago; base ").append(this.base)
                .append(' ').append(this.baseSource);
        if (this.layers.size() > 1) {
            sb.append("; ").append(this.layers.size() - 1).append(" more under it");
        }
        return sb.append(')').toString();
    }

    private boolean removeLayer(String owner) {
        boolean removed = false;
        for (int i = this.layers.size() - 1; i >= 0; i--) {
            if (this.layers.get(i).owner.equals(owner)) {
                this.layers.remove(i);
                removed = true;
            }
        }
        return removed;
    }

    /** Highest source wins; between equals, the one laid last. */
    private Layer<T> top() {
        Layer<T> best = null;
        for (Layer<T> layer : this.layers) {
            if (best == null || layer.source.ordinal() >= best.source.ordinal()) {
                best = layer;
            }
        }
        return best;
    }

    private void recompute() {
        Layer<T> top = top();
        this.value = top == null ? this.base : top.value;
    }

    // --------------------------------------------------------------- misc

    public void parseString() {
    }

    public void setOwner(Module module) {
        this.owner = module;
    }

    public abstract boolean parseString(String string);

    public abstract boolean read(JsonObject jsonObject);

    public abstract void write(JsonObject jsonObject);
}
