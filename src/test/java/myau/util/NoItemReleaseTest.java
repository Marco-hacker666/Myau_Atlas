package myau.util;

import myau.module.modules.NoItemRelease;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

/**
 * NoItemRelease's claim / drop window (2026-10-08). Kept outside
 * myau.module.modules on purpose: a frame from that package on the stack
 * counts as a module sending the packet, which is never claimed.
 */
public class NoItemReleaseTest {

    private static C07PacketPlayerDigging release() {
        return new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN);
    }

    private static void openWindow(boolean open) throws Exception {
        Field field = NoItemRelease.class.getDeclaredField("inVanillaRelease");
        field.setAccessible(true);
        field.setBoolean(null, open);
    }

    @Before
    public void reset() {
        NoItemRelease.endVanillaRelease();
    }

    @After
    public void close() {
        NoItemRelease.endVanillaRelease();
    }

    @Test
    public void vanillaReleaseInsideTheWindowIsDroppedOnce() throws Exception {
        openWindow(true);
        C07PacketPlayerDigging packet = release();
        NoItemRelease.claimVanillaRelease(packet);
        assertTrue(NoItemRelease.dropVanillaRelease(packet));
        assertFalse("dropped only once", NoItemRelease.dropVanillaRelease(packet));
    }

    @Test
    public void nothingIsDroppedOutsideTheWindow() {
        C07PacketPlayerDigging packet = release();
        NoItemRelease.claimVanillaRelease(packet);
        assertFalse(NoItemRelease.dropVanillaRelease(packet));
    }

    @Test
    public void onlyTheFirstReleaseIsClaimedAndOnlyThatInstance() throws Exception {
        openWindow(true);
        C07PacketPlayerDigging first = release();
        C07PacketPlayerDigging second = release();
        NoItemRelease.claimVanillaRelease(first);
        NoItemRelease.claimVanillaRelease(second);
        assertFalse("another instance goes through", NoItemRelease.dropVanillaRelease(second));
        assertTrue(NoItemRelease.dropVanillaRelease(first));
    }

    @Test
    public void otherDiggingActionsAreNeverClaimed() throws Exception {
        openWindow(true);
        C07PacketPlayerDigging dig = new C07PacketPlayerDigging(
                C07PacketPlayerDigging.Action.START_DESTROY_BLOCK, BlockPos.ORIGIN, EnumFacing.UP);
        NoItemRelease.claimVanillaRelease(dig);
        assertFalse(NoItemRelease.dropVanillaRelease(dig));
    }

    @Test
    public void closingTheWindowForgetsTheClaim() throws Exception {
        openWindow(true);
        C07PacketPlayerDigging packet = release();
        NoItemRelease.claimVanillaRelease(packet);
        NoItemRelease.endVanillaRelease();
        assertFalse(NoItemRelease.dropVanillaRelease(packet));
    }
}
