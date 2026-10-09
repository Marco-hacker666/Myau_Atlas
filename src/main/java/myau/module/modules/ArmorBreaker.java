package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): swaps to an axe mid-fight to shred armor.
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.util.ItemUtil;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;

public class ArmorBreaker extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double RANGE = 5.0;
    private static final long SWITCH_BACK_MS = 300L;
    private final TimerUtil switchTimer = new TimerUtil();
    private int prevSlot = -1;
    public final BooleanProperty onlyWhenAttacking = new BooleanProperty("only-when-attacking", true);
    public final BooleanProperty swapBack = new BooleanProperty("swap-back", true);

    public ArmorBreaker() {
        super("ArmorBreaker", false, false, "Swaps to an axe mid-fight to shred armor.");
    }

    @Override
    public void onEnabled() {
        this.prevSlot = -1;
        this.switchTimer.reset();
    }

    @Override
    public void onDisabled() {
        this.restoreSlot();
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND) {
            return;
        }
        if (!(event.getPacket() instanceof C02PacketUseEntity)) {
            return;
        }
        if (((C02PacketUseEntity) event.getPacket()).getAction() != Action.ATTACK) {
            return;
        }
        if (mc.thePlayer == null || mc.playerController == null) {
            return;
        }
        if (this.onlyWhenAttacking.getValue() && !this.isAttacking()) {
            return;
        }
        if (!this.isTargetInRange()) {
            return;
        }
        int axeSlot = this.findBestAxeSlot();
        if (axeSlot == -1 || axeSlot == mc.thePlayer.inventory.currentItem) {
            return;
        }
        if (this.prevSlot == -1) {
            this.prevSlot = mc.thePlayer.inventory.currentItem;
        }
        mc.thePlayer.inventory.currentItem = axeSlot;
        mc.playerController.updateController();
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        this.switchTimer.reset();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (!this.swapBack.getValue() || this.prevSlot == -1) {
            return;
        }
        if (!this.switchTimer.hasTimeElapsed(SWITCH_BACK_MS)) {
            return;
        }
        this.restoreSlot();
    }

    private void restoreSlot() {
        if (this.prevSlot != -1 && mc.thePlayer != null && mc.playerController != null) {
            mc.thePlayer.inventory.currentItem = this.prevSlot;
            mc.playerController.updateController();
            ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        }
        this.prevSlot = -1;
    }

    private boolean isAttacking() {
        return mc.gameSettings.keyBindAttack.isKeyDown() || mc.thePlayer.isSwingInProgress;
    }

    private boolean isTargetInRange() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        EntityLivingBase target = killAura == null ? null : killAura.getTarget();
        if (target != null) {
            return mc.thePlayer.getDistanceToEntity(target) <= RANGE;
        }
        return mc.objectMouseOver != null
                && mc.objectMouseOver.entityHit instanceof EntityLivingBase
                && mc.thePlayer.getDistanceToEntity(mc.objectMouseOver.entityHit) <= RANGE;
    }

    private int findBestAxeSlot() {
        int bestSlot = -1;
        double bestScore = -1.0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack == null || !(stack.getItem() instanceof ItemAxe)) {
                continue;
            }
            double score = ItemUtil.getAttackBonus(stack);
            if (stack.isItemStackDamageable() && stack.getMaxDamage() > 0) {
                score += (double) (stack.getMaxDamage() - stack.getItemDamage()) / (double) stack.getMaxDamage();
            }
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }
}
