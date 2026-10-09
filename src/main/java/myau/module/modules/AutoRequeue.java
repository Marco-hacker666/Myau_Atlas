package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): delayed play-again queue driven by chat.
import java.util.Locale;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.TextProperty;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.server.S02PacketChat;

public class AutoRequeue extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty trigger = new ModeProperty("trigger", 0, new String[]{"ANY_END", "WIN_ONLY", "LOSS_ONLY"});
    public final BooleanProperty playAgain = new BooleanProperty("play-again", true);
    public final BooleanProperty requeue = new BooleanProperty("requeue", true);
    public final TextProperty winText = new TextProperty("win-text", "VICTORY", () -> trigger.getValue() == 1);
    public final TextProperty lossText = new TextProperty("loss-text", "DEFEAT", () -> trigger.getValue() == 2);
    public final FloatProperty delay = new FloatProperty("delay", 1.5F, 0.0F, 5.0F);
    public final BooleanProperty alert = new BooleanProperty("alert", true);

    private String queued;
    private long queuedAt;

    public AutoRequeue() {
        super("AutoRequeue", false, false, "Automatically queues a new game after matches finish.");
    }

    @Override
    public void onEnabled() {
        this.clearQueue();
    }

    @Override
    public void onDisabled() {
        this.clearQueue();
    }

    @Override
    public String[] getSuffix() {
        if (this.queued != null) {
            return new String[]{"QUEUED"};
        }
        return new String[]{this.trigger.getModeString()};
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE || !(event.getPacket() instanceof S02PacketChat)) {
            return;
        }
        String raw;
        try {
            raw = ((S02PacketChat) event.getPacket()).getChatComponent().getUnformattedText();
        } catch (Exception ignored) {
            return;
        }
        if (raw == null || raw.isEmpty()) {
            return;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        boolean prompt = lower.contains("play again") || lower.contains("click here");
        boolean win = this.matchesWin(raw);
        boolean loss = this.matchesLoss(raw);
        if (this.trigger.getValue() == 1) {
            if (!win) {
                return;
            }
        } else if (this.trigger.getValue() == 2) {
            if (!loss) {
                return;
            }
        } else if (!prompt && !win && !loss) {
            return;
        }
        if (!this.playAgain.getValue() && !this.requeue.getValue()) {
            return;
        }
        if (this.queued == null) {
            this.queued = "/play again";
            this.queuedAt = System.currentTimeMillis();
            if (this.alert.getValue()) {
                ChatUtil.sendFormatted(String.format("%s%s: &fRequeueing in &e%.1fs&r", Myau.clientName, this.getName(), this.delay.getValue()));
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || this.queued == null) {
            return;
        }
        if (System.currentTimeMillis() - this.queuedAt >= (long) (this.delay.getValue() * 1000.0F)) {
            if (mc.thePlayer != null) {
                mc.thePlayer.sendChatMessage(this.queued);
            }
            this.clearQueue();
        }
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        this.clearQueue();
    }

    private boolean matchesWin(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        String custom = this.winText.getValue();
        if (custom != null && !custom.trim().isEmpty() && lower.contains(custom.trim().toLowerCase(Locale.ROOT))) {
            return true;
        }
        return lower.contains("victory") || lower.contains("you won") || lower.contains("you win") || lower.contains("#1");
    }

    private boolean matchesLoss(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        String custom = this.lossText.getValue();
        if (custom != null && !custom.trim().isEmpty() && lower.contains(custom.trim().toLowerCase(Locale.ROOT))) {
            return true;
        }
        return lower.contains("defeat") || lower.contains("you lost") || lower.contains("game over") || lower.contains("eliminated");
    }

    private void clearQueue() {
        this.queued = null;
        this.queuedAt = 0L;
    }
}
