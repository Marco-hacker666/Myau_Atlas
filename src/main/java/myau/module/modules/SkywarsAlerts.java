package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): passive held-item scans with cooldowns,
// reported as chat plus HUD text. No ESP.
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
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
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SkywarsAlerts extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty display = new ModeProperty("display", 2, new String[]{"CHAT", "HUD", "BOTH"});
    public final BooleanProperty pearl = new BooleanProperty("pearl", true);
    public final BooleanProperty diamondSword = new BooleanProperty("diamond-sword", true);
    public final BooleanProperty fireSword = new BooleanProperty("fire-sword", true);
    public final BooleanProperty knockback = new BooleanProperty("knockback", true);
    public final BooleanProperty strength = new BooleanProperty("strength", true);
    public final FloatProperty cooldown = new FloatProperty("cooldown", 10.0F, 1.0F, 30.0F, () -> display.getValue() == 1);
    public final BooleanProperty showDistance = new BooleanProperty("show-distance", true, () -> display.getValue() == 2);
    public final IntProperty offsetX = new IntProperty("offset-x", 4, 0, 1000);
    public final IntProperty offsetY = new IntProperty("offset-y", 130, 0, 1000);

    private final Map<String, Long> lastAlert = new HashMap<String, Long>();
    private final List<String> recent = new ArrayList<String>();
    private int tick;

    public SkywarsAlerts() {
        super("SkywarsAlerts", false, false, "Alerts you when enemies hold dangerous SkyWars items.");
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
        return recent.isEmpty() ? new String[0] : new String[]{String.valueOf(recent.size())};
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (++tick % 10 != 0) {
            return;
        }
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == null || player == mc.thePlayer || player.isDead) {
                continue;
            }
            String name;
            try {
                name = player.getName();
            } catch (Exception ignored) {
                continue;
            }
            if (name == null || name.isEmpty()) {
                continue;
            }
            String key = itemKey(player.getHeldItem());
            if (key == null) {
                continue;
            }
            String id = name + ":" + key;
            long now = System.currentTimeMillis();
            Long last = lastAlert.get(id);
            long window = (long) (cooldown.getValue() * 1000.0F);
            if (last != null && now - last < window) {
                continue;
            }
            lastAlert.put(id, now);
            int distance = 0;
            try {
                distance = (int) mc.thePlayer.getDistanceToEntity(player);
            } catch (Exception ignored) {
            }
            String detail = name + " &fhas " + key;
            if (showDistance.getValue()) {
                detail += " &7(" + distance + "m)&r";
            } else {
                detail += "&r";
            }
            recent.add(name + ": " + stripCodes(key));
            if (recent.size() > 4) {
                recent.remove(0);
            }
            if (display.getValue() == 0 || display.getValue() == 2) {
                ChatUtil.sendFormatted(String.format("%s%s: &eAlert: &r%s", Myau.clientName, getName(), detail));
            }
        }
    }

    @EventTarget
    public void onRender(Render2DEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (display.getValue() == 0 || mc.gameSettings.showDebugInfo || recent.isEmpty()) {
            return;
        }
        float baseX = (float) offsetX.getValue();
        float baseY = (float) offsetY.getValue();
        ScaledResolution resolution = new ScaledResolution(mc);
        if (baseX > resolution.getScaledWidth() || baseY > resolution.getScaledHeight()) {
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(baseX, baseY, 0.0F);
        int width = 0;
        for (String line : recent) {
            width = Math.max(width, mc.fontRendererObj.getStringWidth(line));
        }
        int height = recent.size() * (mc.fontRendererObj.FONT_HEIGHT + 1);
        Gui.drawRect(-2, -2, width + 2, height, new Color(0, 0, 0, 120).getRGB());
        float y = 0.0F;
        for (String line : recent) {
            mc.fontRendererObj.drawStringWithShadow(line, 0.0F, y, 16777215);
            y += mc.fontRendererObj.FONT_HEIGHT + 1.0F;
        }
        GlStateManager.popMatrix();
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        reset();
    }

    private String itemKey(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return null;
        }
        try {
            if (pearl.getValue() && stack.getItem() == Items.ender_pearl) {
                return "&3Ender Pearl&r";
            }
            if (diamondSword.getValue() && stack.getItem() == Items.diamond_sword) {
                return "&bDiamond Sword&r";
            }
            if (fireSword.getValue() && stack.getItem() instanceof ItemSword
                    && EnchantmentHelper.getEnchantmentLevel(Enchantment.fireAspect.effectId, stack) > 0) {
                return "&cFire Sword&r";
            }
            if (knockback.getValue() && EnchantmentHelper.getEnchantmentLevel(Enchantment.knockback.effectId, stack) > 0) {
                return "&eKnockback&r";
            }
            if (strength.getValue()) {
                String unlocalized = stack.getItem().getUnlocalizedName();
                if (unlocalized != null && unlocalized.toLowerCase(Locale.ROOT).contains("potion")) {
                    try {
                        for (String line : stack.getTooltip(mc.thePlayer, false)) {
                            if (line != null && line.toLowerCase(Locale.ROOT).contains("strength")) {
                                return "&4Strength&r";
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private String stripCodes(String text) {
        try {
            return text.replaceAll("(?i)&[0-9a-fk-or]", "");
        } catch (Exception ignored) {
            return text;
        }
    }

    private void reset() {
        lastAlert.clear();
        recent.clear();
        tick = 0;
    }
}
