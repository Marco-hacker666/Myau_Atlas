package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): switches to the strongest weapon when
// attacking, with optional enchant weighting and a switch-back mode.
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.ItemUtil;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;

public class AutoWeapon extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final TimerUtil switchTimer = new TimerUtil();
    private int prevSlot = -1;
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"NORMAL", "SWITCH"});
    public final ModeProperty items = new ModeProperty("items", 0, new String[]{"SWORD", "SWORD_AXE", "SWORD_AXE_TOOLS", "ALL"});
    public final BooleanProperty customWeights = new BooleanProperty("custom-weights", false);
    public final IntProperty damageWeight = new IntProperty("damage-weight", 70, 1, 100, () -> this.customWeights.getValue());
    public final IntProperty knockbackWeight = new IntProperty("knockback-weight", 20, 0, 100, () -> this.customWeights.getValue());
    public final IntProperty fireWeight = new IntProperty("fire-weight", 20, 0, 100, () -> this.customWeights.getValue());
    public final BooleanProperty spoof = new BooleanProperty("spoof", false);
    public final IntProperty switchBackDelay = new IntProperty("switch-back-delay", 500, 1, 2000, () -> this.mode.getValue() == 1);
    public final BooleanProperty onlyAura = new BooleanProperty("only-aura", false);

    public AutoWeapon() {
        super("AutoWeapon", false, false, "Automatically switches to your strongest weapon when attacking.");
    }

    @Override
    public void onEnabled() {
        this.prevSlot = -1;
        this.switchTimer.reset();
    }

    @Override
    public void onDisabled() {
        this.restoreSlot();
        this.switchTimer.reset();
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND) {
            return;
        }
        if (!(event.getPacket() instanceof C02PacketUseEntity)) {
            return;
        }
        C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
        if (packet.getAction() != Action.ATTACK) {
            return;
        }
        if (mc.thePlayer == null || mc.playerController == null) {
            return;
        }
        if (this.onlyAura.getValue() && !this.isKillAuraActive()) {
            return;
        }
        int bestSlot = this.findBestSlot();
        if (bestSlot == -1 || bestSlot == mc.thePlayer.inventory.currentItem) {
            return;
        }
        if (this.prevSlot == -1) {
            this.prevSlot = mc.thePlayer.inventory.currentItem;
        }
        mc.thePlayer.inventory.currentItem = bestSlot;
        mc.playerController.updateController();
        if (this.spoof.getValue()) {
            ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        }
        if (this.mode.getValue() == 0) {
            this.prevSlot = -1;
        } else {
            this.switchTimer.reset();
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (this.mode.getValue() != 1 || this.prevSlot == -1) {
            return;
        }
        if (!this.switchTimer.hasTimeElapsed(this.switchBackDelay.getValue())) {
            return;
        }
        this.restoreSlot();
    }

    private void restoreSlot() {
        if (this.prevSlot != -1 && mc.thePlayer != null && mc.playerController != null) {
            mc.thePlayer.inventory.currentItem = this.prevSlot;
            mc.playerController.updateController();
            if (this.spoof.getValue()) {
                ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
            }
        }
        this.prevSlot = -1;
    }

    private int findBestSlot() {
        int bestSlot = -1;
        double bestScore = -1.0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (!this.isWeapon(stack)) {
                continue;
            }
            double score = this.getScore(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private boolean isWeapon(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return false;
        }
        Item item = stack.getItem();
        switch (this.items.getValue()) {
            case 0:
                return item instanceof ItemSword;
            case 1:
                return item instanceof ItemSword || item instanceof ItemAxe;
            case 2:
                return item instanceof ItemSword || item instanceof ItemTool;
            default:
                return true;
        }
    }

    private double getScore(ItemStack stack) {
        double base = ItemUtil.getAttackBonus(stack);
        if (!this.customWeights.getValue()) {
            return base;
        }
        int knockback = EnchantmentHelper.getEnchantmentLevel(Enchantment.knockback.effectId, stack);
        int fire = EnchantmentHelper.getEnchantmentLevel(Enchantment.fireAspect.effectId, stack);
        return base * (double) this.damageWeight.getValue()
                + (double) knockback * (double) this.knockbackWeight.getValue()
                + (double) fire * (double) this.fireWeight.getValue();
    }

    private boolean isKillAuraActive() {
        KillAura killAura = (KillAura) Myau.moduleManager.getModule(KillAura.class);
        return killAura != null && killAura.isEnabled();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
