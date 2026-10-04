package myau.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.util.EnumParticleTypes;

/**
 * Spawns a client-side particle straight into the EffectRenderer.
 *
 * World.spawnParticle goes through RenderGlobal, which drops the particle when
 * the camera is more than 16 blocks away and thins it out by the particle
 * setting -- so hit effects on a target a few blocks off often did not show
 * (2026-10-01). Here the only limit is 32 blocks from the player. Falls back
 * to the world's own path if the renderer is not there.
 */
public final class ParticleFix {
    private static final double MAX_DIST_SQ = 32.0 * 32.0;

    private ParticleFix() {
    }

    public static void spawn(WorldClient world, EnumParticleTypes type, double x, double y, double z,
                             double dx, double dy, double dz, int... params) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            EffectRenderer renderer = mc.effectRenderer;
            EntityPlayerSP player = mc.thePlayer;
            if (renderer != null && player != null) {
                double ox = player.posX - x;
                double oy = player.posY - y;
                double oz = player.posZ - z;
                if (ox * ox + oy * oy + oz * oz > MAX_DIST_SQ) {
                    return;
                }
                renderer.spawnEffectParticle(type.getParticleID(), x, y, z, dx, dy, dz, params);
                return;
            }
        } catch (Throwable ignored) {
            /* A cosmetic must never take the caller down; use the vanilla path. */
        }
        world.spawnParticle(type, x, y, z, dx, dy, dz, params);
    }
}
