package myau.module.modules;

// Ported from OpenSkid (GPL-3.0), itself rebuilt from the raven-bS AutoWho idea
// (cooldown-guarded /who plus a player-count HUD).
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
import myau.property.properties.TextProperty;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.EnumChatFormatting;

import java.awt.Color;
import java.util.Locale;

public class AutoWho extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final BooleanProperty autoSend = new BooleanProperty("auto-send", true);
    public final ModeProperty display = new ModeProperty("display", 2, new String[]{"CHAT", "HUD", "BOTH"});
    public final BooleanProperty hideOnline = new BooleanProperty("hide-online", false);
    public final IntProperty cooldown = new IntProperty("cooldown", 8, 1, 60);
    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 2.0F, () -> display.getValue() == 1);
    public final TextProperty hudFormat = new TextProperty("hud-format", "Players: %d", () -> display.getValue() == 2);
    public final BooleanProperty announce = new BooleanProperty("announce", true);

    private int playerCount;
    private long lastWhoAt;
    private int tick;

    public AutoWho() {
        super("AutoWho", false, false, "Automatically runs who command and shows player count.");
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
        return playerCount > 0 ? new String[]{String.valueOf(playerCount)} : new String[0];
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
        if (stripped.startsWith("ONLINE: ")) {
            String list = stripped.substring("ONLINE: ".length()).trim();
            playerCount = list.isEmpty() ? 0 : list.split(",").length;
            if (hideOnline.getValue()) {
                event.setCancelled(true);
                if (announce.getValue() && display.getValue() != 1) {
                    ChatUtil.sendFormatted(String.format("%s%s: &fPlayers: &e%d&r", Myau.clientName, getName(), playerCount));
                }
            }
            return;
        }
        if (!autoSend.getValue()) {
            return;
        }
        String lower = stripped.toLowerCase(Locale.ROOT);
        boolean started = lower.contains("the game has started") || lower.contains("game has started")
                || lower.contains("protect your bed") || lower.contains("has joined")
                || lower.contains("defend your bed");
        if (!started) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastWhoAt < (long) cooldown.getValue() * 1000L) {
            return;
        }
        lastWhoAt = now;
        if (mc.thePlayer != null) {
            mc.thePlayer.sendChatMessage("/who");
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!isEnabled() || event.getType() != EventType.POST) {
            return;
        }
        if (++tick % 40 != 0 || mc.theWorld == null) {
            return;
        }
        int alive = mc.theWorld.playerEntities.size();
        if (playerCount == 0 && alive > 0) {
            playerCount = alive;
        }
    }

    @EventTarget
    public void onRender(Render2DEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null || playerCount <= 0) {
            return;
        }
        if (display.getValue() == 0 || mc.gameSettings.showDebugInfo) {
            return;
        }
        String text = "Players: " + playerCount;
        if (display.getValue() == 2) {
            String format = hudFormat.getValue();
            if (format != null && format.contains("%d")) {
                try {
                    text = String.format(format, playerCount);
                } catch (Exception ignored) {
                    text = "Players: " + playerCount;
                }
            }
        }
        float scaleValue = scale.getValue();
        ScaledResolution resolution = new ScaledResolution(mc);
        float baseX = 4.0F;
        float baseY = 60.0F;
        if (baseX > resolution.getScaledWidth() || baseY > resolution.getScaledHeight()) {
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(baseX, baseY, 0.0F);
        GlStateManager.scale(scaleValue, scaleValue, 1.0F);
        int width = mc.fontRendererObj.getStringWidth(text);
        Gui.drawRect(-2, -2, width + 2, mc.fontRendererObj.FONT_HEIGHT + 1, new Color(0, 0, 0, 120).getRGB());
        mc.fontRendererObj.drawStringWithShadow(text, 0.0F, 0.0F, 16777215);
        GlStateManager.popMatrix();
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        reset();
    }

    private void reset() {
        playerCount = 0;
        lastWhoAt = 0L;
        tick = 0;
    }
}
