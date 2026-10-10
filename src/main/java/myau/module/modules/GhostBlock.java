package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): keeps client-side blocks by cancelling
// placements, with an optional timed auto-clear.
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.TextProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.BlockPos;
import org.lwjgl.input.Keyboard;

public class GhostBlock extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty placeMode = new ModeProperty("place-mode", 0, new String[]{"Ghost", "Timed"});
    public final IntProperty timeoutMs = new IntProperty("timeout-ms", 3000, 500, 10000, () -> placeMode.getValue() == 1);
    public final TextProperty holdKey = new TextProperty("hold-key", "");

    private BlockPos ghostPos;
    private long ghostTime;

    public GhostBlock() {
        super("GhostBlock", false, false, "Creates client side blocks by cancelling placements.");
    }

    @Override
    public void onEnabled() {
        ghostPos = null;
        ghostTime = 0L;
    }

    @Override
    public void onDisabled() {
        clearGhost();
        ghostPos = null;
        ghostTime = 0L;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || event.getType() != EventType.SEND) return;
        if (!(event.getPacket() instanceof C08PacketPlayerBlockPlacement)) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (!isArmed()) return;

        BlockPos pos = ((C08PacketPlayerBlockPlacement) event.getPacket()).getPosition();
        if (pos == null || pos.equals(BlockPos.ORIGIN)) return;

        event.setCancelled(true);
        ghostPos = pos;
        ghostTime = System.currentTimeMillis();
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!isEnabled() || event.getType() != EventType.PRE) return;
        if (placeMode.getValue() != 1 || ghostPos == null) return;
        if (System.currentTimeMillis() - ghostTime >= timeoutMs.getValue()) {
            clearGhost();
            ghostPos = null;
        }
    }

    private void clearGhost() {
        if (ghostPos != null && mc.theWorld != null) {
            mc.theWorld.setBlockToAir(ghostPos);
        }
    }

    private boolean isArmed() {
        String keyName = this.holdKey.getValue();
        if (keyName == null || keyName.trim().isEmpty()) {
            return true;
        }
        int code = Keyboard.getKeyIndex(keyName.trim().toUpperCase());
        return code != 0 && Keyboard.isKeyDown(code);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{placeMode.getModeString()};
    }
}
