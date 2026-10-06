/*
 * Ported from OpenSkid (https://github.com/loloshelly102-a11y/OpenSkid, GPL-3.0),
 * src/main/java/openskid/module/modules/ItemTags.java, 2026-10-05: package openskid -> myau,
 * OpenSkid -> Myau. Changes after that are marked in comments.
 */
package myau.module.modules;

import myau.event.EventTarget;
import myau.events.Render3DEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.util.RenderUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;

// Floating name plus count tags over dropped items. Concept adapted from the MiauMinus ItemESP count label.
public class ItemTags extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final IntProperty distance = new IntProperty("distance", 32, 8, 64);
    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 2.0F);
    public final BooleanProperty showName = new BooleanProperty("show-name", true);
    public final BooleanProperty showCount = new BooleanProperty("show-count", true);
    public final BooleanProperty background = new BooleanProperty("background", true);
    public final BooleanProperty shadow = new BooleanProperty("shadow", true);

    private int tagCount = 0;

    public ItemTags() {
        super("ItemTags", false, false, "Shows item names and counts above dropped items.");
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.valueOf(tagCount)};
    }

    @Override
    public void onEnabled() {
        tagCount = 0;
    }

    @Override
    public void onDisabled() {
        tagCount = 0;
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null || mc.getRenderViewEntity() == null) {
            return;
        }
        boolean names = showName.getValue();
        boolean counts = showCount.getValue();
        if (!names && !counts) {
            tagCount = 0;
            return;
        }
        float maxDist = distance.getValue().floatValue();
        double maxSq = (double) maxDist * (double) maxDist;
        float textScale = 0.025F * scale.getValue();
        boolean bg = background.getValue();
        boolean sh = shadow.getValue();
        double viewerX = mc.getRenderManager().viewerPosX;
        double viewerY = mc.getRenderManager().viewerPosY;
        double viewerZ = mc.getRenderManager().viewerPosZ;
        float viewYaw = mc.getRenderManager().playerViewY;
        float viewPitch = mc.getRenderManager().playerViewX;
        float flip = mc.gameSettings.thirdPersonView == 2 ? -1.0F : 1.0F;
        int shown = 0;
        for (Entity entity : TeamUtil.getLoadedEntitiesSorted()) {
            if (!(entity instanceof EntityItem)) {
                continue;
            }
            if (entity.ticksExisted < 3) {
                continue;
            }
            if (entity.getDistanceSqToEntity(mc.getRenderViewEntity()) > maxSq) {
                continue;
            }
            if (!entity.ignoreFrustumCheck && !RenderUtil.isInViewFrustum(entity.getEntityBoundingBox(), 0.125)) {
                continue;
            }
            ItemStack stack = ((EntityItem) entity).getEntityItem();
            if (stack == null || stack.getItem() == null || stack.stackSize <= 0) {
                continue;
            }
            String text = names ? stack.getDisplayName() : "";
            if (counts && stack.stackSize > 1) {
                text = text.isEmpty() ? ("x" + stack.stackSize) : (text + " x" + stack.stackSize);
            }
            if (text.isEmpty()) {
                continue;
            }
            double x = RenderUtil.lerpDouble(entity.posX, entity.lastTickPosX, event.getPartialTicks()) - viewerX;
            double y = RenderUtil.lerpDouble(entity.posY, entity.lastTickPosY, event.getPartialTicks()) + 0.6 - viewerY;
            double z = RenderUtil.lerpDouble(entity.posZ, entity.lastTickPosZ, event.getPartialTicks()) - viewerZ;
            GlStateManager.pushMatrix();
            GlStateManager.translate((float) x, (float) y, (float) z);
            GlStateManager.rotate(-viewYaw, 0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(viewPitch, flip, 0.0F, 0.0F);
            GlStateManager.scale(-textScale, -textScale, textScale);
            GlStateManager.disableLighting();
            GlStateManager.depthMask(false);
            GlStateManager.disableDepth();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
            int halfW = mc.fontRendererObj.getStringWidth(text) / 2;
            if (bg) {
                drawTagBackground(halfW);
            }
            mc.fontRendererObj.drawString(text, -halfW, 0, 0xFFFFFFFF, sh);
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GlStateManager.enableLighting();
            GlStateManager.disableBlend();
            GlStateManager.resetColor();
            GlStateManager.popMatrix();
            shown++;
        }
        tagCount = shown;
    }

    private void drawTagBackground(int halfW) {
        GlStateManager.disableTexture2D();
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(-halfW - 1, -1, 0).color(0.0F, 0.0F, 0.0F, 0.3F).endVertex();
        wr.pos(-halfW - 1, 9, 0).color(0.0F, 0.0F, 0.0F, 0.3F).endVertex();
        wr.pos(halfW + 1, 9, 0).color(0.0F, 0.0F, 0.0F, 0.3F).endVertex();
        wr.pos(halfW + 1, -1, 0).color(0.0F, 0.0F, 0.0F, 0.3F).endVertex();
        tess.draw();
        GlStateManager.enableTexture2D();
    }
}
