/*
 * Ported from OpenSkid (https://github.com/loloshelly102-a11y/OpenSkid, GPL-3.0),
 * src/main/java/openskid/module/modules/FKCounter.java, 2026-10-05: package openskid -> myau,
 * OpenSkid -> Myau. Changes after that are marked in comments.
 */
package myau.module.modules;

// Adapted from Expo FKCounter (chat-driven final-kill attribution per scoreboard team).
// Rebuilt defensively: counts any chat line with FINAL KILL, resolves the killer
// team from tab info, degrades to an unknown bucket. No pasted code.
import myau.ui.impl.clickgui.atlas.HudPanel;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.Render2DEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.ui.hud.HudLayout;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.EnumChatFormatting;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FKCounter extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Pattern FINAL_BY = Pattern.compile(".*FINAL KILL.*by ([A-Za-z0-9_]{2,16}).*");
    private static final String UNKNOWN_TEAM = "§7?";

    public final BooleanProperty background = new BooleanProperty("background", true);
    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 2.0F);
    public final IntProperty offsetX = new IntProperty("offset-x", 4, 0, 1000);
    public final IntProperty offsetY = new IntProperty("offset-y", 60, 0, 1000);
    public final BooleanProperty showTotal = new BooleanProperty("show-total", true);

    private final Map<String, Integer> kills = new LinkedHashMap<>();
    private String summary = "";
    private int textWidth = 0;
    private int tick = 0;

    public FKCounter() {
        super("FKCounter", false, false, "Counts final kills per team from chat messages.");
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
        int total = this.total();
        return total > 0 ? new String[]{String.valueOf(total)} : new String[0];
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        if (!(event.getPacket() instanceof S02PacketChat)) {
            return;
        }
        String raw;
        try {
            raw = ((S02PacketChat) event.getPacket()).getChatComponent().getUnformattedText();
        } catch (Exception ignored) {
            return;
        }
        if (raw == null || !raw.contains("FINAL KILL")) {
            return;
        }
        String stripped;
        try {
            stripped = EnumChatFormatting.getTextWithoutFormattingCodes(raw);
        } catch (Exception ignored) {
            return;
        }
        if (stripped == null) {
            return;
        }
        Matcher matcher = FINAL_BY.matcher(stripped);
        if (matcher.matches()) {
            String killer = matcher.group(1);
            String team = this.teamOfName(killer);
            this.kills.put(team, this.kills.getOrDefault(team, 0) + 1);
        } else {
            this.kills.put(UNKNOWN_TEAM, this.kills.getOrDefault(UNKNOWN_TEAM, 0) + 1);
        }
        this.rebuild();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.POST) {
            return;
        }
        if (++this.tick % 20 != 0) {
            return;
        }
        this.rebuild();
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.reset();
    }

    @EventTarget
    public void onRender(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (mc.gameSettings.showDebugInfo) {
            return;
        }
        String text = this.summary.isEmpty()
                ? "FK: 0"
                : (this.showTotal.getValue() ? this.summary + " §7(" + this.total() + ")" : this.summary);
        float scaleValue = this.scale.getValue();
        ScaledResolution resolution = new ScaledResolution(mc);
        float baseX = (float) this.offsetX.getValue();
        float baseY = (float) this.offsetY.getValue();
        if (baseX < 0.0F || baseY < 0.0F || baseX > (float) resolution.getScaledWidth() || baseY > (float) resolution.getScaledHeight()) {
            return;
        }
        int boxWidth = Math.max(this.textWidth, mc.fontRendererObj.getStringWidth(text));
        /* HUD editor (2026-10-05): report where this is drawn, so the editor can find and drag it. */
        HudLayout.report("FKCounter", "FKCounter", baseX - 2.0F * scaleValue, baseY - 2.0F * scaleValue,
                (boxWidth + 4) * scaleValue, (mc.fontRendererObj.FONT_HEIGHT + 3) * scaleValue,
                HudLayout.ints(this.offsetX, 1, this.offsetY, 1));
        GlStateManager.pushMatrix();
        GlStateManager.translate(baseX, baseY, 0.0F);
        GlStateManager.scale(scaleValue, scaleValue, 1.0F);
        if (this.background.getValue()) {
            int width = boxWidth;
            if (HudPanel.active()) {
                HudPanel.panel(-2, -2, width + 2, mc.fontRendererObj.FONT_HEIGHT + 1);
            } else {
                Gui.drawRect(-2, -2, width + 2, mc.fontRendererObj.FONT_HEIGHT + 1, new Color(0, 0, 0, 120).getRGB());
            }
        }
        mc.fontRendererObj.drawStringWithShadow(text, 0.0F, 0.0F, 16777215);
        GlStateManager.popMatrix();
    }

    private String teamOfName(String name) {
        if (name == null || mc.theWorld == null) {
            return UNKNOWN_TEAM;
        }
        try {
            EntityPlayer player = mc.theWorld.getPlayerEntityByName(name);
            if (player != null) {
                String key = teamKey(player);
                if (key != null) {
                    return key;
                }
            }
        } catch (Exception ignored) {
        }
        return UNKNOWN_TEAM;
    }

    private static String teamKey(EntityPlayer player) {
        try {
            if (mc.getNetHandler() != null) {
                NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(player.getUniqueID());
                if (info != null && info.getPlayerTeam() != null) {
                    String prefix = ((ScorePlayerTeam) info.getPlayerTeam()).getColorPrefix();
                    if (prefix != null) {
                        for (int i = 0; i < prefix.length() - 1; i++) {
                            if (prefix.charAt(i) == '§') {
                                return "§" + prefix.charAt(i + 1);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            String formatted = player.getDisplayName().getFormattedText();
            if (formatted != null) {
                for (int i = 0; i < formatted.length() - 1; i++) {
                    if (formatted.charAt(i) == '§') {
                        return "§" + formatted.charAt(i + 1);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private int total() {
        int total = 0;
        for (Integer value : this.kills.values()) {
            total += value;
        }
        return total;
    }

    private void rebuild() {
        if (this.kills.isEmpty()) {
            this.summary = "";
            this.textWidth = 0;
            return;
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(this.kills.entrySet());
        sorted.sort((left, right) -> Integer.compare(right.getValue(), left.getValue()));
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                builder.append("§7 / ");
            }
            builder.append(sorted.get(i).getKey()).append(sorted.get(i).getValue());
        }
        this.summary = builder.toString();
        try {
            this.textWidth = mc.fontRendererObj == null
                    ? 0
                    : mc.fontRendererObj.getStringWidth(EnumChatFormatting.getTextWithoutFormattingCodes(this.summary));
        } catch (Exception ignored) {
            this.textWidth = 0;
        }
    }

    private void reset() {
        this.kills.clear();
        this.summary = "";
        this.textWidth = 0;
        this.tick = 0;
    }
}
