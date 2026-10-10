package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): marks the block you will land on while
// falling, with a distance readout.
//
// Substitution: OpenSkid's RenderUtil.drawCircle has no counterpart in Myau's
// RenderUtil, so the CIRCLE marker is drawn here as a GL line loop in the same
// render-state bracket; the BOX marker uses Myau's drawBlockBoundingBox.
import java.util.Locale;

import myau.event.EventTarget;
import myau.events.Render2DEvent;
import myau.events.Render3DEvent;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.BlockPos;
import org.lwjgl.opengl.GL11;

public class FallIndicator extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final FloatProperty minFall = new FloatProperty("min-fall", 3.0F, 1.0F, 10.0F);
    public final IntProperty maxDistance = new IntProperty("max-distance", 48, 8, 64);
    public final ModeProperty marker = new ModeProperty("marker", 0, new String[]{"CIRCLE", "BOX", "BOTH"});
    public final ColorProperty color = new ColorProperty("color", 0xFF5555);
    public final BooleanProperty showDistance = new BooleanProperty("show-distance", true);

    private float lastDistance = -1.0F;

    public FallIndicator() {
        super("FallIndicator", false, false, "Marks your landing spot and distance while falling.");
    }

    @Override
    public String[] getSuffix() {
        return lastDistance >= 0.0F
                ? new String[]{String.format(Locale.US, "%.1fm", lastDistance)}
                : new String[0];
    }

    @Override
    public void onEnabled() {
        lastDistance = -1.0F;
    }

    @Override
    public void onDisabled() {
        lastDistance = -1.0F;
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        lastDistance = -1.0F;
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (mc.thePlayer.onGround || mc.thePlayer.capabilities.isFlying) {
            return;
        }
        if (mc.thePlayer.fallDistance < minFall.getValue()) {
            return;
        }
        int maxD = maxDistance.getValue();
        int bx = (int) Math.floor(mc.thePlayer.posX);
        int bz = (int) Math.floor(mc.thePlayer.posZ);
        int top = (int) Math.floor(mc.thePlayer.posY) - 1;
        int groundY = -1;
        for (int y = top; y > top - maxD && y > 0; y--) {
            BlockPos pos = new BlockPos(bx, y, bz);
            if (!mc.theWorld.isBlockLoaded(pos, false)) {
                return;
            }
            if (mc.theWorld.getBlockState(pos).getBlock().getMaterial().isSolid()) {
                groundY = y + 1;
                break;
            }
        }
        if (groundY < 0) {
            return;
        }
        float dist = (float) (mc.thePlayer.posY - groundY);
        if (dist > maxD) {
            return;
        }
        lastDistance = dist;
        int rgb = color.getValue();
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        double renderX = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
        double renderY = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
        double renderZ = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();
        RenderUtil.enableRenderState();
        int mode = marker.getValue();
        if (mode == 0 || mode == 2) {
            drawRing(bx + 0.5 - renderX, groundY + 0.06 - renderY, bz + 0.5 - renderZ,
                    0.6, 32, r, g, b, 0.8F);
        }
        if (mode == 1 || mode == 2) {
            RenderUtil.drawBlockBoundingBox(new BlockPos(bx, groundY - 1, bz), 1.0, r, g, b, 255, 1.5F);
        }
        RenderUtil.disableRenderState();
    }

    /** A ring on the ground plane, the marker OpenSkid's drawCircle drew. */
    private static void drawRing(double centerX, double centerY, double centerZ, double radius,
                                 int segments, int red, int green, int blue, float alpha) {
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableDepth();
        GL11.glLineWidth(2.0F);
        GL11.glColor4f(red / 255.0F, green / 255.0F, blue / 255.0F, alpha);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < segments; i++) {
            double angle = Math.PI * 2.0 * i / segments;
            GL11.glVertex3d(centerX + Math.cos(angle) * radius, centerY, centerZ + Math.sin(angle) * radius);
        }
        GL11.glEnd();
        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!isEnabled() || !showDistance.getValue() || lastDistance < 0.0F || mc.thePlayer == null) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        String text = String.format(Locale.US, "Landing %.1fm", lastDistance);
        int w = mc.fontRendererObj.getStringWidth(text);
        mc.fontRendererObj.drawString(text, (sr.getScaledWidth() - w) / 2.0F,
                sr.getScaledHeight() / 2.0F + 12.0F, 0xFF000000 | color.getValue(), true);
    }
}
