package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): spacing tracker holding a preferred range
// band, nudging move input instead of rotating.
import myau.Myau;
import myau.event.EventTarget;
import myau.events.MoveInputEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.RotationUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

public class ForwardTrack extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Hold", "Follow"});
    public final FloatProperty minRange = new FloatProperty("min-range", 2.6F, 1.0F, 6.0F);
    public final FloatProperty maxRange = new FloatProperty("max-range", 3.4F, 1.0F, 6.0F);
    public final BooleanProperty requireInput = new BooleanProperty("require-input", true);
    public final BooleanProperty auraOnly = new BooleanProperty("aura-only", false);
    public final IntProperty followDelay = new IntProperty("follow-delay", 0, 0, 10, () -> this.mode.getValue() == 1);

    private EntityLivingBase current;
    private int followTicks;

    public ForwardTrack() {
        super("ForwardTrack", false, false, "Adjusts movement to hold a set range from your target.");
    }

    @Override
    public void onEnabled() {
        this.current = null;
        this.followTicks = 0;
    }

    @Override
    public void onDisabled() {
        this.current = null;
        this.followTicks = 0;
    }

    private EntityLivingBase resolveTarget() {
        KillAura killAura = (KillAura) Myau.moduleManager.getModule(KillAura.class);
        if (killAura != null && killAura.isEnabled() && killAura.getTarget() != null) {
            EntityLivingBase aura = killAura.getTarget();
            if (aura != null && !aura.isDead && TeamUtil.isEntityLoaded(aura)) {
                return aura;
            }
        }
        if (this.auraOnly.getValue()) {
            return null;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            return null;
        }
        EntityLivingBase best = null;
        double bestDist = 8.0D;
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityLivingBase)) {
                continue;
            }
            if (entity == mc.thePlayer) {
                continue;
            }
            EntityLivingBase living = (EntityLivingBase) entity;
            if (living.isDead) {
                continue;
            }
            if (living instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) living;
                if (TeamUtil.isFriend(player) || TeamUtil.isSameTeam(player) || TeamUtil.isBot(player)) {
                    continue;
                }
            } else {
                continue;
            }
            double dist = mc.thePlayer.getDistanceToEntity(living);
            if (dist < bestDist) {
                bestDist = dist;
                best = living;
            }
        }
        return best;
    }

    private boolean hasInput() {
        return mc.thePlayer.movementInput.moveForward != 0.0F
                || mc.thePlayer.movementInput.moveStrafe != 0.0F;
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        this.current = this.resolveTarget();
        if (this.current == null) {
            this.followTicks = 0;
            return;
        }
        float lo = Math.min(this.minRange.getValue(), this.maxRange.getValue());
        float hi = Math.max(this.minRange.getValue(), this.maxRange.getValue());
        double dist = RotationUtil.distanceToEntity(this.current);
        if (dist < (double) lo - 0.05D) {
            this.followTicks = 0;
            if (this.requireInput.getValue() && !this.hasInput()) {
                return;
            }
            if (mc.thePlayer.movementInput.moveForward > 0.0F) {
                mc.thePlayer.movementInput.moveForward = -1.0F;
            }
            return;
        }
        if (this.mode.getValue() == 1 && dist > (double) hi + 0.05D) {
            this.followTicks++;
            if (this.followTicks <= this.followDelay.getValue()) {
                return;
            }
            if (this.requireInput.getValue() && !this.hasInput()) {
                return;
            }
            if (mc.thePlayer.movementInput.moveForward < 1.0F) {
                mc.thePlayer.movementInput.moveForward = 1.0F;
            }
        } else {
            this.followTicks = 0;
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
