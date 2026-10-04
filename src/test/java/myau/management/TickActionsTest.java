package myau.management;

import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.client.C16PacketClientStatus;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

/** The per-tick action record (2026-09-28, Rise item 2). */
public class TickActionsTest {

    @Before
    public void setUp() {
        TickActions.resetForTests();
    }

    @After
    public void tearDown() {
        TickActions.resetForTests();
    }

    private static C02PacketUseEntity useEntity(C02PacketUseEntity.Action action) throws Exception {
        C02PacketUseEntity packet = new C02PacketUseEntity();
        for (Field field : C02PacketUseEntity.class.getDeclaredFields()) {
            if (field.getType() == C02PacketUseEntity.Action.class) {
                field.setAccessible(true);
                field.set(packet, action);
            }
        }
        return packet;
    }

    @Test
    public void eachActionIsItsOwnKind() throws Exception {
        assertEquals(TickActions.SLOT, TickActions.kindOf(new C09PacketHeldItemChange(3)));
        assertEquals(TickActions.ATTACK, TickActions.kindOf(useEntity(C02PacketUseEntity.Action.ATTACK)));
        assertEquals(TickActions.INTERACT, TickActions.kindOf(useEntity(C02PacketUseEntity.Action.INTERACT)));
        assertEquals(TickActions.SWING, TickActions.kindOf(new C0APacketAnimation()));
        assertEquals(TickActions.USE, TickActions.kindOf(new C08PacketPlayerBlockPlacement()));
        assertEquals(TickActions.DIG, TickActions.kindOf(new C07PacketPlayerDigging()));
        assertEquals(TickActions.INVENTORY, TickActions.kindOf(new C0EPacketClickWindow()));
        assertEquals(TickActions.INVENTORY, TickActions.kindOf(new C0DPacketCloseWindow()));
        assertEquals(TickActions.INVENTORY, TickActions.kindOf(
                new C16PacketClientStatus(C16PacketClientStatus.EnumState.OPEN_INVENTORY_ACHIEVEMENT)));
    }

    @Test
    public void movementAndOtherStatusAreNotActions() {
        assertEquals(0, TickActions.kindOf(new C03PacketPlayer(true)));
        assertEquals(0, TickActions.kindOf(new C16PacketClientStatus(C16PacketClientStatus.EnumState.PERFORM_RESPAWN)));
    }

    @Test
    public void aTickRemembersWhatWasSentUntilTheNext() {
        assertFalse(TickActions.any(TickActions.USE | TickActions.DIG | TickActions.INVENTORY));
        TickActions.noteSent(new C08PacketPlayerBlockPlacement());
        TickActions.noteSent(new C09PacketHeldItemChange(1));
        assertTrue(TickActions.any(TickActions.USE));
        assertTrue(TickActions.any(TickActions.SLOT));
        assertFalse(TickActions.any(TickActions.ATTACK | TickActions.INVENTORY));
        assertEquals(TickActions.USE | TickActions.SLOT, TickActions.sent());
        TickActions.newTick();
        assertEquals(0, TickActions.sent());
        assertFalse(TickActions.any(TickActions.USE));
    }

    @Test
    public void refusalsAreCounted() {
        TickActions.refuse();
        TickActions.refuse();
        assertEquals(2L, TickActions.refusals());
    }
}
