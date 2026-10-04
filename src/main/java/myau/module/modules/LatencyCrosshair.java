package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.Render2DEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.MathHelper;
import org.lwjgl.opengl.GL11;

import java.awt.Color;

/**
 * Draws a second crosshair where the server still thinks this client is aiming.
 *
 * Latency is usually experienced as a number in a corner, which says nothing
 * about what it costs. What it actually costs is this: for a third of a second
 * after every flick, the server is resolving hits against the rotation from
 * before the flick. The gap is what decides whether a swing lands, and it is
 * invisible.
 *
 * The marker is the rotation this client actually sent, one round trip ago,
 * drawn as an angular offset from where the camera points now. Standing still
 * the two sit on top of each other; during a fast turn they separate by exactly
 * as much as the connection is costing, and they close again as the server
 * catches up.
 *
 * The history is built from outgoing look packets rather than from the player's
 * own rotation, so whatever the client sent -- including a rotation some other
 * module spoofed -- is what gets drawn. Nothing is sent, read back, or altered.
 */
public class LatencyCrosshair extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int HISTORY = 64;

    public final IntProperty extraTicks = new IntProperty("extra-ticks", 0, 0, 20);
    public final FloatProperty size = new FloatProperty("size", 4.0F, 1.0F, 12.0F);
    public final FloatProperty thickness = new FloatProperty("thickness", 1.0F, 0.5F, 3.0F);
    /** Hidden while the gap is negligible, so a still screen stays clean. */
    public final FloatProperty minOffset = new FloatProperty("min-offset", 2.0F, 0.0F, 30.0F);
    public final BooleanProperty showDistance = new BooleanProperty("show-degrees", true);
    public final BooleanProperty fade = new BooleanProperty("fade-with-distance", true);

    private final float[] sentYaw = new float[HISTORY];
    private final float[] sentPitch = new float[HISTORY];
    private int index;
    private int filled;

    private float lastSentYaw;
    private float lastSentPitch;
    private boolean haveSent;

    public LatencyCrosshair() {
        super("LatencyCrosshair", false, false,
                "Shows where the server still thinks you are aiming");
    }

    @Override
    public void onEnabled() {
        this.index = 0;
        this.filled = 0;
        this.haveSent = false;
    }

    /**
     * Only the look-bearing movement packets carry a rotation; the
     * position-only form leaves those fields at zero, and recording them would
     * drag the marker to the horizon every time the player moved without
     * turning.
     */
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND) {
            return;
        }
        if (event.getPacket() instanceof C03PacketPlayer.C05PacketPlayerLook
                || event.getPacket() instanceof C03PacketPlayer.C06PacketPlayerPosLook) {
            C03PacketPlayer packet = (C03PacketPlayer) event.getPacket();
            this.lastSentYaw = packet.getYaw();
            this.lastSentPitch = packet.getPitch();
            this.haveSent = true;
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        if (!this.haveSent) {
            this.lastSentYaw = mc.thePlayer.rotationYaw;
            this.lastSentPitch = mc.thePlayer.rotationPitch;
        }
        this.sentYaw[this.index] = this.lastSentYaw;
        this.sentPitch[this.index] = this.lastSentPitch;
        this.index = (this.index + 1) % HISTORY;
        if (this.filled < HISTORY) {
            this.filled++;
        }
    }

    private int delayTicks() {
        int ping = myau.util.Ping.own();
        int ticks = ping > 0 ? (int) Math.ceil(ping / 50.0) : 4;
        ticks += this.extraTicks.getValue();
        return Math.min(Math.max(ticks, 1), HISTORY - 1);
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.fontRendererObj == null
                || mc.currentScreen != null || mc.gameSettings.thirdPersonView != 0
                || mc.gameSettings.showDebugInfo) {
            return;
        }
        int back = this.delayTicks();
        if (this.filled <= back) {
            return;
        }
        int at = ((this.index - 1 - back) % HISTORY + HISTORY) % HISTORY;

        float deltaYaw = MathHelper.wrapAngleTo180_float(this.sentYaw[at] - mc.thePlayer.rotationYaw);
        float deltaPitch = this.sentPitch[at] - mc.thePlayer.rotationPitch;

        ScaledResolution sr = new ScaledResolution(mc);
        int width = sr.getScaledWidth();
        int height = sr.getScaledHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        /* The vertical field of view is the setting; the horizontal one follows
           from the aspect ratio, which is why the two axes need different
           pixels-per-degree. */
        double verticalFov = Math.max(1.0, mc.gameSettings.fovSetting);
        double aspect = (double) width / (double) height;
        double horizontalFov = Math.toDegrees(
                2.0 * Math.atan(Math.tan(Math.toRadians(verticalFov) / 2.0) * aspect));
        double perDegreeX = width / horizontalFov;
        double perDegreeY = height / verticalFov;

        double offsetX = deltaYaw * perDegreeX;
        double offsetY = deltaPitch * perDegreeY;
        double magnitude = Math.sqrt(offsetX * offsetX + offsetY * offsetY);
        if (magnitude < this.minOffset.getValue()) {
            return;
        }

        float centreX = width / 2.0F;
        float centreY = height / 2.0F;
        /* Clamped to the screen so a rotation that has swung right around still
           shows which way the server is behind, instead of vanishing. */
        float x = (float) Math.max(2.0, Math.min(width - 2.0, centreX + offsetX));
        float y = (float) Math.max(2.0, Math.min(height - 2.0, centreY + offsetY));

        int alpha = 255;
        if (this.fade.getValue()) {
            /* Strongest right after a flick, easing off as the server catches
               up, so the eye is drawn to the moment the gap actually matters. */
            alpha = (int) Math.max(70.0, Math.min(255.0, magnitude * 6.0));
        }
        float half = this.size.getValue();
        float t = this.thickness.getValue();
        int colour = new Color(255, 96, 96, alpha).getRGB();

        GL11.glPushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        RenderUtil.enableRenderState();
        RenderUtil.drawRect(x - half, y - t / 2.0F, x + half, y + t / 2.0F, colour);
        RenderUtil.drawRect(x - t / 2.0F, y - half, x + t / 2.0F, y + half, colour);
        RenderUtil.disableRenderState();

        if (this.showDistance.getValue()) {
            double degrees = Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
            String text = String.format("%.0f°", degrees);
            mc.fontRendererObj.drawStringWithShadow(text,
                    x + half + 2.0F, y - 4.0F, (alpha << 24) | 0xFF6060);
        }

        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GL11.glPopMatrix();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.delayTicks() + "t"};
    }
}
