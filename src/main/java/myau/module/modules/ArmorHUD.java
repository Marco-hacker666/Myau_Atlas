package myau.module.modules;

import myau.ui.UiMode;
import myau.ui.hud.HudLayout;
import myau.property.properties.IntProperty;

import myau.event.EventTarget;
import myau.events.Render2DEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;

/** A small armor durability strip above the hotbar. */
public class ArmorHUD extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final BooleanProperty showDurability = new BooleanProperty("durability", true);
    public final BooleanProperty background = new BooleanProperty("background", true);
    /* Added with the HUD editor (2026-10-05): drag it there, or set it here. 0 keeps the original place. */
    public final IntProperty offsetX = new IntProperty("offset-x", 0, -1000, 1000);
    public final IntProperty offsetY = new IntProperty("offset-y", 0, -1000, 1000);

    public ArmorHUD() {
        super("ArmorHUD", false, false, "Shows equipped armor and remaining durability.");
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;

        ScaledResolution resolution = new ScaledResolution(mc);
        int left = resolution.getScaledWidth() / 2 - 42 + this.offsetX.getValue();
        int top = resolution.getScaledHeight() - 54 + this.offsetY.getValue();
        boolean wearing = false;
        for (int slot = 0; slot < 4; slot++) {
            if (mc.thePlayer.inventory.armorItemInSlot(slot) != null) {
                wearing = true;
            }
        }
        if (wearing || HudLayout.isEditing()) {
            HudLayout.report("ArmorHUD", "ArmorHUD", left - 2, top - 2, 83, 33,
                    HudLayout.ints(this.offsetX, 1, this.offsetY, 1));
        }
        /* Backgrounds, then items, then text -- not all three per slot. The
           item overlay switches lighting on again after its durability bar,
           so drawing a damaged piece last left the GL lighting on: everything
           drawn after the HUD, the chat included, came out dark and tinted.
           It showed when the boots came off and the last piece drawn was a
           damaged pair of leggings (2026-10-05). Items are now drawn inside
           the GUI item lighting, as the vanilla hotbar does, and the state is
           put back before any text or anything else is drawn. */
        ItemStack[] pieces = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            pieces[i] = mc.thePlayer.inventory.armorItemInSlot(3 - i);
        }
        if (this.background.getValue()) {
            for (int i = 0; i < 4; i++) {
                if (pieces[i] == null) continue;
                int x = left + i * 21;
                Gui.drawRect(x - 2, top - 2, x + 18, top + 31, UiMode.adapt(0x70000000));
            }
        }
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        RenderHelper.enableGUIStandardItemLighting();
        try {
            for (int i = 0; i < 4; i++) {
                if (pieces[i] == null) continue;
                int x = left + i * 21;
                mc.getRenderItem().renderItemAndEffectIntoGUI(pieces[i], x, top);
                mc.getRenderItem().renderItemOverlayIntoGUI(mc.fontRendererObj, pieces[i], x, top, null);
            }
        } finally {
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableLighting();
            GlStateManager.disableRescaleNormal();
            GlStateManager.enableAlpha();
            GlStateManager.disableBlend();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
        if (this.showDurability.getValue()) {
            for (int i = 0; i < 4; i++) {
                ItemStack stack = pieces[i];
                if (stack == null || !stack.isItemDamaged()) continue;
                int x = left + i * 21;
                int remaining = Math.max(0, stack.getMaxDamage() - stack.getItemDamage());
                int percent = Math.round(remaining * 100.0F / stack.getMaxDamage());
                int color = percent <= 20 ? 0xFFFF5555 : (percent <= 50 ? 0xFFFFCC55 : UiMode.adapt(0xFFE8EDF5));
                String text = percent + "%";
                int textX = x + 8 - mc.fontRendererObj.getStringWidth(text) / 2;
                mc.fontRendererObj.drawStringWithShadow(text, textX, top + 19, color);
            }
        }
    }
}
