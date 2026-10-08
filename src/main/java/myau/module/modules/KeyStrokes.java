/*
 * Ported from OpenSkid (https://github.com/loloshelly102-a11y/OpenSkid, GPL-3.0),
 * src/main/java/openskid/module/modules/KeyStrokes.java, 2026-10-05: package openskid -> myau,
 * OpenSkid -> Myau. Changes after that are marked in comments.
 */
package myau.module.modules;

import myau.ui.impl.clickgui.atlas.HudPanel;
import myau.event.EventTarget;
import myau.events.Render2DEvent;
import myau.module.Module;
import myau.ui.hud.HudLayout;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.ArrayDeque;
import java.util.Deque;

// WASD plus mouse click overlay polled per frame. Concept adapted from the MiauMinus Keystrokes module.
public class KeyStrokes extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int KEY = 22;
    private static final int GAP = 2;
    private static final int SPACE_W = 70;
    private static final int SPACE_H = 14;
    private static final int MOUSE_W = 34;
    private static final int MOUSE_H = 22;
    private static final int BOX_W = 70;

    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 2.0F);
    public final ModeProperty posX = new ModeProperty("position-x", 0, new String[]{"LEFT", "MIDDLE", "RIGHT"});
    public final ModeProperty posY = new ModeProperty("position-y", 1, new String[]{"TOP", "MIDDLE", "BOTTOM"});
    public final IntProperty offX = new IntProperty("offset-x", 4, -500, 500);
    public final IntProperty offY = new IntProperty("offset-y", 40, -500, 500);
    public final BooleanProperty showSpace = new BooleanProperty("space", true);
    public final BooleanProperty showMouse = new BooleanProperty("mouse", true);
    public final BooleanProperty showCPS = new BooleanProperty("cps", true, () -> this.showMouse.getValue());

    private final Deque<Long> leftClicks = new ArrayDeque<Long>();
    private final Deque<Long> rightClicks = new ArrayDeque<Long>();
    private boolean prevLeft = false;
    private boolean prevRight = false;

    public KeyStrokes() {
        super("KeyStrokes", false, false, "Shows your keys and clicks on screen.");
    }

    @Override
    public void onEnabled() {
        resetClicks();
    }

    @Override
    public void onDisabled() {
        resetClicks();
    }

    private void resetClicks() {
        leftClicks.clear();
        rightClicks.clear();
        prevLeft = false;
        prevRight = false;
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.gameSettings == null) {
            return;
        }
        boolean space = showSpace.getValue();
        boolean mouse = showMouse.getValue();
        boolean cps = mouse && showCPS.getValue();
        int boxH = KEY + GAP + KEY;
        if (space) {
            boxH += GAP + SPACE_H;
        }
        if (mouse) {
            boxH += GAP + MOUSE_H;
        }
        float sc = scale.getValue();
        ScaledResolution sr = new ScaledResolution(mc);
        float x = offX.getValue().floatValue();
        switch (posX.getValue()) {
            case 1:
                x += sr.getScaledWidth() / 2.0F - BOX_W * sc / 2.0F;
                break;
            case 2:
                x = sr.getScaledWidth() - BOX_W * sc - x;
                break;
            default:
                break;
        }
        float y = offY.getValue().floatValue();
        switch (posY.getValue()) {
            case 1:
                y += sr.getScaledHeight() / 2.0F - boxH * sc / 2.0F;
                break;
            case 2:
                y = sr.getScaledHeight() - boxH * sc - y;
                break;
            default:
                break;
        }
        /* HUD editor (2026-10-05): report where this is drawn, so the editor can find and drag it. */
        HudLayout.report("KeyStrokes", "KeyStrokes", x, y, BOX_W * sc, boxH * sc,
                HudLayout.ints(offX, posX.getValue() == 2 ? -1 : 1, offY, posY.getValue() == 2 ? -1 : 1));
        long now = System.currentTimeMillis();
        boolean wDown = Keyboard.isKeyDown(mc.gameSettings.keyBindForward.getKeyCode());
        boolean aDown = Keyboard.isKeyDown(mc.gameSettings.keyBindLeft.getKeyCode());
        boolean sDown = Keyboard.isKeyDown(mc.gameSettings.keyBindBack.getKeyCode());
        boolean dDown = Keyboard.isKeyDown(mc.gameSettings.keyBindRight.getKeyCode());
        boolean jumpDown = Keyboard.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode());
        boolean lDown = Mouse.isButtonDown(0);
        boolean rDown = Mouse.isButtonDown(1);
        if (lDown && !prevLeft) {
            leftClicks.addLast(now);
        }
        if (rDown && !prevRight) {
            rightClicks.addLast(now);
        }
        prevLeft = lDown;
        prevRight = rDown;
        prune(leftClicks, now);
        prune(rightClicks, now);
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0F);
        GlStateManager.scale(sc, sc, 1.0F);
        drawKey("W", 24, 0, KEY, KEY, wDown, false, 0);
        drawKey("A", 0, KEY + GAP, KEY, KEY, aDown, false, 0);
        drawKey("S", 24, KEY + GAP, KEY, KEY, sDown, false, 0);
        drawKey("D", 48, KEY + GAP, KEY, KEY, dDown, false, 0);
        int cy = KEY * 2 + GAP * 2;
        if (space) {
            drawSpace(0, cy, jumpDown);
            cy += SPACE_H + GAP;
        }
        if (mouse) {
            drawKey("LMB", 0, cy, MOUSE_W, MOUSE_H, lDown, cps, leftClicks.size());
            drawKey("RMB", MOUSE_W + GAP, cy, MOUSE_W, MOUSE_H, rDown, cps, rightClicks.size());
        }
        GlStateManager.popMatrix();
    }

    private void prune(Deque<Long> clicks, long now) {
        while (!clicks.isEmpty() && now - clicks.peekFirst() > 1000L) {
            clicks.removeFirst();
        }
    }

    private void drawKey(String label, int x, int y, int w, int h, boolean down, boolean cpsLine, int cps) {
        boolean jello = HudPanel.active();
        if (jello) {
            jelloKey(x, y, w, h, down);
        } else {
            RenderUtil.drawRect(x, y, x + w, y + h, down ? 0xDDFFFFFF : 0x66000000);
        }
        int fg = jello ? (down ? 0xFFD3D3D3 : 0xFFFFFFFF) : (down ? 0xFF222222 : 0xFFFFFFFF);
        int labelY = cpsLine ? y + 3 : y + h / 2 - 4;
        mc.fontRendererObj.drawString(label, x + w / 2 - mc.fontRendererObj.getStringWidth(label) / 2, labelY, fg);
        if (cpsLine) {
            String cpsText = cps + " CPS";
            mc.fontRendererObj.drawString(cpsText, x + w / 2 - mc.fontRendererObj.getStringWidth(cpsText) / 2, y + 12, fg);
        }
    }

    /** JelloBounce Key.svelte: rgba(black,.45), 7px corners; active rgba(#2e2e2e,.7) at 95% scale. */
    private static void jelloKey(float x, float y, float w, float h, boolean down) {
        if (down) {
            float dx = w * 0.025F;
            float dy = h * 0.025F;
            HudPanel.rounded(x + dx, y + dy, x + w - dx, y + h - dy, HudPanel.SMALL_RADIUS, 1.0F, true);
            HudPanel.fill(x + dx, y + dy, x + w - dx, y + h - dy, HudPanel.SMALL_RADIUS, 0xB32E2E2E);
        } else {
            HudPanel.rounded(x, y, x + w, y + h, HudPanel.SMALL_RADIUS, 1.0F, true);
        }
    }

    private void drawSpace(int x, int y, boolean down) {
        boolean jello = HudPanel.active();
        if (jello) {
            jelloKey(x, y, SPACE_W, SPACE_H, down);
        } else {
            RenderUtil.drawRect(x, y, x + SPACE_W, y + SPACE_H, down ? 0xDDFFFFFF : 0x66000000);
        }
        int fg = jello ? (down ? 0xFFD3D3D3 : 0xFFFFFFFF) : (down ? 0xFF222222 : 0xFFFFFFFF);
        int lineW = 28;
        RenderUtil.drawRect(x + (SPACE_W - lineW) / 2, y + SPACE_H / 2 - 1,
                x + (SPACE_W + lineW) / 2, y + SPACE_H / 2 + 1, fg);
    }
}
