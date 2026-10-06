package myau.ui.impl.clickgui.atlas;

import myau.Myau;
import myau.module.Category;
import myau.module.Module;
import myau.module.ModuleCategories;
import myau.property.Property;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.KeyProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.property.properties.TextProperty;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A menu built around finding things and understanding them, rather than
 * around looking like a menu.
 *
 * The existing styles all share a shape: columns of collapsible category
 * panels, a row per module, settings hidden behind an arrow. That shape works
 * when a client has thirty modules whose names say what they do. This one has
 * over a hundred, a dozen of which were added in the last week, and several
 * whose behaviour is a paragraph rather than a name -- a module that spends
 * eight minutes measuring without telling you why cannot be explained by being
 * called "Adaptive".
 *
 * So the design puts three things on screen at once, and nothing behind a
 * hover:
 *
 *   SEARCH. Typing filters every module in every category at once. With this
 *   many modules, remembering which category something was filed under is the
 *   actual obstacle, and a search box removes it entirely.
 *
 *   A DETAIL PANE. The selected module's description sits permanently on the
 *   right, wrapped and readable, with its key, whether the array list shows it,
 *   and every setting listed under it.
 *
 *   LIVE STATE. Each row carries the module's own status suffix -- the same
 *   text the array list shows -- so a probe in progress, a flag rate or a hit
 *   percentage is visible while deciding what to change.
 *
 * 2026-09-24: LIQUID GLASS. The window is real refracting glass over the
 * blurred world (Liquid), the text is SF Pro rasterised for the GUI scale
 * (LiquidFont), and everything that moves is on a spring (Spring): the
 * selection pills slide as droplets of clear glass that stretch with their
 * speed and settle with a small overshoot, switches squash as they travel,
 * the window pops open. The layout was prototyped in a browser against the
 * same shaders before any of it was written here.
 *
 * EVERY CONTROL IS HIT TESTED AGAINST WHERE IT WAS ACTUALLY DRAWN. The
 * previous version computed the layout twice -- once to draw it and once to
 * decide what a click had landed on -- and the two copies disagreed by six
 * pixels, so no slider could be dragged. Drawing a control registers the
 * rectangle it occupies; clicking looks the pointer up in that list.
 *
 * Nothing about the modules here is new behaviour. It reads the module list,
 * the property list and the descriptions that were already there, and it
 * writes only through the same setters the other menus use.
 */
public class AtlasClickGui extends GuiScreen {

    private static AtlasClickGui instance;

    // Layout, in scaled pixels.
    private static final int HEADER = 42;
    /* Set from the theme at the start of every frame: the density, the
       corner radius and whether the hint bar has a strip of its own. */
    private int FOOTER = 20;
    private float RADIUS = 18.0F;
    /** A module row, and the pitch rows are laid out at. */
    private int ROW_H = 24;
    private int ROW = 26;
    private int CAT_H = 26;
    private int CAT = 28;

    /** Nothing useful fits below these, so the resize grip stops here. */
    private static final int MIN_WIDTH = 520;
    private static final int MIN_HEIGHT = 280;
    private static final int DEFAULT_WIDTH = 720;
    private static final int DEFAULT_HEIGHT = 400;
    /** Means "no position chosen yet", which is drawn centred. */
    private static final int UNSET = Integer.MIN_VALUE;

    /* Text is opaque and bright; every surface behind it is glass. Text over a
       translucent panel has to be brighter than text over a solid one -- the
       background shows through and eats contrast. */
    /* Dark-mode values. Repainted at the start of every frame by paintPalette(),
       so light mode swaps them for dark text without touching any call site. */
    private static int TEXT = 0xFFF5F8FF;
    private static int DIM = 0xD1C3CEDF;
    private static int FAINT = 0x9E93A3BA;
    private static final int WHITE = 0xFFFFFFFF;
    /* The neutral every hover wash, divider and rim is mixed from: white over
       dark glass, ink over light glass. See ink(). */
    private static int INK = 0xFFFFFFFF;

    /** How the menu looks; edited on the Appearance page, kept in atlas-theme.json. */
    private final AtlasTheme theme = new AtlasTheme();
    private static float textScale = 1.0F;
    private float jelly = 1.0F;
    private float springSpeed = 1.0F;
    private float slideY;

    /** Where the window's size, position and last selection are remembered. */
    private static final File STATE = new File("./config/Myau/atlas-ui.txt");

    private final List<String> categories = new ArrayList<String>();
    private final Map<String, List<Module>> byCategory = new LinkedHashMap<String, List<Module>>();
    /** The Legit page's sub-groups (see LegitGroups), rebuilt with the catalogue. */
    private final Map<String, List<Module>> legitGroups = new LinkedHashMap<String, List<Module>>();

    private String category = "";
    private String page = "Modules";
    private Module selected;
    /** The name of the module to reselect, held until the list is built. */
    private String pendingSelection = "";
    private String search = "";
    private boolean searchFocused;

    private int listScroll;
    private int listMaxScroll;
    private int detailScroll;
    private int detailMaxScroll;

    /** The window's own geometry, which the user owns once they touch it. */
    private int userWidth = DEFAULT_WIDTH;
    private int userHeight = DEFAULT_HEIGHT;
    private int userX = UNSET;
    private int userY = UNSET;
    private boolean stateLoaded;

    private boolean movingWindow;
    private boolean resizingWindow;
    private int grabX;
    private int grabY;

    /**
     * The module whose key is being rebound, if any. The key is not a
     * property, so it would never appear among the settings by itself.
     */
    private Module binding;

    /** A key setting (KeyProperty, e.g. Clutch hold-key) waiting for its key. */
    private KeyProperty keyEditing;

    /* The colour setting whose picker is open. It is edited in HSB, kept
       here: a grey or black has no hue of its own, and recomputing it from
       the RGB would throw the hue away every time saturation reached zero.
       colorSynced is the RGB the HSB was taken from, so a change made
       elsewhere (a typed hex, a theme preset) is picked up. */
    private ColorProperty colorOpen;
    private final float[] colorHsb = new float[3];
    private int colorSynced = -1;
    /** What a press on the picker is dragging: "sv", "hue", or null; and that control's rectangle. */
    private String colorDrag;
    private float colorX;
    private float colorY;
    private float colorW;
    private float colorH;
    /** Height of an open picker, below the setting's own row. */
    private static final int PICKER_HEIGHT = 74;

    /* Dragging a slider. The track's own rectangle is taken from the registry
       when the drag starts, so the value follows the pointer against the bar
       that is on screen rather than against a second guess at where it is. */
    private Property<?> dragging;
    /** The button that started a drag, so the frame loop can tell when it is let go. */
    private int dragButton;
    /** Ctrl+P: frame cost, averaged, and draws per frame. */
    private boolean perf;
    private float perfMillis;
    private float dragMin;
    private float dragMax;
    private float dragX;
    private float dragWidth;

    /**
     * The setting currently being typed into, and what has been typed. A
     * slider can only express as many values as its track has pixels, and
     * most of the latency settings in this client want an exact number.
     */
    private Property<?> editing;
    private String editBuffer = "";

    /** The mode list currently expanded, and where to draw it. */
    private ModeProperty dropdown;
    private float dropX;
    private float dropY;
    private float dropRight;
    private boolean dropAnchored;

    /** What the pointer is over, shown along the bottom. */
    private String hint = "";

    /* The profiles view: a column of its own in the sidebar, under the
       categories, replacing the module list and the detail pane while open. */
    private boolean profilesView;
    /** The Appearance page, and which of its groups is open. */
    private boolean appearanceView;
    private String appearanceGroup = "Colors";
    private String profileSelected;
    private String profileName = "";
    private boolean profileNameFocused;
    /** A delete waits for a second click on the same profile, within this long. */
    private static final long CONFIRM_MS = 3000L;
    private String confirmDelete;
    private long confirmDeleteAt;
    private int profileScroll;
    private int profileMaxScroll;
    private List<AtlasProfiles.Info> profiles = new ArrayList<AtlasProfiles.Info>();
    private long profilesReadAt;

    /* Motion. Easing and springs are driven by wall time rather than ticks,
       so the feel does not change with frame rate or with the game paused. */
    private long lastFrame;
    private float openedFor;
    private final Spring open = new Spring(0.92F, 260.0F, 17.0F);
    private final Map<Object, Float> lit = new HashMap<Object, Float>();
    private final Map<String, Spring> springs = new HashMap<String, Spring>();
    private float delta = 0.016F;

    /* The opening animation scales the window about its centre. Everything
       is drawn through that transform, and the pointer is taken back through
       it, so a click during the pop still lands on what is under it. */
    private float pivotX;
    private float pivotY;
    private float viewScale = 1.0F;

    /** Glass drawn after the content is captured, so it can refract it. */
    private static final class Lens {
        final float x;
        final float y;
        final float x2;
        final float y2;
        final float radius;
        final Liquid.Style style;
        final float[] clip;

        Lens(float x, float y, float x2, float y2, float radius, Liquid.Style style, float[] clip) {
            this.x = x;
            this.y = y;
            this.x2 = x2;
            this.y2 = y2;
            this.radius = radius;
            this.style = style;
            this.clip = clip;
        }
    }

    private final List<Lens> lenses = new ArrayList<Lens>();

    // ---- hit registry -------------------------------------------------

    /**
     * One clickable rectangle, recorded as it is drawn.
     *
     * The kind says what a click there means and the payload says what it
     * means it to. Everything the pointer can act on goes through here,
     * including the window's own frame, so there is exactly one description of
     * where each control is.
     */
    private static final class Hit {
        final String kind;
        final Object payload;
        final float x;
        final float y;
        final float x2;
        final float y2;

        Hit(String kind, Object payload, float x, float y, float x2, float y2) {
            this.kind = kind;
            this.payload = payload;
            this.x = x;
            this.y = y;
            this.x2 = x2;
            this.y2 = y2;
        }

        boolean contains(float mouseX, float mouseY) {
            return mouseX >= this.x && mouseX <= this.x2
                    && mouseY >= this.y && mouseY <= this.y2;
        }
    }

    private final List<Hit> hits = new ArrayList<Hit>();

    private void hit(String kind, Object payload, float x, float y, float x2, float y2) {
        this.hits.add(new Hit(kind, payload, x, y, x2, y2));
    }

    /**
     * The topmost thing under the pointer, in layout coordinates.
     *
     * Searched newest first, because later drawing means nearer the viewer --
     * an open dropdown covers the rows it is drawn over, and the click has to
     * agree with the eye about that.
     */
    private Hit hitAt(float mouseX, float mouseY) {
        for (int i = this.hits.size() - 1; i >= 0; i--) {
            Hit candidate = this.hits.get(i);
            if (candidate.contains(mouseX, mouseY)) {
                return candidate;
            }
        }
        return null;
    }

    /** Screen to layout, undoing the opening scale. */
    private float layoutX(float screenX) {
        return this.pivotX + (screenX - this.pivotX) / this.viewScale;
    }

    private float layoutY(float screenY) {
        return this.pivotY + (screenY - this.slideY - this.pivotY) / this.viewScale;
    }

    /**
     * Moves a stored value a proportion of the way to its target each frame.
     * Exponential approach: frame-rate independent by construction, fast at
     * first and easing in at the end. Used for fades; anything with a position
     * uses a spring instead.
     */
    private float toward(Object key, float target, float rate) {
        Float held = this.lit.get(key);
        float value = held == null ? target : held;
        value += (target - value) * (1.0F - (float) Math.exp(-rate * this.delta));
        if (Math.abs(target - value) < 0.0015F) {
            value = target;
        }
        this.lit.put(key, value);
        return value;
    }

    private float ease(Object key, boolean towards, float rate) {
        return toward(key, towards ? 1.0F : 0.0F, rate);
    }

    /** A spring, created at its target the first time it is asked for. */
    private Spring spring(String key, float target, float stiffness, float damping) {
        Spring s = this.springs.get(key);
        if (s == null) {
            s = new Spring(target, stiffness, damping);
            this.springs.put(key, s);
        }
        s.target = target;
        if (!this.theme.animations.getValue()) {
            s.snap(target);
        } else {
            s.step(this.delta * this.springSpeed);
        }
        return s;
    }

    /** A scroll offset, eased unless the theme turns smooth scrolling off. */
    private float scrollEase(Object key, float target) {
        if (!this.theme.smoothScroll.getValue()) {
            this.lit.put(key, target);
            return target;
        }
        return toward(key, target, 16.0F);
    }

    /** Records the instant something was acted on, for one-shot animations. */
    private final Map<Object, Long> pulses = new HashMap<Object, Long>();

    private void pulse(Object key) {
        this.pulses.put(key, System.currentTimeMillis());
    }

    /** 0 to 1 across the pulse's life, or -1 when it is over. */
    private float pulseAt(Object key, long millis) {
        Long at = this.pulses.get(key);
        if (at == null) {
            return -1.0F;
        }
        float t = (System.currentTimeMillis() - at) / (float) millis;
        if (t >= 1.0F) {
            this.pulses.remove(key);
            return -1.0F;
        }
        return t;
    }

    /** Slow at both ends, which is what makes a movement look intentional. */
    private static float smooth(float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        return t * t * (3.0F - 2.0F * t);
    }

    private static int alpha(int colour, float factor) {
        return Glass.alpha(colour, factor);
    }

    private AtlasClickGui() {
        loadState();
        build();
    }

    public static AtlasClickGui getInstance() {
        if (instance == null) {
            instance = new AtlasClickGui();
        }
        return instance;
    }

    /**
     * Categories come from the same arrays the other menus use, so a module
     * added to those appears here without this class being touched.
     */
    private void build() {
        this.categories.clear();
        this.byCategory.clear();
        for (Map.Entry<String, List<Module>> entry : AtlasCatalogue.catalogue().entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            this.categories.add(entry.getKey());
            this.byCategory.put(entry.getKey(), entry.getValue());
        }
        this.legitGroups.clear();
        List<Module> legit = this.byCategory.get("Legit");
        if (legit != null) {
            this.legitGroups.putAll(LegitGroups.group(legit));
        }
        if ("Legit".equals(this.page) && !this.legitGroups.isEmpty()) {
            if (!this.legitGroups.containsKey(this.category)) {
                this.category = this.legitGroups.keySet().iterator().next();
            }
        } else if (!this.categories.contains(this.category) || "Legit".equals(this.category)) {
            this.category = firstModuleCategory();
        }
        if ("Client Settings".equals(this.page) && !this.profilesView && !this.appearanceView) {
            this.profilesView = true;
        }
        if (!this.categories.isEmpty() && this.category.isEmpty()) {
            this.category = this.categories.get(0);
        }
        /* The remembered selection is a name, because the module instance from
           a previous run of the game is not the one in this one. */
        if (this.selected == null && !this.pendingSelection.isEmpty()) {
            for (List<Module> list : this.byCategory.values()) {
                for (Module module : list) {
                    if (module != null && module.getName().equals(this.pendingSelection)) {
                        this.selected = module;
                    }
                }
            }
            this.pendingSelection = "";
        }
    }

    private String firstModuleCategory() {
        for (String name : this.categories) {
            if (!"Legit".equals(name)) {
                return name;
            }
        }
        return this.categories.isEmpty() ? "" : this.categories.get(0);
    }

    private List<String> sidebarCategories() {
        List<String> out = new ArrayList<String>();
        if ("Legit".equals(this.page)) {
            out.addAll(this.legitGroups.keySet());
        } else if ("Modules".equals(this.page)) {
            for (String name : this.categories) {
                if (!"Legit".equals(name)) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    // ---- persistence --------------------------------------------------

    /**
     * Reads back where the window was and what was open in it.
     *
     * Kept in its own file rather than in the client's config, deliberately.
     * The config is held in memory for a whole session and written once on
     * exit, so anything written into it from here would be overwritten by
     * whatever that copy held; and a menu's window position is not a setting
     * anyone wants travelling with a shared profile.
     */
    private void loadState() {
        if (this.stateLoaded) {
            return;
        }
        this.stateLoaded = true;
        BufferedReader reader = null;
        try {
            if (!STATE.exists()) {
                return;
            }
            reader = new BufferedReader(new FileReader(STATE));
            String line;
            while ((line = reader.readLine()) != null) {
                int split = line.indexOf('=');
                if (split <= 0) {
                    continue;
                }
                String key = line.substring(0, split).trim();
                String value = line.substring(split + 1).trim();
                if ("x".equals(key)) {
                    this.userX = Integer.parseInt(value);
                } else if ("y".equals(key)) {
                    this.userY = Integer.parseInt(value);
                } else if ("width".equals(key)) {
                    this.userWidth = Math.max(MIN_WIDTH, Integer.parseInt(value));
                } else if ("height".equals(key)) {
                    this.userHeight = Math.max(MIN_HEIGHT, Integer.parseInt(value));
                } else if ("category".equals(key)) {
                    this.category = value;
                } else if ("selected".equals(key)) {
                    this.pendingSelection = value;
                } else if ("view".equals(key)) {
                    this.profilesView = "profiles".equals(value);
                    this.appearanceView = "appearance".equals(value);
                    if (this.profilesView || this.appearanceView) {
                        this.page = "Client Settings";
                    }
                } else if ("page".equals(key)) {
                    if ("Modules".equals(value) || "Legit".equals(value) || "Client Settings".equals(value)) {
                        this.page = value;
                    }
                }
            }
        } catch (Exception ignored) {
            /* A missing or unreadable file means the defaults, which is what
               this menu did before it remembered anything at all. */
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                    // Nothing further to do.
                }
            }
        }
    }

    private void saveState() {
        PrintWriter writer = null;
        try {
            File dir = STATE.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                return;
            }
            writer = new PrintWriter(STATE);
            writer.println("x=" + this.userX);
            writer.println("y=" + this.userY);
            writer.println("width=" + this.userWidth);
            writer.println("height=" + this.userHeight);
            writer.println("category=" + this.category);
            writer.println("selected=" + (this.selected == null ? "" : this.selected.getName()));
            writer.println("page=" + this.page);
            writer.println("view=" + (this.profilesView ? "profiles" : this.appearanceView ? "appearance" : "modules"));
            writer.flush();
        } catch (Exception ignored) {
            // Losing a window position is not worth an error on screen.
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    // ---- layout helpers ----------------------------------------------

    private int windowWidth(ScaledResolution sr) {
        return Math.max(Math.min(MIN_WIDTH, sr.getScaledWidth() - 8),
                Math.min(this.userWidth, sr.getScaledWidth() - 8));
    }

    private int windowHeight(ScaledResolution sr) {
        return Math.max(Math.min(MIN_HEIGHT, sr.getScaledHeight() - 8),
                Math.min(this.userHeight, sr.getScaledHeight() - 8));
    }

    private int left(ScaledResolution sr) {
        int width = windowWidth(sr);
        if (this.userX == UNSET) {
            return (sr.getScaledWidth() - width) / 2;
        }
        return Math.max(0, Math.min(sr.getScaledWidth() - width, this.userX));
    }

    private int top(ScaledResolution sr) {
        int height = windowHeight(sr);
        if (this.userY == UNSET) {
            return (sr.getScaledHeight() - height) / 2;
        }
        return Math.max(0, Math.min(sr.getScaledHeight() - height, this.userY));
    }

    /* The columns take a share of the window rather than a fixed number of
       pixels, so making the window bigger buys room in all three. They are
       clamped at both ends: a sidebar narrower than its longest category name
       is useless, and a detail pane much wider than three hundred pixels wraps
       descriptions into lines too long for the eye to find the start of. */
    private static int sidebarWidth(int width) {
        return Math.max(118, Math.min(160, Math.round(width * 0.19F)));
    }

    private static int detailWidth(int width) {
        return Math.max(210, Math.min(300, Math.round(width * 0.35F)));
    }

    /** Modules shown in the middle column: a search result, or one category. */
    private List<Module> visibleModules() {
        List<Module> out = new ArrayList<Module>();
        if ("Client Settings".equals(this.page)) {
            return out;
        }
        String needle = this.search.toLowerCase().trim();
        if (!needle.isEmpty()) {
            /* Search spans every page: this page's matches first, then the other
               page's, which the list draws as borrowed (see drawList). */
            List<Module> elsewhere = new ArrayList<Module>();
            for (List<Module> list : this.byCategory.values()) {
                for (Module module : list) {
                    if (module == null || !module.getName().toLowerCase().contains(needle)
                            || out.contains(module) || elsewhere.contains(module)) {
                        continue;
                    }
                    if (belongsOnPage(module)) {
                        out.add(module);
                    } else {
                        elsewhere.add(module);
                    }
                }
            }
            out.addAll(elsewhere);
            return out;
        }
        List<Module> list = "Legit".equals(this.page) ? this.legitGroups.get(this.category)
                : this.byCategory.get(this.category);
        if (list != null) {
            for (Module module : list) {
                if (module != null) {
                    out.add(module);
                }
            }
        }
        return out;
    }

    private boolean belongsOnPage(Module module) {
        Category category = ModuleCategories.of(module.getClass());
        if ("Legit".equals(this.page)) {
            return category == Category.LEGIT;
        }
        return category != Category.LEGIT;
    }

    private void selectPage(String next) {
        if (!"Modules".equals(next) && !"Legit".equals(next) && !"Client Settings".equals(next)) {
            return;
        }
        this.page = next;
        this.search = "";
        this.searchFocused = false;
        this.listScroll = 0;
        this.detailScroll = 0;
        this.dropdown = null;
        if ("Legit".equals(next)) {
            this.category = this.legitGroups.isEmpty() ? "Legit" : this.legitGroups.keySet().iterator().next();
            this.profilesView = false;
            this.appearanceView = false;
            List<Module> modules = visibleModules();
            this.selected = modules.isEmpty() ? null : modules.get(0);
        } else if ("Modules".equals(next)) {
            this.profilesView = false;
            this.appearanceView = false;
            if ("Legit".equals(this.category) || this.legitGroups.containsKey(this.category)) {
                this.category = firstModuleCategory();
            }
            List<Module> modules = visibleModules();
            if (this.selected == null || !belongsOnPage(this.selected)) {
                this.selected = modules.isEmpty() ? null : modules.get(0);
            }
        } else {
            this.profilesView = true;
            this.appearanceView = false;
            this.selected = null;
            this.profileScroll = 0;
        }
    }

    private List<Property<?>> propertiesOf(Module module) {
        List<Property<?>> out = new ArrayList<Property<?>>();
        if (module == null) {
            return out;
        }
        for (Property<?> property : Myau.propertyManager.properties.getOrDefault(
                module.getClass(), new ArrayList<Property<?>>())) {
            if (property.isVisible()) {
                out.add(property);
            }
        }
        return out;
    }

    private int enabledCount() {
        int on = 0;
        List<Module> seen = new ArrayList<Module>();
        for (List<Module> list : this.byCategory.values()) {
            for (Module module : list) {
                if (module != null && module.isEnabled() && !seen.contains(module)) {
                    seen.add(module);
                    on++;
                }
            }
        }
        return on;
    }

    // ---- numeric settings, whatever class they are ---------------------

    /* Three unrelated classes carry a bounded number: IntProperty,
       FloatProperty and PercentProperty, which extends neither of the others.
       The menu once handled the first two and silently rendered the third as
       plain text, so a percentage could not be set with the pointer at all. */

    private static boolean isNumeric(Property<?> property) {
        return property instanceof IntProperty
                || property instanceof FloatProperty
                || property instanceof PercentProperty;
    }

    private static boolean isIntegral(Property<?> property) {
        return property instanceof IntProperty || property instanceof PercentProperty;
    }

    private static float minimumOf(Property<?> property) {
        if (property instanceof IntProperty) {
            return ((IntProperty) property).getMinimum();
        }
        if (property instanceof PercentProperty) {
            return ((PercentProperty) property).getMinimum();
        }
        return ((FloatProperty) property).getMinimum();
    }

    private static float maximumOf(Property<?> property) {
        if (property instanceof IntProperty) {
            return ((IntProperty) property).getMaximum();
        }
        if (property instanceof PercentProperty) {
            return ((PercentProperty) property).getMaximum();
        }
        return ((FloatProperty) property).getMaximum();
    }

    private static float currentOf(Property<?> property) {
        if (property instanceof IntProperty) {
            return ((IntProperty) property).getBaseValue();
        }
        if (property instanceof PercentProperty) {
            return ((PercentProperty) property).getBaseValue();
        }
        return ((FloatProperty) property).getBaseValue();
    }

    private static void assign(Property<?> property, float value) {
        value = Math.max(minimumOf(property), Math.min(maximumOf(property), value));
        if (isIntegral(property)) {
            property.setBase(Property.Source.USER, Integer.valueOf(Math.round(value)));
        } else {
            property.setBase(Property.Source.USER, Float.valueOf(Math.round(value * 100.0F) / 100.0F));
        }
    }

    // ---- type ---------------------------------------------------------

    private static LiquidFont font(float size, boolean bold) {
        return LiquidFont.of(Math.round(size * textScale * 4.0F) / 4.0F, bold);
    }

    // ---- drawing ------------------------------------------------------

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        myau.module.ModuleDocs.setEnglish(this.theme.language.getValue() == 1);
        ScaledResolution sr = new ScaledResolution(mc);
        long now = System.nanoTime();
        this.delta = this.lastFrame == 0L ? 0.016F
                : Math.min(0.05F, (now - this.lastFrame) / 1.0E9F);
        this.lastFrame = now;
        this.openedFor += this.delta;
        this.hint = "";
        this.hits.clear();
        this.lenses.clear();
        this.dropAnchored = false;
        AtlasInspector.beginFrame();

        long frameStart = System.nanoTime();
        applyTheme();
        paintPalette();
        followDrag(sr, mouseX, mouseY);
        /* Before anything of the menu is drawn: the copy it blurs has to be
           of the world alone. */
        Liquid.beginFrame();

        int width = windowWidth(sr);
        int height = windowHeight(sr);
        int x = left(sr);
        int y = top(sr);
        int sidebar = sidebarWidth(width);
        int detail = detailWidth(width);

        /* The window arrives the way the theme says: popping from 92% on a
           spring that overshoots a little, fading, or sliding up, while it
           fades in over the first fifth of a second. */
        int opening = this.theme.animations.getValue() ? this.theme.opening.getValue() : 3;
        this.open.target = 1.0F;
        float progress = opening == 3 ? 1.0F : this.open.step(this.delta * this.springSpeed);
        this.viewScale = opening == 0 ? progress : 1.0F;
        this.slideY = opening == 2 ? (1.0F - progress) / 0.08F * 18.0F : 0.0F;
        this.pivotX = x + width / 2.0F;
        this.pivotY = y + height / 2.0F;
        float fade = opening == 3 ? 1.0F : smooth(this.openedFor / 0.18F);
        float mx = layoutX(mouseX);
        float my = layoutY(mouseY);

        /* The light the glass catches follows the pointer, a little behind. */
        boolean follow = this.theme.followCursor.getValue();
        Liquid.lightX = toward("lightX", follow ? mouseX : x + width * 0.25F, 7.0F);
        Liquid.lightY = toward("lightY", follow ? mouseY : y - 120.0F, 7.0F);

        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Liquid.alpha = fade;
        LiquidFont.alpha = fade;
        GL11.glPushMatrix();
        try {
            /* The world behind is dimmed a little so the window is the
               brightest thing on screen. */
            float dim = this.theme.dim.getValue() / 100.0F;
            if (dim > 0.0F) {
                Liquid.rect(0, 0, sr.getScaledWidth(), sr.getScaledHeight(), 0.0F,
                        alpha(0xFF050810, dim * 0.87F), alpha(0xFF050810, Math.min(1.0F, dim * 1.25F)));
            }
            if (this.theme.cosmos.getValue()) {
                Cosmos.draw(sr.getScaledWidth(), sr.getScaledHeight(), accent(), this.theme.stars.getValue() / 100.0F,
                        this.theme.comets.getValue(), this.theme.planet.getValue(), fade);
            }

            GL11.glTranslatef(this.pivotX, this.pivotY + this.slideY, 0.0F);
            GL11.glScalef(this.viewScale, this.viewScale, 1.0F);
            GL11.glTranslatef(-this.pivotX, -this.pivotY, 0.0F);
            Liquid.setView(this.viewScale, this.pivotX * (1.0F - this.viewScale),
                    this.pivotY * (1.0F - this.viewScale) + this.slideY);

            float shadowSize = this.theme.shadowSize.getValue();
            if (this.theme.shadow.getValue() > 0 && shadowSize > 0) {
                Liquid.shadow(x, y, x + width, y + height, RADIUS, shadowSize,
                        alpha(0xFF000000, this.theme.shadow.getValue() / 100.0F), shadowSize * 0.4F);
            }
            Liquid.pane(x, y, x + width, y + height, RADIUS, this.theme.pane());
            /* Light caught in the thickness along the top. */
            int sheen = Math.round(this.theme.sheen.getValue() / 100.0F * 26.0F);
            if (sheen > 0) {
                Liquid.rect(x, y, x + width, y + 90, RADIUS, (sheen << 24) | 0x00FFFFFF, 0x00FFFFFF);
            }

            /* Registered first, so everything drawn into the header afterwards
               -- the search box above all -- sits on top of it and wins. */
            hit("move", null, x, y, x + width, y + HEADER - 1);

            int bodyTop = y + HEADER;
            int bodyHeight = height - HEADER - FOOTER;
            drawHeader(x, y, width, sidebar, detail, mx, my);
            Liquid.rect(x + 14, y + HEADER - 0.5F, x + width - 14, y + HEADER, 0.0F, ink(0x12));
            Liquid.rect(x + sidebar, bodyTop + 10, x + sidebar + 0.5F, bodyTop + bodyHeight - 10, 0.0F, ink(0x0F));
            Liquid.rect(x + width - detail, bodyTop + 10, x + width - detail + 0.5F,
                    bodyTop + bodyHeight - 10, 0.0F, ink(0x0F));

            drawSidebar(x, bodyTop, bodyHeight, sidebar, mx, my);
            if (this.profilesView) {
                drawProfileList(sr, x + sidebar, bodyTop, width - sidebar - detail, bodyHeight, mx, my);
                drawProfileDetail(sr, x + width - detail, bodyTop, detail, bodyHeight, mx, my);
            } else if (this.appearanceView) {
                drawAppearanceList(sr, x + sidebar, bodyTop, width - sidebar - detail, bodyHeight, mx, my);
                drawAppearanceDetail(sr, x + width - detail, bodyTop, detail, bodyHeight, mx, my);
            } else {
                drawList(sr, x + sidebar, bodyTop, width - sidebar - detail, bodyHeight, mx, my);
                drawDetail(sr, x + width - detail, bodyTop, detail, bodyHeight, mx, my);
            }
            if (this.theme.hintBar.getValue()) {
                drawHintBar(x, y + height - FOOTER, width);
            }
            drawGrip(x + width, y + height, mx, my);

            /* The lenses refract what is under them, so what is under them has
               to be finished first. */
            Liquid.captureContent(x, y, x + width, y + height);
            for (Lens lens : this.lenses) {
                if (lens.clip != null) {
                    clip(sr, lens.clip[0], lens.clip[1], lens.clip[2], lens.clip[3]);
                }
                int selectionStyle = this.theme.selection.getValue();
                if (lens.style.selection && selectionStyle == 1) {
                    Liquid.rect(lens.x, lens.y, lens.x2, lens.y2, lens.radius,
                            alpha(accent(), 0.10F + this.theme.selectionTint.getValue() / 100.0F),
                            alpha(accent(), 0.06F + this.theme.selectionTint.getValue() / 100.0F));
                } else if (lens.style.selection && selectionStyle == 2) {
                    Liquid.rim(lens.x, lens.y, lens.x2, lens.y2, lens.radius, 1.2F,
                            alpha(accent(), 0.85F), alpha(accent(), 0.45F));
                } else {
                    Liquid.lens(lens.x, lens.y, lens.x2, lens.y2, lens.radius, lens.style);
                }
                if (lens.clip != null) {
                    unclip();
                }
            }

            /* Last of all, and outside every scissor: an expanded mode list has
               to cover the rows below it, and a clipped dropdown is worse than
               none. */
            if (this.dropdown != null) {
                if (this.dropAnchored) {
                    drawDropdown(y, height, mx, my);
                } else {
                    /* Its row scrolled out of the pane, so there is nothing
                       left to hang the list from. */
                    this.dropdown = null;
                }
            }
        } finally {
            GL11.glPopMatrix();
            Liquid.setView(1.0F, 0.0F, 0.0F);
            Liquid.alpha = 1.0F;
            LiquidFont.alpha = 1.0F;
            unclip();
        }

        this.theme.autosave();
        this.perfMillis += ((System.nanoTime() - frameStart) / 1.0E6F - this.perfMillis) * 0.05F;
        if (this.perf) {
            String readout = String.format("%.2f ms  ·  %d draws  ·  %d fps", this.perfMillis, Liquid.draws,
                    net.minecraft.client.Minecraft.getDebugFPS());
            font(8.0F, false).drawRight(readout, sr.getScaledWidth() - 6, sr.getScaledHeight() - 8, 0xFFFFD166);
        }
        GlStateManager.disableBlend();
        super.drawScreen(mouseX, mouseY, partialTicks);
        /* Last, so the capture includes everything this frame drew and the
           audit is of the same frame that was captured. */
        AtlasInspector.tick();
    }

    /* Read from the jar and uploaded as a dynamic texture, like WaterMark's
       pictures: the mod's own assets are not in the game's resource packs,
       so a plain ResourceLocation found nothing and drew the missing-texture
       checkerboard (seen 2026-10-06). */
    private static ResourceLocation logo;
    private static boolean logoTried;

    private static ResourceLocation logo() {
        if (!logoTried) {
            logoTried = true;
            try (java.io.InputStream in = AtlasClickGui.class.getResourceAsStream("/assets/myau/assets/atlas-logo.png")) {
                if (in != null) {
                    logo = net.minecraft.client.Minecraft.getMinecraft().getTextureManager().getDynamicTextureLocation(
                            "myau_atlas_logo",
                            new net.minecraft.client.renderer.texture.DynamicTexture(javax.imageio.ImageIO.read(in)));
                }
            } catch (Exception ignored) {
                logo = null;
            }
        }
        return logo;
    }

    /** The logo texture as a square at (x, y), faded with the rest of the menu. */
    private static void drawLogo(float x, float y, float size) {
        ResourceLocation texture = logo();
        if (texture == null) {
            return;
        }
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(1.0F, 1.0F, 1.0F, Liquid.alpha);
        net.minecraft.client.Minecraft.getMinecraft().getTextureManager().bindTexture(texture);
        /* Smoothed: the 64 px picture is drawn at 18 GUI pixels. */
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        buffer.pos(x, y + size, 0.0D).tex(0.0D, 1.0D).endVertex();
        buffer.pos(x + size, y + size, 0.0D).tex(1.0D, 1.0D).endVertex();
        buffer.pos(x + size, y, 0.0D).tex(1.0D, 0.0D).endVertex();
        buffer.pos(x, y, 0.0D).tex(0.0D, 0.0D).endVertex();
        tessellator.draw();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void drawHeader(int x, int y, int width, int sidebar, int detail, float mx, float my) {
        float cy = y + HEADER / 2.0F;
        int accent = accent();

        /* The mark: the Myau Atlas logo (the A with the orbit) and the name
           in the banner's style -- MYAU in the text colour, ATLAS in lime
           (2026-10-06; it was a bead of the accent colour and "Myau+ Atlas"). */
        Liquid.shadow(x + 11, cy - 9, x + 29, cy + 9, 5.0F, 5.0F, 0x55000000, 0.0F);
        drawLogo(x + 11, cy - 9, 18.0F);
        float brand = font(12.5F, true).draw("MYAU", x + 35, cy, TEXT);
        float brandEnd = x + 35 + brand + 4
                + font(12.5F, true).draw("ATLAS", x + 35 + brand + 4, cy, myau.ui.UiMode.isLight() ? 0xFF5E8000 : 0xFFC6FF00);

        /* One row: the page tabs after the brand, the HUD editor and the
           dark/light switch on the right, and the search box filling the rest. */
        float tabsEnd = drawPageTabs(brandEnd + 14, cy, mx, my);

        float right = x + width - 12;
        float editorWidth = font(8.5F, true).width("HUD Editor") + 22;
        float editorX = right - editorWidth;
        drawButton("hudEditor", null, editorX, cy, editorWidth, "HUD Editor", 0, mx, my);
        if (mx >= editorX && mx <= right && my >= cy - 10 && my <= cy + 10) {
            this.hint = "Move the HUD elements on screen";
        }
        float modeLeft = drawModeSwitch(editorX - 10, cy, width >= 600, mx, my);

        /* Search covers every page; results from the other page are marked in the list. */
        float sx = tabsEnd + 12;
        float sx2 = modeLeft - 10;
        float sy = cy - 11.0F;
        float sy2 = cy + 11.0F;
        float scy = cy;
        boolean searchable = !"Client Settings".equals(this.page) && sx2 - sx >= 70.0F;
        if (searchable) {
            hit("search", null, sx, sy, sx2, sy2);
        }
        boolean hovered = searchable && mx >= sx && mx <= sx2 && my >= sy && my <= sy2;
        float focus = ease("searchFocus", this.searchFocused || !this.search.isEmpty(), 12.0F);
        float lift = ease("searchHover", hovered, 12.0F);
        if (searchable && focus > 0.01F) {
            Liquid.shadow(sx, sy, sx2, sy2, 11.0F, 7.0F, alpha(accent, 0.32F * focus), 0.0F);
        }
        if (searchable) {
            Liquid.rect(sx, sy, sx2, sy2, 11.0F, alpha(INK, 0.075F + 0.03F * lift + 0.03F * focus),
                    alpha(INK, 0.045F + 0.015F * lift));
            Liquid.rim(sx, sy, sx2, sy2, 11.0F, 1.0F, blend(ink(0x33), alpha(accent, 0.8F), focus),
                    ink(0x0D));
            icon("search", sx + 12, scy, focus > 0.5F ? TEXT : FAINT);
            LiquidFont body = font(9.0F, false);
            if (this.search.isEmpty()) {
                body.draw(this.searchFocused ? "" : "Search all modules", sx + 23, scy, FAINT);
            } else {
                float typed = body.draw(body.trim(this.search, sx2 - sx - 70), sx + 23, scy, TEXT);
                String results = visibleModules().size() + " found";
                font(8.0F, false).drawRight(results, sx2 - 10, scy, FAINT);
                if (this.searchFocused && (System.currentTimeMillis() / 500L) % 2L == 0L) {
                    Liquid.rect(sx + 24 + typed, scy - 4.5F, sx + 25 + typed, scy + 4.5F, 0.0F, accent);
                }
            }
            if (this.searchFocused && this.search.isEmpty()
                    && (System.currentTimeMillis() / 500L) % 2L == 0L) {
                Liquid.rect(sx + 23, scy - 4.5F, sx + 24, scy + 4.5F, 0.0F, accent);
            }
        }

        if (hovered) {
            this.hint = "Search every page  ·  results from the other page are tagged";
        } else if (this.hint.isEmpty() && mx >= x && mx <= x + width && my >= y && my <= y + HEADER - 1) {
            this.hint = "Drag here to move the window  ·  Ctrl+R resets it";
        }
    }

    /**
     * The page tabs as one compact segmented control. The selection slides
     * between tabs on a spring and takes each tab's width on the way.
     * Returns the control's right edge.
     */
    private float drawPageTabs(float left, float cy, float mx, float my) {
        String[] names = {"Modules", "Legit", "Client Settings"};
        LiquidFont label = font(8.5F, false);
        float pad = 9.0F;
        float top = cy - 9.0F;
        float bottom = cy + 9.0F;
        float[] lefts = new float[names.length];
        float[] widths = new float[names.length];
        float cursor = left + 2.0F;
        int activeIndex = 0;
        for (int i = 0; i < names.length; i++) {
            widths[i] = label.width(names[i]) + pad * 2.0F;
            lefts[i] = cursor;
            cursor += widths[i];
            if (names[i].equals(this.page)) {
                activeIndex = i;
            }
        }
        float right = cursor + 2.0F;
        Liquid.rect(left, top, right, bottom, 9.0F, ink(0x0E), ink(0x08));
        Liquid.rim(left, top, right, bottom, 9.0F, 1.0F, ink(0x16), ink(0x08));

        /* In the control's own coordinates, so moving the window carries it along. */
        Spring slide = spring("tabSlide", lefts[activeIndex] - left, 420.0F, 30.0F);
        Spring size = spring("tabSize", widths[activeIndex], 420.0F, 30.0F);
        float px = left + slide.value;
        float px2 = px + size.value;
        int accent = accent();
        Liquid.shadow(px, top + 2, px2, bottom - 2, 7.0F, 4.0F, alpha(accent, 0.22F), 0.0F);
        Liquid.rect(px, top + 2, px2, bottom - 2, 7.0F, alpha(accent, 0.32F), alpha(accent, 0.18F));
        Liquid.rim(px, top + 2, px2, bottom - 2, 7.0F, 1.0F, alpha(accent, 0.75F), alpha(accent, 0.30F));

        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            float l = lefts[i];
            float r = l + widths[i];
            boolean active = i == activeIndex;
            boolean hovered = mx >= l && mx <= r && my >= top && my <= bottom;
            hit("page", name, l, top, r, bottom);
            float glow = active ? 0.0F : ease("page:" + name, hovered, 10.0F);
            if (glow > 0.01F) {
                Liquid.rect(l + 1, top + 2, r - 1, bottom - 2, 7.0F,
                        alpha(INK, 0.08F * glow), alpha(INK, 0.035F * glow));
            }
            font(8.5F, active).drawCentred(name, l + widths[i] / 2.0F, cy, active ? TEXT : blend(DIM, TEXT, glow));
            if (hovered) {
                this.hint = "Open " + name;
            }
        }
        return right;
    }

    /**
     * The dark/light switch, right-aligned at {@code right}: a label (when there
     * is room for it) and a switch. Returns its left edge.
     */
    private float drawModeSwitch(float right, float cy, boolean withLabel, float mx, float my) {
        boolean light = this.theme.isLight();
        float switchWidth = 22.0F;
        float switchX = right - switchWidth;
        float left = switchX;
        LiquidFont small = font(8.0F, false);
        String label = light ? "Light" : "Dark";
        if (withLabel) {
            left = switchX - 6 - small.width(label);
        }
        hit("themeMode", null, left - 4, cy - 10, right + 2, cy + 10);
        boolean hovered = mx >= left - 4 && mx <= right + 2 && my >= cy - 10 && my <= cy + 10;
        if (withLabel) {
            small.draw(label, left, cy, hovered ? TEXT : DIM);
        }
        drawSwitch("themeMode", switchX, cy, switchWidth, 12.0F, light);
        if (hovered) {
            this.hint = light ? "Switch the menu and the HUD to dark" : "Switch the menu and the HUD to light";
        }
        return left - 4;
    }

    /**
     * The corner grip, drawn as the conventional diagonal. There is no other
     * affordance this small that anyone reads as "pull me".
     */
    private void drawGrip(int right, int bottom, float mx, float my) {
        boolean hovered = mx >= right - 14 && mx <= right - 2 && my >= bottom - 14 && my <= bottom - 2;
        hit("grip", null, right - 14, bottom - 14, right - 2, bottom - 2);
        int colour = hovered || this.resizingWindow ? accent() : ink(0x40);
        Liquid.line(right - 13, bottom - 5, right - 5, bottom - 13, 1.1F, colour);
        Liquid.line(right - 9, bottom - 5, right - 5, bottom - 9, 1.1F, colour);
        if (hovered) {
            this.hint = "Drag to resize  ·  currently " + this.userWidth + "x" + this.userHeight;
        }
    }

    private void drawSidebar(int x, int y, int height, int sidebar, float mx, float my) {
        List<String> shownCategories = sidebarCategories();
        String sidebarTitle = "Modules".equals(this.page) ? "MODULES"
                : "Legit".equals(this.page) ? "LEGIT" : "CLIENT SETTINGS";
        font(7.0F, true).drawTracked(sidebarTitle, x + 18, y + 16, FAINT, 0.8F);
        float top = y + 26;
        float profilesLabel = top + shownCategories.size() * CAT + 12;
        float profilesRow = profilesLabel + 10;
        float appearanceRow = profilesRow + CAT;
        boolean settingsPage = "Client Settings".equals(this.page);
        boolean profile = settingsPage && this.theme.accountCard.getValue()
                && y + height - (appearanceRow + CAT_H) >= 46;
        float limit = y + height - (profile ? 44 : 4);
        boolean profilesFit = settingsPage && profilesRow + CAT_H <= limit;
        boolean appearanceFit = settingsPage && appearanceRow + CAT_H <= limit;

        int activeIndex = this.profilesView || this.appearanceView ? -1
                : this.search.isEmpty() ? shownCategories.indexOf(this.category) : -1;
        boolean pillOnProfiles = this.profilesView && profilesFit;
        boolean pillOnAppearance = this.appearanceView && appearanceFit;

        /* The selected category sits under a droplet of clear glass. It slides
           between rows on a spring and stretches along its path in proportion
           to its speed, so it reads as liquid being pulled rather than a box
           being moved. Its shadow goes down before the labels, or it would
           darken the very text the glass is showing. */
        /* Sprung in the column's own coordinates, so dragging the window
           carries the pill with it instead of leaving it to catch up. */
        Spring pill = spring("catPill", pillOnProfiles ? profilesRow - top
                        : pillOnAppearance ? appearanceRow - top : Math.max(0, activeIndex) * CAT,
                380.0F, 26.0F);
        float shownPill = ease("catPillShown", activeIndex >= 0 || pillOnProfiles || pillOnAppearance, 12.0F);
        if (shownPill > 0.02F) {
            float stretch = Math.min(10.0F, Math.abs(pill.velocity) * this.jelly * 0.018F);
            float px = x + 10 + stretch * 0.25F;
            float px2 = x + sidebar - 10 - stretch * 0.25F;
            float py = top + pill.value - stretch / 2.0F;
            float py2 = top + pill.value + CAT_H + stretch / 2.0F;
            Liquid.shadow(px, py, px2, py2, 11.0F, 6.0F, alpha(0x40000000, shownPill), 2.0F);
            this.lenses.add(new Lens(px, py, px2, py2, 11.0F,
                    pillStyle(shownPill), null));
        }

        for (int i = 0; i < shownCategories.size(); i++) {
            String name = shownCategories.get(i);
            float rowY = top + i * CAT;
            if (rowY + CAT_H > limit) {
                break;
            }
            boolean active = i == activeIndex;
            boolean hovered = mx >= x + 10 && mx <= x + sidebar - 10 && my >= rowY && my <= rowY + CAT_H;
            hit("category", name, x + 10, rowY, x + sidebar - 10, rowY + CAT_H);
            float rowLit = ease("cat:" + name, hovered && !active, 10.0F);
            if (rowLit > 0.01F) {
                Liquid.rect(x + 10, rowY, x + sidebar - 10, rowY + CAT_H, 11.0F,
                        alpha(ink(0x12), rowLit), alpha(ink(0x0A), rowLit));
            }
            float cy = rowY + CAT_H / 2.0F;
            icon(sidebarIcon(name), x + 25, cy, active ? TEXT : DIM);
            font(9.5F, active).draw(font(9.5F, active).trim(name, sidebar - 60), x + 36, cy, active ? TEXT : DIM);
            List<Module> list = modulesOf(name);
            int on = 0;
            for (Module module : list) {
                if (module != null && module.isEnabled()) {
                    on++;
                }
            }
            if (on > 0 && this.theme.counts.getValue()) {
                font(8.0F, false).drawRight(String.valueOf(on), x + sidebar - 16, cy,
                        active ? alpha(TEXT, 0.8F) : FAINT);
            }
            if (hovered) {
                this.hint = on + " of " + list.size() + " enabled in " + name;
            }
        }

        if (profilesFit) {
            font(7.0F, true).drawTracked("CLIENT", x + 18, profilesLabel, FAINT, 0.8F);
            boolean hovered = mx >= x + 10 && mx <= x + sidebar - 10 && my >= profilesRow
                    && my <= profilesRow + CAT_H;
            hit("profiles", null, x + 10, profilesRow, x + sidebar - 10, profilesRow + CAT_H);
            float rowLit = ease("cat:\u0000profiles", hovered && !this.profilesView, 10.0F);
            if (rowLit > 0.01F) {
                Liquid.rect(x + 10, profilesRow, x + sidebar - 10, profilesRow + CAT_H, 11.0F,
                        alpha(ink(0x12), rowLit), alpha(ink(0x0A), rowLit));
            }
            float cy = profilesRow + CAT_H / 2.0F;
            icon("folder", x + 25, cy, this.profilesView ? TEXT : DIM);
            font(9.5F, this.profilesView).draw("Profiles", x + 36, cy, this.profilesView ? TEXT : DIM);
            font(8.0F, false).drawRight(String.valueOf(profiles().size()), x + sidebar - 16, cy,
                    this.profilesView ? alpha(TEXT, 0.8F) : FAINT);
            if (hovered) {
                this.hint = "Saved profiles  ·  using " + AtlasProfiles.current();
            }
        }
        if (appearanceFit) {
            boolean hovered = mx >= x + 10 && mx <= x + sidebar - 10 && my >= appearanceRow
                    && my <= appearanceRow + CAT_H;
            hit("appearance", null, x + 10, appearanceRow, x + sidebar - 10, appearanceRow + CAT_H);
            float rowLit = ease("cat:#appearance", hovered && !this.appearanceView, 10.0F);
            if (rowLit > 0.01F) {
                Liquid.rect(x + 10, appearanceRow, x + sidebar - 10, appearanceRow + CAT_H, 11.0F,
                        alpha(ink(0x12), rowLit), alpha(ink(0x0A), rowLit));
            }
            float cy = appearanceRow + CAT_H / 2.0F;
            icon("palette", x + 25, cy, this.appearanceView ? TEXT : DIM);
            font(9.5F, this.appearanceView).draw("Appearance", x + 36, cy, this.appearanceView ? TEXT : DIM);
            if (hovered) {
                this.hint = "Colours, glass, motion, text and layout of this menu";
            }
        }

        if (profile) {
            drawProfile(x, y + height - 40, sidebar);
        }
    }

    /** The profile list, re-read from disk at most twice a second. */
    private List<AtlasProfiles.Info> profiles() {
        /* Listing the folder means touching every file in it -- there are over
           a hundred logs beside the profiles -- so the sidebar's count is
           refreshed every ten seconds, and only the open page every second.
           Saving or deleting from here refreshes at once anyway. */
        long now = System.currentTimeMillis();
        if (now - this.profilesReadAt > (this.profilesView ? 1000L : 10000L)) {
            this.profiles = AtlasProfiles.list();
            this.profilesReadAt = now;
        }
        return this.profiles;
    }

    private AtlasProfiles.Info profileNamed(String name) {
        for (AtlasProfiles.Info info : profiles()) {
            if (info.name.equals(name)) {
                return info;
            }
        }
        return null;
    }

    /**
     * The middle column in the profiles view: a field to save the current
     * setup under a new name, and every saved profile below it.
     */
    private void drawProfileList(ScaledResolution sr, int x, int y, int width, int height, float mx, float my) {
        List<AtlasProfiles.Info> list = profiles();
        String current = AtlasProfiles.current();
        font(15.0F, true).draw("Profiles", x + 16, y + 17, TEXT);
        font(8.0F, false).draw(list.size() + (list.size() == 1 ? " saved" : " saved") + "  ·  using " + current,
                x + 16, y + 31, FAINT);
        int accent = accent();

        /* New profile: type a name, press Enter or Save. */
        float fy = y + 42;
        float fy2 = fy + 22;
        float fcy = fy + 11;
        float buttonX = x + width - 10 - 54;
        float focus = ease("profileNameFocus", this.profileNameFocused, 12.0F);
        hit("profileName", null, x + 10, fy, buttonX - 6, fy2);
        if (focus > 0.01F) {
            Liquid.shadow(x + 10, fy, buttonX - 6, fy2, 11.0F, 6.0F, alpha(accent, 0.30F * focus), 0.0F);
        }
        Liquid.rect(x + 10, fy, buttonX - 6, fy2, 11.0F, alpha(INK, 0.07F + 0.03F * focus), ink(0x0B));
        Liquid.rim(x + 10, fy, buttonX - 6, fy2, 11.0F, 1.0F, blend(ink(0x2E), alpha(accent, 0.8F), focus),
                ink(0x0A));
        LiquidFont body = font(9.0F, false);
        float typed = 0.0F;
        if (this.profileName.isEmpty()) {
            if (!this.profileNameFocused) {
                body.draw("New profile name", x + 20, fcy, FAINT);
            }
        } else {
            typed = body.draw(body.trim(this.profileName, buttonX - x - 40), x + 20, fcy, TEXT);
        }
        if (this.profileNameFocused && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            Liquid.rect(x + 21 + typed, fcy - 4.5F, x + 22 + typed, fcy + 4.5F, 0.0F, accent);
        }
        if (mx >= x + 10 && mx <= buttonX - 6 && my >= fy && my <= fy2) {
            this.hint = "Name a profile for everything as it is set now  ·  Enter saves it";
        }
        drawButton("profileCreate", null, buttonX, fcy, 54.0F, "Save", 0, mx, my);

        float top = y + 72;
        float bottom = y + height - 4;
        int maxRows = Math.max(1, (int) ((bottom - top) / ROW));
        this.profileMaxScroll = Math.max(0, list.size() - maxRows);
        if (this.profileScroll > this.profileMaxScroll) {
            this.profileScroll = this.profileMaxScroll;
        }
        if (list.isEmpty()) {
            font(9.0F, false).draw("No profiles yet", x + 16, top + 12, FAINT);
            return;
        }
        float shown = scrollEase("profileScroll", this.profileScroll * (float) ROW);
        clip(sr, x, top - 2, width, bottom - top + 2);

        int selectedIndex = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(this.profileSelected)) {
                selectedIndex = i;
            }
        }
        if (selectedIndex >= 0) {
            Spring pill = spring("profilePill", selectedIndex * ROW, 380.0F, 26.0F);
            float stretch = Math.min(8.0F, Math.abs(pill.velocity) * this.jelly * 0.015F);
            float py = top + pill.value - shown - stretch / 2.0F;
            float py2 = top + pill.value - shown + ROW_H + stretch / 2.0F;
            float px = x + 10 - stretch * 0.3F;
            float px2 = x + width - 10 + stretch * 0.3F;
            if (py2 > top && py < bottom) {
                Liquid.shadow(px, py, px2, py2, 9.0F, 5.0F, 0x33000000, 2.0F);
                this.lenses.add(new Lens(px, py, px2, py2, 9.0F, pillStyle(1.0F),
                        new float[]{x, top - 2, width, bottom - top + 2}));
            }
        }

        for (int i = 0; i < list.size(); i++) {
            AtlasProfiles.Info info = list.get(i);
            float rowY = top + i * ROW - shown;
            if (rowY + ROW_H < top || rowY > bottom) {
                continue;
            }
            float cy = rowY + ROW_H / 2.0F;
            boolean inUse = info.name.equalsIgnoreCase(current);
            boolean hovered = mx >= x + 10 && mx <= x + width - 10 && my >= rowY && my <= rowY + ROW_H
                    && my >= top && my <= bottom;
            float clippedTop = Math.max(rowY, top);
            float clippedBottom = Math.min(rowY + ROW_H, bottom);
            if (clippedBottom - clippedTop > 4.0F) {
                hit("profile", info.name, x + 10, clippedTop, x + width - 10, clippedBottom);
            }
            float hoverLit = ease("prow:" + info.name, hovered && !info.name.equals(this.profileSelected), 9.0F);
            if (hoverLit > 0.01F) {
                Liquid.rect(x + 10, rowY, x + width - 10, rowY + ROW_H, 9.0F,
                        alpha(ink(0x10), hoverLit), alpha(ink(0x0A), hoverLit));
            }
            float saved = pulseAt("saved:" + info.name, 600L);
            if (saved >= 0.0F) {
                Liquid.rect(x + 10, rowY, x + width - 10, rowY + ROW_H, 9.0F,
                        alpha(accent, 0.30F * (1.0F - saved)));
            }
            icon("file", x + 21, cy, inUse ? accent : DIM);
            LiquidFont name = font(9.5F, inUse);
            float nameWidth = name.draw(name.trim(info.name, width - 150), x + 30, cy, inUse ? TEXT : DIM);
            if (inUse) {
                LiquidFont tag = font(7.0F, true);
                float tagX = x + 30 + nameWidth + 8;
                float tagWidth = tag.width("IN USE") + 10;
                Liquid.rect(tagX, cy - 5.5F, tagX + tagWidth, cy + 5.5F, 5.5F, alpha(accent, 0.22F));
                tag.draw("IN USE", tagX + 5, cy, accent);
            }
            LiquidFont small = font(8.0F, false);
            small.drawRight(info.enabled.size() + " on  ·  " + AtlasProfiles.age(info.modified),
                    x + width - 18, cy, FAINT);
            if (hovered) {
                this.hint = info.name + "  ·  " + info.enabled.size() + " modules on  ·  click for details";
            }
        }
        unclip();
    }

    /**
     * The right column in the profiles view: what the selected profile turns
     * on, and what can be done with it.
     */
    private void drawProfileDetail(ScaledResolution sr, int x, int y, int width, int height, float mx, float my) {
        AtlasProfiles.Info info = this.profileSelected == null ? null : profileNamed(this.profileSelected);
        LiquidFont body = font(8.5F, false);
        if (info == null) {
            font(14.0F, true).draw("Select a profile", x + 16, y + 17, TEXT);
            float cursor = y + 34;
            for (String line : body.wrap("Profiles are the same files .config save writes, in config/Myau. "
                    + "Load one to switch every module and setting at once.", width - 32)) {
                body.draw(line, x + 16, cursor, DIM);
                cursor += 11;
            }
            cursor += 8;
            drawButton("profileFolder", null, x + 16, cursor, 0.0F, "Open folder", 1, mx, my);
            return;
        }
        float arrived = toward("profileArrive", 1.0F, 11.0F);
        float lift = (1.0F - arrived) * 6.0F;
        boolean inUse = info.name.equalsIgnoreCase(AtlasProfiles.current());
        LiquidFont title = font(14.0F, true);
        title.draw(title.trim(info.name, width - 32), x + 16, y + 17 + lift, alpha(TEXT, arrived));
        float cursor = y + 33 + lift;
        String saved = "Saved " + AtlasProfiles.age(info.modified) + "  ·  "
                + new java.text.SimpleDateFormat("MMM d, HH:mm").format(new java.util.Date(info.modified));
        font(8.0F, false).draw(saved, x + 16, cursor, alpha(FAINT, arrived));
        cursor += 12;
        if (inUse) {
            for (String line : body.wrap("In use. Changes made since loading it stay in memory "
                    + "until you save them here.", width - 32)) {
                body.draw(line, x + 16, cursor, alpha(DIM, arrived));
                cursor += 11;
            }
        }
        cursor += 12;

        /* Load, save over it, delete. Delete asks twice. */
        float bx = x + 16;
        bx += drawButton("profileLoad", info.name, bx, cursor, 0.0F, inUse ? "Reload" : "Load", 0, mx, my) + 6;
        bx += drawButton("profileSave", info.name, bx, cursor, 0.0F, "Save here", 1, mx, my) + 6;
        boolean confirming = info.name.equals(this.confirmDelete)
                && System.currentTimeMillis() - this.confirmDeleteAt < CONFIRM_MS;
        if (!"default".equalsIgnoreCase(info.name)) {
            drawButton("profileDelete", info.name, bx, cursor, 0.0F, confirming ? "Sure?" : "Delete", 2, mx, my);
        }
        cursor += 22;

        font(7.0F, true).drawTracked("ENABLED MODULES  ·  " + info.enabled.size(), x + 18, cursor, FAINT, 0.8F);
        cursor += 8;
        float cardX = x + 12;
        float cardX2 = x + width - 12;
        float cardBottom = y + height - 32;
        if (cardBottom - cursor < 24) {
            return;
        }
        StringBuilder names = new StringBuilder();
        for (String module : info.enabled) {
            if (names.length() > 0) {
                names.append("  ·  ");
            }
            names.append(module);
        }
        List<String> lines = body.wrap(names.length() == 0 ? "Nothing enabled" : names.toString(), cardX2 - cardX - 20);
        float needed = lines.size() * 11 + 12;
        cardBottom = Math.min(cardBottom, cursor + needed);
        Liquid.rect(cardX, cursor, cardX2, cardBottom, 12.0F, ink(0x0E), ink(0x09));
        Liquid.rim(cardX, cursor, cardX2, cardBottom, 12.0F, 1.0F, ink(0x24), ink(0x0A));
        clip(sr, cardX, cursor + 1, cardX2 - cardX, cardBottom - cursor - 2);
        float lineY = cursor + 11;
        for (String line : lines) {
            body.draw(line, cardX + 10, lineY, names.length() == 0 ? FAINT : DIM);
            lineY += 11;
        }
        unclip();
        drawButton("profileFolder", null, x + 16, y + height - 16, 0.0F, "Open folder", 1, mx, my);
    }

    /**
     * Pushes the theme into everything that reads it, once a frame and before
     * any of it is drawn: the blur, the typeface, the springs and the layout.
     */
    private void applyTheme() {
        Liquid.blurLevels = this.theme.blurLevels();
        Liquid.blurOffset = this.theme.blurOffset();
        LiquidFont.faceFile = this.theme.fontFile();
        boolean animated = this.theme.animations.getValue();
        Spring.dampingScale = animated ? this.theme.damping() : 1.0F;
        this.springSpeed = animated ? this.theme.springSpeed() : 1.0F;
        this.jelly = animated ? this.theme.jelly() : 0.0F;
        /* In steps of five percent, and the old sheets dropped on a change:
           every distinct size is a set of glyph sheets, and dragging the
           slider would otherwise leave hundreds of them behind. */
        float scale = Math.round(this.theme.textSize.getValue() / 5.0F) * 0.05F;
        if (scale != textScale) {
            textScale = scale;
            LiquidFont.clear();
        }
        boolean compact = this.theme.compact();
        this.ROW_H = compact ? 20 : 24;
        this.ROW = compact ? 22 : 26;
        this.CAT_H = compact ? 22 : 26;
        this.CAT = compact ? 24 : 28;
        this.RADIUS = this.theme.radius.getValue();
        this.FOOTER = this.theme.hintBar.getValue() ? 20 : 8;
    }

    /**
     * The middle column of the Appearance page: presets, then the groups of
     * settings, each with a one-line summary of where it stands.
     */
    private void drawAppearanceList(ScaledResolution sr, int x, int y, int width, int height, float mx, float my) {
        font(15.0F, true).draw("Appearance", x + 16, y + 17, TEXT);
        font(8.0F, false).draw("Applies as you change it  ·  saved automatically", x + 16, y + 31, FAINT);

        float cursor = y + 50;
        font(7.0F, true).drawTracked("PRESETS", x + 18, cursor, FAINT, 0.8F);
        cursor += 15;
        LiquidFont buttonFont = font(8.5F, true);
        float bx = x + 16;
        for (String preset : AtlasTheme.PRESETS) {
            float w = buttonFont.width(preset) + 22;
            if (bx + w > x + width - 12) {
                bx = x + 16;
                cursor += 24;
            }
            drawButton("preset", preset, bx, cursor, 0.0F, preset, 1, mx, my);
            bx += w + 6;
        }
        float resetWidth = buttonFont.width("Reset") + 22;
        if (bx + resetWidth > x + width - 12) {
            bx = x + 16;
            cursor += 24;
        }
        drawButton("themeReset", null, bx, cursor, 0.0F, "Reset", 2, mx, my);
        cursor += 24;

        font(7.0F, true).drawTracked("SETTINGS", x + 18, cursor, FAINT, 0.8F);
        float top = cursor + 8;
        float bottom = y + height - 4;
        List<String> groups = new ArrayList<String>(this.theme.groups.keySet());
        int selectedIndex = groups.indexOf(this.appearanceGroup);
        clip(sr, x, top - 2, width, bottom - top + 2);
        if (selectedIndex >= 0) {
            Spring pill = spring("groupPill", selectedIndex * ROW, 380.0F, 26.0F);
            float stretch = Math.min(8.0F, Math.abs(pill.velocity) * this.jelly * 0.015F);
            float py = top + pill.value - stretch / 2.0F;
            float py2 = top + pill.value + ROW_H + stretch / 2.0F;
            Liquid.shadow(x + 10, py, x + width - 10, py2, 9.0F, 5.0F, 0x33000000, 2.0F);
            this.lenses.add(new Lens(x + 10 - stretch * 0.3F, py, x + width - 10 + stretch * 0.3F, py2, 9.0F,
                    pillStyle(1.0F), new float[]{x, top - 2, width, bottom - top + 2}));
        }
        for (int i = 0; i < groups.size(); i++) {
            String group = groups.get(i);
            float rowY = top + i * ROW;
            if (rowY > bottom) {
                break;
            }
            float cy = rowY + ROW_H / 2.0F;
            boolean active = group.equals(this.appearanceGroup);
            boolean hovered = mx >= x + 10 && mx <= x + width - 10 && my >= rowY && my <= rowY + ROW_H
                    && my <= bottom;
            hit("group", group, x + 10, rowY, x + width - 10, Math.min(rowY + ROW_H, bottom));
            float hoverLit = ease("grp:" + group, hovered && !active, 9.0F);
            if (hoverLit > 0.01F) {
                Liquid.rect(x + 10, rowY, x + width - 10, rowY + ROW_H, 9.0F,
                        alpha(ink(0x10), hoverLit), alpha(ink(0x0A), hoverLit));
            }
            icon(group, x + 22, cy, active ? TEXT : DIM);
            font(9.5F, active).draw(group, x + 34, cy, active ? TEXT : DIM);
            LiquidFont small = font(8.0F, false);
            small.drawRight(small.trim(this.theme.summary(group), width - 120), x + width - 18, cy, FAINT);
            if (hovered) {
                this.hint = this.theme.descriptions.get(group);
            }
        }
        unclip();
    }

    /** The right column of the Appearance page: the open group's settings. */
    private void drawAppearanceDetail(ScaledResolution sr, int x, int y, int width, int height, float mx, float my) {
        List<Property<?>> all = this.theme.groups.get(this.appearanceGroup);
        if (all == null) {
            this.appearanceGroup = "Colors";
            all = this.theme.groups.get(this.appearanceGroup);
        }
        float arrived = toward("groupArrive", 1.0F, 11.0F);
        float lift = (1.0F - arrived) * 6.0F;
        font(14.0F, true).draw(this.appearanceGroup, x + 16, y + 17 + lift, alpha(TEXT, arrived));
        float cursor = y + 34 + lift;
        LiquidFont body = font(8.5F, false);
        List<String> lines = body.wrap(this.theme.descriptions.get(this.appearanceGroup), width - 32);
        for (int i = 0; i < Math.min(4, lines.size()); i++) {
            body.draw(lines.get(i), x + 16, cursor, alpha(DIM, arrived));
            cursor += 11;
        }
        cursor += 8;
        List<Property<?>> visible = new ArrayList<Property<?>>();
        for (Property<?> property : all) {
            if (property.isVisible()) {
                visible.add(property);
            }
        }
        drawSettingsCard(sr, "theme", visible, x + 12, x + width - 12, cursor, y + height - 6, mx, my);
    }

    /**
     * A capsule button. Style 0 is the accent, 1 plain glass, 2 destructive.
     * A width of 0 fits the label. Returns the width drawn.
     */
    private float drawButton(String kind, Object payload, float x, float cy, float width, String label,
                             int style, float mx, float my) {
        LiquidFont bold = font(8.5F, true);
        float w = width > 0.0F ? width : bold.width(label) + 22;
        float y = cy - 10;
        float y2 = cy + 10;
        hit(kind, payload, x, y, x + w, y2);
        boolean hovered = mx >= x && mx <= x + w && my >= y && my <= y2;
        float hover = ease("btn:" + kind, hovered, 12.0F);
        float press = pulseAt("btn:" + kind, 260L);
        float squash = press >= 0.0F ? (float) Math.sin(press * Math.PI) * 1.2F : 0.0F;
        int colour = style == 0 ? accent() : style == 2 ? 0xFFFF5A5F : 0xFFFFFFFF;
        float fill = style == 1 ? 0.08F + 0.05F * hover : 0.30F + 0.12F * hover;
        if (style != 1) {
            Liquid.shadow(x, y, x + w, y2, 10.0F, 5.0F, alpha(colour, 0.25F + 0.15F * hover), 0.0F);
        }
        Liquid.rect(x + squash, y + squash * 0.5F, x + w - squash, y2 - squash * 0.5F, 10.0F,
                alpha(colour, fill), alpha(colour, fill * 0.7F));
        Liquid.rim(x + squash, y + squash * 0.5F, x + w - squash, y2 - squash * 0.5F, 10.0F, 1.0F,
                alpha(style == 1 ? 0xFFFFFFFF : colour, style == 1 ? 0.2F : 0.65F), ink(0x0A));
        bold.drawCentred(label, x + w / 2.0F, cy, TEXT);
        if (hovered) {
            if ("profileLoad".equals(kind)) {
                this.hint = "Switch every module and setting to " + payload;
            } else if ("profileSave".equals(kind)) {
                this.hint = "Overwrite " + payload + " with everything as it is set now";
            } else if ("profileDelete".equals(kind)) {
                this.hint = "Delete " + payload + ".json  ·  click twice";
            } else if ("profileFolder".equals(kind)) {
                this.hint = "Open " + AtlasProfiles.DIR.getPath();
            } else if ("profileCreate".equals(kind)) {
                this.hint = "Save everything as it is set now under the typed name";
            } else if ("preset".equals(kind)) {
                this.hint = "Start from the " + payload + " look  ·  every Appearance setting is replaced";
            } else if ("themeReset".equals(kind)) {
                this.hint = "Put every Appearance setting back to how the menu was designed";
            }
        }
        return w;
    }

    /** Who is playing, and where: the account, the server and the ping. */
    private void drawProfile(int x, float y, int sidebar) {
        Liquid.rect(x + 10, y, x + sidebar - 10, y + 28, 11.0F, ink(0x0D), ink(0x08));
        Liquid.rim(x + 10, y, x + sidebar - 10, y + 28, 11.0F, 1.0F, ink(0x1F), ink(0x08));
        String name = mc.getSession() == null ? "Player" : mc.getSession().getUsername();
        float cy = y + 14;
        Liquid.rect(x + 16, cy - 7, x + 30, cy + 7, 7.0F, 0xFF8E7CFF, accent());
        font(8.0F, true).drawCentred(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(),
                x + 23, cy, TEXT);
        LiquidFont bold = font(8.5F, true);
        bold.draw(bold.trim(name, sidebar - 50), x + 35, cy - 4.5F, TEXT);
        String where = "Singleplayer";
        if (mc.getCurrentServerData() != null && mc.getCurrentServerData().serverIP != null) {
            where = mc.getCurrentServerData().serverIP;
            int colon = where.indexOf(':');
            if (colon > 0) {
                where = where.substring(0, colon);
            }
            if (where.startsWith("play.")) {
                where = where.substring(5);
            }
        }
        try {
            if (mc.getNetHandler() != null && mc.thePlayer != null) {
                NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
                if (info != null && info.getResponseTime() > 0) {
                    where = where + "  ·  " + info.getResponseTime() + " ms";
                }
            }
        } catch (Exception ignored) {
            // Mid-transfer; the server name alone will do.
        }
        LiquidFont small = font(7.0F, false);
        small.draw(small.trim(where, sidebar - 50), x + 35, cy + 4.5F, FAINT);
    }

    private void drawList(ScaledResolution sr, int x, int y, int width, int height, float mx, float my) {
        List<Module> modules = visibleModules();
        boolean searching = !this.search.trim().isEmpty();
        int on = 0;
        for (Module module : modules) {
            if (module.isEnabled()) {
                on++;
            }
        }
        String title = searching ? "Search" : this.category;
        font(15.0F, true).draw(title, x + 16, y + 17, TEXT);
        int elsewhere = 0;
        if (searching) {
            for (Module module : modules) {
                if (!belongsOnPage(module)) {
                    elsewhere++;
                }
            }
        }
        String subtitle = searching
                ? modules.size() + (modules.size() == 1 ? " result" : " results")
                        + (elsewhere > 0 ? "  ·  " + elsewhere + " from another page" : "")
                        + "  ·  " + on + " enabled"
                : modules.size() + " modules  ·  " + on + " enabled";
        font(8.0F, false).draw(subtitle, x + 16, y + 31, FAINT);

        float top = y + 42;
        float bottom = y + height - 4;
        int maxRows = Math.max(1, (int) ((bottom - top) / ROW));
        this.listMaxScroll = Math.max(0, modules.size() - maxRows);
        if (this.listScroll > this.listMaxScroll) {
            this.listScroll = this.listMaxScroll;
        }
        if (modules.isEmpty()) {
            font(9.0F, false).draw("No module matches \"" + this.search + "\"", x + 16, top + 12, FAINT);
            return;
        }

        /* Scrolling moves a pixel offset rather than an index, so a row can be
           part-way past the edge. Clipping is what lets that be true. */
        float shown = scrollEase("listScroll", this.listScroll * (float) ROW);
        clip(sr, x, top - 2, width, bottom - top + 2);

        /* The selected module sits under the same glass as the category, its
           shadow laid down first for the same reason. */
        int selectedIndex = modules.indexOf(this.selected);
        if (selectedIndex >= 0) {
            /* In list coordinates for the same reason, and before the scroll
               offset, so scrolling moves the pill exactly with its row and
               only a change of selection makes it slide. */
            Spring pill = spring("rowPill", selectedIndex * ROW, 380.0F, 26.0F);
            float stretch = Math.min(8.0F, Math.abs(pill.velocity) * this.jelly * 0.015F);
            float px = x + 10 - stretch * 0.3F;
            float px2 = x + width - 10 + stretch * 0.3F;
            float py = top + pill.value - shown - stretch / 2.0F;
            float py2 = top + pill.value - shown + ROW_H + stretch / 2.0F;
            if (py2 > top && py < bottom) {
                Liquid.shadow(px, py, px2, py2, 9.0F, 5.0F, 0x33000000, 2.0F);
                this.lenses.add(new Lens(px, py, px2, py2, 9.0F, pillStyle(1.0F),
                        new float[]{x, top - 2, width, bottom - top + 2}));
            }
        }

        int first = Math.max(0, (int) (shown / ROW) - 1);
        for (int i = 0; i < modules.size(); i++) {
            Module module = modules.get(i);
            if (i < first) {
                continue;
            }
            float rowY = top + i * ROW - shown;
            if (rowY > bottom) {
                continue;
            }
            AtlasInspector.note("list", module.getName(), x + 10, rowY, x + width - 10, rowY + ROW_H);
            boolean hovered = mx >= x + 10 && mx <= x + width - 10 && my >= rowY && my <= rowY + ROW_H
                    && my >= top && my <= bottom;
            /* Only the part of a row inside the clip can be clicked, because
               only that part can be seen. */
            float clippedTop = Math.max(rowY, top);
            float clippedBottom = Math.min(rowY + ROW_H, bottom);
            if (clippedBottom - clippedTop > 4.0F) {
                hit("module", module, x + 10, clippedTop, x + width - 10, clippedBottom);
            }
            boolean isSelected = module == this.selected;
            float hoverLit = ease("row:" + module.getName(), hovered && !isSelected, 9.0F);
            if (hoverLit > 0.01F) {
                Liquid.rect(x + 10, rowY, x + width - 10, rowY + ROW_H, 9.0F,
                        alpha(ink(0x10), hoverLit), alpha(ink(0x0A), hoverLit));
            }
            /* A result from the other page looks borrowed: its own tint, a dashed
               edge, and a tag naming the page it lives on. */
            boolean foreign = searching && !belongsOnPage(module);
            if (foreign) {
                Liquid.rect(x + 10, rowY, x + width - 10, rowY + ROW_H, 9.0F,
                        alpha(foreignColour(), 0.10F), alpha(foreignColour(), 0.05F));
                dashedRim(x + 10, rowY, x + width - 10, rowY + ROW_H, 9.0F, alpha(foreignColour(), 0.75F));
            }
            float cy = rowY + ROW_H / 2.0F;
            int accent = accent();

            /* The state bead is the leftmost thing on the row and the only
               accent-coloured dot there, so "what is on" is answerable at a
               glance. A ring leaves it once on the click that changed it. */
            float onLit = ease("on:" + module.getName(), module.isEnabled(), 12.0F);
            float ring = pulseAt("tog:" + module.getName(), 420L);
            boolean dots = this.theme.stateDots.getValue();
            float nameX = dots ? x + 30 : x + 20;
            if (!dots) {
                ring = -1.0F;
                onLit = 0.0F;
            }
            if (ring >= 0.0F) {
                float expand = smooth(ring) * 7.0F;
                Liquid.ring(x + 20.5F, cy, 2.5F + expand, 1.0F, alpha(accent, 0.6F * (1.0F - ring)));
            }
            if (onLit > 0.01F) {
                Liquid.shadow(x + 18, cy - 2.5F, x + 23, cy + 2.5F, 2.5F, 4.0F, alpha(accent, 0.9F * onLit), 0.0F);
            }
            if (dots) {
                Liquid.dot(x + 20.5F, cy, 2.5F, blend(ink(0x2E), accent, onLit));
            }

            LiquidFont name = font(9.5F, false);
            float nameWidth = name.draw(module.getName(), nameX, cy, module.isEnabled() ? TEXT : DIM);
            if (module.isHidden()) {
                icon("eyeOff", nameX + nameWidth + 10, cy, FAINT);
            }

            float switchX = x + width - 18 - 22;
            /* Only the switch turns a module on or off; the rest of the row
               selects it. Registered after the row, so it wins where they
               overlap, and a little larger than drawn so it is easy to hit. */
            if (clippedBottom - clippedTop > 4.0F) {
                hit("rowSwitch", module, switchX - 4, Math.max(cy - 9, top), switchX + 26, Math.min(cy + 9, bottom));
            }
            drawSwitch("row:" + module.getName(), switchX, cy, 22.0F, 12.0F, module.isEnabled());
            if (mx >= switchX - 4 && mx <= switchX + 26 && my >= cy - 9 && my <= cy + 9) {
                this.hint = (module.isEnabled() ? "Turn off " : "Turn on ") + module.getName();
            }
            float tagRight = switchX - 8;
            if (foreign) {
                String source = pageOf(module);
                LiquidFont tag = font(7.0F, true);
                float tagLeft = tagRight - tag.width(source) - 10;
                Liquid.rect(tagLeft, cy - 5.5F, tagRight, cy + 5.5F, 5.5F,
                        alpha(foreignColour(), 0.28F), alpha(foreignColour(), 0.18F));
                tag.drawCentred(source, (tagLeft + tagRight) / 2.0F, cy, foreignText());
                tagRight = tagLeft - 6;
            }
            String suffix = this.theme.moduleStatus.getValue() ? suffixOf(module) : "";
            if (!suffix.isEmpty()) {
                LiquidFont small = font(8.0F, false);
                float room = tagRight - (nameX + nameWidth + (module.isHidden() ? 22 : 10));
                if (room > 20) {
                    small.drawRight(small.trim(suffix, room), tagRight, cy, FAINT);
                }
            }
            if (hovered && foreign && this.hint.isEmpty()) {
                this.hint = "From the " + pageOf(module) + " page  ·  its settings open here";
            }
            if (hovered && this.hint.isEmpty()) {
                this.hint = module.getDescription() == null || module.getDescription().isEmpty()
                        ? "Click to show the settings  ·  the switch turns it on or off"
                        : module.getDescription();
            }
        }
        unclip();

        /* A thin capsule on the right edge, only while there is more. */
        if (this.listMaxScroll > 0) {
            float track = bottom - top;
            float thumb = Math.max(18.0F, track * maxRows / (float) modules.size());
            float along = (track - thumb) * (shown / (this.listMaxScroll * (float) ROW));
            float barX = x + width - 5;
            Liquid.rect(barX, top + along, barX + 2.5F, top + along + thumb, 1.25F, ink(0x40));
        }
    }

    /** The selection droplet, as the theme has it; {@code shown} fades it with its pill. */
    private Liquid.Style pillStyle(float shown) {
        Liquid.Style style = this.theme.pill(accent(), shown);
        style.selection = true;
        return style;
    }

    /**
     * An on/off switch whose knob travels on a spring and stretches with its
     * speed, like a drop of something thick being pushed.
     */
    private void drawSwitch(String key, float x, float cy, float w, float h, boolean on) {
        Spring s = spring("sw:" + key, on ? 1.0F : 0.0F, 520.0F, 24.0F);
        float t = Math.max(-0.08F, Math.min(1.08F, s.value));
        float lit = Math.max(0.0F, Math.min(1.0F, t));
        int accent = accent();
        float y = cy - h / 2.0F;
        Liquid.rect(x, y, x + w, y + h, h / 2.0F, alpha(ink(0x1A), 1.0F - lit));
        if (lit > 0.01F) {
            Liquid.rect(x, y, x + w, y + h, h / 2.0F, alpha(accent, 0.95F * lit), alpha(accent, 0.80F * lit));
        }
        Liquid.rim(x, y, x + w, y + h, h / 2.0F, 1.0F, ink(0x38), ink(0x0F));
        float pad = 1.5F;
        float knob = h - pad * 2.0F;
        float stretch = Math.min(knob * 0.45F, Math.abs(s.velocity) * this.jelly * 0.018F * knob);
        float kx = x + pad + (w - pad * 2.0F - knob) * t;
        Liquid.shadow(kx - stretch / 2.0F, y + pad, kx + knob + stretch / 2.0F, y + pad + knob,
                knob / 2.0F, 2.5F, 0x59000000, 0.8F);
        Liquid.rect(kx - stretch / 2.0F, y + pad, kx + knob + stretch / 2.0F, y + pad + knob,
                knob / 2.0F, 0xFFFFFFFF, 0xFFE9EEF6);
    }

    private static String suffixOf(Module module) {
        try {
            String[] parts = module.getSuffix();
            if (parts == null || parts.length == 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                if (part == null || part.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(part.replaceAll("[&§][0-9a-fklmnor]", ""));
            }
            return sb.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private void drawDetail(ScaledResolution sr, int x, int y, int width, int height, float mx, float my) {
        if (this.selected == null) {
            font(14.0F, true).draw("Select a module", x + 16, y + 17, TEXT);
            float cursor = y + 34;
            LiquidFont body = font(8.5F, false);
            for (String line : body.wrap("Its description, its key, whether the ArrayList shows it, "
                    + "and every setting appear here.", width - 32)) {
                body.draw(line, x + 16, cursor, DIM);
                cursor += 11;
            }
            return;
        }

        /* A new selection arrives rather than replacing the old one in place:
           the pane fades up and settles a few pixels. */
        float arrived = toward("detailArrive", 1.0F, 11.0F);
        float lift = (1.0F - arrived) * 6.0F;
        int accent = accent();

        float titleY = y + 17 + lift;
        LiquidFont title = font(14.0F, true);
        title.draw(title.trim(this.selected.getName(), width - 80), x + 16, titleY,
                alpha(TEXT, arrived));
        float powerX = x + width - 16 - 30;
        hit("power", this.selected, powerX - 3, titleY - 11, powerX + 33, titleY + 11);
        drawSwitch("power:" + this.selected.getName(), powerX, titleY, 30.0F, 16.0F, this.selected.isEnabled());
        if (mx >= powerX - 3 && mx <= powerX + 33 && my >= titleY - 11 && my <= titleY + 11) {
            this.hint = (this.selected.isEnabled() ? "Turn off " : "Turn on ") + this.selected.getName();
        }

        float cursor = y + 34 + lift;
        String description = this.selected.getDescription();
        LiquidFont body = font(8.5F, false);
        if (description != null && !description.isEmpty()) {
            List<String> lines = body.wrap(description, width - 32);
            int shownLines = Math.min(lines.size(), 5);
            for (int i = 0; i < shownLines; i++) {
                String line = lines.get(i);
                if (i == shownLines - 1 && lines.size() > shownLines) {
                    line = body.trim(line + " " + lines.get(i + 1), width - 32);
                }
                body.draw(line, x + 16, cursor, alpha(DIM, arrived));
                cursor += 11;
            }
            cursor += 10;
        } else {
            cursor += 4;
        }

        /* The two things about a module that are not properties: its key and
           whether the array list shows it. Both live on Module itself, which
           is why neither appeared among the settings. */
        boolean rebinding = this.binding == this.selected;
        String keyName = rebinding ? "Press a key"
                : (this.selected.getKey() == 0 ? "None"
                        : myau.util.KeyBindUtil.getKeyName(this.selected.getKey()));
        float chipX = x + 16;
        float bindWidth = drawChip("bind", this.selected, chipX, cursor, "key", "Bind", keyName,
                rebinding, mx, my);
        boolean hidden = this.selected.isHidden();
        drawChip("hide", this.selected, chipX + bindWidth + 6, cursor, hidden ? "eyeOff" : "eye",
                "ArrayList", hidden ? "Hidden" : "Shown", !hidden, mx, my);
        cursor += 22;

        font(7.0F, true).drawTracked("SETTINGS", x + 18, cursor, FAINT, 0.8F);
        cursor += 8;

        drawSettingsCard(sr, this.selected.getName(), propertiesOf(this.selected),
                x + 12, x + width - 12, cursor, y + height - 6, mx, my);
    }

    /**
     * A rounded card of settings rows that scrolls inside itself: a module's
     * settings, or one group of the Appearance page. {@code owner} keeps the
     * animations of two owners' same-named settings apart.
     */
    private void drawSettingsCard(ScaledResolution sr, String owner, List<Property<?>> properties,
                                  float cardX, float cardX2, float cardTop, float cardBottom, float mx, float my) {
        LiquidFont body = font(8.5F, false);
        if (cardBottom - cardTop < 24) {
            return;
        }
        if (properties.isEmpty()) {
            Liquid.rect(cardX, cardTop, cardX2, cardTop + 28, 12.0F, ink(0x0E), ink(0x09));
            Liquid.rim(cardX, cardTop, cardX2, cardTop + 28, 12.0F, 1.0F, ink(0x24), ink(0x0A));
            body.draw("No settings", cardX + 10, cardTop + 14, FAINT);
            return;
        }
        int content = 4;
        for (int i = 0; i < properties.size(); i++) {
            content += headingBefore(properties, i) != null ? HEADING_HEIGHT : 0;
            content += rowHeightOf(properties.get(i));
        }
        float visible = cardBottom - cardTop;
        cardBottom = Math.min(cardBottom, cardTop + content);
        this.detailMaxScroll = Math.max(0, Math.round(content - visible));
        if (this.detailScroll > this.detailMaxScroll) {
            this.detailScroll = this.detailMaxScroll;
        }
        Liquid.rect(cardX, cardTop, cardX2, cardBottom, 12.0F, ink(0x0E), ink(0x09));
        Liquid.rim(cardX, cardTop, cardX2, cardBottom, 12.0F, 1.0F, ink(0x24), ink(0x0A));

        float scroll = scrollEase("detailScroll", this.detailScroll);
        float rowY = cardTop + 2 - scroll;
        float clipTop = cardTop + 1;
        float clipBottom = cardBottom - 1;
        clip(sr, cardX, clipTop, cardX2 - cardX, clipBottom - clipTop);
        boolean firstRow = true;
        for (int index = 0; index < properties.size(); index++) {
            Property<?> property = properties.get(index);
            String heading = headingBefore(properties, index);
            if (heading != null) {
                if (rowY > clipBottom) {
                    break;
                }
                if (rowY + HEADING_HEIGHT > clipTop) {
                    font(7.0F, true).drawTracked(myau.module.ModuleDocs.heading(heading).toUpperCase(java.util.Locale.ROOT), cardX + 12,
                            rowY + HEADING_HEIGHT / 2.0F + 1, accentText(), 0.6F);
                    Liquid.rect(cardX + 10, rowY + HEADING_HEIGHT - 1, cardX2 - 10, rowY + HEADING_HEIGHT - 0.5F,
                            0.0F, ink(0x1A));
                }
                rowY += HEADING_HEIGHT;
                firstRow = true;
            }
            int rowHeight = rowHeightOf(property);
            if (rowY > clipBottom) {
                break;
            }
            if (rowY + rowHeight > clipTop) {
                if (!firstRow) {
                    Liquid.rect(cardX + 10, rowY, cardX2 - 10, rowY + 0.5F, 0.0F, ink(0x12));
                }
                AtlasInspector.note("settings", property.getName(),
                        cardX + 10, rowY, cardX2 - 10, rowY + rowHeight);
                drawProperty(owner, property, cardX, rowY, cardX2 - cardX, mx, my, clipTop, clipBottom);
            }
            firstRow = false;
            rowY += rowHeight;
        }
        unclip();
        if (this.detailMaxScroll > 0) {
            float track = clipBottom - clipTop - 8;
            float thumb = Math.max(16.0F, track * visible / content);
            float along = (track - thumb) * (scroll / this.detailMaxScroll);
            Liquid.rect(cardX2 - 5, clipTop + 4 + along, cardX2 - 2.5F, clipTop + 4 + along + thumb,
                    1.25F, ink(0x40));
        }
    }

    /**
     * A capsule with an icon, a faint label and a bright value. Returns its
     * width so the next one can follow it.
     */
    private float drawChip(String kind, Module module, float x, float cy, String iconName,
                           String label, String value, boolean active, float mx, float my) {
        LiquidFont small = font(8.0F, false);
        LiquidFont bold = font(8.0F, true);
        float width = 20 + small.width(label) + 5 + bold.width(value) + 10;
        float y = cy - 9;
        float y2 = cy + 9;
        int accent = accent();
        boolean hovered = mx >= x && mx <= x + width && my >= y && my <= y2;
        hit(kind, module, x, y, x + width, y2);
        float hover = ease("chip:" + kind, hovered, 12.0F);
        float on = ease("chipOn:" + kind, active, 12.0F);
        float press = pulseAt("chip:" + kind, 260L);
        /* A tap squashes the chip and lets it spring back. */
        float squash = press >= 0.0F ? (float) Math.sin(press * Math.PI) * 1.2F : 0.0F;
        float x1 = x + squash;
        float x3 = x + width - squash;
        if (on > 0.01F) {
            Liquid.shadow(x1, y, x3, y2, 9.0F, 5.0F, alpha(accent, 0.30F * on), 0.0F);
        }
        Liquid.rect(x1, y + squash * 0.5F, x3, y2 - squash * 0.5F, 9.0F,
                blend(alpha(INK, 0.07F + 0.04F * hover), alpha(accent, 0.24F), on),
                blend(alpha(INK, 0.04F + 0.02F * hover), alpha(accent, 0.14F), on));
        Liquid.rim(x1, y + squash * 0.5F, x3, y2 - squash * 0.5F, 9.0F, 1.0F,
                blend(ink(0x2E), alpha(accent, 0.6F), on), ink(0x0A));
        icon(iconName, x + 11, cy, blend(DIM, accent, on));
        float labelWidth = small.draw(label, x + 20, cy, FAINT);
        bold.draw(value, x + 20 + labelWidth + 5, cy, TEXT);
        if (hovered) {
            if ("bind".equals(kind)) {
                this.hint = this.binding == module
                        ? "Press any key to bind, Escape to clear"
                        : "Click to set a key for " + module.getName();
            } else {
                this.hint = module.isHidden()
                        ? module.getName() + " is hidden from the ArrayList  ·  click to show it"
                        : module.getName() + " is shown in the ArrayList  ·  click to hide it";
            }
        }
        return width;
    }

    /**
     * How tall a settings row is. A slider row carries its name and value on
     * one line and the track under them.
     */
    /** Height of a settings heading row (ModuleDocs groups). */
    private static final int HEADING_HEIGHT = 16;

    /**
     * The heading to draw before the setting at index: its group, when it
     * differs from the setting before it. Null for none -- and none at all in
     * a list whose settings share one group or have none.
     */
    private static String headingBefore(List<Property<?>> properties, int index) {
        String group = properties.get(index).getGroup();
        if (group == null) {
            return null;
        }
        String previous = index == 0 ? null : properties.get(index - 1).getGroup();
        if (group.equals(previous)) {
            return null;
        }
        if (index == 0) {
            /* A single heading over everything says nothing. */
            boolean other = false;
            for (Property<?> property : properties) {
                if (!group.equals(property.getGroup())) {
                    other = true;
                    break;
                }
            }
            if (!other) {
                return null;
            }
        }
        return group;
    }

    /** A heading's colour: the accent, toned down so it reads as a label rather than a control. */
    private int accentText() {
        return (0xCC << 24) | (accent() & 0x00FFFFFF);
    }

    /** The hint line's start for a setting: its name, and what it does when that is written down. */
    private static String hintName(Property<?> property) {
        return property.getHelp() == null ? property.getLabel()
                : property.getLabel() + "  ·  " + property.getHelp();
    }

    private int rowHeightOf(Property<?> property) {
        if (isNumeric(property)) {
            return 32;
        }
        return property == this.colorOpen ? 22 + PICKER_HEIGHT : 22;
    }

    /**
     * Draws one setting and registers every part of it that can be clicked.
     *
     * The registration is the point. Each control's hit box is the rectangle
     * this method just drew it at, written once, so there is no second piece
     * of arithmetic to fall out of step with the first.
     */
    private void drawProperty(String owner, Property<?> property, float x, float top, float width,
                              float mx, float my, float paneTop, float paneBottom) {
        float labelX = x + 10;
        float right = x + width - 10;
        float cy = top + 11;
        int rowHeight = rowHeightOf(property);
        int accent = accent();
        boolean hovered = mx >= x && mx <= x + width && my >= top && my <= top + rowHeight
                && my >= paneTop && my <= paneBottom;
        /* A row straddling the edge of the clip is half drawn, so only a row
           wholly inside it takes a hit box at all. */
        boolean clickable = top >= paneTop - 2 && top + rowHeight <= paneBottom + 2;
        if (hovered) {
            /* The range is the part people actually need and the part most
               menus hide: a slider with no numbers cannot be set deliberately. */
            this.hint = hintName(property) + "  ·  " + property.getValuePrompt()
                    + (property instanceof ModeProperty ? "  ·  click for the list, right click to cycle"
                            : isNumeric(property)
                                    ? "  ·  drag the bar, or click the number to type one"
                                    : "");
        }

        LiquidFont label = font(8.5F, false);
        LiquidFont valueFont = font(8.5F, true);
        String value = property.formatValue().replaceAll("[&§][0-9a-fklmnor]", "");
        boolean beingEdited = this.editing == property;
        String id = owner + ":" + property.getName();

        if (property instanceof BooleanProperty) {
            label.draw(label.trim(property.getLabel(), width - 50), labelX, cy, DIM);
            if (clickable) {
                hit("toggle", property, x + 2, top, x + width - 2, top + rowHeight);
            }
            drawSwitch(id, right - 22, cy, 22.0F, 12.0F, ((BooleanProperty) property).getValue());
            return;
        }

        if (isNumeric(property)) {
            /* The bar is the player's value (the base); the number is the one in
               effect. While a tuner holds an override they differ, and the
               number alone looked like a slider that did nothing (BackTrack
               adaptive-delay under an AutoTune trial, 2026-10-02). Say whose. */
            String overrider = property.isOverridden() ? property.getSourceOwner() : null;
            if (hovered && overrider != null) {
                this.hint = hintName(property) + "  ·  " + property.describe() + "  ·  the bar is your value";
            }
            String shownValue = beingEdited ? this.editBuffer
                    : overrider != null ? value + " (" + overrider + ")" : value;
            float shownWidth = valueFont.width(shownValue);
            float valueLeft = right - Math.max(14.0F, shownWidth) - 3;
            label.draw(label.trim(property.getLabel(), Math.min(width - 70, valueLeft - labelX - 8)),
                    labelX, cy, DIM);
            if (beingEdited) {
                Liquid.rect(valueLeft - 3, cy - 6.5F, right + 3, cy + 6.5F, 5.0F, ink(0x1F));
                Liquid.rim(valueLeft - 3, cy - 6.5F, right + 3, cy + 6.5F, 5.0F, 1.0F,
                        alpha(accent, 0.7F), ink(0x14));
            }
            float commit = pulseAt("edit:" + property.getName(), 360L);
            if (commit >= 0.0F) {
                Liquid.rect(valueLeft - 3, cy - 6.5F, right + 3, cy + 6.5F, 5.0F,
                        alpha(accent, 0.35F * (1.0F - commit)));
            }
            valueFont.draw(shownValue, right - shownWidth, cy, beingEdited ? accent : TEXT);
            if (beingEdited && (System.currentTimeMillis() / 500L) % 2L == 0L) {
                Liquid.rect(right + 1, cy - 4.5F, right + 2, cy + 4.5F, 0.0F, accent);
            }
            if (clickable) {
                hit("type", property, valueLeft - 4, cy - 7, right + 4, cy + 6);
            }

            float barY = top + 23;
            float barX = labelX;
            float barX2 = right;
            /* The grab area is the whole strip the track sits in, not the
               three pixels it is painted in, and it starts below the number's
               box so the two never share a pixel. */
            if (clickable) {
                hit("slide", property, barX - 3, barY - 5, barX2 + 3, barY + 7);
            }
            float min = minimumOf(property);
            float max = maximumOf(property);
            float fraction = max <= min ? 0.0F : (currentOf(property) - min) / (max - min);
            /* On a spring even while dragging: the handle trailing the pointer
               very slightly is what gives the control weight, and the value
               itself is already exact. */
            Spring knob = spring("sl:" + id, fraction, 520.0F, 30.0F);
            float f = Math.max(0.0F, Math.min(1.0F, knob.value));
            float fillX = barX + (barX2 - barX) * f;
            boolean hue = property.getName().endsWith("-hue");
            int knobColour = 0xFFFFFFFF;
            if (hue) {
                /* A hue is chosen by looking, so the track is the spectrum. */
                float segment = (barX2 - barX) / 6.0F;
                for (int i = 0; i < 6; i++) {
                    Liquid.rectH(barX + segment * i, barY - 2.0F, barX + segment * (i + 1) + 0.5F, barY + 2.0F,
                            0.0F, AtlasTheme.hsb(i * 60, 0.75F, 1.0F), AtlasTheme.hsb((i + 1) * 60, 0.75F, 1.0F));
                }
                knobColour = AtlasTheme.hsb(currentOf(property), 0.75F, 1.0F);
            } else {
                Liquid.rect(barX, barY - 1.5F, barX2, barY + 1.5F, 1.5F, ink(0x1F));
                if (fillX > barX + 0.5F) {
                    Liquid.shadow(barX, barY - 1.5F, fillX, barY + 1.5F, 1.5F, 4.0F, alpha(accent, 0.55F), 0.0F);
                    Liquid.rectH(barX, barY - 1.5F, fillX, barY + 1.5F, 1.5F, alpha(accent, 0.75F), accent);
                }
            }
            /* The knob swells while held and turns into a drop of clear glass
               that magnifies the track under it. */
            boolean held = this.dragging == property;
            Spring grow = spring("grab:" + id, held ? 1.0F : 0.0F, 420.0F, 20.0F);
            float r = 5.0F + 2.6F * Math.max(0.0F, grow.value);
            Liquid.shadow(fillX - r, barY - r, fillX + r, barY + r, r, 3.0F, 0x66000000, 1.0F);
            if (grow.value > 0.05F && Liquid.glass()) {
                Liquid.Style drop = new Liquid.Style(r * 0.8F, 3.0F, 0.15F, 1.1F, 1.15F,
                        ink(0x14), 0.85F, 0.0F, 0.0F);
                drop.magnify = 1.0F + 0.45F * Math.min(1.0F, grow.value);
                this.lenses.add(new Lens(fillX - r, barY - r, fillX + r, barY + r, r, drop,
                        new float[]{x, paneTop, width, paneBottom - paneTop}));
                Liquid.rect(fillX - r, barY - r, fillX + r, barY + r, r,
                        alpha(0xFFFFFFFF, 1.0F - Math.min(1.0F, grow.value)),
                        alpha(0xFFE6ECF5, 1.0F - Math.min(1.0F, grow.value)));
            } else {
                Liquid.rect(fillX - r, barY - r, fillX + r, barY + r, r, 0xFFFFFFFF, 0xFFE6ECF5);
            }
            if (hue) {
                Liquid.dot(fillX, barY, r - 1.8F, knobColour);
            }
            return;
        }

        if (property instanceof ModeProperty) {
            ModeProperty mode = (ModeProperty) property;
            boolean open = this.dropdown == mode;
            LiquidFont modeFont = font(8.0F, true);
            float textWidth = modeFont.width(value);
            float capsuleLeft = right - textWidth - 22;
            label.draw(label.trim(property.getLabel(), capsuleLeft - labelX - 6), labelX, cy, DIM);
            if (clickable) {
                hit("mode", mode, capsuleLeft - 2, cy - 8, right + 2, cy + 8);
            }
            float hover = ease("mode:" + id, hovered || open, 12.0F);
            float commit = pulseAt("edit:" + property.getName(), 360L);
            Liquid.rect(capsuleLeft, cy - 7, right, cy + 7, 7.0F,
                    alpha(accent, 0.16F + 0.08F * hover + (commit >= 0.0F ? 0.3F * (1.0F - commit) : 0.0F)));
            if (hover > 0.01F) {
                Liquid.rim(capsuleLeft, cy - 7, right, cy + 7, 7.0F, 1.0F,
                        alpha(accent, 0.5F * hover), alpha(accent, 0.15F * hover));
            }
            modeFont.draw(value, capsuleLeft + 7, cy, accent);
            icon(open ? "chevronUp" : "chevron", right - 8, cy, accent);
            if (open) {
                /* Where the list hangs from, recorded here because this is the
                   only place that knows where the row ended up after scrolling. */
                this.dropX = capsuleLeft;
                this.dropY = cy + 8;
                this.dropRight = right;
                this.dropAnchored = clickable;
            }
            return;
        }

        if (property instanceof TextProperty) {
            label.draw(property.getLabel(), labelX, cy, DIM);
            String shownValue = beingEdited ? this.editBuffer : value;
            float available = width - 30 - label.width(property.getLabel());
            String clipped = shownValue;
            if (valueFont.width(clipped) > available) {
                /* The tail rather than the head: the end of a comma separated
                   list is the part being typed. */
                while (clipped.length() > 1 && valueFont.width("..." + clipped) > available) {
                    clipped = clipped.substring(1);
                }
                clipped = "..." + clipped;
            }
            float clippedWidth = valueFont.width(clipped);
            if (clickable) {
                hit("type", property, right - clippedWidth - 6, cy - 7, right + 4, cy + 7);
            }
            if (beingEdited) {
                Liquid.rect(right - clippedWidth - 5, cy - 6.5F, right + 3, cy + 6.5F, 5.0F, ink(0x1F));
                Liquid.rim(right - clippedWidth - 5, cy - 6.5F, right + 3, cy + 6.5F, 5.0F, 1.0F,
                        alpha(accent, 0.7F), ink(0x14));
                if ((System.currentTimeMillis() / 500L) % 2L == 0L) {
                    Liquid.rect(right + 1, cy - 4.5F, right + 2, cy + 4.5F, 0.0F, accent);
                }
            }
            valueFont.draw(clipped, right - clippedWidth, cy, beingEdited ? accent : TEXT);
            return;
        }

        if (property instanceof KeyProperty) {
            /* A key of its own, such as Clutch's hold-key: click, then press it.
               It used to fall through to the read-only row below, so a hold
               trigger could be chosen but never given a key (2026-10-02). */
            KeyProperty key = (KeyProperty) property;
            boolean listening = this.keyEditing == key;
            String shown = listening ? "Press a key" : key.getKeyName();
            LiquidFont keyFont = font(8.0F, true);
            float capsuleLeft = right - keyFont.width(shown) - 14;
            label.draw(label.trim(property.getLabel(), capsuleLeft - labelX - 6), labelX, cy, DIM);
            if (clickable) {
                hit("keyProp", key, capsuleLeft - 2, cy - 8, right + 2, cy + 8);
            }
            if (hovered) {
                this.hint = hintName(property) + "  ·  click, then press a key  ·  Esc clears it";
            }
            Liquid.rect(capsuleLeft, cy - 7, right, cy + 7, 7.0F, alpha(accent, listening ? 0.38F : 0.16F));
            if (listening) {
                Liquid.rim(capsuleLeft, cy - 7, right, cy + 7, 7.0F, 1.0F, alpha(accent, 0.8F), alpha(accent, 0.3F));
            }
            keyFont.draw(shown, capsuleLeft + 7, cy, listening ? TEXT : accent);
            return;
        }

        if (property instanceof ColorProperty) {
            drawColour((ColorProperty) property, x, top, width, cy, labelX, right, hovered, my, paneTop, paneBottom,
                    label, valueFont, beingEdited, accent);
            return;
        }

        /* Anything else -- item lists -- is shown but not editable here,
           because there is no honest control for it yet. Printing it is
           still better than hiding it. */
        label.draw(property.getLabel(), labelX, cy, DIM);
        String shown = valueFont.trim(value, width - 30 - label.width(property.getLabel()));
        valueFont.drawRight(shown, right, cy, TEXT);
    }

    /**
     * A colour setting: a swatch and its hex on the row; a click on the row
     * opens a picker under it -- saturation/brightness field, hue strip --
     * and a click on the hex types one. Theme's colour-1..3, HUD's custom
     * colours and the rest used to be read-only here (2026-10-02).
     *
     * Each part takes clicks only while it is wholly inside the pane, like
     * every other row, but measured per part: the open picker is tall, and
     * a row half scrolled out of view should still close from its header.
     */
    private void drawColour(ColorProperty colour, float x, float top, float width, float cy, float labelX,
                            float right, boolean hovered, float my, float paneTop, float paneBottom,
                            LiquidFont label, LiquidFont valueFont, boolean beingEdited, int accent) {
        int rgb = colour.getValue() & 0xFFFFFF;
        boolean open = this.colorOpen == colour;
        String hex = beingEdited ? this.editBuffer : String.format("#%06X", rgb);
        float swatchLeft = right - 22;
        float hexRight = swatchLeft - 6;
        float hexLeft = hexRight - valueFont.width(hex);
        label.draw(label.trim(colour.getName(), hexLeft - labelX - 8), labelX, cy, DIM);
        if (top >= paneTop - 2 && top + 22 <= paneBottom + 2) {
            hit("colorOpen", colour, x + 2, top, x + width - 2, top + 22);
            /* Registered after the row, so it is found first. */
            hit("type", colour, hexLeft - 4, cy - 7, hexRight + 3, cy + 7);
        }
        if (hovered && my <= top + 22) {
            this.hint = colour.getName() + "  ·  click to " + (open ? "close" : "open") + " the picker"
                    + "  ·  click the hex to type one";
        }
        if (beingEdited) {
            Liquid.rect(hexLeft - 3, cy - 6.5F, hexRight + 3, cy + 6.5F, 5.0F, ink(0x1F));
            Liquid.rim(hexLeft - 3, cy - 6.5F, hexRight + 3, cy + 6.5F, 5.0F, 1.0F, alpha(accent, 0.7F), ink(0x14));
        }
        valueFont.draw(hex, hexLeft, cy, beingEdited ? accent : TEXT);
        Liquid.rect(swatchLeft, cy - 6, right, cy + 6, 4.0F, 0xFF000000 | rgb);
        Liquid.rim(swatchLeft, cy - 6, right, cy + 6, 4.0F, 1.0F, ink(0x40), ink(0x20));
        if (!open) {
            return;
        }

        if (rgb != this.colorSynced) {
            java.awt.Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, this.colorHsb);
            this.colorSynced = rgb;
        }
        int pure = 0xFF000000 | (java.awt.Color.HSBtoRGB(this.colorHsb[0], 1.0F, 1.0F) & 0xFFFFFF);

        /* Saturation left to right, brightness top to bottom: white to the
           pure hue across, then a veil from clear to black down. */
        float boxX = labelX;
        float boxX2 = right;
        float boxY = top + 24;
        float boxY2 = boxY + 50;
        Liquid.rectH(boxX, boxY, boxX2, boxY2, 4.0F, 0xFFFFFFFF, pure);
        Liquid.rect(boxX, boxY, boxX2, boxY2, 4.0F, 0x00000000, 0xFF000000);
        Liquid.rim(boxX, boxY, boxX2, boxY2, 4.0F, 1.0F, ink(0x30), ink(0x18));
        float px = boxX + (boxX2 - boxX) * this.colorHsb[1];
        float py = boxY + (boxY2 - boxY) * (1.0F - this.colorHsb[2]);
        Liquid.ring(px, py, 3.5F, 1.5F, 0xFFFFFFFF);
        if (boxY >= paneTop - 2 && boxY2 <= paneBottom + 2) {
            hit("colorSV", colour, boxX, boxY, boxX2, boxY2);
        }

        float hueY = boxY2 + 12;
        float segment = (boxX2 - boxX) / 6.0F;
        for (int i = 0; i < 6; i++) {
            Liquid.rectH(boxX + segment * i, hueY - 3.0F, boxX + segment * (i + 1) + 0.5F, hueY + 3.0F, 0.0F,
                    AtlasTheme.hsb(i * 60, 1.0F, 1.0F), AtlasTheme.hsb((i + 1) * 60, 1.0F, 1.0F));
        }
        float hx = boxX + (boxX2 - boxX) * this.colorHsb[0];
        Liquid.dot(hx, hueY, 5.0F, 0xFFFFFFFF);
        Liquid.dot(hx, hueY, 3.5F, pure);
        if (hueY - 6 >= paneTop - 2 && hueY + 6 <= paneBottom + 2) {
            hit("colorHue", colour, boxX - 3, hueY - 6, boxX2 + 3, hueY + 6);
        }
        if (hovered && my > top + 22) {
            this.hint = colour.getName() + "  ·  drag in the field for shade, along the strip for hue";
        }
    }

    /** Moves the open picker's dragged control to the pointer and writes the colour. */
    private void colourDragTo(float mouseX, float mouseY) {
        if (this.colorDrag == null || this.colorOpen == null || this.colorW <= 0.0F) {
            return;
        }
        float fx = Math.max(0.0F, Math.min(1.0F, (mouseX - this.colorX) / this.colorW));
        if ("sv".equals(this.colorDrag)) {
            float fy = this.colorH <= 0.0F ? 0.0F : Math.max(0.0F, Math.min(1.0F, (mouseY - this.colorY) / this.colorH));
            this.colorHsb[1] = fx;
            this.colorHsb[2] = 1.0F - fy;
        } else {
            /* Hue 1.0 is hue 0.0: stop short so the knob stays at the right end. */
            this.colorHsb[0] = Math.min(fx, 0.9999F);
        }
        int rgb = java.awt.Color.HSBtoRGB(this.colorHsb[0], this.colorHsb[1], this.colorHsb[2]) & 0xFFFFFF;
        this.colorSynced = rgb;
        this.colorOpen.setValue(Integer.valueOf(rgb));
    }

    /**
     * The expanded list of a mode setting, as a glass popover that springs
     * out of the value it belongs to.
     *
     * Cycling on click is fine for two options and hostile for eight: finding
     * the one wanted means clicking past the others and reading each in turn.
     */
    private void drawDropdown(int windowTop, int windowHeight, float mx, float my) {
        String[] modes = modesOf(this.dropdown);
        if (modes.length == 0) {
            this.dropdown = null;
            return;
        }
        LiquidFont item = font(8.5F, false);
        LiquidFont itemBold = font(8.5F, true);
        float widest = 0.0F;
        for (String mode : modes) {
            widest = Math.max(widest, itemBold.width(mode));
        }
        int rowHeight = 16;
        float height = modes.length * rowHeight + 8;
        float width = Math.max(this.dropRight - this.dropX, widest + 34);
        float right = this.dropRight;
        float left = right - width;
        float top = this.dropY + 4;
        boolean above = false;
        /* Flipped above the row when there is no room below it. */
        if (top + height > windowTop + windowHeight - 4) {
            top = this.dropY - 20 - height;
            above = true;
        }
        if (top < windowTop + 4) {
            top = windowTop + 4;
        }
        Spring grow = spring("drop:" + this.dropdown.getName(), 1.0F, 420.0F, 22.0F);
        float g = Math.max(0.0F, grow.value);
        float anchorY = above ? top + height : top;
        float y1 = anchorY + (top - anchorY) * g;
        float y2 = anchorY + (top + height - anchorY) * g;
        float squeeze = (1.0F - Math.min(1.0F, g)) * width * 0.08F;

        Liquid.shadow(left + squeeze, y1, right - squeeze, y2, 12.0F, 14.0F, 0x73000000, 6.0F);
        Liquid.Style popover = new Liquid.Style(8.0F, 6.0F, 0.10F, 1.2F, 0.85F, 0xB80D1119, 0.6F, 0.0F, 0.0F);
        popover.frost = 5.0F;
        Liquid.lens(left + squeeze, y1, right - squeeze, y2, 12.0F, popover);
        if (!Liquid.glass()) {
            Liquid.rect(left + squeeze, y1, right - squeeze, y2, 12.0F, 0xF0101420, 0xF00B0E14);
            Liquid.rim(left + squeeze, y1, right - squeeze, y2, 12.0F, 1.0F, ink(0x40), ink(0x0F));
        }
        if (g < 0.6F) {
            return;
        }
        float content = Math.min(1.0F, (g - 0.6F) / 0.4F);
        int current = this.dropdown.getValue();
        int accent = accent();
        for (int i = 0; i < modes.length; i++) {
            float rowY = top + 4 + i * rowHeight;
            float cy = rowY + rowHeight / 2.0F;
            boolean hovered = mx >= left && mx <= right && my >= rowY && my <= rowY + rowHeight;
            hit("pick", Integer.valueOf(i), left, rowY, right, rowY + rowHeight);
            /* Colour choices show their colour. */
            int swatch = this.theme.swatch(this.dropdown, i, clickGuiAccent());
            if (swatch != 0) {
                Liquid.dot(left + 12, cy, 3.5F, alpha(swatch, content));
                Liquid.ring(left + 12, cy, 3.5F, 0.8F, alpha(ink(0x40), content));
            }
            if (i == current) {
                Liquid.rect(left + 4, rowY + 1, right - 4, rowY + rowHeight - 1, 7.0F,
                        alpha(accent, 0.24F * content));
                if (swatch == 0) {
                    icon("check", left + 12, cy, alpha(accent, content));
                } else {
                    Liquid.ring(left + 12, cy, 5.2F, 1.0F, alpha(TEXT, content));
                }
            } else if (hovered) {
                Liquid.rect(left + 4, rowY + 1, right - 4, rowY + rowHeight - 1, 7.0F,
                        alpha(ink(0x1A), content));
            }
            (i == current ? itemBold : item).draw(modes[i], left + 22, cy,
                    alpha(i == current ? TEXT : (hovered ? TEXT : DIM), content));
        }
        if (mx >= left && mx <= right && my >= top && my <= top + height) {
            this.hint = this.dropdown.getName() + "  ·  " + modes.length + " options";
        }
    }

    /**
     * The options of a mode setting. ModeProperty keeps its list private and
     * exposes only the current one, so the names come back out of the prompt
     * it builds for the chat command, joined with ", " by that class.
     */
    private static String[] modesOf(ModeProperty property) {
        String prompt = property.getValuePrompt();
        if (prompt == null || prompt.isEmpty()) {
            return new String[0];
        }
        String[] parts = prompt.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    /**
     * The hint bar. Always present, always at the same place, showing whatever
     * the pointer is over -- so an explanation never moves, never covers what
     * it describes, and never has to be chased.
     */
    private void drawHintBar(int x, int y, int width) {
        float shownHint = ease("hint", !this.hint.isEmpty(), 12.0F);
        String text = this.hint.isEmpty()
                ? "Click a module for its settings  ·  its switch turns it on or off  ·  type to search  ·  drag the header to move"
                : this.hint;
        LiquidFont small = font(7.5F, false);
        text = small.trim(text, width - 50);
        small.draw(text, x + 16, y + FOOTER / 2.0F - 1, blend(FAINT, DIM, shownHint));
    }

    // ---- icons --------------------------------------------------------

    /**
     * Icons, drawn from primitives so they are sharp at any scale. Category
     * icons are chosen by name; anything unknown gets the grid.
     */
    private static void icon(String name, float x, float y, int c) {
        String key = name.toLowerCase();
        if (key.equals("combat")) {
            Liquid.ring(x, y, 4.2F, 1.1F, c);
            Liquid.dot(x, y, 1.3F, c);
            Liquid.line(x, y - 6.0F, x, y - 4.6F, 1.1F, c);
            Liquid.line(x, y + 4.6F, x, y + 6.0F, 1.1F, c);
            Liquid.line(x - 6.0F, y, x - 4.6F, y, 1.1F, c);
            Liquid.line(x + 4.6F, y, x + 6.0F, y, 1.1F, c);
        } else if (key.equals("theme")) {
            /* A palette: the board and three wells of paint. */
            Liquid.ring(x, y, 5.0F, 1.1F, c);
            Liquid.dot(x - 2.2F, y - 1.6F, 1.1F, c);
            Liquid.dot(x + 1.6F, y - 2.2F, 1.1F, c);
            Liquid.dot(x + 2.4F, y + 1.4F, 1.1F, c);
        } else if (key.equals("movement")) {
            Liquid.line(x - 4.5F, y - 3.5F, x - 1.0F, y, 1.3F, c);
            Liquid.line(x - 1.0F, y, x - 4.5F, y + 3.5F, 1.3F, c);
            Liquid.line(x + 0.5F, y - 3.5F, x + 4.0F, y, 1.3F, c);
            Liquid.line(x + 4.0F, y, x + 0.5F, y + 3.5F, 1.3F, c);
        } else if (key.equals("render") || key.equals("eye")) {
            Liquid.rim(x - 5.5F, y - 3.3F, x + 5.5F, y + 3.3F, 3.3F, 1.1F, c, c);
            Liquid.dot(x, y, 1.7F, c);
        } else if (key.equals("eyeoff")) {
            Liquid.rim(x - 5.5F, y - 3.3F, x + 5.5F, y + 3.3F, 3.3F, 1.1F, c, c);
            Liquid.dot(x, y, 1.7F, c);
            Liquid.line(x - 5.0F, y + 4.0F, x + 5.0F, y - 4.0F, 1.2F, c);
        } else if (key.equals("player")) {
            Liquid.dot(x, y - 2.6F, 2.2F, c);
            Liquid.rect(x - 4.0F, y + 0.9F, x + 4.0F, y + 5.2F, 2.6F, c);
        } else if (key.equals("key")) {
            Liquid.rim(x - 5.0F, y - 3.5F, x + 5.0F, y + 3.5F, 1.8F, 1.0F, c, c);
            Liquid.dot(x - 2.4F, y - 1.1F, 0.6F, c);
            Liquid.dot(x, y - 1.1F, 0.6F, c);
            Liquid.dot(x + 2.4F, y - 1.1F, 0.6F, c);
            Liquid.line(x - 2.2F, y + 1.4F, x + 2.2F, y + 1.4F, 1.0F, c);
        } else if (key.equals("chevron")) {
            Liquid.line(x - 2.6F, y - 1.2F, x, y + 1.3F, 1.2F, c);
            Liquid.line(x, y + 1.3F, x + 2.6F, y - 1.2F, 1.2F, c);
        } else if (key.equals("chevronup")) {
            Liquid.line(x - 2.6F, y + 1.2F, x, y - 1.3F, 1.2F, c);
            Liquid.line(x, y - 1.3F, x + 2.6F, y + 1.2F, 1.2F, c);
        } else if (key.equals("check")) {
            Liquid.line(x - 3.0F, y, x - 1.0F, y + 2.2F, 1.3F, c);
            Liquid.line(x - 1.0F, y + 2.2F, x + 3.2F, y - 2.4F, 1.3F, c);
        } else if (key.equals("palette")) {
            Liquid.ring(x, y, 4.8F, 1.1F, c);
            Liquid.dot(x - 1.8F, y - 1.9F, 1.0F, c);
            Liquid.dot(x + 1.9F, y - 1.2F, 1.0F, c);
            Liquid.dot(x - 0.6F, y + 2.2F, 1.0F, c);
        } else if (key.equals("colors")) {
            Liquid.dot(x - 2.3F, y - 1.2F, 2.6F, alpha(c, 0.9F));
            Liquid.dot(x + 2.3F, y - 1.2F, 2.6F, alpha(c, 0.6F));
            Liquid.dot(x, y + 2.3F, 2.6F, alpha(c, 0.4F));
        } else if (key.equals("glass")) {
            Liquid.rim(x - 5.0F, y - 5.0F, x + 5.0F, y + 5.0F, 2.5F, 1.1F, c, c);
            Liquid.line(x - 2.0F, y + 1.5F, x + 1.5F, y - 2.0F, 1.1F, c);
        } else if (key.equals("depth")) {
            Liquid.rim(x - 5.0F, y - 2.0F, x + 3.0F, y + 5.0F, 1.8F, 1.0F, alpha(c, 0.5F), alpha(c, 0.5F));
            Liquid.rim(x - 3.0F, y - 5.0F, x + 5.0F, y + 2.0F, 1.8F, 1.1F, c, c);
        } else if (key.equals("selection")) {
            Liquid.rim(x - 6.0F, y - 3.2F, x + 6.0F, y + 3.2F, 3.2F, 1.1F, c, c);
            Liquid.dot(x - 2.6F, y, 1.2F, c);
        } else if (key.equals("motion")) {
            Liquid.line(x - 5.0F, y + 1.5F, x - 2.5F, y - 2.0F, 1.2F, c);
            Liquid.line(x - 2.5F, y - 2.0F, x, y + 2.0F, 1.2F, c);
            Liquid.line(x, y + 2.0F, x + 2.5F, y - 2.0F, 1.2F, c);
            Liquid.line(x + 2.5F, y - 2.0F, x + 5.0F, y + 1.5F, 1.2F, c);
        } else if (key.equals("text")) {
            Liquid.line(x - 4.5F, y - 4.0F, x + 4.5F, y - 4.0F, 1.3F, c);
            Liquid.line(x, y - 4.0F, x, y + 4.5F, 1.3F, c);
        } else if (key.equals("folder")) {
            Liquid.rim(x - 5.5F, y - 3.2F, x + 5.5F, y + 4.2F, 1.8F, 1.1F, c, c);
            Liquid.rect(x - 5.5F, y - 4.8F, x - 0.8F, y - 2.4F, 1.1F, c);
        } else if (key.equals("file")) {
            Liquid.rim(x - 3.8F, y - 5.0F, x + 3.8F, y + 5.0F, 1.6F, 1.0F, c, c);
            Liquid.line(x - 1.8F, y - 1.2F, x + 1.8F, y - 1.2F, 0.9F, c);
            Liquid.line(x - 1.8F, y + 1.3F, x + 1.8F, y + 1.3F, 0.9F, c);
        } else if (key.equals("search")) {
            Liquid.ring(x - 0.8F, y - 0.8F, 3.3F, 1.2F, c);
            Liquid.line(x + 1.6F, y + 1.6F, x + 3.8F, y + 3.8F, 1.3F, c);
        } else {
            float q = 1.9F;
            float[][] cells = {{-2.3F, -2.3F}, {2.3F, -2.3F}, {-2.3F, 2.3F}, {2.3F, 2.3F}};
            for (float[] cell : cells) {
                Liquid.rect(x + cell[0] - q, y + cell[1] - q, x + cell[0] + q, y + cell[1] + q, 1.1F, c);
            }
        }
    }

    // ---- colour -------------------------------------------------------

    private static int blend(int a, int b, float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        int aa = (a >>> 24) & 0xFF;
        int ba = (b >>> 24) & 0xFF;
        int ar = (a >> 16) & 0xFF;
        int br = (b >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int ab = a & 0xFF;
        int bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * t) << 24)
                | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8)
                | (int) (ab + (bb - ab) * t);
    }

    private static int darker(int colour) {
        int r = (int) (((colour >> 16) & 0xFF) * 0.55F);
        int g = (int) (((colour >> 8) & 0xFF) * 0.55F);
        int b = (int) ((colour & 0xFF) * 0.7F);
        return (colour & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** The accent, as the theme has it: its own colour, or the ClickGUI module's. */
    private int accent() {
        return this.theme.accent(clickGuiAccent());
    }

    /** A neutral of the given alpha: white in dark mode, ink in light mode. */
    private static int ink(int alpha) {
        return (alpha << 24) | (INK & 0x00FFFFFF);
    }

    /** Sets the text and neutral colours for the current mode, and tells the HUD. */
    private void paintPalette() {
        boolean light = this.theme.isLight();
        myau.ui.UiMode.setLight(light);
        TEXT = light ? 0xFF161A21 : 0xFFF5F8FF;
        DIM = light ? 0xD1404958 : 0xD1C3CEDF;
        FAINT = light ? 0xA6667080 : 0x9E93A3BA;
        INK = light ? 0xFF0E1726 : 0xFFFFFFFF;
    }

    /** The modules behind a sidebar row: a Legit sub-group on the Legit page, a category elsewhere. */
    private List<Module> modulesOf(String name) {
        List<Module> list = "Legit".equals(this.page) ? this.legitGroups.get(name) : this.byCategory.get(name);
        return list == null ? new ArrayList<Module>() : list;
    }

    private String sidebarIcon(String name) {
        return "Legit".equals(this.page) ? LegitGroups.icon(name) : name;
    }

    /** The top-level page a module lives on, as its tab is labelled. */
    private static String pageOf(Module module) {
        return ModuleCategories.of(module.getClass()) == Category.LEGIT ? "Legit" : "Modules";
    }

    /** The colour that marks a search result from the other page. */
    private static int foreignColour() {
        return myau.ui.UiMode.isLight() ? 0xFF6A4BD6 : 0xFFB59CFF;
    }

    private static int foreignText() {
        return myau.ui.UiMode.isLight() ? 0xFF3E2A99 : 0xFFE9E1FF;
    }

    /** A dashed outline along the straight parts of a rounded rectangle. */
    private static void dashedRim(float x, float y, float x2, float y2, float radius, int colour) {
        float dash = 4.0F;
        float gap = 3.0F;
        float t = 1.0F;
        for (float px = x + radius; px < x2 - radius; px += dash + gap) {
            float end = Math.min(px + dash, x2 - radius);
            Liquid.rect(px, y, end, y + t, 0.0F, colour);
            Liquid.rect(px, y2 - t, end, y2, 0.0F, colour);
        }
        float inset = radius * 0.6F;
        for (float py = y + inset; py < y2 - inset; py += dash + gap) {
            float end = Math.min(py + dash, y2 - inset);
            Liquid.rect(x, py, x + t, end, 0.0F, colour);
            Liquid.rect(x2 - t, py, x2, end, 0.0F, colour);
        }
    }

    private int clickGuiAccent() {
        try {
            myau.module.modules.ClickGUIModule gui =
                    (myau.module.modules.ClickGUIModule) Myau.moduleManager.modules
                            .get(myau.module.modules.ClickGUIModule.class);
            return gui == null ? 0xFF4FC3F7 : gui.getAccentColor().getRGB();
        } catch (Exception ignored) {
            return 0xFF4FC3F7;
        }
    }

    // ---- clipping -----------------------------------------------------

    /**
     * Restricts drawing to a rectangle given in layout pixels.
     *
     * Scissor works in real framebuffer pixels with the origin at the bottom
     * left, and knows nothing of the opening scale, so the rectangle is taken
     * through both rather than passed through.
     */
    private void clip(ScaledResolution sr, float x, float y, float width, float height) {
        float sx = this.pivotX + (x - this.pivotX) * this.viewScale;
        float sy = this.pivotY + (y - this.pivotY) * this.viewScale + this.slideY;
        float sw = width * this.viewScale;
        float sh = height * this.viewScale;
        int factor = sr.getScaleFactor();
        int left = (int) Math.floor(sx * factor);
        int bottom = (int) Math.floor((sr.getScaledHeight() - (sy + sh)) * factor);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(left, bottom, Math.max(0, (int) Math.ceil(sw * factor)),
                Math.max(0, (int) Math.ceil(sh * factor)));
    }

    private static void unclip() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    // ---- input --------------------------------------------------------

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        ScaledResolution sr = new ScaledResolution(mc);
        float mx = layoutX(mouseX);
        float my = layoutY(mouseY);
        Hit target = hitAt(mx, my);

        /* An open list takes the next click wherever it lands, and that click
           does nothing else. Letting a dismissing click also toggle whatever
           was underneath turns closing a menu into changing a setting. */
        if (this.dropdown != null) {
            ModeProperty open = this.dropdown;
            this.springs.remove("drop:" + open.getName());
            this.dropdown = null;
            if (target != null && "pick".equals(target.kind)) {
                open.setValue(Integer.valueOf(((Integer) target.payload).intValue()));
                pulse("edit:" + open.getName());
            }
            return;
        }

        /* Typing into a setting ends when the pointer goes anywhere else, and
           what was typed is kept rather than thrown away. */
        if (this.editing != null && (target == null || target.payload != this.editing)) {
            commitEdit();
        }
        /* A key setting waiting for its key stops waiting on a click elsewhere. */
        if (this.keyEditing != null && (target == null || target.payload != this.keyEditing)) {
            this.keyEditing = null;
        }

        this.searchFocused = target != null && "search".equals(target.kind);
        this.profileNameFocused = target != null && "profileName".equals(target.kind);
        if (target == null || "search".equals(target.kind) || "profileName".equals(target.kind)) {
            return;
        }
        if (target.kind.startsWith("profile") && handleProfileClick(target)) {
            return;
        }

        this.dragButton = button;
        if ("move".equals(target.kind)) {
            anchorWindow(sr);
            this.movingWindow = true;
            this.grabX = mouseX - this.userX;
            this.grabY = mouseY - this.userY;
            return;
        }
        if ("grip".equals(target.kind)) {
            anchorWindow(sr);
            this.resizingWindow = true;
            this.grabX = this.userWidth - mouseX;
            this.grabY = this.userHeight - mouseY;
            return;
        }
        if ("themeMode".equals(target.kind)) {
            this.theme.toggleMode();
            paintPalette();
            return;
        }
        if ("hudEditor".equals(target.kind)) {
            pulse("btn:hudEditor");
            this.mc.displayGuiScreen(new myau.ui.hud.HudEditorScreen(this));
            return;
        }
        if ("page".equals(target.kind)) {
            selectPage((String) target.payload);
            return;
        }
        if ("appearance".equals(target.kind)) {
            this.appearanceView = true;
            this.profilesView = false;
            this.page = "Client Settings";
            this.search = "";
            this.detailScroll = 0;
            return;
        }
        if ("group".equals(target.kind)) {
            String group = (String) target.payload;
            if (!group.equals(this.appearanceGroup)) {
                this.lit.put("groupArrive", Float.valueOf(0.0F));
                this.detailScroll = 0;
                this.lit.put("detailScroll", Float.valueOf(0.0F));
            }
            this.appearanceGroup = group;
            return;
        }
        if ("preset".equals(target.kind)) {
            pulse("btn:preset:" + target.payload);
            this.theme.apply((String) target.payload);
            say("appearance set to " + target.payload);
            return;
        }
        if ("themeReset".equals(target.kind)) {
            pulse("btn:themeReset");
            this.theme.reset();
            say("appearance reset");
            return;
        }
        if ("category".equals(target.kind)) {
            this.appearanceView = false;
            this.profilesView = false;
            this.category = (String) target.payload;
            this.search = "";
            this.listScroll = 0;
            List<Module> modules = visibleModules();
            this.selected = modules.isEmpty() ? null : modules.get(0);
            return;
        }
        if ("rowSwitch".equals(target.kind)) {
            Module module = (Module) target.payload;
            module.setEnabled(!module.isEnabled());
            pulse("tog:" + module.getName());
            return;
        }
        if ("module".equals(target.kind)) {
            Module module = (Module) target.payload;
            /* A row only selects. Toggling from anywhere on the row turned
               modules on by accident while reaching for their settings. */
            if (this.selected != module) {
                this.lit.put("detailArrive", Float.valueOf(0.0F));
                this.detailScroll = 0;
                this.lit.put("detailScroll", Float.valueOf(0.0F));
            }
            this.selected = module;
            return;
        }
        if ("power".equals(target.kind)) {
            Module module = (Module) target.payload;
            module.setEnabled(!module.isEnabled());
            pulse("tog:" + module.getName());
            return;
        }
        if ("bind".equals(target.kind)) {
            this.binding = this.binding == target.payload ? null : (Module) target.payload;
            pulse("chip:bind");
            return;
        }
        if ("hide".equals(target.kind)) {
            Module module = (Module) target.payload;
            module.setHidden(!module.isHidden());
            pulse("chip:hide");
            return;
        }
        if ("toggle".equals(target.kind)) {
            BooleanProperty property = (BooleanProperty) target.payload;
            property.setValue(Boolean.valueOf(!property.getValue()));
            return;
        }
        if ("mode".equals(target.kind)) {
            ModeProperty mode = (ModeProperty) target.payload;
            if (button == 1) {
                /* Right click still cycles, because for a two-option setting
                   opening a list of two is more work than it saves. */
                mode.nextMode();
                pulse("edit:" + mode.getName());
                return;
            }
            Spring grow = new Spring(0.0F, 420.0F, 22.0F);
            this.springs.put("drop:" + mode.getName(), grow);
            this.dropdown = mode;
            return;
        }
        if ("type".equals(target.kind)) {
            Property<?> property = (Property<?>) target.payload;
            if (this.editing == property) {
                /* Already typing into it. Re-reading the value here would
                   replace what has been typed so far. */
                return;
            }
            this.editing = property;
            this.editBuffer = property.formatValue()
                    .replaceAll("[&§][0-9a-fklmnor]", "").replace("%", "");
            return;
        }
        if ("keyProp".equals(target.kind)) {
            KeyProperty key = (KeyProperty) target.payload;
            this.keyEditing = this.keyEditing == key ? null : key;
            return;
        }
        if ("colorOpen".equals(target.kind)) {
            ColorProperty colour = (ColorProperty) target.payload;
            this.colorOpen = this.colorOpen == colour ? null : colour;
            this.colorSynced = -1;
            return;
        }
        if ("colorSV".equals(target.kind) || "colorHue".equals(target.kind)) {
            boolean field = "colorSV".equals(target.kind);
            this.colorDrag = field ? "sv" : "hue";
            /* The strip's hit box has 3 px of grab margin either side. */
            this.colorX = field ? target.x : target.x + 3.0F;
            this.colorW = field ? target.x2 - target.x : (target.x2 - 3.0F) - (target.x + 3.0F);
            this.colorY = target.y;
            this.colorH = target.y2 - target.y;
            colourDragTo(mx, my);
            return;
        }
        if ("slide".equals(target.kind)) {
            Property<?> property = (Property<?>) target.payload;
            this.dragging = property;
            this.dragMin = minimumOf(property);
            this.dragMax = maximumOf(property);
            /* Straight off the rectangle the track was drawn at, less the grab
               margin either side, so the value under the pointer is the value
               on screen. */
            this.dragX = target.x + 3.0F;
            this.dragWidth = (target.x2 - 3.0F) - (target.x + 3.0F);
            dragTo(mx);
        }
    }

    /** Everything the profiles view can be clicked for. Returns whether it was one. */
    private boolean handleProfileClick(Hit target) {
        String kind = target.kind;
        if ("profiles".equals(kind)) {
            this.profilesView = true;
            this.appearanceView = false;
            this.page = "Client Settings";
            this.search = "";
            return true;
        }
        if ("profile".equals(kind)) {
            String name = (String) target.payload;
            if (!name.equals(this.profileSelected)) {
                this.lit.put("profileArrive", Float.valueOf(0.0F));
            }
            this.profileSelected = name;
            return true;
        }
        if ("profileCreate".equals(kind)) {
            pulse("btn:" + kind);
            createProfile();
            return true;
        }
        if ("profileLoad".equals(kind)) {
            pulse("btn:" + kind);
            AtlasProfiles.load((String) target.payload);
            return true;
        }
        if ("profileSave".equals(kind)) {
            pulse("btn:" + kind);
            String name = (String) target.payload;
            AtlasProfiles.save(name);
            pulse("saved:" + name);
            this.profilesReadAt = 0L;
            return true;
        }
        if ("profileDelete".equals(kind)) {
            String name = (String) target.payload;
            long now = System.currentTimeMillis();
            if (name.equals(this.confirmDelete) && now - this.confirmDeleteAt < CONFIRM_MS) {
                this.confirmDelete = null;
                if (AtlasProfiles.delete(name)) {
                    say("deleted profile " + name);
                    this.profileSelected = null;
                } else {
                    say("could not delete " + name);
                }
                this.profilesReadAt = 0L;
            } else {
                this.confirmDelete = name;
                this.confirmDeleteAt = now;
                pulse("btn:" + kind);
            }
            return true;
        }
        if ("profileFolder".equals(kind)) {
            pulse("btn:" + kind);
            AtlasProfiles.openFolder();
            return true;
        }
        return false;
    }

    /** Saves the current setup under the typed name, and selects it. */
    private void createProfile() {
        String name = AtlasProfiles.clean(this.profileName);
        if (name.isEmpty()) {
            say("type a name for the profile first");
            this.profileNameFocused = true;
            return;
        }
        AtlasProfiles.save(name);
        this.profileName = "";
        this.profileNameFocused = false;
        this.profileSelected = name;
        this.lit.put("profileArrive", Float.valueOf(0.0F));
        this.profilesReadAt = 0L;
        pulse("saved:" + name);
    }

    /**
     * Turns the centred default into explicit coordinates. Until the window
     * is moved it is drawn in the middle of whatever screen it finds itself
     * on; the moment it is dragged it needs a real number to drag from.
     */
    private void anchorWindow(ScaledResolution sr) {
        int width = windowWidth(sr);
        int height = windowHeight(sr);
        if (this.userX == UNSET) {
            this.userX = left(sr);
        }
        if (this.userY == UNSET) {
            this.userY = top(sr);
        }
        this.userWidth = width;
        this.userHeight = height;
    }

    /**
     * Carries on whatever the pointer is dragging, every frame.
     *
     * The game hands a screen its mouse events from the tick loop, twenty
     * times a second, so a drag driven only by mouseClickMove moved the
     * window and the sliders in twenty jumps a second however fast the frame
     * rate was -- which is what dragging the menu looked like. The position
     * passed to drawScreen is read every frame, so the drag follows that
     * instead, and ends as soon as the button is seen to be up.
     */
    private void followDrag(ScaledResolution sr, int mouseX, int mouseY) {
        if (!this.movingWindow && !this.resizingWindow && this.dragging == null && this.colorDrag == null) {
            return;
        }
        if (!org.lwjgl.input.Mouse.isButtonDown(this.dragButton)) {
            this.dragging = null;
            this.colorDrag = null;
            this.movingWindow = false;
            this.resizingWindow = false;
            return;
        }
        applyDrag(sr, mouseX, mouseY);
    }

    private void applyDrag(ScaledResolution sr, int mouseX, int mouseY) {
        if (this.movingWindow) {
            this.userX = Math.max(0, Math.min(sr.getScaledWidth() - windowWidth(sr),
                    mouseX - this.grabX));
            this.userY = Math.max(0, Math.min(sr.getScaledHeight() - windowHeight(sr),
                    mouseY - this.grabY));
            return;
        }
        if (this.resizingWindow) {
            this.userWidth = Math.max(MIN_WIDTH,
                    Math.min(sr.getScaledWidth() - 8, mouseX + this.grabX));
            this.userHeight = Math.max(MIN_HEIGHT,
                    Math.min(sr.getScaledHeight() - 8, mouseY + this.grabY));
            /* A window grown larger than the room left of it would otherwise
               be pushed back by the clamp in left(), which looks like the drag
               fighting the pointer. */
            this.userX = Math.max(0, Math.min(this.userX, sr.getScaledWidth() - this.userWidth));
            this.userY = Math.max(0, Math.min(this.userY, sr.getScaledHeight() - this.userHeight));
            return;
        }
        if (this.colorDrag != null) {
            colourDragTo(layoutX(mouseX), layoutY(mouseY));
            return;
        }
        dragTo(layoutX(mouseX));
    }

    private void dragTo(float mouseX) {
        if (this.dragging == null || this.dragWidth <= 0.0F) {
            return;
        }
        float fraction = (mouseX - this.dragX) / this.dragWidth;
        fraction = Math.max(0.0F, Math.min(1.0F, fraction));
        assign(this.dragging, this.dragMin + (this.dragMax - this.dragMin) * fraction);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long held) {
        /* Still applied, so a drag works even between frames; the frame loop
           (followDrag) is what makes it smooth. */
        applyDrag(new ScaledResolution(mc), mouseX, mouseY);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        this.dragging = null;
        this.colorDrag = null;
        this.movingWindow = false;
        this.resizingWindow = false;
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int wheel = org.lwjgl.input.Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        float mouseX = layoutX(org.lwjgl.input.Mouse.getEventX() * sr.getScaledWidth() / (float) mc.displayWidth);
        int step = wheel > 0 ? -1 : 1;
        int width = windowWidth(sr);
        if (mouseX > left(sr) + width - detailWidth(width)) {
            /* Bounded by the content, measured when it was last drawn, so the
               pane cannot be scrolled into empty space. */
            this.detailScroll = Math.max(0, Math.min(this.detailMaxScroll, this.detailScroll + step * 16));
            /* An open list is anchored to a row that has just moved. */
            this.dropdown = null;
        } else if (this.appearanceView) {
            return;
        } else if (this.profilesView) {
            this.profileScroll = Math.max(0, Math.min(this.profileMaxScroll, this.profileScroll + step));
        } else {
            this.listScroll = Math.max(0, Math.min(this.listMaxScroll, this.listScroll + step));
        }
    }

    /**
     * Takes what was typed into a setting and applies it. Out of range is
     * clamped rather than refused: someone who types 9000 into a field that
     * stops at 5000 wants the largest value it has.
     */
    private void commitEdit() {
        Property<?> property = this.editing;
        this.editing = null;
        if (property == null) {
            return;
        }
        String text = this.editBuffer.trim();
        if (text.isEmpty()) {
            return;
        }
        try {
            if (isNumeric(property)) {
                assign(property, Float.parseFloat(text.replace("%", "")));
            } else {
                property.parseString(text);
            }
            pulse("edit:" + property.getName());
        } catch (Exception ignored) {
            say("\"" + text + "\" is not a value for " + property.getLabel()
                    + " (" + property.getValuePrompt() + ")");
        }
    }

    @Override
    protected void keyTyped(char typed, int key) {
        if (this.binding != null) {
            /* Escape clears rather than cancels: wanting to remove a key is far
               commoner than changing one's mind about setting one. */
            this.binding.setKey(key == Keyboard.KEY_ESCAPE ? 0 : key);
            say(this.binding.getName() + " bound to "
                    + (key == Keyboard.KEY_ESCAPE ? "nothing"
                            : myau.util.KeyBindUtil.getKeyName(key)));
            this.binding = null;
            return;
        }
        if (this.keyEditing != null) {
            /* Escape clears, as for a module's own key. */
            KeyProperty edited = this.keyEditing;
            this.keyEditing = null;
            edited.setValue(Integer.valueOf(key == Keyboard.KEY_ESCAPE ? Keyboard.KEY_NONE : key));
            pulse("edit:" + edited.getName());
            say(edited.getName() + " set to " + edited.getKeyName());
            return;
        }
        if (this.editing != null) {
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                commitEdit();
                return;
            }
            if (key == Keyboard.KEY_ESCAPE) {
                this.editing = null;
                return;
            }
            if (key == Keyboard.KEY_BACK) {
                if (!this.editBuffer.isEmpty()) {
                    this.editBuffer = this.editBuffer.substring(0, this.editBuffer.length() - 1);
                }
                return;
            }
            if (typed >= 32 && typed != 127) {
                this.editBuffer += typed;
            }
            return;
        }
        if (this.dropdown != null && key == Keyboard.KEY_ESCAPE) {
            this.dropdown = null;
            return;
        }
        if (this.profileNameFocused) {
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                createProfile();
            } else if (key == Keyboard.KEY_ESCAPE) {
                this.profileNameFocused = false;
            } else if (key == Keyboard.KEY_BACK) {
                if (!this.profileName.isEmpty()) {
                    this.profileName = this.profileName.substring(0, this.profileName.length() - 1);
                }
            } else if (typed >= 32 && typed != 127 && this.profileName.length() < 32) {
                this.profileName += typed;
            }
            return;
        }
        /* Client Settings has no search to type into -- but Escape still
           closes the menu, as on every other page. It used to return here for
           every key, Escape included, so the menu could not be closed from it
           (2026-10-04). */
        if ("Client Settings".equals(this.page) && !this.searchFocused && key != Keyboard.KEY_ESCAPE) {
            return;
        }
        /* The menu's own commands live behind Control rather than on the
           function keys: F11 is the game's fullscreen toggle and is handled in
           the frame loop, F3 is the debug prefix, F5 the camera, F1 the HUD.
           Control plus a letter is untouched inside a screen, and holding
           Control keeps the character out of the search box as well. */
        if (GuiScreen.isCtrlKeyDown()) {
            if (key == Keyboard.KEY_L) {
                if (AtlasInspector.isRecording()) {
                    say(AtlasInspector.report());
                } else {
                    AtlasInspector.check();
                    say("checking layout -- press Ctrl+L again");
                }
            } else if (key == Keyboard.KEY_K) {
                /* So the menu can be looked at without asking anyone to take a
                   screenshot and describe it. */
                say(AtlasInspector.capture());
            } else if (key == Keyboard.KEY_I) {
                say(AtlasInspector.toggleLive());
            } else if (key == Keyboard.KEY_R) {
                this.userWidth = DEFAULT_WIDTH;
                this.userHeight = DEFAULT_HEIGHT;
                this.userX = UNSET;
                this.userY = UNSET;
                say("window reset to " + DEFAULT_WIDTH + "x" + DEFAULT_HEIGHT + ", centred");
            } else if (key == Keyboard.KEY_P) {
                this.perf = !this.perf;
            } else if (key == Keyboard.KEY_H && this.selected != null) {
                this.selected.setHidden(!this.selected.isHidden());
                pulse("chip:hide");
            }
            return;
        }
        if (key == Keyboard.KEY_ESCAPE) {
            if (!this.search.isEmpty()) {
                /* Escape clears the search before it closes the menu: losing a
                   filter is a smaller surprise than losing the menu. */
                this.search = "";
                this.listScroll = 0;
                return;
            }
            mc.displayGuiScreen(null);
            return;
        }
        if (key == Keyboard.KEY_BACK) {
            if (!this.search.isEmpty()) {
                this.search = this.search.substring(0, this.search.length() - 1);
                this.listScroll = 0;
            }
            return;
        }
        /* Any printable character starts a search, wherever focus nominally
           is. Having to click a box first is what stops people searching. */
        if (typed >= 32 && typed != 127) {
            /* A search is of modules, so it leaves the profiles and appearance views. */
            this.profilesView = false;
            this.appearanceView = false;
            this.search += typed;
            this.searchFocused = true;
            this.listScroll = 0;
        }
    }

    private void say(String message) {
        myau.util.ChatUtil.sendFormatted("&7[&bAtlas&7] &f" + message);
    }

    @Override
    public void initGui() {
        loadState();
        build();
        this.binding = null;
        this.keyEditing = null;
        this.colorDrag = null;
        this.editing = null;
        this.dropdown = null;
        this.dragging = null;
        this.movingWindow = false;
        this.resizingWindow = false;
        this.searchFocused = false;
        this.profileNameFocused = false;
        this.confirmDelete = null;
        this.profilesReadAt = 0L;
        /* The filter is per-visit; the category and the selected module are
           not, which is the difference between "where I was" and "what I was
           looking for at the time". */
        this.search = "";
        this.listScroll = 0;
        this.openedFor = 0.0F;
        this.open.snap(0.92F);
        applyTheme();
        paintPalette();
        this.lastFrame = 0L;
        this.lit.clear();
        this.springs.clear();
        this.hits.clear();
        this.lenses.clear();
        /* Every size the menu draws, built now rather than on the first frame
           that needs it, where building them would stall the opening. */
        Liquid.refreshScale();
        float[][] faces = {{7.0F, 1}, {7.0F, 0}, {7.5F, 0}, {8.0F, 0}, {8.0F, 1}, {8.5F, 0}, {8.5F, 1},
                {9.0F, 0}, {9.5F, 0}, {9.5F, 1}, {12.5F, 0}, {12.5F, 1}, {14.0F, 1}, {15.0F, 1}};
        for (float[] face : faces) {
            LiquidFont.of(face[0], face[1] > 0.5F);
        }
    }

    @Override
    public void onGuiClosed() {
        /* Anything half-typed counts, so closing the menu is not a way to lose
           a number that was already entered. */
        commitEdit();
        this.dragging = null;
        this.movingWindow = false;
        this.resizingWindow = false;
        this.dropdown = null;
        saveState();
        this.theme.save();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
