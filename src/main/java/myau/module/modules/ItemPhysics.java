package myau.module.modules;

import myau.module.Module;
import myau.property.properties.FloatProperty;

import java.util.Random;

/**
 * Dropped items lie flat on the ground and tumble while in the air.
 *
 * The rendering is done by MixinRenderEntityItem, which replaces vanilla's
 * placement of the item (bob and spin) and reads this module. Until
 * 2026-09-28 nothing read it at all -- the mixin that used to had gone before
 * any local backup, so the module could be switched on and did nothing
 * (F-23, docs/ARCH-AUDIT-2026-09-28.md).
 */
public class ItemPhysics extends Module {
    public static ItemPhysics instance;

    public final FloatProperty rotationSpeed = new FloatProperty("rotation-speed", 1.0F, 0.0F, 5.0F);

    /**
     * Stands in for the renderer's random while this is on.
     *
     * Vanilla draws a stack as several copies, each nudged by a random offset
     * of up to 0.15 along all three axes of the item. Laid flat, the item's
     * own Z axis points straight up, so those nudges would push copies into
     * the ground. A random that always returns the middle of its range makes
     * every nudge zero: the copies coincide and the stack reads as one item.
     */
    public static final Random STILL = new StillRandom();

    public ItemPhysics() {
        super("ItemPhysics", false);
    }

    @Override
    public void onEnabled() {
        instance = this;
    }

    @Override
    public void onDisabled() {
        instance = null;
    }

    public float getRotationSpeed() {
        return this.rotationSpeed.getValue();
    }

    private static final class StillRandom extends Random {
        private static final long serialVersionUID = 1L;

        @Override
        public float nextFloat() {
            return 0.5F;
        }
    }
}
