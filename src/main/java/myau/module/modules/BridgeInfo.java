package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): enemy, score, distance and block HUD for
// Bridge duels, read from the server's own chat lines.
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.EnumChatFormatting;

public class BridgeInfo extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty display = new ModeProperty("display", 0, new String[]{"FULL", "COMPACT"});
    public final BooleanProperty showEnemy = new BooleanProperty("show-enemy", true);
    public final BooleanProperty showTimer = new BooleanProperty("show-timer", true);
    public final BooleanProperty showBlocks = new BooleanProperty("show-blocks", true, () -> display.getValue() == 0);
    public final BooleanProperty chatScore = new BooleanProperty("chat-score", true);
    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 2.0F);
    public final IntProperty offsetX = new IntProperty("offset-x", 5, 0, 1000);
    public final IntProperty offsetY = new IntProperty("offset-y", 70, 0, 1000);

    private String enemy = "";
    private int goalsUs;
    private int goalsThem;
    private long startAt;
    private int blocks;
    private double foeDist = -1.0;

    public BridgeInfo() {
        super("BridgeInfo", false, false, "Shows enemy, score and block info for Bridge duels.");
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
        if (goalsUs > 0 || goalsThem > 0) {
            return new String[]{goalsUs + "-" + goalsThem};
        }
        return enemy.isEmpty() ? new String[0] : new String[]{enemy};
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
        if (stripped.contains("Opponent:")) {
            String name = stripped.substring(stripped.indexOf("Opponent:") + "Opponent:".length()).trim();
            if (name.contains(" ")) {
                String[] parts = name.split(" ");
                name = parts[parts.length - 1];
            }
            if (name.startsWith("[") && name.contains("]")) {
                name = name.substring(name.indexOf("]") + 1).trim();
            }
            enemy = name;
            goalsUs = 0;
            goalsThem = 0;
            startAt = System.currentTimeMillis();
            foeDist = -1.0;
            return;
        }
        if (lower.contains("scored") || lower.contains("goal")) {
            if (!enemy.isEmpty() && stripped.contains(enemy)) {
                goalsThem++;
            } else if (mc.thePlayer != null && (lower.contains("you scored") || lower.contains("your team scored")
                    || stripped.contains(mc.thePlayer.getName()))) {
                goalsUs++;
            } else {
                return;
            }
            if (chatScore.getValue()) {
                ChatUtil.sendFormatted(String.format("%s%s: &fScore &a%d&r-&c%d&r", Myau.clientName, getName(), goalsUs, goalsThem));
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!isEnabled() || event.getType() != EventType.POST || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        if (mc.thePlayer.ticksExisted % 10 != 0) {
            return;
        }
        if (!enemy.isEmpty()) {
            EntityPlayer foe = mc.theWorld.getPlayerEntityByName(enemy);
            if (foe != null && foe != mc.thePlayer) {
                try {
                    foeDist = Math.round(mc.thePlayer.getDistanceToEntity(foe) * 10.0) / 10.0;
                } catch (Exception ignored) {
                    foeDist = -1.0;
                }
            } else {
                foeDist = -1.0;
            }
        }
        int count = 0;
        try {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
                if (stack != null && stack.getItem() instanceof ItemBlock) {
                    count += stack.stackSize;
                }
            }
        } catch (Exception ignored) {
        }
        blocks = count;
    }

    @EventTarget
    public void onRender(Render2DEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null || enemy.isEmpty()) {
            return;
        }
        if (mc.gameSettings.showDebugInfo) {
            return;
        }
        List<String> lines = new ArrayList<String>();
        if (showEnemy.getValue()) {
            lines.add("Enemy: " + enemy);
        }
        lines.add("Score: " + goalsUs + "-" + goalsThem);
        if (display.getValue() == 0) {
            if (foeDist >= 0.0) {
                lines.add("Foe: " + foeDist + "m");
            }
            if (showBlocks.getValue()) {
                lines.add("Blocks: " + blocks);
            }
            if (showTimer.getValue() && startAt > 0) {
                long seconds = (System.currentTimeMillis() - startAt) / 1000L;
                lines.add("Time: " + seconds / 60L + ":" + String.format("%02d", seconds % 60L));
            }
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
            mc.fontRendererObj.drawStringWithShadow(line, 0.0F, y, 0x00C8C8);
            y += mc.fontRendererObj.FONT_HEIGHT + 1.0F;
        }
        GlStateManager.popMatrix();
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        reset();
    }

    private void reset() {
        enemy = "";
        goalsUs = 0;
        goalsThem = 0;
        startAt = 0L;
        blocks = 0;
        foeDist = -1.0;
    }
}
