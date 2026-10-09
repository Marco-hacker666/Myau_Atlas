package myau.module.modules;

// Ported from OpenSkid (GPL-3.0). Upstream's ScriptModule is an adapter over
// keystrokesmod script modules (keystrokesmod.module.Module plus its Setting
// types), a library that exists in neither project's tree, so there is
// nothing to port there. What this keeps is the behaviour that layer existed
// for: a loaded script is driven through the client's own module, and its
// hooks fire from the client's own events.
//
// The ported myau.script.Script API exposes no settings, so unlike upstream
// there are no per-setting properties to mirror into the menu yet.
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.script.ScriptManager;
import net.minecraft.network.Packet;

public class ScriptModule extends Module {
    /** Packet summaries are short strings: scripts never see the live packet. */
    private static final int MAX_SUMMARY = 120;

    public ScriptModule() {
        super("ScriptModule", false, false, "Loads script files and runs their hooks.");
    }

    @Override
    public void onEnabled() {
        ScriptManager.getInstance().loadAll();
    }

    @Override
    public void onDisabled() {
        ScriptManager.getInstance().disableAll();
    }

    @Override
    public String[] getSuffix() {
        int loaded = ScriptManager.getInstance().loadedCount();
        if (loaded == 0) {
            return new String[0];
        }
        return new String[]{loaded + " loaded"};
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.POST) {
            return;
        }
        ScriptManager.getInstance().fireTick();
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getPacket() == null) {
            return;
        }
        String packetClass = event.getPacket().getClass().getSimpleName();
        String summary = this.summarize(event.getPacket());
        if (event.getType() == EventType.SEND) {
            if (!ScriptManager.getInstance().firePacketSend(packetClass, summary)) {
                event.setCancelled(true);
            }
        } else if (event.getType() == EventType.RECEIVE) {
            if (!ScriptManager.getInstance().firePacketReceive(packetClass, summary)) {
                event.setCancelled(true);
            }
        }
    }

    private String summarize(Packet<?> packet) {
        String text;
        try {
            text = String.valueOf(packet);
        } catch (Exception e) {
            return "";
        }
        if (text.length() > MAX_SUMMARY) {
            text = text.substring(0, MAX_SUMMARY);
        }
        return text;
    }
}
