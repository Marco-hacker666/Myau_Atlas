package myau.module.modules;

// Ported from OpenSkid (GPL-3.0). Party join, leave and invite alerts plus a
// tracked member list, parsed from party chat lines.
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.util.ChatUtil;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.EnumChatFormatting;

public class PartyDetector extends Module {
    private static final Pattern JOINED = Pattern.compile("^([A-Za-z0-9_]{2,16}) joined the party.*");
    private static final Pattern LEFT = Pattern.compile("^([A-Za-z0-9_]{2,16}) (left|has left|was removed from) the party.*");

    public final BooleanProperty alerts = new BooleanProperty("alerts", true);
    public final BooleanProperty showList = new BooleanProperty("show-list", true);

    private final Set<String> members = new LinkedHashSet<String>();

    public PartyDetector() {
        super("PartyDetector", false, false, "Tracks party joins, leaves and invites with alerts.");
    }

    @Override
    public void onEnabled() {
        this.members.clear();
    }

    @Override
    public void onDisabled() {
        this.members.clear();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.valueOf(this.members.size())};
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE
                || !(event.getPacket() instanceof S02PacketChat)) {
            return;
        }
        String raw;
        try {
            raw = ((S02PacketChat) event.getPacket()).getChatComponent().getUnformattedText();
        } catch (Exception ignored) {
            return;
        }
        if (raw == null) {
            return;
        }
        String stripped = EnumChatFormatting.getTextWithoutFormattingCodes(raw).trim();
        if (stripped.isEmpty()) {
            return;
        }
        String lower = stripped.toLowerCase(Locale.ROOT);
        if (lower.contains("has invited you to the party") || lower.contains("invited you to join")) {
            announce("Invite: " + stripped);
            return;
        }
        if (lower.contains("disbanded") && lower.contains("party")) {
            this.members.clear();
            announce("Party disbanded");
            return;
        }
        Matcher joined = JOINED.matcher(stripped);
        if (joined.matches()) {
            this.members.add(joined.group(1));
            announce(joined.group(1) + " joined the party");
            return;
        }
        Matcher left = LEFT.matcher(stripped);
        if (left.matches()) {
            this.members.remove(left.group(1));
            announce(left.group(1) + " left the party");
        }
    }

    private void announce(String detail) {
        if (!this.alerts.getValue()) {
            return;
        }
        String suffix = "";
        if (this.showList.getValue() && !this.members.isEmpty()) {
            suffix = String.format(" &7(%d: %s)", this.members.size(), String.join(", ", this.members));
        }
        ChatUtil.sendFormatted(String.format("%s%s: &b%s&r%s", Myau.clientName, this.getName(), detail, suffix));
    }
}
