package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): tab-health heuristics for enemy potion heals
// and phoenix resurrections, reported via chat plus HUD. Class tables have no
// Myau equivalent, so generic thresholds apply.
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.Render2DEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.util.ChatUtil;
import myau.util.SoundUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.IScoreObjectiveCriteria;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.world.WorldSettings;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MegaWallsDetector extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int LOW_HEALTH = 6;
    private static final int LOW_MEMORY_TICKS = 12;
    private static final int MIN_POTION_GAIN = 6;
    private static final int MAX_POTION_GAIN = 16;
    private static final int[] RESURRECT_VALUES = new int[]{20, 24, 32, 40, 44};
    private static final int MAX_HUD_ROWS = 5;

    public final BooleanProperty potionDetector = new BooleanProperty("potion-detector", true);
    public final BooleanProperty potionNotify = new BooleanProperty("potion-notify", true);
    public final BooleanProperty phoenixDetector = new BooleanProperty("phoenix-detector", true);
    public final BooleanProperty phoenixNotify = new BooleanProperty("phoenix-notify", true);
    public final BooleanProperty showHud = new BooleanProperty("show-hud", true);
    public final FloatProperty hudScale = new FloatProperty("hud-scale", 1.0F, 0.5F, 2.0F);
    public final IntProperty hudOffsetX = new IntProperty("hud-offset-x", 4, 0, 1000);
    public final IntProperty hudOffsetY = new IntProperty("hud-offset-y", 90, 0, 1000);

    private final Map<String, Integer> lastHealth = new HashMap<String, Integer>();
    private final Map<String, Integer> lowMemory = new HashMap<String, Integer>();
    private final Map<String, Integer> potions = new HashMap<String, Integer>();
    private final Set<String> resurrected = new HashSet<String>();
    private int tick = 0;

    public MegaWallsDetector() {
        super("MegaWallsDetector", false, false, "Tracks enemy potion uses and resurrections.");
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
        int total = 0;
        for (Integer value : this.potions.values()) {
            total += value;
        }
        return total > 0 || !this.resurrected.isEmpty()
                ? new String[]{total + "/" + this.resurrected.size()}
                : new String[0];
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.POST) {
            return;
        }
        if (mc.theWorld == null || mc.thePlayer == null || mc.getNetHandler() == null) {
            return;
        }
        if (++this.tick % 2 != 0) {
            return;
        }
        this.decayLowMemory();
        Set<String> seen = new HashSet<String>();
        try {
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                if (info == null || info.getGameProfile() == null) {
                    continue;
                }
                if (info.getGameType() == WorldSettings.GameType.SPECTATOR) {
                    continue;
                }
                String name = info.getGameProfile().getName();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                seen.add(name);
                int health = this.readHealth(name);
                if (health <= 0) {
                    this.lastHealth.remove(name);
                    continue;
                }
                Integer previous = this.lastHealth.get(name);
                this.lastHealth.put(name, health);
                if (previous == null) {
                    continue;
                }
                if (this.potionDetector.getValue() && health > previous) {
                    int gain = health - previous;
                    if (gain >= MIN_POTION_GAIN && gain <= MAX_POTION_GAIN) {
                        this.potions.put(name, this.potions.getOrDefault(name, 0) + 1);
                        if (this.potionNotify.getValue()) {
                            ChatUtil.sendFormatted(String.format("&f%s &edrank a potion &7(+%d%s&r&7)", name, gain / 2, "❤"));
                        }
                    }
                }
                if (this.phoenixDetector.getValue()) {
                    if (previous <= LOW_HEALTH && health > previous) {
                        this.lowMemory.put(name, LOW_MEMORY_TICKS);
                    }
                    if (this.lowMemory.containsKey(name) && health > previous && isResurrectValue(health)) {
                        this.lowMemory.remove(name);
                        if (this.resurrected.add(name) && this.phoenixNotify.getValue()) {
                            ChatUtil.sendFormatted(String.format("&f%s &b§lresurrected", name));
                            try {
                                SoundUtil.playSound("note.pling");
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        this.lastHealth.keySet().retainAll(seen);
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.reset();
    }

    @EventTarget
    public void onRender(Render2DEvent event) {
        if (!this.isEnabled() || !this.showHud.getValue()) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null || mc.gameSettings.showDebugInfo) {
            return;
        }
        if (this.potions.isEmpty() && this.resurrected.isEmpty()) {
            return;
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<Map.Entry<String, Integer>>(this.potions.entrySet());
        sorted.sort((left, right) -> Integer.compare(right.getValue(), left.getValue()));
        List<String> lines = new ArrayList<String>();
        for (Map.Entry<String, Integer> entry : sorted) {
            if (lines.size() >= MAX_HUD_ROWS) {
                break;
            }
            String marker = this.resurrected.contains(entry.getKey()) ? " §b✦" : "";
            lines.add(entry.getKey() + " §7(§a" + entry.getValue() + "§7)" + marker);
        }
        for (String name : this.resurrected) {
            if (lines.size() >= MAX_HUD_ROWS || this.potions.containsKey(name)) {
                continue;
            }
            lines.add(name + " §b✦");
        }
        if (lines.isEmpty()) {
            return;
        }
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, mc.fontRendererObj.getStringWidth(line));
        }
        ScaledResolution resolution = new ScaledResolution(mc);
        float baseX = (float) this.hudOffsetX.getValue();
        float baseY = (float) this.hudOffsetY.getValue();
        if (baseX < 0.0F || baseY < 0.0F || baseX > (float) resolution.getScaledWidth() || baseY > (float) resolution.getScaledHeight()) {
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(baseX, baseY, 0.0F);
        GlStateManager.scale(this.hudScale.getValue(), this.hudScale.getValue(), 1.0F);
        Gui.drawRect(-2, -2, width + 2, lines.size() * 10, new Color(0, 0, 0, 120).getRGB());
        for (int i = 0; i < lines.size(); i++) {
            mc.fontRendererObj.drawStringWithShadow(lines.get(i), 0.0F, (float) (i * 10), 16777215);
        }
        GlStateManager.popMatrix();
    }

    private int readHealth(String name) {
        try {
            EntityPlayer player = mc.theWorld.getPlayerEntityByName(name);
            if (player != null && !player.isDead) {
                return Math.round(player.getHealth());
            }
        } catch (Exception ignored) {
        }
        try {
            Scoreboard scoreboard = mc.theWorld.getScoreboard();
            ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(0);
            if (objective != null && objective.getRenderType() != IScoreObjectiveCriteria.EnumRenderType.HEARTS) {
                return scoreboard.getValueFromObjective(name, objective).getScorePoints();
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private void decayLowMemory() {
        if (this.lowMemory.isEmpty()) {
            return;
        }
        List<String> expired = new ArrayList<String>();
        for (Map.Entry<String, Integer> entry : this.lowMemory.entrySet()) {
            int left = entry.getValue() - 1;
            if (left <= 0) {
                expired.add(entry.getKey());
            } else {
                entry.setValue(left);
            }
        }
        for (String name : expired) {
            this.lowMemory.remove(name);
        }
    }

    private static boolean isResurrectValue(int health) {
        for (int value : RESURRECT_VALUES) {
            if (health == value) {
                return true;
            }
        }
        return false;
    }

    private void reset() {
        this.lastHealth.clear();
        this.lowMemory.clear();
        this.potions.clear();
        this.resurrected.clear();
        this.tick = 0;
    }
}
