package myau.ui.hud;

import myau.Myau;
import myau.module.modules.ClickGUIModule;
import myau.ui.UiMode;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.input.Keyboard;

import java.util.List;

/**
 * Lets the player drag HUD elements around the screen.
 *
 * Opened from the menu's HUD Editor button. The HUD keeps drawing underneath,
 * as it does behind any menu; this screen only draws a frame around each
 * element that reported where it is (see {@link HudLayout}), and moves the one
 * being dragged. Escape goes back to the menu that opened it.
 */
public class HudEditorScreen extends GuiScreen {
    /** How close, in pixels, an edge or centre has to come to a guide to snap to it. */
    private static final int SNAP = 4;

    private final GuiScreen parent;
    private HudLayout.Element dragging;
    private HudLayout.Element selected;
    private float startX;
    private float startY;
    private int pressX;
    private int pressY;
    private int movedX;
    private int movedY;
    /* The guide lines the dragged element is snapped to, if any. */
    private boolean guideXShown;
    private boolean guideYShown;
    private float guideX;
    private float guideY;

    public HudEditorScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        HudLayout.setEditing(true);
    }

    @Override
    public void onGuiClosed() {
        HudLayout.setEditing(false);
        this.dragging = null;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        ScaledResolution sr = new ScaledResolution(this.mc);
        int sw = sr.getScaledWidth();
        int sh = sr.getScaledHeight();
        boolean light = UiMode.isLight();
        int accent = accent();
        int neutral = light ? 0xFF1A1F28 : 0xFFFFFFFF;

        /* Faint centre lines, the two guides everything can snap to. */
        Gui.drawRect(sw / 2, 0, sw / 2 + 1, sh, withAlpha(neutral, 0x18));
        Gui.drawRect(0, sh / 2, sw, sh / 2 + 1, withAlpha(neutral, 0x18));

        List<HudLayout.Element> elements = HudLayout.visible();
        HudLayout.Element hovered = this.dragging != null ? null : topAt(elements, mouseX, mouseY);
        for (HudLayout.Element element : elements) {
            boolean active = element == this.dragging || element == this.selected;
            boolean hover = element == hovered;
            int left = Math.round(element.x);
            int top = Math.round(element.y);
            int right = Math.round(element.x + element.width);
            int bottom = Math.round(element.y + element.height);
            if (active || hover) {
                Gui.drawRect(left, top, right, bottom, withAlpha(accent, active ? 0x2E : 0x1A));
            }
            int edge = active || hover ? withAlpha(accent, 0xE6) : withAlpha(neutral, 0x80);
            frame(left, top, right, bottom, edge);
            drawLabel(element.label, left, top, bottom, sh, active || hover ? accent : neutral, light);
        }

        if (this.dragging != null) {
            if (this.guideXShown) {
                int gx = Math.round(this.guideX);
                Gui.drawRect(gx, 0, gx + 1, sh, withAlpha(accent, 0xC0));
            }
            if (this.guideYShown) {
                int gy = Math.round(this.guideY);
                Gui.drawRect(0, gy, sw, gy + 1, withAlpha(accent, 0xC0));
            }
        }

        String help = elements.isEmpty()
                ? "Turn on a HUD module to place it here  |  Esc to go back"
                : "Drag to move  |  Arrow keys nudge, Shift x10  |  Esc to go back";
        int helpWidth = this.fontRendererObj.getStringWidth(help);
        int helpX = (sw - helpWidth) / 2;
        int helpY = sh - 34;
        Gui.drawRect(helpX - 8, helpY - 5, helpX + helpWidth + 8, helpY + 13, light ? 0xD0F3F5F9 : 0xB0101318);
        this.fontRendererObj.drawStringWithShadow(help, helpX, helpY, light ? 0xFF1A1F28 : 0xFFE6EBF2);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0) {
            return;
        }
        HudLayout.Element element = topAt(HudLayout.visible(), mouseX, mouseY);
        this.selected = element;
        this.dragging = element;
        this.guideXShown = false;
        this.guideYShown = false;
        if (element != null) {
            this.startX = element.x;
            this.startY = element.y;
            this.pressX = mouseX;
            this.pressY = mouseY;
            this.movedX = 0;
            this.movedY = 0;
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (this.dragging == null || clickedMouseButton != 0) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(this.mc);
        int sw = sr.getScaledWidth();
        int sh = sr.getScaledHeight();
        float w = this.dragging.width;
        float h = this.dragging.height;
        float wantX = this.startX + (mouseX - this.pressX);
        float wantY = this.startY + (mouseY - this.pressY);

        List<HudLayout.Element> others = HudLayout.visible();
        others.remove(this.dragging);
        float[] snappedX = snapAxis(wantX, w, sw, others, true);
        float[] snappedY = snapAxis(wantY, h, sh, others, false);
        float x = snappedX[0];
        float y = snappedY[0];
        this.guideXShown = snappedX[1] > 0.5F;
        this.guideX = snappedX[2];
        this.guideYShown = snappedY[1] > 0.5F;
        this.guideY = snappedY[2];

        /* Never past the edge of the screen. */
        x = Math.max(0.0F, Math.min(Math.max(0.0F, sw - w), x));
        y = Math.max(0.0F, Math.min(Math.max(0.0F, sh - h), y));
        moveTo(Math.round(x - this.startX), Math.round(y - this.startY));
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        this.dragging = null;
        this.guideXShown = false;
        this.guideYShown = false;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.mc.displayGuiScreen(this.parent);
            return;
        }
        if (this.selected == null || this.selected.mover == null || this.dragging != null) {
            return;
        }
        int step = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT) ? 10 : 1;
        if (keyCode == Keyboard.KEY_LEFT) {
            this.selected.mover.moveBy(-step, 0);
        } else if (keyCode == Keyboard.KEY_RIGHT) {
            this.selected.mover.moveBy(step, 0);
        } else if (keyCode == Keyboard.KEY_UP) {
            this.selected.mover.moveBy(0, -step);
        } else if (keyCode == Keyboard.KEY_DOWN) {
            this.selected.mover.moveBy(0, step);
        }
    }

    /** Moves the dragged element so it ends up this far from where the drag began. */
    private void moveTo(int totalX, int totalY) {
        int[] moved = this.dragging.mover.moveBy(totalX - this.movedX, totalY - this.movedY);
        this.movedX += moved[0];
        this.movedY += moved[1];
    }

    /**
     * Snaps one axis. Returns {position, 1 if snapped else 0, guide line}.
     * Guides: the screen's edges and centre, and every other element's edges and centre.
     */
    private static float[] snapAxis(float want, float size, int screen, List<HudLayout.Element> others,
                                    boolean horizontal) {
        float best = want;
        float bestDistance = SNAP + 1;
        float line = 0.0F;
        float[][] candidates = new float[3 + others.size() * 5][];
        int n = 0;
        candidates[n++] = new float[]{0.0F, 0.0F};
        candidates[n++] = new float[]{screen - size, screen};
        candidates[n++] = new float[]{screen / 2.0F - size / 2.0F, screen / 2.0F};
        for (HudLayout.Element other : others) {
            float start = horizontal ? other.x : other.y;
            float length = horizontal ? other.width : other.height;
            float end = start + length;
            candidates[n++] = new float[]{start, start};
            candidates[n++] = new float[]{end, end};
            candidates[n++] = new float[]{end - size, end};
            candidates[n++] = new float[]{start - size, start};
            candidates[n++] = new float[]{start + length / 2.0F - size / 2.0F, start + length / 2.0F};
        }
        for (int i = 0; i < n; i++) {
            float distance = Math.abs(candidates[i][0] - want);
            if (distance <= SNAP && distance < bestDistance) {
                bestDistance = distance;
                best = candidates[i][0];
                line = candidates[i][1];
            }
        }
        return new float[]{best, bestDistance <= SNAP ? 1.0F : 0.0F, line};
    }

    private static HudLayout.Element topAt(List<HudLayout.Element> elements, int mouseX, int mouseY) {
        /* Last reported is drawn last, so it is on top. */
        for (int i = elements.size() - 1; i >= 0; i--) {
            if (elements.get(i).contains(mouseX, mouseY)) {
                return elements.get(i);
            }
        }
        return null;
    }

    private static void frame(int left, int top, int right, int bottom, int colour) {
        Gui.drawRect(left, top, right, top + 1, colour);
        Gui.drawRect(left, bottom - 1, right, bottom, colour);
        Gui.drawRect(left, top + 1, left + 1, bottom - 1, colour);
        Gui.drawRect(right - 1, top + 1, right, bottom - 1, colour);
    }

    private void drawLabel(String label, int left, int top, int bottom, int screenHeight, int colour, boolean light) {
        if (label == null || label.isEmpty()) {
            return;
        }
        int width = this.fontRendererObj.getStringWidth(label);
        /* Above the frame, or below it when there is no room above. */
        int y = top - 11 >= 0 ? top - 11 : Math.min(screenHeight - 10, bottom + 2);
        Gui.drawRect(left, y - 1, left + width + 6, y + 9, light ? 0xC8F3F5F9 : 0xB0101318);
        this.fontRendererObj.drawStringWithShadow(label, left + 3, y, colour);
    }

    private static int withAlpha(int colour, int alpha) {
        return (alpha << 24) | (colour & 0x00FFFFFF);
    }

    private static int accent() {
        try {
            ClickGUIModule gui = (ClickGUIModule) Myau.moduleManager.modules.get(ClickGUIModule.class);
            return gui == null ? 0xFF4FC3F7 : gui.getAccentColor().getRGB();
        } catch (Exception ignored) {
            return 0xFF4FC3F7;
        }
    }
}
