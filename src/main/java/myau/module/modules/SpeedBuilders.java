package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): tracks Speed Builders phases and hotbar
// block counts. Passive only -- no auto-build, no auto-swap.
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
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.EnumChatFormatting;

public class SpeedBuilders extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty display = new ModeProperty("display", 2, new String[]{"CHAT", "HUD", "BOTH"});
    public final BooleanProperty phaseTimer = new BooleanProperty("phase-timer", true);
    public final BooleanProperty blockHelper = new BooleanProperty("block-helper", true);
    public final IntProperty warnSecs = new IntProperty("warn-secs", 10, 1, 60, () -> this.display.getValue() == 1);
    public final BooleanProperty announcePhase = new BooleanProperty("announce-phase", true, () -> this.display.getValue() == 2);
    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 2.0F);
    public final IntProperty offsetX = new IntProperty("offset-x", 4, 0, 1000);
    public final IntProperty offsetY = new IntProperty("offset-y", 120, 0, 1000);

    private String phase = "";
    private long phaseAt;
    private int blocks;
    private int tick;
    private boolean warned;

    public SpeedBuilders() {
        super("SpeedBuilders", false, false, "Tracks Speed Builders phases and block counts.");
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
        return this.phase.isEmpty() ? new String[0] : new String[]{this.phase};
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE || !(event.getPacket() instanceof S02PacketChat)) {
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
        String next = null;
        if (lower.contains("recreate the build") || lower.contains("game starts in")) {
            next = "SHOWING";
        } else if (lower.contains("build phase") || lower.contains("start building") || lower.contains("time left")) {
            next = "BUILDING";
        } else if (lower.contains("judging") || lower.contains("vote")) {
            next = "JUDGING";
        } else if (lower.contains("perfect build") || lower.contains("round over")) {
            next = "DONE";
        }
        if (next != null && !next.equals(this.phase)) {
            this.setPhase(next);
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        if (++this.tick % 20 != 0) {
            return;
        }
        if (this.blockHelper.getValue()) {
            this.blocks = this.countBlocks();
        }
        if (this.phaseTimer.getValue() && "BUILDING".equals(this.phase) && !this.warned && this.warnSecs.getValue() > 0 && this.phaseAt > 0L) {
            long elapsed = (System.currentTimeMillis() - this.phaseAt) / 1000L;
            if (elapsed >= this.warnSecs.getValue()) {
                this.warned = true;
                if (this.display.getValue() == 0 || this.display.getValue() == 2) {
                    ChatUtil.sendFormatted(String.format("%s%s: &fBuild time check &7(%ds in, %d blocks held)&r",
                            Myau.clientName, this.getName(), elapsed, this.blocks));
                }
            }
        }
    }

    @EventTarget
    public void onRender(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (this.display.getValue() == 0 || mc.gameSettings.showDebugInfo || this.phase.isEmpty()) {
            return;
        }
        List<String> lines = new ArrayList<String>();
        if (this.phaseTimer.getValue()) {
            long secs = this.phaseAt > 0L ? (System.currentTimeMillis() - this.phaseAt) / 1000L : 0L;
            lines.add("Phase: " + this.phase + " " + secs + "s");
        }
        if (this.blockHelper.getValue()) {
            lines.add("Blocks: " + this.blocks);
        }
        if (lines.isEmpty()) {
            return;
        }
        float scaleValue = this.scale.getValue();
        ScaledResolution resolution = new ScaledResolution(mc);
        float baseX = (float) this.offsetX.getValue();
        float baseY = (float) this.offsetY.getValue();
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
        this.reset();
    }

    private void setPhase(String next) {
        this.phase = next;
        this.phaseAt = System.currentTimeMillis();
        this.warned = false;
        if (this.phaseTimer.getValue() && (this.display.getValue() == 0 || (this.display.getValue() == 2 && this.announcePhase.getValue()))) {
            ChatUtil.sendFormatted(String.format("%s%s: &fPhase &e%s&r", Myau.clientName, this.getName(), this.phase));
        }
    }

    private int countBlocks() {
        int count = 0;
        try {
            if (mc.thePlayer.inventory == null || mc.thePlayer.inventory.mainInventory == null) {
                return 0;
            }
            for (ItemStack stack : mc.thePlayer.inventory.mainInventory) {
                if (stack != null && stack.getItem() instanceof ItemBlock) {
                    count += stack.stackSize;
                }
            }
        } catch (Exception ignored) {
        }
        return count;
    }

    private void reset() {
        this.phase = "";
        this.phaseAt = 0L;
        this.blocks = 0;
        this.tick = 0;
        this.warned = false;
    }
}
