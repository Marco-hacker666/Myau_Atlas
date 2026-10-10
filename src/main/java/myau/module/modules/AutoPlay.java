package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): sniffs the "/play" click command out of the
// game-end message and runs it after a delay.
import java.util.regex.Pattern;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.util.ChatUtil;
import myau.util.ServerUtil;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.event.ClickEvent;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.IChatComponent;

public class AutoPlay extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Pattern PLAY_COMMAND = Pattern.compile("^/play\\s+\\w[\\w\\-]*$", Pattern.CASE_INSENSITIVE);
    private final TimerUtil timer = new TimerUtil();

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"HYPIXEL", "NONE"});
    public final FloatProperty delay = new FloatProperty("delay", 2.5F, 0.0F, 10.0F);

    private String queuedCommand;

    public AutoPlay() {
        super("AutoPlay", false, false, "Automatically runs queued play commands after games end.");
    }

    @Override
    public void onEnabled() {
        this.queuedCommand = null;
    }

    @Override
    public void onDisabled() {
        this.queuedCommand = null;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE
                || !(event.getPacket() instanceof S02PacketChat) || this.mode.getValue() != 0) {
            return;
        }
        S02PacketChat packet = (S02PacketChat) event.getPacket();
        if (packet.isChat()) {
            return;
        }
        if (!ServerUtil.isHypixel()) {
            return;
        }
        IChatComponent root;
        try {
            root = packet.getChatComponent();
        } catch (Exception ignored) {
            return;
        }
        if (root == null) {
            return;
        }
        String command = extractPlayCommand(root);
        if (command == null) {
            return;
        }
        this.queuedCommand = command;
        this.timer.reset();
        ChatUtil.sendFormatted(String.format("&7AutoPlay queued &b%s&r &7in %ss", command, this.delay.getValue()));
    }

    private static String extractPlayCommand(IChatComponent component) {
        try {
            if (component.getChatStyle() != null) {
                ClickEvent click = component.getChatStyle().getChatClickEvent();
                if (click != null && click.getAction() == ClickEvent.Action.RUN_COMMAND
                        && click.getValue() != null
                        && PLAY_COMMAND.matcher(click.getValue().trim()).matches()) {
                    return click.getValue().trim();
                }
            }
            if (component.getSiblings() != null) {
                for (IChatComponent sibling : component.getSiblings()) {
                    String found = extractPlayCommand(sibling);
                    if (found != null) {
                        return found;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || this.queuedCommand == null) {
            return;
        }
        if (this.timer.hasTimeElapsed((long) (this.delay.getValue() * 1000.0F)) && mc.thePlayer != null) {
            mc.thePlayer.sendChatMessage(this.queuedCommand);
            this.queuedCommand = null;
        }
    }
}
