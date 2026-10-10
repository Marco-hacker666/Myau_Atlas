package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): chat plus HUD text only — role, map, mode
// and gold helpers for Murder Mystery. No ESP.
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
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.EnumChatFormatting;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MurderMystery extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty display = new ModeProperty("display", 2, new String[]{"CHAT", "HUD", "BOTH"});
    public final BooleanProperty showRole = new BooleanProperty("show-role", true);
    public final BooleanProperty showMap = new BooleanProperty("show-map", true);
    public final BooleanProperty showMode = new BooleanProperty("show-mode", true);
    public final IntProperty goldTarget = new IntProperty("gold-target", 10, 1, 64, () -> display.getValue() == 1);
    public final BooleanProperty announceRole = new BooleanProperty("announce-role", true, () -> display.getValue() == 2);
    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 2.0F);
    public final IntProperty offsetX = new IntProperty("offset-x", 4, 0, 1000);
    public final IntProperty offsetY = new IntProperty("offset-y", 120, 0, 1000);

    private String role = "";
    private String map = "";
    private String modeName = "";
    private int gold;
    private int tick;
    private boolean roleAnnounced;
    private boolean goldAnnounced;

    public MurderMystery() {
        super("MurderMystery", false, false, "Shows your role map and gold in Murder Mystery.");
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
        return role.isEmpty() ? new String[0] : new String[]{role};
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
        if (lower.contains("you are the murderer") || lower.contains("role: murderer")) {
            setRole("MURDERER");
        } else if (lower.contains("you are the detective") || lower.contains("role: detective")) {
            setRole("DETECTIVE");
        } else if (lower.contains("you are innocent") || lower.contains("you are an innocent") || lower.contains("role: innocent")) {
            setRole("INNOCENT");
        }
        if (showMap.getValue() && stripped.contains("Map:")) {
            map = stripped.substring(stripped.indexOf("Map:") + "Map:".length()).trim();
        }
        if (showMode.getValue() && stripped.contains("Mode:")) {
            modeName = stripped.substring(stripped.indexOf("Mode:") + "Mode:".length()).trim();
        }
        if (lower.contains("murderer") && (lower.contains("win") || lower.contains("kill"))) {
            if (display.getValue() == 0 || display.getValue() == 2) {
                ChatUtil.sendFormatted(String.format("%s%s: &e%s&r", Myau.clientName, getName(), stripped.trim()));
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!isEnabled() || event.getType() != EventType.POST || mc.thePlayer == null) {
            return;
        }
        if (++tick % 20 != 0) {
            return;
        }
        int count = 0;
        try {
            for (ItemStack stack : mc.thePlayer.inventory.mainInventory) {
                if (stack != null && stack.getItem() == Items.gold_ingot) {
                    count += stack.stackSize;
                }
            }
        } catch (Exception ignored) {
        }
        gold = count;
        if (!goldAnnounced && goldTarget.getValue() > 0 && gold >= goldTarget.getValue()) {
            goldAnnounced = true;
            if (display.getValue() == 0 || display.getValue() == 2) {
                ChatUtil.sendFormatted(String.format("%s%s: &fGold target reached &e%d&r", Myau.clientName, getName(), gold));
            }
        }
    }

    @EventTarget
    public void onRender(Render2DEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (display.getValue() == 0 || mc.gameSettings.showDebugInfo) {
            return;
        }
        List<String> lines = new ArrayList<String>();
        if (showRole.getValue() && !role.isEmpty()) {
            lines.add("Role: " + role);
        }
        if (showMap.getValue() && !map.isEmpty()) {
            lines.add("Map: " + map);
        }
        if (showMode.getValue() && !modeName.isEmpty()) {
            lines.add("Mode: " + modeName);
        }
        if (display.getValue() == 1) {
            lines.add("Gold: " + gold + "/" + goldTarget.getValue());
        }
        if (lines.isEmpty()) {
            return;
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

    private void setRole(String next) {
        role = next;
        if (!roleAnnounced && showRole.getValue()) {
            boolean chat = display.getValue() == 0 || (display.getValue() == 2 && announceRole.getValue());
            if (chat) {
                ChatUtil.sendFormatted(String.format("%s%s: &fYou are &e%s&r", Myau.clientName, getName(), role));
            }
            roleAnnounced = true;
        }
    }

    private void reset() {
        role = "";
        map = "";
        modeName = "";
        gold = 0;
        tick = 0;
        roleAnnounced = false;
        goldAnnounced = false;
    }
}
