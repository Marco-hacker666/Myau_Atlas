package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): nearest fireball or held-charge impact
// prediction, drawn as a block box plus a source-to-impact line. The boxes go
// through myau.util.RenderUtil, which applies the camera offset itself, in the
// bracket BedPlates and BlockOverlay use; the line has no box to hand it and is
// drawn directly, as Blink draws its marker.
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.Render3DEvent;
import myau.events.TickEvent;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.PercentProperty;
import myau.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.entity.projectile.EntityWitherSkull;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

public class FireBallPredict extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double RED_DISTANCE = 8.0;
    private static final double GREEN_DISTANCE = 48.0;
    private static final double MID_DISTANCE = 24.0;
    private static final double HELD_SCAN_RADIUS = 48.0;

    public final BooleanProperty realFireballs = new BooleanProperty("real-fireballs", true);
    public final BooleanProperty heldCharges = new BooleanProperty("held-charges", true);
    public final IntProperty predictRange = new IntProperty("predict-range", 100, 16, 200);
    public final IntProperty maxDistance = new IntProperty("max-distance", 64, 16, 128);
    public final IntProperty renderRadius = new IntProperty("render-radius", 1, 1, 2);
    public final PercentProperty opacity = new PercentProperty("opacity", 60);

    private BlockPos impact;
    private Vec3 impactFrom;
    private int impactColor;
    private double impactDistance;
    private boolean hasImpact;
    private int tick = 0;

    public FireBallPredict() {
        super("FireBallPredict", false, false, "Predicts fireball impacts and marks the landing block.");
    }

    @Override
    public void onEnabled() {
        this.clear();
    }

    @Override
    public void onDisabled() {
        this.clear();
    }

    @Override
    public String[] getSuffix() {
        return this.hasImpact ? new String[]{(int) this.impactDistance + "m"} : new String[0];
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.POST) {
            return;
        }
        if (mc.theWorld == null || mc.thePlayer == null) {
            this.clear();
            return;
        }
        if (++this.tick % 2 != 0) {
            return;
        }
        this.recompute();
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.clear();
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || !this.hasImpact || this.impact == null || this.impactFrom == null) {
            return;
        }
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        int red = this.impactColor >> 16 & 255;
        int green = this.impactColor >> 8 & 255;
        int blue = this.impactColor & 255;
        int alpha = Math.max(0, Math.min(255, this.opacity.getValue() * 255 / 100));
        RenderUtil.enableRenderState();
        try {
            RenderUtil.drawBlockBox(this.impact, 1.0, red, green, blue);
            RenderUtil.drawBlockBoundingBox(this.impact, 1.0, red, green, blue, alpha, 2.0F);

            if (this.renderRadius.getValue() >= 2) {
                int faint = Math.max(alpha / 2, 0);
                if (faint > 0) {
                    for (EnumFacing facing : EnumFacing.values()) {
                        BlockPos neighbor = this.impact.offset(facing);
                        try {
                            if (mc.theWorld.isAirBlock(neighbor)
                                    || !mc.theWorld.getBlockState(neighbor).getBlock().isFullCube()) {
                                continue;
                            }
                        } catch (Exception ignored) {
                            continue;
                        }
                        RenderUtil.drawBlockBoundingBox(neighbor, 1.0, red, green, blue, faint, 1.5F);
                    }
                }
            }

            Vec3 center = new Vec3(this.impact.getX() + 0.5, this.impact.getY() + 0.5, this.impact.getZ() + 0.5);
            this.drawTrajectory(this.impactFrom, center, red, green, blue, alpha);
        } finally {
            RenderUtil.disableRenderState();
        }
    }

    /** The source-to-impact line, in the impact's own colour. */
    private void drawTrajectory(Vec3 from, Vec3 to, int red, int green, int blue, int alpha) {
        double renderX = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
        double renderY = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
        double renderZ = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();
        GlStateManager.color(red / 255.0F, green / 255.0F, blue / 255.0F, Math.max(alpha, 1) / 255.0F);
        GL11.glLineWidth(1.5F);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex3d(from.xCoord - renderX, from.yCoord - renderY, from.zCoord - renderZ);
        GL11.glVertex3d(to.xCoord - renderX, to.yCoord - renderY, to.zCoord - renderZ);
        GL11.glEnd();
        GlStateManager.resetColor();
    }

    private void recompute() {
        Vec3 self = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        double maxDistSq = (double) this.maxDistance.getValue() * (double) this.maxDistance.getValue();
        BlockPos best = null;
        Vec3 bestFrom = null;
        int bestColor = 0;
        double bestDistSq = Double.MAX_VALUE;
        double bestFlight = 0.0;

        if (this.realFireballs.getValue()) {
            for (Entity entity : mc.theWorld.loadedEntityList) {
                if (!(entity instanceof EntityFireball) || entity instanceof EntityWitherSkull) {
                    continue;
                }
                EntityFireball fireball = (EntityFireball) entity;
                double motionSq = fireball.motionX * fireball.motionX
                        + fireball.motionY * fireball.motionY
                        + fireball.motionZ * fireball.motionZ;
                if (motionSq < 1.0E-4) {
                    continue;
                }
                double distSq;
                try {
                    distSq = fireball.getDistanceSqToEntity(mc.thePlayer);
                } catch (Exception ignored) {
                    continue;
                }
                if (distSq > maxDistSq) {
                    continue;
                }
                Vec3 from = new Vec3(fireball.posX, fireball.posY, fireball.posZ);
                Vec3 direction = new Vec3(fireball.motionX, fireball.motionY, fireball.motionZ).normalize();
                Vec3 to = from.addVector(
                        direction.xCoord * (double) this.predictRange.getValue(),
                        direction.yCoord * (double) this.predictRange.getValue(),
                        direction.zCoord * (double) this.predictRange.getValue());
                MovingObjectPosition hit;
                try {
                    hit = mc.theWorld.rayTraceBlocks(from, to, false, true, false);
                } catch (Exception ignored) {
                    continue;
                }
                if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || hit.getBlockPos() == null) {
                    continue;
                }
                Vec3 center = new Vec3(hit.getBlockPos().getX() + 0.5, hit.getBlockPos().getY() + 0.5, hit.getBlockPos().getZ() + 0.5);
                double impactDistSq = self.squareDistanceTo(center);
                if (impactDistSq >= bestDistSq) {
                    continue;
                }
                bestDistSq = impactDistSq;
                best = hit.getBlockPos();
                bestFrom = from;
                bestFlight = from.distanceTo(hit.hitVec);
                bestColor = distanceColor(bestFlight);
            }
        }

        if (best == null && this.heldCharges.getValue()) {
            double heldRadiusSq = HELD_SCAN_RADIUS * HELD_SCAN_RADIUS;
            for (EntityPlayer player : mc.theWorld.playerEntities) {
                if (player == null || player == mc.thePlayer || player.isDead) {
                    continue;
                }
                ItemStack held = player.getHeldItem();
                if (held == null || held.getItem() != Items.fire_charge) {
                    continue;
                }
                double distSq;
                try {
                    distSq = player.getDistanceSqToEntity(mc.thePlayer);
                } catch (Exception ignored) {
                    continue;
                }
                if (distSq > heldRadiusSq || distSq > maxDistSq) {
                    continue;
                }
                Vec3 from;
                Vec3 look;
                try {
                    from = player.getPositionEyes(1.0F);
                    look = player.getLook(1.0F);
                } catch (Exception ignored) {
                    continue;
                }
                Vec3 to = from.addVector(
                        look.xCoord * (double) this.predictRange.getValue(),
                        look.yCoord * (double) this.predictRange.getValue(),
                        look.zCoord * (double) this.predictRange.getValue());
                MovingObjectPosition hit;
                try {
                    hit = mc.theWorld.rayTraceBlocks(from, to, false, true, false);
                } catch (Exception ignored) {
                    continue;
                }
                if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || hit.getBlockPos() == null) {
                    continue;
                }
                Vec3 center = new Vec3(hit.getBlockPos().getX() + 0.5, hit.getBlockPos().getY() + 0.5, hit.getBlockPos().getZ() + 0.5);
                double impactDistSq = self.squareDistanceTo(center);
                if (impactDistSq >= bestDistSq) {
                    continue;
                }
                bestDistSq = impactDistSq;
                best = hit.getBlockPos();
                bestFrom = from;
                bestFlight = from.distanceTo(hit.hitVec);
                bestColor = 0xFFFF00;
            }
        }

        if (best == null) {
            this.clear();
            return;
        }
        this.impact = best;
        this.impactFrom = bestFrom;
        this.impactColor = bestColor;
        this.impactDistance = Math.sqrt(bestDistSq);
        this.hasImpact = true;
    }

    private static int distanceColor(double distance) {
        if (distance <= RED_DISTANCE) {
            return 0xFF0000;
        }
        if (distance >= GREEN_DISTANCE) {
            return 0x00FF00;
        }
        if (distance <= MID_DISTANCE) {
            float t = (float) ((distance - RED_DISTANCE) / (MID_DISTANCE - RED_DISTANCE));
            return 255 << 16 | Math.round(255.0F * t) << 8;
        }
        float t = (float) ((distance - MID_DISTANCE) / (GREEN_DISTANCE - MID_DISTANCE));
        return Math.round(255.0F * (1.0F - t)) << 16 | 255 << 8;
    }

    private void clear() {
        this.impact = null;
        this.impactFrom = null;
        this.impactColor = 0;
        this.impactDistance = 0.0;
        this.hasImpact = false;
        this.tick = 0;
    }
}
