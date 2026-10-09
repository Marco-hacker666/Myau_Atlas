package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): BedWars quick-buy helper. Preset plus
// per-item toggles, fired when the shop GUI opens. Buy lines go out as chat
// commands, so this fits servers with a /buy style shop.
import java.util.ArrayDeque;
import java.util.Deque;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.util.ChatUtil;
import myau.util.ServerUtil;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiChest;

public class AutoBuy extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final TimerUtil timer = new TimerUtil();
    private final Deque<String> queue = new ArrayDeque<String>();

    public final ModeProperty preset = new ModeProperty("preset", 0, new String[]{"RUSH", "DEFENSE", "ARCHER"});
    public final BooleanProperty rushSword = new BooleanProperty("rush-sword", true, () -> this.preset.getValue() == 0);
    public final BooleanProperty rushBlocks = new BooleanProperty("rush-blocks", true, () -> this.preset.getValue() == 0);
    public final BooleanProperty defenseArmor = new BooleanProperty("defense-armor", true, () -> this.preset.getValue() == 1);
    public final BooleanProperty defenseTnt = new BooleanProperty("defense-tnt", true, () -> this.preset.getValue() == 1);
    public final BooleanProperty archerBow = new BooleanProperty("archer-bow", true, () -> this.preset.getValue() == 2);
    public final BooleanProperty archerArrows = new BooleanProperty("archer-arrows", true, () -> this.preset.getValue() == 2);
    public final FloatProperty delay = new FloatProperty("delay", 1.0F, 0.0F, 10.0F);

    private boolean armedForScreen;

    public AutoBuy() {
        super("AutoBuy", false, false, "Automatically buys preset BedWars shop items with commands.");
    }

    @Override
    public void onEnabled() {
        this.queue.clear();
        this.armedForScreen = false;
        this.timer.reset();
    }

    @Override
    public void onDisabled() {
        this.queue.clear();
        this.armedForScreen = false;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.preset.getModeString()};
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (!ServerUtil.isHypixel()) {
            this.armedForScreen = false;
            this.queue.clear();
            return;
        }
        if (!(mc.currentScreen instanceof GuiChest)) {
            this.armedForScreen = false;
            this.drainQueue();
            return;
        }
        if (!this.armedForScreen && this.isShopScreen((GuiChest) mc.currentScreen)) {
            this.armedForScreen = true;
            this.armQueue();
            this.timer.reset();
        }
        this.drainQueue();
    }

    private void armQueue() {
        this.queue.clear();
        switch (this.preset.getValue()) {
            case 0:
                if (this.rushSword.getValue()) {
                    this.queue.add("/buy sword");
                }
                if (this.rushBlocks.getValue()) {
                    this.queue.add("/buy blocks");
                }
                break;
            case 1:
                if (this.defenseArmor.getValue()) {
                    this.queue.add("/buy armor");
                }
                if (this.defenseTnt.getValue()) {
                    this.queue.add("/buy tnt");
                }
                break;
            default:
                if (this.archerBow.getValue()) {
                    this.queue.add("/buy bow");
                }
                if (this.archerArrows.getValue()) {
                    this.queue.add("/buy arrows");
                }
                break;
        }
        if (!this.queue.isEmpty()) {
            ChatUtil.sendFormatted(String.format("&7AutoBuy buying &b%d&r &7items", this.queue.size()));
        }
    }

    private void drainQueue() {
        if (this.queue.isEmpty() || mc.thePlayer == null) {
            return;
        }
        if (this.timer.hasTimeElapsed((long) (this.delay.getValue() * 1000.0F))) {
            this.timer.reset();
            mc.thePlayer.sendChatMessage(this.queue.poll());
        }
    }

    private boolean isShopScreen(GuiChest chest) {
        try {
            if (!(mc.thePlayer.openContainer instanceof net.minecraft.inventory.ContainerChest)) {
                return false;
            }
            String title = ((net.minecraft.inventory.ContainerChest) mc.thePlayer.openContainer)
                    .getLowerChestInventory().getDisplayName().getUnformattedText();
            if (title == null) {
                return false;
            }
            String lower = title.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("chestshop") || lower.contains("chest shop") || lower.contains("faction")
                    || lower.contains("auction") || lower.contains("market") || lower.contains("crate")) {
                return false;
            }
            return lower.contains("shop") || lower.contains("buy");
        } catch (Exception ignored) {
            return false;
        }
    }
}
