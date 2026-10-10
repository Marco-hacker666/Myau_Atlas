package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): custom kill line with a {victim} placeholder,
// confirmed by the server's own kill message before it is sent.
import java.util.Locale;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.TextProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.S02PacketChat;

public class KillMessage extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final long TARGET_TIMEOUT_MS = 10000L;
    private static final long KILL_CHAT_WINDOW_MS = 8000L;
    private static final String[] KILL_TOKENS = {
        "was slain", "was killed", "killed", "eliminat", "final kill",
        "died", "fell", "burned", "drowned", "blew up", "was shot", "knocked"
    };

    public final TextProperty message = new TextProperty("message", "RIP {victim}");
    public final IntProperty delay = new IntProperty("delay", 0, 0, 50);
    public final BooleanProperty shout = new BooleanProperty("shout", false);

    private EntityPlayer target;
    private long lastAttackAt;
    private int ticks;
    private int kills;
    private String killChatVictim;
    private long killChatAt;

    public KillMessage() {
        super("KillMessage", false, false, "Sends a custom chat message after your kills.");
    }

    @Override
    public void onEnabled() {
        clearTarget();
    }

    @Override
    public void onDisabled() {
        clearTarget();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.valueOf(this.kills)};
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || !(event.getTarget() instanceof EntityPlayer)) {
            return;
        }
        this.target = (EntityPlayer) event.getTarget();
        this.lastAttackAt = System.currentTimeMillis();
        this.ticks = 0;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || this.target == null) {
            return;
        }
        if (this.target.isDead || this.target.getHealth() <= 0.0F) {
            if (this.ticks >= this.delay.getValue() && isChatConfirmed(this.target.getName())) {
                announce(this.target.getName());
            }
            this.target = null;
            return;
        }
        if (System.currentTimeMillis() - this.lastAttackAt > TARGET_TIMEOUT_MS) {
            this.target = null;
            return;
        }
        this.ticks++;
    }

    @EventTarget
    public void onChat(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE
                || !(event.getPacket() instanceof S02PacketChat) || this.target == null) {
            return;
        }
        String message;
        try {
            message = ((S02PacketChat) event.getPacket()).getChatComponent().getUnformattedText();
        } catch (Exception ignored) {
            return;
        }
        if (message == null) {
            return;
        }
        String name = this.target.getName();
        if (name == null || name.isEmpty()) {
            return;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (!lower.contains(name.toLowerCase(Locale.ROOT))) {
            return;
        }
        for (String token : KILL_TOKENS) {
            if (lower.contains(token)) {
                this.killChatVictim = name;
                this.killChatAt = System.currentTimeMillis();
                return;
            }
        }
    }

    private boolean isChatConfirmed(String victim) {
        return victim != null && victim.equalsIgnoreCase(this.killChatVictim)
                && System.currentTimeMillis() - this.killChatAt <= KILL_CHAT_WINDOW_MS;
    }

    private void announce(String victim) {
        if (mc.thePlayer == null) {
            return;
        }
        String text = this.message.getValue().replace("{victim}", victim);
        if (text.trim().isEmpty()) {
            return;
        }
        if (this.shout.getValue()) {
            text = "/shout " + text;
        }
        mc.thePlayer.sendChatMessage(text);
        this.kills++;
    }

    private void clearTarget() {
        this.target = null;
        this.lastAttackAt = 0L;
        this.ticks = 0;
        this.killChatVictim = null;
        this.killChatAt = 0L;
    }
}
