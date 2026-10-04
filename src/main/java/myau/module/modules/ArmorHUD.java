package myau.module.modules;

import myau.event.EventTarget;
import myau.events.Render2DEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.item.ItemStack;

/** A small armor durability strip above the hotbar. */
public class ArmorHUD extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final BooleanProperty showDurability = new BooleanProperty("durability", true);
    public final BooleanProperty background = new BooleanProperty("background", true);

    public ArmorHUD() {
        super("ArmorHUD", false, false, "Shows equipped armor and remaining durability.");
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;

        ScaledResolution resolution = new ScaledResolution(mc);
        int left = resolution.getScaledWidth() / 2 - 42;
        int top = resolution.getScaledHeight() - 54;
        for (int i = 0; i < 4; i++) {
            ItemStack stack = mc.thePlayer.inventory.armorItemInSlot(3 - i);
            if (stack == null) continue;

            int x = left + i * 21;
            if (this.background.getValue()) {
                Gui.drawRect(x - 2, top - 2, x + 18, top + 31, 0x70000000);
            }
            mc.getRenderItem().renderItemAndEffectIntoGUI(stack, x, top);
            mc.getRenderItem().renderItemOverlayIntoGUI(mc.fontRendererObj, stack, x, top, null);

            if (this.showDurability.getValue() && stack.isItemDamaged()) {
                int remaining = Math.max(0, stack.getMaxDamage() - stack.getItemDamage());
                int percent = Math.round(remaining * 100.0F / stack.getMaxDamage());
                int color = percent <= 20 ? 0xFFFF5555 : (percent <= 50 ? 0xFFFFCC55 : 0xFFE8EDF5);
                String text = percent + "%";
                int textX = x + 8 - mc.fontRendererObj.getStringWidth(text) / 2;
                mc.fontRendererObj.drawStringWithShadow(text, textX, top + 19, color);
            }
        }
    }
}
