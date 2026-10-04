package myau.ui.impl.clickgui.riselb;

import myau.property.properties.BooleanProperty;
import myau.ui.impl.clickgui.modern.component.Component;
import myau.util.AnimationUtil;
import myau.util.RenderUtil;

import java.awt.Color;

/**
 * Boolean setting row, ported from setting/common/Switch.svelte.
 *
 * That component uses a smaller control than the card's own toggle: a 22x12
 * track with a 12px knob that overhangs it slightly, the track sitting at
 * 45% accent when checked rather than full accent. Keeping the two sizes
 * distinct is what stops a card full of settings from competing with the
 * card's own on/off switch.
 */
public class RiseLBSwitch extends Component {

    private static final float TRACK_W = 22.0f;
    private static final float TRACK_H = 8.0f;
    private static final float KNOB = 12.0f;

    private final BooleanProperty property;
    private float anim;

    public RiseLBSwitch(BooleanProperty property, int x, int y, int width, int height) {
        super(x, y, width, height);
        this.property = property;
        this.anim = property.getValue() ? 1.0f : 0.0f;
    }

    public BooleanProperty getProperty() {
        return property;
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTicks, float animationProgress,
                       boolean isLast, int scrollOffset, float deltaTime) {
        if (!property.isVisible()) {
            return;
        }
        anim = AnimationUtil.animateSmooth(property.getValue() ? 1.0f : 0.0f, anim, 14.0f, deltaTime);
        float eased = RiseLBTheme.spring(anim);

        RiseLBTheme.draw(12, property.getName(), x,
                RiseLBTheme.textY(y, height, 12),
                RiseLBTheme.solid(RiseLBTheme.TEXT, animationProgress));

        float trackX = x + width - TRACK_W;
        float trackY = y + (height - TRACK_H) / 2.0f;

        int off = RiseLBTheme.fade(new Color(255, 255, 255, 46).getRGB(), animationProgress);
        Color accent = RiseLBTheme.accent();
        int on = RiseLBTheme.rgba(accent, (int) (115 * animationProgress));
        RenderUtil.drawRoundedRect(trackX, trackY, TRACK_W, TRACK_H, TRACK_H / 2.0f,
                AnimationUtil.interpolateColor(off, on, eased), true, true, true, true);

        int knobColor = AnimationUtil.interpolateColor(
                RiseLBTheme.rgba(Color.WHITE, (int) (255 * animationProgress)),
                RiseLBTheme.rgba(accent, (int) (255 * animationProgress)), eased);
        float knobX = trackX + eased * (TRACK_W - KNOB);
        RenderUtil.drawRoundedRect(knobX, trackY - 2.0f, KNOB, KNOB, KNOB / 2.0f, knobColor,
                true, true, true, true);
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int mouseButton) {
        return false;
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int mouseButton, int scrollOffset) {
        if (mouseButton == 0 && mouseX >= x && mouseX <= x + width
                && mouseY >= y && mouseY <= y + height) {
            property.setValue(!property.getValue());
            return true;
        }
        return false;
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int mouseButton) {
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) {
    }
}
