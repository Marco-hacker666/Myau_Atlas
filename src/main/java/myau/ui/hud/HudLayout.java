package myau.ui.hud;

import myau.property.properties.DragProperty;
import myau.property.properties.IntProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where each HUD element was drawn on the last frame, and how to move it.
 *
 * A HUD module calls {@link #report} after drawing, with the rectangle it
 * covered (in scaled screen pixels) and a {@link Mover} built over the settings
 * that store its position. The HUD editor reads these back to draw the frames
 * and to drag the elements. Moving is done by whole-pixel deltas, so an element
 * follows the pointer exactly whatever its own position formula is (an anchor
 * and an offset, a right-hand margin, a free position), as long as the setting
 * moves the element one pixel per unit -- which is true of every element that
 * reports here.
 */
public final class HudLayout {
    /** Moves an element; returns how far it really moved, since a setting may be at its limit. */
    public interface Mover {
        int[] moveBy(int dx, int dy);
    }

    public static final class Element {
        public final String id;
        public String label;
        public float x;
        public float y;
        public float width;
        public float height;
        public Mover mover;
        long seenAt;

        Element(String id) {
            this.id = id;
        }

        public boolean contains(float px, float py) {
            return px >= this.x && px <= this.x + this.width && py >= this.y && py <= this.y + this.height;
        }
    }

    /** An element counts as on screen if it reported this recently. */
    private static final long VISIBLE_MS = 250L;
    private static final Map<String, Element> ELEMENTS = new LinkedHashMap<String, Element>();
    private static boolean editing;

    private HudLayout() {
    }

    /** True while the HUD editor is open: elements that hide behind menus should keep drawing. */
    public static boolean isEditing() {
        return editing;
    }

    static void setEditing(boolean value) {
        editing = value;
    }

    public static void report(String id, String label, float x, float y, float width, float height, Mover mover) {
        if (width <= 0.0F || height <= 0.0F || mover == null) {
            return;
        }
        Element element = ELEMENTS.get(id);
        if (element == null) {
            element = new Element(id);
            ELEMENTS.put(id, element);
        }
        element.label = label;
        element.x = x;
        element.y = y;
        element.width = width;
        element.height = height;
        element.mover = mover;
        element.seenAt = System.currentTimeMillis();
    }

    static List<Element> visible() {
        long now = System.currentTimeMillis();
        List<Element> out = new ArrayList<Element>();
        for (Element element : ELEMENTS.values()) {
            if (now - element.seenAt <= VISIBLE_MS) {
                out.add(element);
            }
        }
        return out;
    }

    /**
     * A mover over two integer settings. A sign of 1 means a larger value moves
     * the element right (or down); -1 means it moves it left (or up), as with a
     * right-hand margin. Either setting may be null to lock that axis.
     */
    public static Mover ints(final IntProperty xSetting, final int xSign, final IntProperty ySetting, final int ySign) {
        return new Mover() {
            @Override
            public int[] moveBy(int dx, int dy) {
                return new int[]{shift(xSetting, xSign, dx), shift(ySetting, ySign, dy)};
            }
        };
    }

    /** A mover over a {@link DragProperty}, which stores a free position. */
    public static Mover drag(final DragProperty setting) {
        return new Mover() {
            @Override
            public int[] moveBy(int dx, int dy) {
                setting.position.x += dx;
                setting.position.y += dy;
                setting.targetPosition.x = setting.position.x;
                setting.targetPosition.y = setting.position.y;
                return new int[]{dx, dy};
            }
        };
    }

    private static int shift(IntProperty setting, int sign, int delta) {
        if (setting == null || sign == 0 || delta == 0) {
            return 0;
        }
        int old = setting.getValue();
        int wanted = old + sign * delta;
        int clamped = Math.max(setting.getMinimum(), Math.min(setting.getMaximum(), wanted));
        if (clamped == old || !setting.setValue(clamped)) {
            return 0;
        }
        return (clamped - old) * sign;
    }
}
