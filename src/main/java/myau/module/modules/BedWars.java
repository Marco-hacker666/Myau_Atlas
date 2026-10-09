package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): passive chat alerts for enemy gear plus
// BedWars trap and upgrade window timers.
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
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
import myau.util.ChatUtil;
import myau.util.SoundUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S02PacketChat;

public class BedWars extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final BooleanProperty diamond = new BooleanProperty("diamond-armor", true);
    public final BooleanProperty fireball = new BooleanProperty("fireball", true);
    public final BooleanProperty pearl = new BooleanProperty("pearl", true);
    public final BooleanProperty obsidian = new BooleanProperty("obsidian", true);
    public final ModeProperty timers = new ModeProperty("timers", 0, new String[]{"OFF", "CHAT", "HUD"});
    public final IntProperty trapWindow = new IntProperty("trap-window", 30, 5, 120, () -> this.timers.getValue() == 1);
    public final IntProperty upgradeWindow = new IntProperty("upgrade-window", 6, 1, 12, () -> this.timers.getValue() == 2);
    public final BooleanProperty chatAlert = new BooleanProperty("chat-alert", true);
    public final BooleanProperty sound = new BooleanProperty("sound", true);

    private final Set<String> diamondNotified = new HashSet<String>();
    private final Map<String, String> heldNotified = new HashMap<String, String>();
    private int alerts;
    private int tick;
    private long lastTrapAt;
    private long lastUpgradeAt;

    public BedWars() {
        super("BedWars", false, false, "Alerts for enemy gear and tracks BedWars upgrades.");
    }

    @Override
    public void onEnabled() {
        this.reset();
    }

    @Override
    public void onDisabled() {
        this.reset();
    }

    @Override
    public String[] getSuffix() {
        if (this.alerts > 0) {
            return new String[]{String.valueOf(this.alerts)};
        }
        return new String[]{this.timers.getModeString()};
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        this.checkTimerExpiry();
        if (++this.tick % 10 != 0) {
            return;
        }
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == null || player == mc.thePlayer) {
                continue;
            }
            String name = player.getName();
            if (name == null) {
                continue;
            }
            if (this.diamond.getValue() && !this.diamondNotified.contains(name) && this.hasDiamondLeggings(player)) {
                this.diamondNotified.add(name);
                this.notify(name + " &fhas &bDiamond Armor&r");
            }
            String key = this.itemKey(player.getHeldItem());
            if (key == null) {
                this.heldNotified.remove(name);
                continue;
            }
            String prev = this.heldNotified.get(name);
            if (!key.equals(prev)) {
                this.heldNotified.put(name, key);
                int distance = 0;
                try {
                    distance = (int) mc.thePlayer.getDistanceToEntity(player);
                } catch (Exception ignored) {
                }
                this.notify(name + " &fis holding " + key + " &7(" + distance + "m)&r");
            }
        }
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
        long now = System.currentTimeMillis();
        if (lower.contains("trap")) {
            if (lower.contains("triggered") || lower.contains("set off")) {
                this.notify("&cTrap triggered!&r");
            } else if (lower.contains("purchased") && this.timers.getValue() != 0) {
                this.lastTrapAt = now;
            }
        }
        if (lower.contains("upgrade") || lower.contains("sharpened") || lower.contains("reinforced") || lower.contains("forge")) {
            if (lower.contains("purchased") && this.timers.getValue() != 0) {
                this.lastUpgradeAt = now;
            }
        }
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        this.reset();
    }

    private void checkTimerExpiry() {
        long now = System.currentTimeMillis();
        if (this.timers.getValue() == 1) {
            if (this.lastTrapAt > 0 && now - this.lastTrapAt >= (long) this.trapWindow.getValue() * 1000L) {
                this.lastTrapAt = 0L;
                this.notify("&fTrap window passed&r");
            }
            return;
        }
        if (this.timers.getValue() == 2) {
            if (this.lastUpgradeAt > 0 && now - this.lastUpgradeAt >= (long) this.upgradeWindow.getValue() * 60L * 1000L) {
                this.lastUpgradeAt = 0L;
                this.notify("&fUpgrade window passed&r");
            }
        }
    }

    private boolean hasDiamondLeggings(EntityPlayer player) {
        try {
            if (player.inventory == null || player.inventory.armorInventory == null || player.inventory.armorInventory.length < 2) {
                return false;
            }
            ItemStack leggings = player.inventory.armorInventory[1];
            return leggings != null && leggings.getItem() == Items.diamond_leggings;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String itemKey(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return null;
        }
        String unlocalized;
        try {
            unlocalized = stack.getItem().getUnlocalizedName();
        } catch (Exception ignored) {
            return null;
        }
        if (unlocalized == null) {
            return null;
        }
        String lower = unlocalized.toLowerCase(Locale.ROOT);
        if (this.pearl.getValue() && lower.contains("enderpearl")) {
            return "&3Ender Pearl&r";
        }
        if (this.obsidian.getValue() && lower.contains("obsidian")) {
            return "&dObsidian&r";
        }
        if (this.fireball.getValue() && (lower.contains("fireball") || lower.contains("fire_charge"))) {
            return "&6Fireball&r";
        }
        return null;
    }

    private void notify(String detail) {
        this.alerts++;
        if (this.chatAlert.getValue()) {
            ChatUtil.sendFormatted(String.format("%s%s: &eAlert: &r%s", Myau.clientName, this.getName(), detail));
        }
        if (this.sound.getValue()) {
            try {
                SoundUtil.playSound("note.pling");
            } catch (Exception ignored) {
            }
        }
    }

    private void reset() {
        this.diamondNotified.clear();
        this.heldNotified.clear();
        this.alerts = 0;
        this.tick = 0;
        this.lastTrapAt = 0L;
        this.lastUpgradeAt = 0L;
    }
}
