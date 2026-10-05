package myau.module.modules;

import myau.ui.UiMode;
import myau.ui.hud.HudLayout;

import myau.event.EventTarget;
import myau.events.LeftClickMouseEvent;
import myau.events.Render2DEvent;
import myau.events.RightClickMouseEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.IntProperty;
import myau.util.Ping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.MathHelper;
import org.lwjgl.input.Mouse;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.Deque;

/** Lightweight, client-only HUD pieces inspired by common Lunar client mods. */
public class LegitHUD extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final long CPS_WINDOW_MS = 1000L;
    private final Deque<Long> leftClicks = new ArrayDeque<Long>();
    private final Deque<Long> rightClicks = new ArrayDeque<Long>();

    public final BooleanProperty showCoordinates = new BooleanProperty("coordinates", true);
    public final BooleanProperty showFps = new BooleanProperty("fps", true);
    public final BooleanProperty showPing = new BooleanProperty("ping", true);
    public final BooleanProperty showCps = new BooleanProperty("cps", true);
    public final BooleanProperty showKeystrokes = new BooleanProperty("keystrokes", true);
    public final BooleanProperty background = new BooleanProperty("background", true);
    public final ColorProperty textColor = new ColorProperty("text-color", Color.WHITE.getRGB());
    public final IntProperty x = new IntProperty("x", 6, 0, 500);
    public final IntProperty y = new IntProperty("y", 6, 0, 300);
    public final IntProperty keysX = new IntProperty("keys-x", 6, 0, 500);
    public final IntProperty keysY = new IntProperty("keys-y", 86, 0, 400);

    public LegitHUD() {
        super("LegitHUD", false, false, "Coordinates, FPS, ping, CPS and keystrokes.");
    }

    @EventTarget
    public void onLeftClick(LeftClickMouseEvent event) {
        if (this.isEnabled() && this.showCps.getValue()) {
            this.leftClicks.addLast(System.currentTimeMillis());
        }
    }

    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (this.isEnabled() && this.showCps.getValue()) {
            this.rightClicks.addLast(System.currentTimeMillis());
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null
                || (mc.currentScreen != null && !HudLayout.isEditing())) return;

        ScaledResolution resolution = new ScaledResolution(mc);
        int screenWidth = resolution.getScaledWidth();
        int screenHeight = resolution.getScaledHeight();
        int textX = Math.min(this.x.getValue(), Math.max(0, screenWidth - 120));
        int textY = Math.min(this.y.getValue(), Math.max(0, screenHeight - 20));
        int color = UiMode.adapt(this.textColor.getValue());
        int lineHeight = mc.fontRendererObj.FONT_HEIGHT + 3;
        int row = 0;
        int widest = 0;

        if (this.showCoordinates.getValue()) {
            String coordinates = String.format("XYZ: %d %d %d",
                    MathHelper.floor_double(mc.thePlayer.posX),
                    MathHelper.floor_double(mc.thePlayer.getEntityBoundingBox().minY),
                    MathHelper.floor_double(mc.thePlayer.posZ));
            widest = Math.max(widest, drawRow(coordinates, textX, textY + row++ * lineHeight, color));
        }
        if (this.showFps.getValue()) {
            widest = Math.max(widest, drawRow("FPS: " + Minecraft.getDebugFPS(), textX, textY + row++ * lineHeight, color));
        }
        if (this.showPing.getValue()) {
            int ping = Ping.own();
            widest = Math.max(widest, drawRow("Ping: " + (ping < 0 ? "--" : ping + " ms"), textX, textY + row++ * lineHeight, color));
        }
        if (this.showCps.getValue()) {
            long now = System.currentTimeMillis();
            prune(this.leftClicks, now);
            prune(this.rightClicks, now);
            widest = Math.max(widest, drawRow("CPS: L " + this.leftClicks.size() + "  R " + this.rightClicks.size(),
                    textX, textY + row++ * lineHeight, color));
        }

        if (row > 0) {
            HudLayout.report("LegitHUD.info", "LegitHUD", textX - 3, textY - 2, widest + 6,
                    row * lineHeight + 1, HudLayout.ints(this.x, 1, this.y, 1));
        }
        if (this.showKeystrokes.getValue()) {
            drawKeystrokes(this.keysX.getValue(), this.keysY.getValue(), screenWidth, screenHeight, color);
        }
    }

    @Override
    public void onDisabled() {
        this.leftClicks.clear();
        this.rightClicks.clear();
    }

    private int drawRow(String text, int x, int y, int color) {
        if (this.background.getValue()) {
            Gui.drawRect(x - 3, y - 2, x + mc.fontRendererObj.getStringWidth(text) + 3,
                    y + mc.fontRendererObj.FONT_HEIGHT + 2, UiMode.adapt(0x70000000));
        }
        mc.fontRendererObj.drawStringWithShadow(text, x, y, color);
        return mc.fontRendererObj.getStringWidth(text);
    }

    private void drawKeystrokes(int x, int y, int screenWidth, int screenHeight, int color) {
        int box = 20;
        int gap = 2;
        int left = Math.min(x, Math.max(0, screenWidth - box * 3 - gap * 2));
        int top = Math.min(y, Math.max(0, screenHeight - box * 4 - gap * 3));
        HudLayout.report("LegitHUD.keys", "Keystrokes", left, top, box * 3 + gap * 2, box * 4 + gap * 3,
                HudLayout.ints(this.keysX, 1, this.keysY, 1));
        drawKey("W", left + box + gap, top, mc.gameSettings.keyBindForward.isKeyDown(), color);
        drawKey("A", left, top + box + gap, mc.gameSettings.keyBindLeft.isKeyDown(), color);
        drawKey("S", left + box + gap, top + box + gap, mc.gameSettings.keyBindBack.isKeyDown(), color);
        drawKey("D", left + (box + gap) * 2, top + box + gap, mc.gameSettings.keyBindRight.isKeyDown(), color);
        int mouseY = top + (box + gap) * 2;
        drawKey("LMB", left, mouseY, Mouse.isButtonDown(0), color, box + 11);
        drawKey("RMB", left + box + gap + 11, mouseY, Mouse.isButtonDown(1), color, box + 11);
        drawKey("SPACE", left, top + (box + gap) * 3,
                mc.gameSettings.keyBindJump.isKeyDown(), color, box * 3 + gap * 2);
    }

    private void drawKey(String label, int x, int y, boolean down, int color) {
        drawKey(label, x, y, down, color, 20);
    }

    private void drawKey(String label, int x, int y, boolean down, int color, int width) {
        int fill = down ? (color & 0x55FFFFFF) | 0xA0000000 : UiMode.adapt(0x70000000);
        Gui.drawRect(x, y, x + width, y + 20, fill);
        int labelWidth = mc.fontRendererObj.getStringWidth(label);
        mc.fontRendererObj.drawStringWithShadow(label, x + (width - labelWidth) / 2,
                y + 6, down ? color : UiMode.adapt(0xFFE0E0E0));
    }

    private static void prune(Deque<Long> clicks, long now) {
        while (!clicks.isEmpty() && now - clicks.peekFirst() > CPS_WINDOW_MS) {
            clicks.removeFirst();
        }
    }
}
