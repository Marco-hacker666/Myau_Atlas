package myau.script;

import myau.util.ChatUtil;

// Ported from OpenSkid (GPL-3.0): the base class a script file extends. The
// donor hands scripts live Minecraft, packet, render and reflection helpers;
// this API deliberately exposes none of that.
public abstract class Script {
    private String name = "unknown";

    // Called once by ScriptManager after construction. Package-private so
    // scripts cannot rename themselves behind the manager's back.
    final void initName(String scriptName) {
        if (scriptName != null && !scriptName.isEmpty()) {
            this.name = scriptName;
        }
    }

    public final String getName() {
        return this.name;
    }

    // Print a chat line. The only output channel scripts get.
    protected final void print(String message) {
        if (message == null) {
            return;
        }
        ChatUtil.sendFormatted("&7[&bScript&7:&b" + this.name + "&7]&r " + message);
    }

    // Lifecycle. All no-ops by default.
    public void onLoad() {
    }

    public void onEnable() {
    }

    public void onDisable() {
    }

    public void onTick() {
    }

    // Read-only chat copy. No message object, no formatting codes to abuse.
    public void onChat(String message) {
    }

    // Packet hooks get class name plus a summary string only. Never the live
    // packet, so scripts cannot mutate, replay or forge traffic directly.
    // Return true to allow, false to ask the caller to cancel.
    public boolean onPacketSend(String packetClass, String packetSummary) {
        return true;
    }

    public boolean onPacketReceive(String packetClass, String packetSummary) {
        return true;
    }
}
