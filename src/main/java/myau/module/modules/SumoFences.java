package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): HUD text only — Sumo centre distance plus a
// round tracker. No automation, no packets.
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
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.EnumChatFormatting;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SumoFences extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty display = new ModeProperty("display", 0, new String[]{"FULL", "COMPACT"});
    public final BooleanProperty showDistance = new BooleanProperty("show-distance", true);
    public final BooleanProperty showRounds = new BooleanProperty("show-rounds", true);
    public final BooleanProperty chatRound = new BooleanProperty("chat-round", true);
    public final FloatProperty warnDist = new FloatProperty("warn-dist", 6.0F, 2.0F, 10.0F, () -> display.getValue() == 0);
    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 2.0F);
    public final IntProperty offsetX = new IntProperty("offset-x", 4, 0, 1000);
    public final IntProperty offsetY = new IntProperty("offset-y", 150, 0, 1000);

    private double centerX;
    private double centerZ;
    private boolean hasCenter;
    private int won;
    private int lost;
    private double dist;

    public SumoFences() {
        super("SumoFences", false, false, "Shows Sumo center distance and round score.");
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
        return won + lost > 0 ? new String[]{won + "-" + lost} : new String[0];
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
        boolean win = lower.contains("you won the duel") || lower.contains("you win the duel")
                || (lower.contains("duel") && lower.contains("victory"))
                || (lower.contains("round") && lower.contains("you won"));
        boolean loss = lower.contains("you lost the duel") || lower.contains("you lose the duel")
                || (lower.contains("duel") && lower.contains("defeat"))
                || (lower.contains("round") && lower.contains("you lost"))
                || lower.contains("you were knocked into the void");
        if (!win && !loss) {
            return;
        }
        if (win) {
            won++;
        } else {
            lost++;
        }
        if (chatRound.getValue()) {
            ChatUtil.sendFormatted(String.format("%s%s: &fRound %d &a%dW&r-&c%dL&r", Myau.clientName, getName(), won + lost, won, lost));
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!isEnabled() || event.getType() != EventType.POST || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        if (!hasCenter) {
            centerX = mc.thePlayer.posX;
            centerZ = mc.thePlayer.posZ;
            hasCenter = true;
        }
        double dx = mc.thePlayer.posX - centerX;
        double dz = mc.thePlayer.posZ - centerZ;
        dist = Math.round(Math.sqrt(dx * dx + dz * dz) * 10.0) / 10.0;
    }

    @EventTarget
    public void onRender(Render2DEvent event) {
        if (!isEnabled() || !hasCenter || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (mc.gameSettings.showDebugInfo) {
            return;
        }
        List<String> lines = new ArrayList<String>();
        if (display.getValue() == 1) {
            String compact = "R" + (won + lost + 1);
            if (showRounds.getValue() && won + lost > 0) {
                compact += " " + won + "-" + lost;
            }
            if (showDistance.getValue()) {
                compact += " " + dist + "m";
            }
            lines.add(compact);
        } else {
            if (showRounds.getValue()) {
                lines.add("Round: " + (won + lost + 1) + " (" + won + "-" + lost + ")");
            }
            if (showDistance.getValue()) {
                boolean warn = dist >= warnDist.getValue();
                lines.add((warn ? "! " : "") + "Center: " + dist + "m");
            }
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
        boolean warn = display.getValue() == 0 && showDistance.getValue() && dist >= warnDist.getValue();
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
            mc.fontRendererObj.drawStringWithShadow(line, 0.0F, y, warn ? 0xFF5555 : 16777215);
            y += mc.fontRendererObj.FONT_HEIGHT + 1.0F;
        }
        GlStateManager.popMatrix();
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        hasCenter = false;
        centerX = 0.0;
        centerZ = 0.0;
        dist = 0.0;
    }

    private void reset() {
        hasCenter = false;
        centerX = 0.0;
        centerZ = 0.0;
        dist = 0.0;
        won = 0;
        lost = 0;
    }
}
