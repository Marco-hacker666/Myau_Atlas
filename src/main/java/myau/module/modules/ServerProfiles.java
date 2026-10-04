package myau.module.modules;

import myau.config.Config;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.TextProperty;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.play.server.S01PacketJoinGame;

import java.io.File;

/**
 * Loads a different saved config depending on which server was joined.
 *
 * What is safe on one server is not on another: the same backtrack window and
 * reach that pass unnoticed on a practice server are what a stricter anticheat
 * corrects, and the settings that suit it are a different set rather than a
 * softer version of the same one. Keeping those as separate saved configs is
 * already the answer -- the part that keeps going wrong is remembering to
 * switch before joining rather than three deaths afterwards.
 *
 * The rules are matched as plain substrings of the server address, in the order
 * written, so the specific can be put before the general. Only configs that
 * already exist are loaded: a missing file makes the client write the current
 * state out under that name, which would turn a typo into a new profile nobody
 * asked for, so a rule pointing at nothing is reported and skipped.
 *
 * Nothing is ever saved here. Switching profiles does change where the client's
 * own save-on-exit will go, which is what makes in-game adjustments stick to
 * the profile they were made under -- worth knowing before editing settings on
 * a server whose profile you did not intend to change.
 */
public class ServerProfiles extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File CONFIG_DIR = new File("./config/Myau/");

    /** {@code address fragment = config name}, comma separated, first match wins. */
    public final TextProperty rules = new TextProperty("profiles", "");
    public final BooleanProperty chat = new BooleanProperty("chat", true);

    private String applied;

    public ServerProfiles() {
        super("ServerProfiles", false, false,
                "Loads a saved config that matches the server you joined");
    }

    @Override
    public void onEnabled() {
        this.applied = null;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE
                || !(event.getPacket() instanceof S01PacketJoinGame)) {
            return;
        }
        /* Proxy networks send this again for every child server, so the work is
           guarded by which profile is already applied rather than by the packet
           being rare. */
        String address = address();
        if (address == null) {
            return;
        }
        String target = match(address);
        if (target == null || target.equals(this.applied)) {
            return;
        }
        /* Not applied here. This handler runs on the network thread, before
           vanilla has even created the new world and player, and a config load
           switches modules on and off -- their onEnabled/onDisabled run whatever
           they run. The next tick is on the client thread with a world and a
           player in place. */
        this.pendingTarget = target;
        this.pendingAddress = address;
    }

    private volatile String pendingTarget;
    private volatile String pendingAddress;

    @EventTarget
    public void onTick(myau.events.TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        String target = this.pendingTarget;
        String address = this.pendingAddress;
        if (target == null) {
            return;
        }
        this.pendingTarget = null;
        this.pendingAddress = null;
        if (!target.equals(this.applied)) {
            apply(target, address);
        }
    }

    private String address() {
        ServerData data = mc.getCurrentServerData();
        if (data == null || data.serverIP == null) {
            return null;
        }
        return data.serverIP.toLowerCase();
    }

    /** First rule whose fragment appears in the address. */
    private String match(String address) {
        String raw = this.rules.getValue();
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        for (String rule : raw.split(",")) {
            int equals = rule.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String fragment = rule.substring(0, equals).trim().toLowerCase();
            String config = rule.substring(equals + 1).trim();
            if (!fragment.isEmpty() && !config.isEmpty() && address.contains(fragment)) {
                return config;
            }
        }
        return null;
    }

    private void apply(String target, String address) {
        File file = new File(CONFIG_DIR, target + ".json");
        if (!file.exists()) {
            /* Loading a name that does not exist makes the client save the
               current state under it. Refusing keeps a mistyped rule from
               quietly becoming a profile. */
            if (this.chat.getValue()) {
                ChatUtil.sendFormatted("&7[&bServerProfiles&7] &cno config named &f"
                        + target + "&c, left alone");
            }
            this.applied = target;
            return;
        }
        this.applied = target;
        new Config(target, false).load();
        if (this.chat.getValue()) {
            ChatUtil.sendFormatted("&7[&bServerProfiles&7] loaded &f" + target
                    + "&7 for &f" + address);
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.applied == null ? "idle" : this.applied};
    }
}
