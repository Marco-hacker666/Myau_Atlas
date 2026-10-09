package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): held-item scan plus chat flags and a
// traitor id set for Hypixel Murder Mystery.
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.TextProperty;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S02PacketChat;

public class MurderDetector extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Set<Integer> KNIFE_IDS = new HashSet<Integer>();
    private static final Set<UUID> MURDERER_IDS = new HashSet<UUID>();
    private static final String[] ROLE_HINTS = {
        "you are the murderer", "a murderer", "the murderer has", "murderer wins",
        "has picked up the knife", "knife has been dropped"
    };

    static {
        int[] ids = {267, 272, 256, 268, 276, 283, 271, 273, 277, 279, 285, 359};
        for (int id : ids) {
            KNIFE_IDS.add(id);
        }
    }

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"HYPIXEL", "GENERIC"});
    public final BooleanProperty chatHints = new BooleanProperty("chat-hints", true);
    public final BooleanProperty highlight = new BooleanProperty("highlight", true);
    public final TextProperty suspectWords = new TextProperty("suspect-words", "knife,sword", () -> mode.getValue() == 1);

    private final Set<UUID> notified = new HashSet<UUID>();
    private final Map<UUID, Long> addedAt = new HashMap<UUID, Long>();
    private final Map<UUID, String> names = new HashMap<UUID, String>();
    private static final long EXPIRY_MS = 120000L;
    private static final List<String> traitors = new ArrayList<String>();

    public MurderDetector() {
        super("MurderDetector", false, false, "Detects and announces the Murder Mystery murderer.");
    }

    public static boolean isMurderer(EntityPlayer player) {
        return player != null && MURDERER_IDS.contains(player.getUniqueID());
    }

    public static List<String> getTraitors() {
        return new ArrayList<String>(traitors);
    }

    @Override
    public void onEnabled() {
        this.clearDetections();
    }

    @Override
    public void onDisabled() {
        this.clearDetections();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.valueOf(MURDERER_IDS.size())};
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE
                || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        if (mc.thePlayer.ticksExisted % 10 != 0) {
            return;
        }
        this.expireEntries();
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            this.checkPlayer(player);
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || !this.chatHints.getValue()
                || event.getType() != EventType.RECEIVE
                || !(event.getPacket() instanceof S02PacketChat)) {
            return;
        }
        String message = ((S02PacketChat) event.getPacket()).getChatComponent().getUnformattedText();
        if (message == null) {
            return;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        for (String hint : ROLE_HINTS) {
            if (lower.contains(hint)) {
                ChatUtil.sendFormatted(String.format("%s%s: &e%s&r", Myau.clientName, this.getName(), message.trim()));
                return;
            }
        }
        if (this.mode.getValue() == 1) {
            for (String word : this.suspectWords.getValue().split(",")) {
                String trimmed = word.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty() && lower.contains(trimmed)) {
                    ChatUtil.sendFormatted(String.format("%s%s: &e%s&r", Myau.clientName, this.getName(), message.trim()));
                    return;
                }
            }
        }
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        this.clearDetections();
    }

    private void checkPlayer(EntityPlayer player) {
        if (player == null || player == mc.thePlayer || !this.highlight.getValue() || isMurderer(player)) {
            return;
        }
        if (this.isMurderItem(player.getHeldItem())) {
            this.addMurderer(player);
        }
    }

    private boolean isMurderItem(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return false;
        }
        if (!KNIFE_IDS.contains(Item.getIdFromItem(stack.getItem()))) {
            return false;
        }
        String display = stack.getDisplayName();
        if (display == null) {
            return false;
        }
        String lower = display.toLowerCase(Locale.ROOT);
        if (lower.contains("knife")) {
            return true;
        }
        if (this.mode.getValue() == 1) {
            for (String word : this.suspectWords.getValue().split(",")) {
                String trimmed = word.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty() && lower.contains(trimmed)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void addMurderer(EntityPlayer player) {
        MURDERER_IDS.add(player.getUniqueID());
        this.addedAt.put(player.getUniqueID(), System.currentTimeMillis());
        this.names.put(player.getUniqueID(), player.getName());
        if (!traitors.contains(player.getName())) {
            traitors.add(player.getName());
        }
        if (this.notified.add(player.getUniqueID()) && this.chatHints.getValue()) {
            ChatUtil.sendFormatted(String.format("%s%s: &e%s &fis Murderer!&r", Myau.clientName, this.getName(), player.getName()));
        }
    }

    private void expireEntries() {
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<UUID>(this.addedAt.keySet())) {
            Long at = this.addedAt.get(id);
            if (at != null && now - at >= EXPIRY_MS) {
                this.addedAt.remove(id);
                MURDERER_IDS.remove(id);
                this.notified.remove(id);
                String name = this.names.remove(id);
                if (name != null) {
                    traitors.remove(name);
                }
            }
        }
    }

    private void clearDetections() {
        MURDERER_IDS.clear();
        this.notified.clear();
        this.addedAt.clear();
        this.names.clear();
        traitors.clear();
    }
}
