package myau.module.modules;

// Ported from OpenSkid (GPL-3.0), itself adapted from MiauMinus misc/StaffDetector
// (tablist watch) and a per-server watch with an optional auto-lobby.
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.TextProperty;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S38PacketPlayerListItem;

public class StaffDetector extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String[] RANK_TOKENS = {
        "admin", "mod", "helper", "gm", "owner", "dev", "staff", "support", "mod+", "admin+"
    };
    private static final String[] VANISH_TOKENS = {"vanish", "poofed", "disappeared", "is now invisible"};

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"HYPIXEL", "GENERIC"});
    public final BooleanProperty checkTablist = new BooleanProperty("check-tablist", true);
    public final BooleanProperty checkChat = new BooleanProperty("check-chat", true);
    public final BooleanProperty alertChat = new BooleanProperty("alert-chat", true);
    public final BooleanProperty vanishAlert = new BooleanProperty("vanish-alert", true, () -> mode.getValue() == 0);
    public final TextProperty customNames = new TextProperty("custom-names", "", () -> mode.getValue() == 1);
    public final BooleanProperty autoLeave = new BooleanProperty("auto-leave", false);
    public final IntProperty leaveDelay = new IntProperty("leave-delay", 3, 0, 30, this.autoLeave::getValue);
    public final TextProperty leaveCommand = new TextProperty("leave-command", "/hub", this.autoLeave::getValue);

    private final Set<String> alerted = new HashSet<String>();
    private long pendingLeaveAt = -1L;

    public StaffDetector() {
        super("StaffDetector", false, false, "Detects staff in tablist and chat with alerts.");
    }

    @Override
    public void onEnabled() {
        this.alerted.clear();
        this.pendingLeaveAt = -1L;
    }

    @Override
    public void onDisabled() {
        this.alerted.clear();
        this.pendingLeaveAt = -1L;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (packet instanceof S38PacketPlayerListItem) {
            this.handleListPacket((S38PacketPlayerListItem) packet);
        } else if (packet instanceof S02PacketChat) {
            this.handleChat(((S02PacketChat) packet).getChatComponent().getUnformattedText());
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        if (this.checkTablist.getValue() && mc.getNetHandler() != null && mc.thePlayer.ticksExisted % 20 == 0) {
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                if (info == null || info.getGameProfile() == null) {
                    continue;
                }
                String display = info.getDisplayName() != null
                        ? info.getDisplayName().getFormattedText()
                        : info.getGameProfile().getName();
                this.checkName(info.getGameProfile().getName(), display);
            }
        }
        if (this.pendingLeaveAt != -1L && System.currentTimeMillis() >= this.pendingLeaveAt) {
            this.pendingLeaveAt = -1L;
            ChatUtil.sendMessage(this.leaveCommand.getValue());
            this.setEnabled(false);
        }
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        this.alerted.clear();
        this.pendingLeaveAt = -1L;
    }

    private void handleListPacket(S38PacketPlayerListItem packet) {
        if (!this.checkTablist.getValue()) {
            return;
        }
        if (packet.getAction() != S38PacketPlayerListItem.Action.ADD_PLAYER) {
            return;
        }
        for (S38PacketPlayerListItem.AddPlayerData entry : packet.getEntries()) {
            if (entry == null || entry.getProfile() == null) {
                continue;
            }
            String name = entry.getProfile().getName();
            if (name != null) {
                this.checkName(name, name);
            }
        }
    }

    private void handleChat(String message) {
        if (!this.checkChat.getValue() || message == null) {
            return;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (this.vanishAlert.getValue() && this.mode.getValue() == 0) {
            for (String token : VANISH_TOKENS) {
                if (lower.contains(token)) {
                    this.announce("Vanish pattern in chat: " + message.trim());
                    return;
                }
            }
        }
        if (lower.contains("joined") || lower.contains("is now online")) {
            for (String token : RANK_TOKENS) {
                if (lower.contains(token)) {
                    this.announce("Possible staff join: " + message.trim());
                    return;
                }
            }
        }
        if (message.contains("[")) {
            this.checkName(null, message);
        }
    }

    private void checkName(String name, String display) {
        if (display == null) {
            return;
        }
        String lowerDisplay = display.toLowerCase(Locale.ROOT);
        boolean staff = false;
        for (String token : RANK_TOKENS) {
            if (lowerDisplay.contains("[" + token) || lowerDisplay.contains(token + "]")
                    || lowerDisplay.contains(" " + token + " ")) {
                staff = true;
                break;
            }
        }
        if (!staff && this.mode.getValue() == 1) {
            for (String custom : this.customNames.getValue().split(",")) {
                String trimmed = custom.trim();
                if (!trimmed.isEmpty() && lowerDisplay.contains(trimmed.toLowerCase(Locale.ROOT))) {
                    staff = true;
                    break;
                }
            }
        }
        if (!staff) {
            return;
        }
        String key = name != null ? name.toLowerCase(Locale.ROOT) : lowerDisplay;
        if (!this.alerted.add(key)) {
            return;
        }
        this.announce("Staff detected: " + (name != null ? name : display.trim()));
        if (this.autoLeave.getValue() && this.pendingLeaveAt == -1L) {
            this.pendingLeaveAt = System.currentTimeMillis() + (long) this.leaveDelay.getValue() * 1000L;
        }
    }

    private void announce(String detail) {
        if (this.alertChat.getValue()) {
            ChatUtil.sendFormatted(String.format("%s%s: &c%s&r", Myau.clientName, this.getName(), detail));
        }
    }
}
