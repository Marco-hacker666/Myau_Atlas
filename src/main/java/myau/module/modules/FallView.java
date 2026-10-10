package myau.module.modules;

import net.minecraft.entity.player.EntityPlayer;

// Ported from OpenSkid (GPL-3.0): pure fall-camera math used by ViewClip's FALL
// mode. Deliberately NOT a registered module: it holds no state and needs no
// toggle of its own, so it must stay out of the module registry, the category
// table and the docs. Nothing in Myau calls it yet; Myau's own ViewClip has its
// own fall handling.
public final class FallView {
    private FallView() {
    }

    public static boolean shouldApplyFallView(EntityPlayer player, float minFall) {
        if (player == null) {
            return false;
        }
        try {
            if (player.capabilities != null && player.capabilities.isFlying) {
                return false;
            }
        } catch (Exception ignored) {
        }
        if (player.onGround) {
            return false;
        }
        if (player.fallDistance < minFall) {
            return false;
        }
        return player.motionY < -0.08;
    }

    public static float fallFactor(float fallDistance, float minFall) {
        float over = fallDistance - minFall;
        if (over <= 0.0F) {
            return 0.0F;
        }
        float factor = over / 8.0F;
        if (factor < 0.0F) {
            return 0.0F;
        }
        if (factor > 1.0F) {
            return 1.0F;
        }
        return factor;
    }

    public static float fovKick(float tiltAmount, float factor) {
        if (factor <= 0.0F || tiltAmount <= 0.0F) {
            return 0.0F;
        }
        return tiltAmount * factor;
    }

    public static float dollyBoost(float factor) {
        if (factor <= 0.0F) {
            return 0.0F;
        }
        return 3.0F * factor;
    }
}
