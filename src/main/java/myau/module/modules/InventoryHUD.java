/*
 * Ported from OpenSkid (https://github.com/loloshelly102-a11y/OpenSkid, GPL-3.0),
 * src/main/java/openskid/module/modules/InventoryHUD.java, 2026-10-05: package openskid -> myau,
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
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;

import java.awt.Color;

// Armor plus hotbar status panel. Concept adapted from the Expo InventoryHUD module.
public class InventoryHUD extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int SLOT = 18;
    private static final int PAD = 3;

    public final ModeProperty posX = new ModeProperty("position-x", 0, new String[]{"LEFT", "MIDDLE", "RIGHT"});
    public final ModeProperty posY = new ModeProperty("position-y", 1, new String[]{"TOP", "MIDDLE", "BOTTOM"});
    public final IntProperty offX = new IntProperty("offset-x", 4, -500, 500);
    public final IntProperty offY = new IntProperty("offset-y", 30, -500, 500);
    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 2.0F);
    public final BooleanProperty showArmor = new BooleanProperty("armor", true);
    public final BooleanProperty showHotbar = new BooleanProperty("hotbar", true);
    public final BooleanProperty durability = new BooleanProperty("durability", true);
    public final BooleanProperty background = new BooleanProperty("background", true);

    public InventoryHUD() {
        super("InventoryHUD", false, false, "Displays your armor and hotbar items on screen.");
    }

    @Override
    public String[] getSuffix() {
        if (mc.thePlayer == null || mc.thePlayer.inventory == null) {
            return new String[0];
        }
        int count = 0;
        if (showArmor.getValue()) {
            for (ItemStack stack : mc.thePlayer.inventory.armorInventory) {
                if (stack != null) {
                    count++;
                }
            }
        }
        if (showHotbar.getValue()) {
            for (int i = 0; i < 9; i++) {
                if (mc.thePlayer.inventory.mainInventory[i] != null) {
                    count++;
                }
            }
        }
        return new String[]{String.valueOf(count)};
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.thePlayer.inventory == null) {
            return;
        }
        boolean armor = showArmor.getValue();
        boolean hotbar = showHotbar.getValue();
        if (!armor && !hotbar) {
            return;
        }
        int rows = (armor ? 1 : 0) + (hotbar ? 1 : 0);
        int boxW = 9 * SLOT + PAD * 2;
        int boxH = rows * SLOT + PAD * 2;
        float sc = scale.getValue();
        ScaledResolution sr = new ScaledResolution(mc);
        float x = offX.getValue().floatValue();
        switch (posX.getValue()) {
            case 1:
                x += sr.getScaledWidth() / 2.0F - boxW * sc / 2.0F;
                break;
            case 2:
                x = sr.getScaledWidth() - boxW * sc - x;
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
        HudLayout.report("InventoryHUD", "InventoryHUD", x, y, boxW * sc, boxH * sc,
                HudLayout.ints(offX, posX.getValue() == 2 ? -1 : 1, offY, posY.getValue() == 2 ? -1 : 1));
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0F);
        GlStateManager.scale(sc, sc, 1.0F);
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        if (background.getValue()) {
            if (HudPanel.active()) {
                HudPanel.panel(0, 0, boxW, boxH);
            } else {
                RenderUtil.drawRect(0, 0, boxW, boxH, new Color(0, 0, 0, 110).getRGB());
            }
        }
        boolean showDura = durability.getValue();
        int rowY = PAD;
        if (armor) {
            int startX = PAD + (boxW - PAD * 2 - 4 * SLOT) / 2;
            for (int i = 0; i < 4; i++) {
                drawSlot(mc.thePlayer.inventory.armorInventory[3 - i], startX + i * SLOT, rowY, showDura);
            }
            rowY += SLOT;
        }
        if (hotbar) {
            for (int i = 0; i < 9; i++) {
                drawSlot(mc.thePlayer.inventory.mainInventory[i], PAD + i * SLOT, rowY, showDura);
            }
        }
        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    private void drawSlot(ItemStack stack, int x, int y, boolean dura) {
        if (HudPanel.active()) {
            HudPanel.fill(x, y, x + 16, y + 16, 0x14FFFFFF);
        } else {
            RenderUtil.drawRect(x, y, x + 16, y + 16, new Color(255, 255, 255, 18).getRGB());
        }
        if (stack == null) {
            return;
        }
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        RenderUtil.renderItemAndEffectIntoGui3D(stack, x, y);
        mc.getRenderItem().renderItemOverlays(mc.fontRendererObj, stack, x, y);
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableBlend();
        if (dura && stack.isItemStackDamageable() && stack.getItemDamage() > 0 && stack.getMaxDamage() > 0) {
            RenderUtil.drawDurabilityBar(x, y, 1.0F - (float) stack.getItemDamage() / (float) stack.getMaxDamage());
        }
    }
}
