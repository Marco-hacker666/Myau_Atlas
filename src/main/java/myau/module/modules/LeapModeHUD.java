package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): shows the current Spider leap mode, read from
// the server's own switch messages.
import java.awt.Color;

import myau.event.EventTarget;
import myau.events.PacketEvent;
import myau.events.Render2DEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.util.ServerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.EnumChatFormatting;

public class LeapModeHUD extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 2.0F);
    public final IntProperty offsetX = new IntProperty("offset-x", 0, -500, 500);
    public final IntProperty offsetY = new IntProperty("offset-y", 40, -500, 500);
    public final BooleanProperty background = new BooleanProperty("background", true);
    public final BooleanProperty spiderOnly = new BooleanProperty("spider-only", false);

    private String mode = "§6Arrow";

    public LeapModeHUD() {
        super("LeapModeHUD", false, false, "Shows your current Spider leap mode on screen.");
    }

    @Override
    public void onEnabled() {
        this.mode = "§6Arrow";
    }

    @Override
    public String[] getSuffix() {
        try {
            return new String[]{EnumChatFormatting.getTextWithoutFormattingCodes(this.mode)};
        } catch (Exception ignored) {
            return new String[0];
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || !(event.getPacket() instanceof S02PacketChat)) {
            return;
        }
        String text;
        try {
            text = ((S02PacketChat) event.getPacket()).getChatComponent().getUnformattedText();
        } catch (Exception ignored) {
            return;
        }
        if (text == null) {
            return;
        }
        if (text.contains("Your primary Leap skill switched to Arrow mode.")) {
            this.mode = "§6Arrow";
        } else if (text.contains("Your primary Leap skill switched to Arced mode.")) {
            this.mode = "§bArced";
        }
    }

    @EventTarget
    public void onRender(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (mc.gameSettings.showDebugInfo) {
            return;
        }
        if (this.spiderOnly.getValue() && !this.likelySpider()) {
            return;
        }
        String text = "Leap Mode: " + this.mode;
        ScaledResolution resolution = new ScaledResolution(mc);
        float centerX = (float) resolution.getScaledWidth() / 2.0F + (float) this.offsetX.getValue();
        float centerY = (float) resolution.getScaledHeight() / 2.0F + (float) this.offsetY.getValue();
        int width = mc.fontRendererObj.getStringWidth(text);
        int height = mc.fontRendererObj.FONT_HEIGHT;
        GlStateManager.pushMatrix();
        GlStateManager.translate(centerX, centerY, 0.0F);
        GlStateManager.scale(this.scale.getValue(), this.scale.getValue(), 1.0F);
        if (this.background.getValue()) {
            Gui.drawRect(-width / 2 - 4, -height / 2 - 3, width / 2 + 4, height / 2 + 3, new Color(0, 0, 0, 120).getRGB());
        }
        mc.fontRendererObj.drawStringWithShadow(text, (float) -width / 2.0F, (float) -height / 2.0F, 16777215);
        GlStateManager.popMatrix();
    }

    private boolean likelySpider() {
        try {
            for (String line : ServerUtil.getScoreboardLines()) {
                if (line != null && line.contains("Spider")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
