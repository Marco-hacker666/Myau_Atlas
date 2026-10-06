/*
 * Ported from OpenSkid (https://github.com/loloshelly102-a11y/OpenSkid, GPL-3.0),
 * src/main/java/openskid/module/modules/ClosestPlayerHUD.java, 2026-10-05: package openskid -> myau,
 * OpenSkid -> Myau. Changes after that are marked in comments.
 */
package myau.module.modules;

// Adapted from Expo ClosestPlayerHUD (nearest player per scoreboard team, own team first).
// Rebuilt with openskid TeamUtil bot checks and BedTracker style HUD props. No pasted code.
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.Render2DEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.ui.hud.HudLayout;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClosestPlayerHUD extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int MAX_ROWS = 8;

    public final BooleanProperty showName = new BooleanProperty("show-name", false);
    public final BooleanProperty showDistance = new BooleanProperty("show-distance", true);
    public final BooleanProperty showCount = new BooleanProperty("show-count", true);
    public final BooleanProperty showHeight = new BooleanProperty("show-height", true);
    public final BooleanProperty showHealth = new BooleanProperty("show-health", true);
    public final BooleanProperty background = new BooleanProperty("background", true);
    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 2.0F);
    public final IntProperty offsetX = new IntProperty("offset-x", 4, 0, 1000);
    public final IntProperty offsetY = new IntProperty("offset-y", 120, 0, 1000);

    private final List<Row> rows = new ArrayList<>();
    private int tick = 0;

    private static final class Row {
        final String team;
        final String name;
        final double distance;
        final int count;
        final int heightDiff;
        final int health;

        Row(String team, String name, double distance, int count, int heightDiff, int health) {
            this.team = team;
            this.name = name;
            this.distance = distance;
            this.count = count;
            this.heightDiff = heightDiff;
            this.health = health;
        }
    }

    public ClosestPlayerHUD() {
        super("ClosestPlayerHUD", false, false, "Shows nearest player info for each team on screen.");
    }

    @Override
    public void onEnabled() {
        this.rows.clear();
        this.tick = 0;
    }

    @Override
    public void onDisabled() {
        this.rows.clear();
        this.tick = 0;
    }

    @Override
    public String[] getSuffix() {
        double nearest = Double.MAX_VALUE;
        for (Row row : this.rows) {
            if (row.distance < nearest) {
                nearest = row.distance;
            }
        }
        return nearest == Double.MAX_VALUE ? new String[0] : new String[]{(int) nearest + "m"};
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.POST) {
            return;
        }
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        if (++this.tick % 5 != 0) {
            return;
        }
        this.rebuild();
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.rows.clear();
        this.tick = 0;
    }

    @EventTarget
    public void onRender(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (mc.gameSettings.showDebugInfo) {
            return;
        }
        if (this.rows.isEmpty()) {
            if (HudLayout.isEditing()) {
                float s = this.scale.getValue();
                int w = mc.fontRendererObj.getStringWidth("Closest player");
                HudLayout.report("ClosestPlayerHUD", "ClosestPlayerHUD (nobody near)", this.offsetX.getValue() - 2.0F * s,
                        this.offsetY.getValue() - 2.0F * s, (w + 4) * s, 12 * s,
                        HudLayout.ints(this.offsetX, 1, this.offsetY, 1));
            }
            return;
        }
        List<String> lines = new ArrayList<>();
        int width = 0;
        for (Row row : this.rows) {
            String line = this.format(row);
            lines.add(line);
            try {
                width = Math.max(width, mc.fontRendererObj.getStringWidth(line));
            } catch (Exception ignored) {
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        float scaleValue = this.scale.getValue();
        int height = lines.size() * 10;
        /* HUD editor (2026-10-05): report where this is drawn, so the editor can find and drag it. */
        HudLayout.report("ClosestPlayerHUD", "ClosestPlayerHUD", this.offsetX.getValue() - 2.0F * scaleValue,
                this.offsetY.getValue() - 2.0F * scaleValue, (width + 4) * scaleValue, (height + 2) * scaleValue,
                HudLayout.ints(this.offsetX, 1, this.offsetY, 1));
        GlStateManager.pushMatrix();
        GlStateManager.translate((float) this.offsetX.getValue(), (float) this.offsetY.getValue(), 0.0F);
        GlStateManager.scale(scaleValue, scaleValue, 1.0F);
        if (this.background.getValue()) {
            Gui.drawRect(-2, -2, width + 2, height, new Color(0, 0, 0, 120).getRGB());
        }
        for (int i = 0; i < lines.size(); i++) {
            mc.fontRendererObj.drawStringWithShadow(lines.get(i), 0.0F, (float) (i * 10), 16777215);
        }
        GlStateManager.popMatrix();
    }

    private String format(Row row) {
        StringBuilder builder = new StringBuilder(row.team);
        if (this.showName.getValue()) {
            builder.append(" ").append(row.name);
        }
        if (this.showDistance.getValue()) {
            builder.append(" ").append((int) row.distance).append("m");
        }
        if (this.showCount.getValue()) {
            builder.append(" §7(").append(row.count).append(")");
        }
        if (this.showHeight.getValue()) {
            if (row.heightDiff > 0) {
                builder.append(" §a▲").append(row.heightDiff);
            } else if (row.heightDiff < 0) {
                builder.append(" §c▼").append(-row.heightDiff);
            } else {
                builder.append(" §7■");
            }
        }
        if (this.showHealth.getValue()) {
            String color = row.health <= 8 ? "§c" : (row.health <= 14 ? "§e" : "§a");
            builder.append(" ").append(color).append(row.health);
        }
        return builder.toString();
    }

    private void rebuild() {
        Map<String, List<EntityPlayer>> groups = new LinkedHashMap<>();
        String ownTeam = teamKey(mc.thePlayer);
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == null || player == mc.thePlayer || player.isDead || player.isInvisible()) {
                continue;
            }
            try {
                if (TeamUtil.isBot(player)) {
                    continue;
                }
            } catch (Exception ignored) {
            }
            String key = teamKey(player);
            if (key == null) {
                continue;
            }
            if (!groups.containsKey(key)) {
                groups.put(key, new ArrayList<EntityPlayer>());
            }
            groups.get(key).add(player);
        }
        Row ownRow = null;
        List<Row> others = new ArrayList<>();
        for (Map.Entry<String, List<EntityPlayer>> entry : groups.entrySet()) {
            EntityPlayer nearest = null;
            double best = Double.MAX_VALUE;
            for (EntityPlayer player : entry.getValue()) {
                double distSq;
                try {
                    distSq = mc.thePlayer.getDistanceSqToEntity(player);
                } catch (Exception ignored) {
                    continue;
                }
                if (distSq < best) {
                    best = distSq;
                    nearest = player;
                }
            }
            if (nearest == null) {
                continue;
            }
            int heightDiff = (int) Math.round(nearest.posY - mc.thePlayer.posY);
            int health = 0;
            try {
                health = (int) Math.ceil(nearest.getHealth());
            } catch (Exception ignored) {
            }
            Row row = new Row(entry.getKey(), nearest.getName(), Math.sqrt(best), entry.getValue().size(), heightDiff, health);
            if (ownTeam != null && entry.getKey().equals(ownTeam)) {
                ownRow = row;
            } else {
                others.add(row);
            }
        }
        others.sort((left, right) -> Double.compare(left.distance, right.distance));
        this.rows.clear();
        if (ownRow != null) {
            this.rows.add(ownRow);
        }
        for (Row row : others) {
            if (this.rows.size() >= MAX_ROWS) {
                break;
            }
            this.rows.add(row);
        }
    }

    private static String teamKey(EntityPlayer player) {
        try {
            if (mc.getNetHandler() != null) {
                NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(player.getUniqueID());
                if (info != null && info.getPlayerTeam() != null) {
                    String prefix = info.getPlayerTeam().getColorPrefix();
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
}
