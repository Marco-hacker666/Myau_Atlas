package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): eats soup to heal when health is low, with
// an optional inventory hotbar refill.
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.mixin.IAccessorKeyBinding;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.init.Items;
import net.minecraft.item.ItemSoup;
import net.minecraft.item.ItemStack;

public class AutoSoup extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int EAT_DURATION_TICKS = 32;
    private final TimerUtil cooldown = new TimerUtil();
    private final TimerUtil refillTimer = new TimerUtil();
    private boolean eating = false;
    private int eatTicks = 0;
    private int originalSlot = -1;
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"NORMAL", "REFILL"});
    public final PercentProperty health = new PercentProperty("health", 60);
    public final IntProperty delay = new IntProperty("delay", 1000, 0, 5000);
    public final BooleanProperty dropBowls = new BooleanProperty("drop-bowls", true);
    public final IntProperty refillDelay = new IntProperty("refill-delay", 2, 0, 20, () -> mode.getValue() == 1);

    public AutoSoup() {
        super("AutoSoup", false, false, "Automatically eats soup to heal when health is low.");
    }

    @Override
    public void onEnabled() {
        this.eating = false;
        this.eatTicks = 0;
        this.originalSlot = -1;
        this.cooldown.reset();
        this.refillTimer.reset();
    }

    @Override
    public void onDisabled() {
        this.stopEating();
        this.cooldown.reset();
        this.refillTimer.reset();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null || mc.playerController == null) {
            return;
        }
        if (this.eating) {
            this.eatTicks++;
            ItemStack held = mc.thePlayer.getHeldItem();
            boolean finished = held == null || !(held.getItem() instanceof ItemSoup) || this.eatTicks >= EAT_DURATION_TICKS;
            if (finished) {
                this.finishEating();
            }
            return;
        }
        if (this.mode.getValue() == 1) {
            this.handleRefill();
        }
        if (!this.cooldown.hasTimeElapsed(this.delay.getValue())) {
            return;
        }
        if ((float) Math.ceil(mc.thePlayer.getHealth() + mc.thePlayer.getAbsorptionAmount()) / mc.thePlayer.getMaxHealth()
                > (float) this.health.getValue() / 100.0F) {
            return;
        }
        int soupSlot = this.findSoupSlot();
        if (soupSlot == -1) {
            return;
        }
        this.originalSlot = mc.thePlayer.inventory.currentItem;
        mc.thePlayer.inventory.currentItem = soupSlot;
        mc.playerController.updateController();
        ((IAccessorKeyBinding) mc.gameSettings.keyBindUseItem).setPressed(true);
        this.eating = true;
        this.eatTicks = 0;
    }

    private void finishEating() {
        ((IAccessorKeyBinding) mc.gameSettings.keyBindUseItem).setPressed(false);
        ItemStack held = mc.thePlayer != null ? mc.thePlayer.getHeldItem() : null;
        if (this.dropBowls.getValue() && held != null && held.getItem() == Items.bowl) {
            mc.thePlayer.dropOneItem(false);
        }
        if (mc.thePlayer != null && mc.playerController != null && this.originalSlot != -1
                && mc.thePlayer.inventory.currentItem != this.originalSlot) {
            mc.thePlayer.inventory.currentItem = this.originalSlot;
            mc.playerController.updateController();
        }
        this.stopEating();
        this.cooldown.reset();
    }

    private void stopEating() {
        if (mc.gameSettings != null) {
            ((IAccessorKeyBinding) mc.gameSettings.keyBindUseItem).setPressed(false);
        }
        this.eating = false;
        this.eatTicks = 0;
        this.originalSlot = -1;
    }

    private void handleRefill() {
        if (!(mc.currentScreen instanceof GuiInventory)) {
            return;
        }
        if (!this.refillTimer.hasTimeElapsed((long) this.refillDelay.getValue() * 50L)) {
            return;
        }
        if (!this.hasHotbarSpace()) {
            return;
        }
        for (int i = 9; i < 36; i++) {
            ItemStack stack = mc.thePlayer.inventoryContainer.getSlot(i).getStack();
            if (stack != null && stack.getItem() instanceof ItemSoup) {
                mc.playerController.windowClick(0, i, 0, 1, mc.thePlayer);
                this.refillTimer.reset();
                break;
            }
        }
    }

    private int findSoupSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() instanceof ItemSoup) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasHotbarSpace() {
        for (int i = 0; i < 9; i++) {
            if (mc.thePlayer.inventory.getStackInSlot(i) == null) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
