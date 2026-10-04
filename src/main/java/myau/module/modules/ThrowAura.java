package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.MoveInputEvent;
import myau.events.UpdateEvent;
import myau.management.RotationState;
import myau.module.Module;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.ItemUtil;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import myau.util.RotationUtil;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;

public class ThrowAura extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final IntProperty cooldown = new IntProperty("cooldown", 500, 0, 2000);
    public final FloatProperty maxRange = new FloatProperty("max-range", 20.0F, 3.0F, 64.0F);
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "REAL"});
    private final TimerUtil timer = new TimerUtil();
    private float moveYaw = Float.NaN;

    public ThrowAura() {
        super("ThrowAura", false, true, "Throw ur ball to the enemy");
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) return;
        if (event.getType() != EventType.PRE) return;
        moveYaw = Float.NaN;

        KillAura killAura = (KillAura) myau.Myau.moduleManager.modules.get(KillAura.class);
        if (killAura == null || !killAura.isEnabled()) return;

        EntityLivingBase target = killAura.getTarget();
        if (target == null) return;

        double distance = mc.thePlayer.getDistanceToEntity(target);
        if (distance > killAura.attackRange.getValue() && distance <= maxRange.getValue()) {
            int projectileCount = ItemUtil.findInventorySlot(ItemUtil.ItemType.Projectile);
            if (projectileCount > 0 && timer.hasTimeElapsed(cooldown.getValue().longValue())) {
                int projectileSlot = findProjectileHotbarSlot();
                if (projectileSlot != -1) {
                    float[] rotations = RotationUtil.getRotationsToBox(
                            target.getEntityBoundingBox(),
                            event.getYaw(),
                            event.getPitch(),
                            180.0F,
                            0.0F
                    );
                    event.setRotation(rotations[0], rotations[1], 1);
                    // Move by the yaw the packet reports (ENGINEERING-NOTES 4.12).
                    moveYaw = moveFix.getValue() != 0 ? rotations[0] : mc.thePlayer.rotationYaw;
                    event.setPervRotation(moveYaw, 1);
                    if (moveFix.getModeString().equals("REAL")) {
                        /* REAL: the camera turns as well, spread over this tick's frames by
                           RotationManager; the mouse still works (not forced). */
                        Myau.rotationManager.setRotation(rotations[0], rotations[1], 1, false);
                    }
                    int originalSlot = mc.thePlayer.inventory.currentItem;
                    if (projectileSlot != originalSlot) {
                        PacketUtil.sendPacket(new C09PacketHeldItemChange(projectileSlot));
                    }
                    PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(mc.thePlayer.inventory.getStackInSlot(projectileSlot)));
                    if (projectileSlot != originalSlot) {
                        PacketUtil.sendPacket(new C09PacketHeldItemChange(originalSlot));
                    }

                    timer.reset();
                }
            }
        }
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled()) return;
        // Only this module's own rotation: KillAura runs later at the same
        // priority and takes over when it rotates too.
        if (moveFix.getValue() == 1
                && RotationState.isActived()
                && RotationState.getPriority() == 1
                && RotationState.getSmoothedYaw() == moveYaw
                && MoveUtil.isForwardPressed()) {
            MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
        }
    }

    private int findProjectileHotbarSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (ItemUtil.isProjectile(stack)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String[] getSuffix() {
        int count = ItemUtil.findInventorySlot(ItemUtil.ItemType.Projectile);
        return new String[]{String.valueOf(count)};
    }
}