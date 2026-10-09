package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): passive SkyWars chat plus HUD text —
// strength windows, refill warnings and combat alerts. No ESP.
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
import myau.util.SoundUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.EnumChatFormatting;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SkyWars extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty display = new ModeProperty("display", 2, new String[]{"CHAT", "HUD", "BOTH"});
    public final BooleanProperty strength = new BooleanProperty("strength-alert", true);
    public final BooleanProperty combat = new BooleanProperty("combat-alert", true);
    public final BooleanProperty refill = new BooleanProperty("refill-alert", true);
    public final IntProperty strengthSecs = new IntProperty("strength-secs", 5, 1, 30, () -> display.getValue() == 1);
    public final BooleanProperty announceKiller = new BooleanProperty("announce-killer", true, () -> display.getValue() == 2);
    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 2.0F);
    public final IntProperty offsetX = new IntProperty("offset-x", 4, 0, 1000);
    public final IntProperty offsetY = new IntProperty("offset-y", 100, 0, 1000);
    public final BooleanProperty sound = new BooleanProperty("sound", true);

    private final Map<String, Long> strengthAt = new HashMap<String, Long>();
    private long refillAt;
    private int alerts;
    private int tick;

    public SkyWars() {
        super("SkyWars", false, false, "Shows SkyWars strength, refill and combat alerts.");
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
        prune();
        if (!strengthAt.isEmpty()) {
            return new String[]{strengthAt.size() + " str"};
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
        if (refill.getValue() && (lower.contains("refill") || lower.contains("chests have been"))) {
            refillAt = System.currentTimeMillis();
            notify("&bChests refilled&r");
            return;
        }
        if (lower.contains("time warp") || lower.contains("warped back")) {
            if (combat.getValue()) {
                notify("&dTime warp used nearby&r");
            }
            return;
        }
        if (strength.getValue() && stripped.endsWith(".") && looksLikeKill(lower)) {
            String killer = guessKiller(stripped);
            if (killer != null && !killer.equalsIgnoreCase(ownName())) {
                strengthAt.put(killer, System.currentTimeMillis());
                prune();
                if (display.getValue() == 0 || (display.getValue() == 2 && announceKiller.getValue())) {
                    notify("&c" + killer + " &fmay have &cStrength&r");
                } else {
                    alerts++;
                }
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (++tick % 20 == 0) {
            int before = strengthAt.size();
            prune();
            if (strengthAt.size() != before) {
                alerts++;
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
        prune();
        List<String> lines = new ArrayList<String>();
        if (strength.getValue() && !strengthAt.isEmpty()) {
            lines.add("Strength: " + strengthAt.size());
        }
        if (refill.getValue() && refillAt > 0L) {
            long secs = (System.currentTimeMillis() - refillAt) / 1000L;
            lines.add("Refill: " + secs + "s ago");
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

    private boolean looksLikeKill(String lower) {
        return lower.contains(" by ") || lower.contains(" with ") || lower.contains(" of ")
                || lower.contains(" from ") || lower.contains(" to ") || lower.contains(" for ");
    }

    private String guessKiller(String stripped) {
        try {
            String[] parts = stripped.split(" ");
            for (int i = parts.length - 1; i >= 0; i--) {
                String part = parts[i];
                if (part.endsWith(".")) {
                    String name = part.substring(0, part.length() - 1).trim();
                    if (!name.isEmpty() && name.length() <= 16) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String ownName() {
        try {
            return mc.thePlayer != null ? mc.thePlayer.getName() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private void prune() {
        if (strengthAt.isEmpty()) {
            return;
        }
        long window = Math.max(1, strengthSecs.getValue()) * 1000L;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = strengthAt.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (entry.getValue() == null || now - entry.getValue() >= window) {
                it.remove();
            }
        }
    }

    private void notify(String detail) {
        alerts++;
        if (display.getValue() == 0 || display.getValue() == 2) {
            ChatUtil.sendFormatted(String.format("%s%s: &eAlert: &r%s", Myau.clientName, getName(), detail));
        }
        if (sound.getValue()) {
            try {
                SoundUtil.playSound("note.pling");
            } catch (Exception ignored) {
            }
        }
    }

    private void reset() {
        strengthAt.clear();
        refillAt = 0L;
        alerts = 0;
        tick = 0;
    }
}
