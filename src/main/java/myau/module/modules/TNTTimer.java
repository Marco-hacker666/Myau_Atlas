/*
 * Ported from OpenSkid (https://github.com/loloshelly102-a11y/OpenSkid, GPL-3.0),
 * src/main/java/openskid/module/modules/TNTTimer.java, 2026-10-05: package openskid -> myau,
 * OpenSkid -> Myau. Changes after that are marked in comments.
 */
package myau.module.modules;

import myau.event.EventTarget;
import myau.events.Render3DEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityTNTPrimed;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.Locale;

// Countdown labels above primed TNT. Concept adapted from the raven-bS TNTTimer module.
public class TNTTimer extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final float FULL_FUSE = 80.0f;

    public final FloatProperty scale = new FloatProperty("Scale", 1.0f, 0.5f, 3.0f);
    public final FloatProperty radius = new FloatProperty("Radius", 64.0f, 8.0f, 128.0f);
    public final BooleanProperty shadow = new BooleanProperty("Shadow", true);
    public final BooleanProperty background = new BooleanProperty("Background", true);

    private int tracked = 0;

    public TNTTimer() {
        super("TNTTimer", false, false, "Shows countdown labels above primed TNT.");
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.valueOf(tracked)};
    }

    @Override
    public void onEnabled() {
        tracked = 0;
    }

    @Override
    public void onDisabled() {
        tracked = 0;
    }

    @EventTarget
    public void onRender(Render3DEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        float partialTicks = event.getPartialTicks();
        RenderManager rm = mc.getRenderManager();
        float maxDist = radius.getValue();
        int count = 0;
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityTNTPrimed)) {
                continue;
            }
            EntityTNTPrimed tnt = (EntityTNTPrimed) entity;
            if (tnt.isDead || tnt.fuse < 1) {
                continue;
            }
            if (mc.thePlayer.getDistanceToEntity(tnt) > maxDist) {
                continue;
            }
            count++;
            double x = tnt.lastTickPosX + (tnt.posX - tnt.lastTickPosX) * partialTicks - rm.viewerPosX;
            double y = tnt.lastTickPosY + (tnt.posY - tnt.lastTickPosY) * partialTicks - rm.viewerPosY + tnt.height + 0.6;
            double z = tnt.lastTickPosZ + (tnt.posZ - tnt.lastTickPosZ) * partialTicks - rm.viewerPosZ;
            renderTimer(rm, x, y, z, tnt.fuse, partialTicks);
        }
        tracked = count;
    }

    private void renderTimer(RenderManager rm, double x, double y, double z, int fuse, float partialTicks) {
        float seconds = Math.max(0.05f, (fuse - partialTicks) / 20.0f);
        String text = String.format(Locale.US, "%.1f", seconds);
        float heat = Math.min(1.0f, fuse / FULL_FUSE);
        int color = new Color(1.0f - heat, heat, 0.0f).getRGB();

        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x, (float) y, (float) z);
        GlStateManager.rotate(-rm.playerViewY, 0.0f, 1.0f, 0.0f);
        GlStateManager.rotate(rm.playerViewX, 1.0f, 0.0f, 0.0f);
        float s = 0.02666667f * scale.getValue();
        GlStateManager.scale(-s, -s, s);
        GlStateManager.disableLighting();
        GlStateManager.depthMask(false);
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);

        int halfW = mc.fontRendererObj.getStringWidth(text) / 2;
        if (background.getValue()) {
            GlStateManager.disableTexture2D();
            Tessellator tess = Tessellator.getInstance();
            WorldRenderer wr = tess.getWorldRenderer();
            wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            wr.pos(-halfW - 1, -1, 0).color(0.0f, 0.0f, 0.0f, 0.25f).endVertex();
            wr.pos(-halfW - 1, 8, 0).color(0.0f, 0.0f, 0.0f, 0.25f).endVertex();
            wr.pos(halfW + 1, 8, 0).color(0.0f, 0.0f, 0.0f, 0.25f).endVertex();
            wr.pos(halfW + 1, -1, 0).color(0.0f, 0.0f, 0.0f, 0.25f).endVertex();
            tess.draw();
            GlStateManager.enableTexture2D();
        }
        mc.fontRendererObj.drawString(text, -halfW, 0, color, shadow.getValue());

        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.popMatrix();
    }
}
