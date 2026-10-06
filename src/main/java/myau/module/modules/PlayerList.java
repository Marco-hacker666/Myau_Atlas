/*
 * Ported from OpenSkid (https://github.com/loloshelly102-a11y/OpenSkid, GPL-3.0),
 * src/main/java/openskid/module/modules/PlayerList.java, 2026-10-05: package openskid -> myau,
 * OpenSkid -> Myau. Changes after that are marked in comments.
 */
package myau.module.modules;

// Tablist HUD with name or ping sort plus a game-mode filter. Render shape follows FKCounter.
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.Render2DEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.ui.hud.HudLayout;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.WorldSettings;

import java.awt.Color;

public class PlayerList extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty sort = new ModeProperty("sort", 0, new String[]{"NAME", "PING"});
    public final ModeProperty filter = new ModeProperty("filter", 0, new String[]{"ALL", "SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR"});
    public final IntProperty maxLines = new IntProperty("max-lines", 10, 1, 40);
    public final BooleanProperty showPing = new BooleanProperty("show-ping", true);
    public final BooleanProperty background = new BooleanProperty("background", true);
    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 2.0F);
    public final IntProperty offsetX = new IntProperty("offset-x", 4, 0, 1000);
    public final IntProperty offsetY = new IntProperty("offset-y", 60, 0, 1000);

    private final List<String> lines = new ArrayList<>();
    private int tick;

    public PlayerList() {
        super("PlayerList", false, false, "Shows a custom on screen player list with ping.");
    }

    @Override
    public void onEnabled() {
        this.lines.clear();
        this.tick = 0;
    }

    @Override
    public void onDisabled() {
        this.lines.clear();
        this.tick = 0;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.valueOf(this.lines.size())};
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.POST) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null || mc.getNetHandler() == null) {
            return;
        }
        if (++this.tick % 20 != 0) {
            return;
        }
        rebuild();
    }

    @EventTarget
    public void onRender(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        boolean placeholder = this.lines.isEmpty();
        if (placeholder && !HudLayout.isEditing()) {
            return;
        }
        List<String> shown = placeholder ? java.util.Collections.singletonList("Player list") : this.lines;
        if (mc.gameSettings.showDebugInfo) {
            return;
        }
        float scaleValue = this.scale.getValue();
        ScaledResolution resolution = new ScaledResolution(mc);
        float baseX = (float) this.offsetX.getValue();
        float baseY = (float) this.offsetY.getValue();
        if (baseX < 0.0F || baseY < 0.0F || baseX > (float) resolution.getScaledWidth() || baseY > (float) resolution.getScaledHeight()) {
            return;
        }
        int lineHeight = mc.fontRendererObj.FONT_HEIGHT + 1;
        int width = 0;
        for (String line : shown) {
            width = Math.max(width, mc.fontRendererObj.getStringWidth(EnumChatFormatting.getTextWithoutFormattingCodes(line)));
        }
        /* HUD editor (2026-10-05): report where this is drawn, so the editor can find and drag it. */
        HudLayout.report("PlayerList", placeholder ? "PlayerList (empty)" : "PlayerList", baseX - 2.0F * scaleValue,
                baseY - 2.0F * scaleValue, (width + 4) * scaleValue, (shown.size() * lineHeight + 2) * scaleValue,
                HudLayout.ints(this.offsetX, 1, this.offsetY, 1));
        if (placeholder) {
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(baseX, baseY, 0.0F);
        GlStateManager.scale(scaleValue, scaleValue, 1.0F);
        if (this.background.getValue()) {
            Gui.drawRect(-2, -2, width + 2, this.lines.size() * lineHeight, new Color(0, 0, 0, 120).getRGB());
        }
        for (int i = 0; i < this.lines.size(); i++) {
            mc.fontRendererObj.drawStringWithShadow(this.lines.get(i), 0.0F, (float) (i * lineHeight), 16777215);
        }
        GlStateManager.popMatrix();
    }

    private void rebuild() {
        this.lines.clear();
        if (mc.getNetHandler() == null) {
            return;
        }
        List<NetworkPlayerInfo> infos = new ArrayList<>(mc.getNetHandler().getPlayerInfoMap());
        final WorldSettings.GameType wanted = wantedType();
        List<Entry> entries = new ArrayList<>();
        for (NetworkPlayerInfo info : infos) {
            if (info == null || info.getGameProfile() == null) {
                continue;
            }
            try {
                if (wanted != null && info.getGameType() != wanted) {
                    continue;
                }
                String name = info.getGameProfile().getName();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                String line = this.showPing.getValue() ? String.format("%s §7%dms", name, info.getResponseTime()) : name;
                entries.add(new Entry(line, name, info.getResponseTime()));
            } catch (Exception ignored) {
            }
        }
        if (this.sort.getValue() == 1) {
            Collections.sort(entries, new Comparator<Entry>() {
                @Override
                public int compare(Entry a, Entry b) {
                    return Integer.compare(a.ping, b.ping);
                }
            });
        } else {
            Collections.sort(entries, new Comparator<Entry>() {
                @Override
                public int compare(Entry a, Entry b) {
                    return String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name);
                }
            });
        }
        for (int i = 0; i < Math.min(entries.size(), this.maxLines.getValue()); i++) {
            this.lines.add(entries.get(i).line);
        }
    }

    private WorldSettings.GameType wantedType() {
        switch (this.filter.getValue()) {
            case 1:
                return WorldSettings.GameType.SURVIVAL;
            case 2:
                return WorldSettings.GameType.CREATIVE;
            case 3:
                return WorldSettings.GameType.ADVENTURE;
            case 4:
                return WorldSettings.GameType.SPECTATOR;
            default:
                return null;
        }
    }

    private static final class Entry {
        final String line;
        final String name;
        final int ping;

        Entry(String line, String name, int ping) {
            this.line = line;
            this.name = name;
            this.ping = ping;
        }
    }
}
