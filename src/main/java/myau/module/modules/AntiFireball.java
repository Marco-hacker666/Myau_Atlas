package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.*;
import myau.management.RotationState;
import myau.module.Module;
import myau.util.*;
import myau.property.properties.*;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;
import net.minecraft.network.play.client.C0APacketAnimation;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class AntiFireball extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final ArrayList<EntityFireball> farList = new ArrayList<>();
    private final ArrayList<EntityFireball> nearList = new ArrayList<>();
    private EntityFireball target = null;
    public final FloatProperty range = new FloatProperty("range", 5.0F, 3.0F, 8.0F);
    public final IntProperty fov = new IntProperty("fov", 360, 1, 360);
    public final BooleanProperty rotations = new BooleanProperty("rotations", true);
    public final BooleanProperty swing = new BooleanProperty("swing", true);
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "STRICT", "REAL"});
    public final ModeProperty showTarget = new ModeProperty("show-target", 0, new String[]{"NONE", "DEFAULT", "HUD"});

    private boolean isValidTarget(EntityFireball entityFireball) {
        return !entityFireball.getEntityBoundingBox().hasNaN() && RotationUtil.distanceToEntity(entityFireball) <= (double) this.range.getValue() + 3.0
                && RotationUtil.angleToEntity(entityFireball) <= (float) this.fov.getValue();
    }

    private void doAttackAnimation() {
        if (this.swing.getValue()) {
            mc.thePlayer.swingItem();
        } else {
            PacketUtil.sendPacket(new C0APacketAnimation());
        }
    }

    public AntiFireball() {
        super("AntiFireball", false);
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            List<EntityFireball> fireballs = mc.theWorld
                    .loadedEntityList
                    .stream()
                    .filter(entity -> entity instanceof EntityFireball)
                    .map(entity -> (EntityFireball) entity)
                    .collect(Collectors.toList());
            this.farList.removeIf(entityFireball -> !fireballs.contains(entityFireball));
            this.nearList.removeIf(entityFireball -> !fireballs.contains(entityFireball));
            for (EntityFireball fireball : fireballs) {
                if (!this.farList.contains(fireball) && !this.nearList.contains(fireball)) {
                    if (RotationUtil.distanceToEntity(fireball) > 3.0) {
                        this.farList.add(fireball);
                    } else {
                        this.nearList.add(fireball);
                    }
                }
            }
            if (mc.thePlayer.capabilities.allowFlying) {
                this.target = null;
            } else {
                this.target = this.farList.stream().filter(this::isValidTarget).min(Comparator.comparingDouble(RotationUtil::distanceToEntity)).orElse(null);
            }
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onUpdate(UpdateEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            EntityFireball fireball = this.target;
            if (TeamUtil.isEntityLoaded(fireball)) {
                float[] rotations = RotationUtil.getRotationsToBox(this.target.getEntityBoundingBox(), event.getYaw(), event.getPitch(), 180.0F, 0.0F);
                if (this.rotations.getValue()
                        && !ItemUtil.isHoldingNonEmpty()
                        && !ItemUtil.isUsingBow()
                        && !ItemUtil.hasHoldItem()) {
                    event.setRotation(rotations[0], rotations[1], 0);
                    event.setPervRotation(this.moveFix.getValue() != 0 ? rotations[0] : mc.thePlayer.rotationYaw, 0);
                    if (this.moveFix.getModeString().equals("REAL")) {
                        /* REAL: the camera turns as well, spread over this tick's frames by
                           RotationManager; the mouse still works (not forced). */
                        Myau.rotationManager.setRotation(rotations[0], rotations[1], 0, false);
                    }
                }
                /* Only in reach and only while it is still coming this way. The
                   swing used to run whenever a fireball was within range + 3,
                   i.e. at air, and a fireball already knocked back stayed the
                   target and was swung at and attacked every tick -- twenty a
                   second -- until it left. */
                if (!Myau.playerStateManager.attacking && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing
                        && RotationUtil.distanceToEntity(this.target) <= (double) this.range.getValue().floatValue()
                        && approaching(this.target)) {
                    this.doAttackAnimation();
                    PacketUtil.sendPacket(new C02PacketUseEntity(this.target, Action.ATTACK));
                    PlayerUtil.attackEntity(this.target);
                }
            }
        }
    }

    /** Same sign test the render colour uses: velocity towards the player is positive. */
    private static boolean approaching(EntityFireball fireball) {
        double dot = (fireball.posX - fireball.lastTickPosX) * (mc.thePlayer.posX - fireball.posX)
                + (fireball.posY - fireball.lastTickPosY) * (mc.thePlayer.posY - fireball.posY)
                + (fireball.posZ - fireball.lastTickPosZ) * (mc.thePlayer.posZ - fireball.posZ);
        return dot > 0.0;
    }

    @EventTarget
    public void onMove(MoveInputEvent event) {
        if (this.isEnabled()) {
            if (this.moveFix.getValue() == 1
                    && RotationState.isActived()
                    && RotationState.getPriority() == 0.0F
                    && MoveUtil.isForwardPressed()) {
                MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
            }
        }
    }

    @EventTarget
    public void onRender(Render3DEvent event) {
        if (this.isEnabled()) {
            if (this.showTarget.getValue() != 0 && TeamUtil.isEntityLoaded(this.target)) {
                Color color = new Color(-1);
                switch (this.showTarget.getValue()) {
                    case 1:
                        double dist = (this.target.posX - this.target.lastTickPosX) * (mc.thePlayer.posX - this.target.posX)
                                + (this.target.posY - this.target.lastTickPosY) * (mc.thePlayer.posY - this.target.posY)
                                * (mc.thePlayer.posY + (double) mc.thePlayer.getEyeHeight() - this.target.posY - (double) this.target.height / 2.0)
                                + (this.target.posZ - this.target.lastTickPosZ) * (mc.thePlayer.posZ - this.target.posZ);
                        if (dist < 0.0) {
                            color = new Color(16733525);
                        } else {
                            color = new Color(5635925);
                        }
                        break;
                    case 2:
                        color = ((HUD) Myau.moduleManager.modules.get(HUD.class)).getColor(System.currentTimeMillis());
                }
                RenderUtil.enableRenderState();
                RenderUtil.drawEntityBox(this.target, color.getRed(), color.getGreen(), color.getBlue());
                RenderUtil.disableRenderState();
            }
        }
    }

    @EventTarget(whenDisabled = true)
    public void onLoadWorld(LoadWorldEvent event) {
        this.farList.clear();
        this.nearList.clear();
    }
}
