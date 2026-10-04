package myau.util;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * PlaceUtil's geometry. The build of 2026-10-02 that was compiled outside
 * Gradle read every Vec3/AxisAlignedBB coordinate as 0.0, so rotationsTo gave
 * the same rotation for every target and no box intersected anything. These
 * fail on such a build.
 */
public class PlaceUtilTest {

    private static final Vec3 EYE = new Vec3(0.5, 65.62, 0.5);

    @Test
    public void rotationsPointAtTheTarget() {
        assertEquals(-90.0F, PlaceUtil.rotationsTo(EYE, new Vec3(5.5, 65.62, 0.5), 0.0F)[0], 0.01F); // +x is yaw -90
        assertEquals(0.0F, PlaceUtil.rotationsTo(EYE, new Vec3(0.5, 65.62, 5.5), 0.0F)[0], 0.01F);   // +z is yaw 0
        assertEquals(90.0F, PlaceUtil.rotationsTo(EYE, new Vec3(0.5, 60.0, 0.5001), 0.0F)[1], 0.1F); // straight down
    }

    @Test
    public void differentTargetsGiveDifferentRotations() {
        float[] east = PlaceUtil.rotationsTo(EYE, new Vec3(3.0, 64.0, 0.5), 0.0F);
        float[] west = PlaceUtil.rotationsTo(EYE, new Vec3(-2.0, 64.0, 0.5), 0.0F);
        assertTrue(PlaceUtil.angle(east[0], east[1], west[0], west[1]) > 90.0);
    }

    @Test
    public void yawStaysNearTheReference() {
        float yaw = PlaceUtil.rotationsTo(EYE, new Vec3(0.5, 65.62, 5.5), 720.0F)[0];
        assertEquals(720.0F, yaw, 0.01F);
    }

    @Test
    public void angleWrapsAround() {
        assertEquals(20.0, PlaceUtil.angle(170.0F, 0.0F, -170.0F, 0.0F), 1.0E-3);
        assertEquals(5.0, PlaceUtil.angle(0.0F, 0.0F, 3.0F, 4.0F), 1.0E-3);
    }

    @Test
    public void boxIntersectsOnlyTouchedCells() {
        AxisAlignedBB player = new AxisAlignedBB(0.2, 64.0, 0.2, 0.8, 65.8, 0.8);
        assertTrue(PlaceUtil.boxIntersects(player, new BlockPos(0, 64, 0)));
        assertTrue(PlaceUtil.boxIntersects(player, new BlockPos(0, 65, 0)));
        assertFalse(PlaceUtil.boxIntersects(player, new BlockPos(1, 64, 0)));
        assertFalse(PlaceUtil.boxIntersects(player, new BlockPos(0, 66, 0)));
    }

    @Test
    public void faceKeysCompareByValue() {
        PlaceUtil.FaceKey a = new PlaceUtil.FaceKey(new BlockPos(1, 2, 3), net.minecraft.util.EnumFacing.UP);
        PlaceUtil.FaceKey b = new PlaceUtil.FaceKey(new BlockPos(1, 2, 3), net.minecraft.util.EnumFacing.UP);
        PlaceUtil.FaceKey c = new PlaceUtil.FaceKey(new BlockPos(1, 2, 3), net.minecraft.util.EnumFacing.DOWN);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }
}
