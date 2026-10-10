package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): aims and throws projectiles at enemies
// automatically, either at KillAura's target or at the closest one.
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.ItemUtil;
import myau.util.PacketUtil;
import myau.util.RotationUtil;
import myau.util.TeamUtil;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.util.AxisAlignedBB;

public class ProjectileAimBot extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"KILLAURA", "CLOSEST"});
    public final FloatProperty maxRange = new FloatProperty("max-range", 20.0F, 3.0F, 64.0F);
    public final FloatProperty minDistance = new FloatProperty("min-distance", 3.5F, 1.0F, 10.0F);
    public final IntProperty fov = new IntProperty("fov", 90, 20, 180);
    public final BooleanProperty prediction = new BooleanProperty("prediction", true);
    public final IntProperty predictedTicks = new IntProperty("predicted-ticks", 2, 0, 10, () -> this.prediction.getValue());
    public final IntProperty cooldown = new IntProperty("cooldown", 500, 0, 2000);
    public final BooleanProperty ignoreTeammates = new BooleanProperty("ignore-teammates", true);
    public final BooleanProperty aimInvis = new BooleanProperty("aim-invis", false);

    private final TimerUtil timer = new TimerUtil();

    public ProjectileAimBot() {
        super("ProjectileAimBot", false, false, "Aims and throws projectiles at enemies automatically.");
    }

    @Override
    public void onEnabled() {
        this.timer.reset();
    }

    @Override
    public void onDisabled() {
        this.timer.reset();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (event.getType() != EventType.PRE) {
            return;
        }

        KillAura killAura = (KillAura) Myau.moduleManager.getModule(KillAura.class);

        EntityLivingBase target = this.resolveTarget(killAura);
        if (target == null || target.isDead) {
            return;
        }

        double distance = mc.thePlayer.getDistanceToEntity(target);
        float meleeRange = killAura != null ? killAura.attackRange.getValue() : 3.0F;
        float min = Math.max(meleeRange, this.minDistance.getValue());
        if (distance <= min || distance > this.maxRange.getValue()) {
            return;
        }

        int projectileCount = ItemUtil.findInventorySlot(ItemUtil.ItemType.Projectile);
        if (projectileCount <= 0 || !this.timer.hasTimeElapsed(this.cooldown.getValue().longValue())) {
            return;
        }

        int projectileSlot = this.findProjectileHotbarSlot();
        if (projectileSlot == -1) {
            return;
        }

        AxisAlignedBB box = target.getEntityBoundingBox();
        if (this.prediction.getValue() && this.predictedTicks.getValue() > 0 && target instanceof EntityPlayer) {
            int ticks = this.predictedTicks.getValue();
            box = box.offset(target.motionX * ticks, target.motionY * ticks * 0.5, target.motionZ * ticks);
        }

        float[] rotations = RotationUtil.getRotationsToBox(
                box,
                event.getYaw(),
                event.getPitch(),
                180.0F,
                0.0F
        );
        event.setRotation(rotations[0], rotations[1], 1);

        int originalSlot = mc.thePlayer.inventory.currentItem;
        if (projectileSlot != originalSlot) {
            PacketUtil.sendPacket(new C09PacketHeldItemChange(projectileSlot));
        }
        PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(mc.thePlayer.inventory.getStackInSlot(projectileSlot)));
        if (projectileSlot != originalSlot) {
            PacketUtil.sendPacket(new C09PacketHeldItemChange(originalSlot));
        }

        this.timer.reset();
    }

    private EntityLivingBase resolveTarget(KillAura killAura) {
        if (this.mode.getValue() == 0) {
            if (killAura == null || !killAura.isEnabled()) {
                return null;
            }
            EntityLivingBase auraTarget = killAura.getTarget();
            if (!(auraTarget instanceof EntityPlayer)) {
                return null;
            }
            if (!this.isValidTarget((EntityPlayer) auraTarget)) {
                return null;
            }
            return auraTarget;
        }
        return this.findClosestTarget();
    }

    private EntityLivingBase findClosestTarget() {
        EntityPlayer best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == null || player == mc.thePlayer) {
                continue;
            }
            if (!this.isValidTarget(player)) {
                continue;
            }
            if (mc.thePlayer.getDistanceToEntity(player) > this.maxRange.getValue()) {
                continue;
            }
            double distSq = mc.thePlayer.getDistanceSqToEntity(player);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = player;
            }
        }
        return best;
    }

    private boolean isValidTarget(EntityPlayer player) {
        if (player.isDead || player.getHealth() <= 0.0F) {
            return false;
        }
        if (player.isInvisible() && !this.aimInvis.getValue()) {
            return false;
        }
        if (this.ignoreTeammates.getValue() && TeamUtil.isSameTeam(player)) {
            return false;
        }
        if (TeamUtil.isFriend(player) || TeamUtil.isBot(player)) {
            return false;
        }
        if (RotationUtil.angleToEntity(player) > this.fov.getValue()) {
            return false;
        }
        return true;
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
}
