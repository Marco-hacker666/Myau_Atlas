package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): automatically equips the best armor from the
// inventory while an inventory screen is open. Myau's InvManager covers the same
// ground for hotbar play; this is kept as its own module on purpose.
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.ItemUtil;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;

public class AutoArmor extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final TimerUtil timer = new TimerUtil();
    private int armorType = 0;
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"NORMAL", "CAREFUL"});
    public final IntProperty delay = new IntProperty("delay", 100, 0, 1000);
    public final BooleanProperty smartSwap = new BooleanProperty("smart-swap", true);
    public final IntProperty minDurability = new IntProperty("min-durability", 30, 0, 500, () -> this.mode.getValue() == 1);

    public AutoArmor() {
        super("AutoArmor", false, false, "Automatically equips the best armor from your inventory.");
    }

    @Override
    public void onEnabled() {
        this.armorType = 0;
        this.timer.reset();
    }

    @Override
    public void onDisabled() {
        this.armorType = 0;
        this.timer.reset();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.playerController == null) {
            return;
        }
        if (!(mc.currentScreen instanceof GuiInventory)) {
            return;
        }
        if (!this.timer.hasTimeElapsed(this.delay.getValue().longValue())) {
            return;
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            int type = this.armorType;
            this.armorType = (this.armorType + 1) % 4;
            if (this.tryEquip(type)) {
                this.timer.reset();
                break;
            }
        }
    }

    private boolean tryEquip(int type) {
        ItemStack current = mc.thePlayer.inventory.armorInventory[type];
        double bestScore = current == null ? -1.0 : this.getScore(current);
        int bestSlot = -1;
        for (int i = 9; i < 45; i++) {
            ItemStack stack = mc.thePlayer.inventoryContainer.getSlot(i).getStack();
            if (stack == null || !(stack.getItem() instanceof ItemArmor)) {
                continue;
            }
            if (((ItemArmor) stack.getItem()).armorType != 3 - type) {
                continue;
            }
            if (this.mode.getValue() == 1 && !this.passesDurability(stack)) {
                continue;
            }
            double score = this.getScore(stack);
            if (this.smartSwap.getValue() ? score > bestScore : score >= bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        if (bestSlot == -1) {
            return false;
        }
        mc.playerController.windowClick(0, bestSlot, 0, 1, mc.thePlayer);
        return true;
    }

    private boolean passesDurability(ItemStack stack) {
        if (stack.getMaxDamage() <= 0) {
            return true;
        }
        return stack.getMaxDamage() - stack.getItemDamage() >= this.minDurability.getValue();
    }

    private double getScore(ItemStack stack) {
        double score = ItemUtil.getArmorProtection(stack) * 100.0;
        if (stack.getMaxDamage() > 0) {
            score += (double) (stack.getMaxDamage() - stack.getItemDamage()) / 100.0;
        }
        return score;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString(), this.delay.getValue() + "ms"};
    }
}
