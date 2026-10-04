package myau.ui.impl.clickgui.riselb;

import myau.property.Property;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.PercentProperty;
import myau.ui.impl.clickgui.modern.component.Component;
import myau.util.RenderUtil;
import org.lwjgl.input.Mouse;

import java.awt.Color;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Numeric setting row: label and value on one line, a thin accent track below.
 *
 * Modelled on the nouislider styling the theme uses -- a 3px neutral track
 * with an accent fill and a small round handle, no tick marks or bubble.
 */
public class RiseLBSlider extends Component {

    private static final float TRACK_H = 3.0f;
    private static final float HANDLE = 8.0f;
    private static final double FLOAT_STEP = 0.05;

    private final Property property;
    private final double min;
    private final double max;
    private final boolean integral;
    private boolean dragging;

    public RiseLBSlider(Property property, int x, int y, int width, int height) {
        super(x, y, width, height);
        this.property = property;
        if (property instanceof IntProperty) {
            this.min = ((IntProperty) property).getMinimum();
            this.max = ((IntProperty) property).getMaximum();
            this.integral = true;
        } else if (property instanceof PercentProperty) {
            this.min = 0.0;
            this.max = 100.0;
            this.integral = true;
        } else {
            this.min = ((FloatProperty) property).getMinimum();
            this.max = ((FloatProperty) property).getMaximum();
            this.integral = false;
        }
    }

    public Property getProperty() {
        return property;
    }

    private double value() {
        if (property instanceof IntProperty || property instanceof PercentProperty) {
            return ((Integer) property.getValue()).doubleValue();
        }
        return ((Float) property.getValue()).doubleValue();
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTicks, float animationProgress,
                       boolean isLast, int scrollOffset, float deltaTime) {
        if (!property.isVisible()) {
            return;
        }
        if (dragging) {
            if (Mouse.isButtonDown(0)) {
                apply(mouseX);
            } else {
                dragging = false;
            }
        }

        double current = value();
        double span = max - min;
        float fill = span == 0.0 ? 0.0f : (float) ((current - min) / span);
        if (fill < 0.0f) {
            fill = 0.0f;
        } else if (fill > 1.0f) {
            fill = 1.0f;
        }

        String label = property.getName();
        String shown = (integral ? String.valueOf((long) current) : String.valueOf(round(current)))
                + (property instanceof PercentProperty ? "%" : "");

        RiseLBTheme.draw(12, label, x, y + 2.0f,
                RiseLBTheme.solid(RiseLBTheme.TEXT_DIM, animationProgress));
        RiseLBTheme.draw(12, shown, x + width - RiseLBTheme.width(12, shown), y + 2.0f,
                RiseLBTheme.solid(RiseLBTheme.TEXT, animationProgress));

        float trackY = y + height - 9.0f;
        RenderUtil.drawRoundedRect(x, trackY, width, TRACK_H, TRACK_H / 2.0f,
                RiseLBTheme.fade(new Color(255, 255, 255, 28).getRGB(), animationProgress),
                true, true, true, true);

        int accent = RiseLBTheme.rgba(RiseLBTheme.accent(), (int) (255 * animationProgress));
        if (fill > 0.0f) {
            RenderUtil.drawRoundedRect(x, trackY, width * fill, TRACK_H, TRACK_H / 2.0f, accent,
                    true, true, true, true);
        }
        float handleX = x + width * fill - HANDLE / 2.0f;
        if (handleX < x) {
            handleX = x;
        } else if (handleX > x + width - HANDLE) {
            handleX = x + width - HANDLE;
        }
        RenderUtil.drawRoundedRect(handleX, trackY - (HANDLE - TRACK_H) / 2.0f, HANDLE, HANDLE,
                HANDLE / 2.0f, accent, true, true, true, true);
    }

    private void apply(int mouseX) {
        double progress = (mouseX - x) / (double) width;
        if (progress < 0.0) {
            progress = 0.0;
        } else if (progress > 1.0) {
            progress = 1.0;
        }
        double next = min + (max - min) * progress;
        if (integral) {
            property.setValue((int) Math.round(next));
            return;
        }
        double stepped = Math.round(next / FLOAT_STEP) * FLOAT_STEP;
        stepped = Math.max(min, Math.min(max, round(stepped)));
        property.setValue((float) stepped);
    }

    private static double round(double value) {
        return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int mouseButton) {
        return false;
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int mouseButton, int scrollOffset) {
        if (mouseButton == 0 && mouseX >= x && mouseX <= x + width
                && mouseY >= y + height - 16.0f && mouseY <= y + height) {
            dragging = true;
            apply(mouseX);
            return true;
        }
        return false;
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int mouseButton) {
        dragging = false;
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int mouseButton, int scrollOffset) {
        dragging = false;
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) {
    }
}
