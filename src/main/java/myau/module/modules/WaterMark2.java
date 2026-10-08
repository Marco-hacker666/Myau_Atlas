package myau.module.modules;

import myau.ui.impl.clickgui.atlas.HudPanel;
import myau.ui.UiMode;
import myau.ui.hud.HudLayout;

import myau.Myau;
import net.minecraft.client.Minecraft;
import myau.event.EventTarget;
import myau.events.Render2DEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.util.RenderUtil;
import myau.font.impl.UFontRenderer; // 必须导入自定义字体类

public class WaterMark2 extends Module {
    public final IntProperty rectLeft = new IntProperty("RectLeft", 2, 0, 20);
    public final IntProperty rectTop = new IntProperty("RectTop", 2, 0, 20);
    public final BooleanProperty shadow = new BooleanProperty("Shadow", true);

    public WaterMark2() {
        super("WaterMark2", false);
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled()) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return;

        UFontRenderer fr = Myau.fontManagers.getFont(20);
        String text = "Myau+";

        float textWidth = (float) fr.getStringWidth(text);
        float textHeight = (float) fr.getHeight();

        float padX = 6.0F;
        float padY = 4.0F;

        float startX = (float) rectLeft.getValue();
        float startY = (float) rectTop.getValue();

        float rectRight = startX + textWidth + (padX);
        float rectBottom = startY + textHeight + (padY);

        HudLayout.report("WaterMark2", "WaterMark2", startX, startY, rectRight - startX, rectBottom - startY,
                HudLayout.ints(this.rectLeft, 1, this.rectTop, 1));
        float radius = 4.0f;

        HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);

        int fillColor = UiMode.adapt(0x80000000);
        int hudColor = hud.getColor(System.currentTimeMillis()).getRGB();

        if (HudPanel.active()) {
            HudPanel.panel(startX, startY, rectRight, rectBottom);
        } else {
            RenderUtil.drawRoundedGradientOutlinedRectangle(
                    startX, startY, rectRight, rectBottom,
                    radius, fillColor, hudColor, hudColor
            );
        }

        fr.drawString(
                text,
                startX + padX / 2,
                startY,
                hudColor,
                shadow.getValue()
        );
    }
}