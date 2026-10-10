package myau.module.modules;

// Ported from OpenSkid (GPL-3.0), itself adapted from MiauMinus misc/AutoReconnect.
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.IntProperty;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.play.server.S40PacketDisconnect;
import net.minecraft.util.IChatComponent;

public class AutoReconnect extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String[] KICK_DENY_TOKENS = {"ban", "blacklist", "fly", "cheat"};

    public final IntProperty delay = new IntProperty("delay", 10, 1, 60);
    public final IntProperty maxAttempts = new IntProperty("max-attempts", 5, 1, 100);

    private ServerData lastServer;
    private long disconnectAt;
    private boolean shouldReconnect;
    private int attempts;

    public AutoReconnect() {
        super("AutoReconnect", false, false, "Automatically reconnects after being disconnected from servers.");
    }

    @Override
    public void onEnabled() {
        reset();
    }

    @Override
    public void onDisabled() {
        reset();
    }

    @Override
    public String[] getSuffix() {
        if (!this.shouldReconnect) {
            return new String[0];
        }
        long elapsed = (System.currentTimeMillis() - this.disconnectAt) / 1000L;
        long remaining = Math.max(0L, (long) this.delay.getValue() + (long) this.attempts * 5L - elapsed);
        return new String[]{remaining + "s"};
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE
                || !(event.getPacket() instanceof S40PacketDisconnect)) {
            return;
        }
        ServerData current = mc.getCurrentServerData();
        if (current == null) {
            return;
        }
        if (isDeniedKick((S40PacketDisconnect) event.getPacket())) {
            this.shouldReconnect = false;
            return;
        }
        this.lastServer = current;
        this.shouldReconnect = true;
        this.disconnectAt = System.currentTimeMillis();
        this.attempts = 0;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || !this.shouldReconnect) {
            return;
        }
        if (!(mc.currentScreen instanceof GuiDisconnected)) {
            if (!(mc.currentScreen instanceof GuiConnecting)) {
                this.shouldReconnect = false;
            }
            return;
        }
        long elapsed = (System.currentTimeMillis() - this.disconnectAt) / 1000L;
        long wait = (long) this.delay.getValue() + (long) this.attempts * 5L;
        if (elapsed < wait || this.lastServer == null) {
            return;
        }
        if (this.attempts >= this.maxAttempts.getValue()) {
            this.shouldReconnect = false;
            ChatUtil.sendFormatted(String.format("%s%s: &cmax reconnect attempts reached&r", Myau.clientName, this.getName()));
            return;
        }
        this.attempts++;
        this.disconnectAt = System.currentTimeMillis();
        mc.displayGuiScreen(new GuiConnecting(new GuiMultiplayer(new GuiMainMenu()), mc, this.lastServer));
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        reset();
    }

    private void reset() {
        this.lastServer = null;
        this.disconnectAt = 0L;
        this.shouldReconnect = false;
        this.attempts = 0;
    }

    private static boolean isDeniedKick(S40PacketDisconnect packet) {
        try {
            IChatComponent reason = packet.getReason();
            if (reason == null) {
                return false;
            }
            String lower = reason.getUnformattedText().toLowerCase(java.util.Locale.ROOT);
            for (String token : KICK_DENY_TOKENS) {
                if (lower.contains(token)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
