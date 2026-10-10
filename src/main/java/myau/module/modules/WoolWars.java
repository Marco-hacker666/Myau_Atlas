package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): passive intel only — middle-control chat
// alerts plus a wool count tracker. No automation, no rotation, no packets.
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.Render2DEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.EnumChatFormatting;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class WoolWars extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty display = new ModeProperty("display", 2, new String[]{"CHAT", "HUD", "BOTH"});
    public final BooleanProperty middleAlert = new BooleanProperty("middle-alert", true);
    public final BooleanProperty woolTracker = new BooleanProperty("wool-tracker", true);
    public final IntProperty woolGoal = new IntProperty("wool-goal", 16, 1, 64, () -> display.getValue() == 1);
    public final BooleanProperty announceGoal = new BooleanProperty("announce-goal", true, () -> display.getValue() == 2);
    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 2.0F);
    public final IntProperty offsetX = new IntProperty("offset-x", 4, 0, 1000);
    public final IntProperty offsetY = new IntProperty("offset-y", 110, 0, 1000);

    private int wool;
    private int middleNotices;
    private int tick;
    private boolean goalAnnounced;

    public WoolWars() {
        super("WoolWars", false, false, "Tracks Wool Wars middle events and wool count.");
    }

    @Override
    public void onEnabled() {
        reset();
    }

    @Override
    public void onDisabled() {
        reset();
    }

    @Override
    public String[] getSuffix() {
        if (woolTracker.getValue() && wool > 0) {
            return new String[]{String.valueOf(wool)};
        }
        return new String[]{display.getModeString()};
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || event.getType() != EventType.RECEIVE || !(event.getPacket() instanceof S02PacketChat)) {
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
        String stripped;
        try {
            stripped = EnumChatFormatting.getTextWithoutFormattingCodes(raw);
        } catch (Exception ignored) {
            return;
        }
        if (stripped == null || stripped.isEmpty()) {
            return;
        }
        String lower = stripped.toLowerCase(Locale.ROOT);
        boolean middle = lower.contains("control point") || lower.contains("middle")
                || (lower.contains("captur") && lower.contains("point"))
                || lower.contains("active round");
        if (middleAlert.getValue() && middle) {
            middleNotices++;
            if (display.getValue() == 0 || display.getValue() == 2) {
                ChatUtil.sendFormatted(String.format("%s%s: &eMiddle: &r%s", Myau.clientName, getName(), stripped.trim()));
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        if (++tick % 20 != 0) {
            return;
        }
        wool = countWool();
        if (!goalAnnounced && woolTracker.getValue() && woolGoal.getValue() > 0 && wool >= woolGoal.getValue()) {
            goalAnnounced = true;
            if (display.getValue() == 0 || (display.getValue() == 2 && announceGoal.getValue())) {
                ChatUtil.sendFormatted(String.format("%s%s: &fWool goal reached &e%d&r", Myau.clientName, getName(), wool));
            }
        }
        if (wool < woolGoal.getValue()) {
            goalAnnounced = false;
        }
    }

    @EventTarget
    public void onRender(Render2DEvent event) {
        if (!isEnabled() || !woolTracker.getValue() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (display.getValue() == 0 || mc.gameSettings.showDebugInfo) {
            return;
        }
        List<String> lines = new ArrayList<String>();
        lines.add("Wool: " + wool + "/" + woolGoal.getValue());
        if (middleAlert.getValue() && middleNotices > 0) {
            lines.add("Middle: " + middleNotices);
        }
        float scaleValue = scale.getValue();
        ScaledResolution resolution = new ScaledResolution(mc);
        float baseX = (float) offsetX.getValue();
        float baseY = (float) offsetY.getValue();
        if (baseX > resolution.getScaledWidth() || baseY > resolution.getScaledHeight()) {
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(baseX, baseY, 0.0F);
        GlStateManager.scale(scaleValue, scaleValue, 1.0F);
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, mc.fontRendererObj.getStringWidth(line));
        }
        int height = lines.size() * (mc.fontRendererObj.FONT_HEIGHT + 1);
        Gui.drawRect(-2, -2, width + 2, height, new Color(0, 0, 0, 120).getRGB());
        float y = 0.0F;
        for (String line : lines) {
            mc.fontRendererObj.drawStringWithShadow(line, 0.0F, y, 16777215);
            y += mc.fontRendererObj.FONT_HEIGHT + 1.0F;
        }
        GlStateManager.popMatrix();
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        reset();
    }

    private int countWool() {
        int count = 0;
        try {
            if (mc.thePlayer.inventory == null || mc.thePlayer.inventory.mainInventory == null) {
                return 0;
            }
            for (ItemStack stack : mc.thePlayer.inventory.mainInventory) {
                if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
                    continue;
                }
                try {
                    if (((ItemBlock) stack.getItem()).getBlock() == Blocks.wool) {
                        count += stack.stackSize;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return count;
    }

    private void reset() {
        wool = 0;
        middleNotices = 0;
        tick = 0;
        goalAnnounced = false;
    }
}
