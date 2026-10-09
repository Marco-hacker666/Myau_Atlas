package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): spoofs the client brand on outgoing
// MC|Brand payloads and can drop FML/FORGE mod channels.
import io.netty.buffer.Unpooled;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.mixin.IAccessorC17PacketCustomPayload;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.TextProperty;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C17PacketCustomPayload;

public class ModSpoofer extends Module {
    private static final String BRAND_CHANNEL = "MC|Brand";

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Forge", "Vanilla", "Custom"});
    public final TextProperty customBrand = new TextProperty("custom-brand", "fml,forge", () -> mode.getValue() == 2);
    public final BooleanProperty hideModChannels = new BooleanProperty("hide-mod-channels", false);

    public ModSpoofer() {
        super("ModSpoofer", false, false, "Spoofs your client brand and hides mod channels.");
    }

    @Override
    public void onEnabled() {
    }

    @Override
    public void onDisabled() {
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || event.getType() != EventType.SEND) return;
        if (!(event.getPacket() instanceof C17PacketCustomPayload)) return;

        C17PacketCustomPayload packet = (C17PacketCustomPayload) event.getPacket();
        String channel = packet.getChannelName();
        if (channel == null) return;

        if (BRAND_CHANNEL.equals(channel)) {
            ((IAccessorC17PacketCustomPayload) packet).setData(createBrandBuffer(getBrand()));
        } else if (hideModChannels.getValue() && isModChannel(channel)) {
            event.setCancelled(true);
        }
    }

    private boolean isModChannel(String channel) {
        return channel.startsWith("FML") || channel.startsWith("FORGE");
    }

    private PacketBuffer createBrandBuffer(String brand) {
        return new PacketBuffer(Unpooled.buffer()).writeString(brand);
    }

    private String getBrand() {
        if (mode.getValue() == 2) {
            String custom = customBrand.getValue();
            return custom == null || custom.isEmpty() ? "vanilla" : custom;
        }
        return mode.getValue() == 1 ? "vanilla" : "fml,forge";
    }

    @Override
    public String[] getSuffix() {
        return new String[]{mode.getModeString()};
    }
}
