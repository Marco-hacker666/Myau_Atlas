package myau.module.modules;

import myau.ui.impl.clickgui.atlas.HudPanel;
import myau.ui.UiMode;
import myau.ui.hud.HudLayout;
import myau.property.properties.IntProperty;

import myau.event.EventTarget;
import myau.events.Render2DEvent;
import myau.module.Module;
import myau.util.AnimationUtil;
import myau.util.RenderUtil;
import myau.util.shader.BlurUtils;
import myau.util.shader.RoundedUtils;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.client.Minecraft;

import java.awt.Color;

public class Hotbar extends Module {
    protected static final Minecraft mc = Minecraft.getMinecraft();

    private AnimationUtil animationUtil;
    private int lastSlot = -1;
    /* Added with the HUD editor (2026-10-05): drag it there, or set it here. 0 keeps the original place. */
    public final IntProperty offsetX = new IntProperty("offset-x", 0, -1000, 1000);
    public final IntProperty offsetY = new IntProperty("offset-y", 0, -1000, 1000);

    public Hotbar() {
        super("Hotbar", true, false, "Custom Hotbar rendering");
    }

    @Override
    public void onEnabled() {
        animationUtil = new AnimationUtil(AnimationUtil.Easing.EASE_OUT_QUINT, 300);
        lastSlot = -1;
        super.onEnabled();
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || mc.gameSettings.showDebugInfo || mc.thePlayer == null || mc.playerController.isSpectator()) return;

        ScaledResolution sr = new ScaledResolution(mc);
        int middleX = sr.getScaledWidth() / 2;
        int bottomY = sr.getScaledHeight();

        float width = 182;
        float height = 22;
        float startX = middleX - width / 2.0f + this.offsetX.getValue();
        float startY = bottomY - height - 2 + this.offsetY.getValue();
        HudLayout.report("Hotbar", "Hotbar", startX, startY, width, height,
                HudLayout.ints(this.offsetX, 1, this.offsetY, 1));
        
        // Draw Glassmorphic Background
        ThemeStyle widget = ThemeStyle.active(WidgetColors.class);
        if (HudPanel.active()) {
            HudPanel.panel(startX, startY, startX + width, startY + height);
        } else {
            if (RenderFixes.shouldUseShaders()) {
                BlurUtils.prepareBlur();
                RoundedUtils.drawRound(startX, startY, width, height, 8.0F, Color.WHITE);
                BlurUtils.blurEnd(2, 4.0F);
            }

            Color backgroundColor = UiMode.adapt(new Color(15, 15, 15, 100));
            if (widget != null) {
                backgroundColor = widget.fill(backgroundColor, null, 0);
            }

            RoundedUtils.drawRound(startX, startY, width, height, 8.0F, backgroundColor);
        }

        // Selection Box Animation
        int currentItem = mc.thePlayer.inventory.currentItem;
        float targetX = startX + currentItem * 20 + 1;
        
        if (animationUtil == null || lastSlot == -1) {
            animationUtil = new AnimationUtil(AnimationUtil.Easing.EASE_OUT_QUINT, 250);
            animationUtil.run(targetX);
            lastSlot = currentItem;
        }
        
        if (lastSlot != currentItem) {
            animationUtil.run(targetX);
            lastSlot = currentItem;
        }
        
        float highlightX = (float) animationUtil.getValue();

        // Draw Selection Highlight
        Color highlightColor = UiMode.adapt(new Color(255, 255, 255, 60));
        if (widget != null) {
            /* The selection in the theme colour, a little stronger than the bar under it. */
            Color themed = widget.color(highlightColor, null, currentItem);
            highlightColor = new Color(themed.getRed(), themed.getGreen(), themed.getBlue(),
                    Math.min(255, Math.round(widget.fillAlpha255() * (widget.recolours() ? 1.2F : 0.6F))));
        }
        RoundedUtils.drawRound(highlightX, startY + 1, 20, 20, 6.0F, highlightColor);

        // Draw Items
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        for (int i = 0; i < 9; ++i) {
            float itemX = startX + i * 20 + 3;
            float itemY = startY + 3;
            
            ItemStack stack = mc.thePlayer.inventory.mainInventory[i];
            if (stack != null) {
                RenderUtil.renderItemAndEffectIntoGui3D(stack, (int) itemX, (int) itemY);
                mc.getRenderItem().renderItemOverlays(mc.fontRendererObj, stack, (int)itemX, (int)itemY);
            }
        }
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableBlend();
    }
}
