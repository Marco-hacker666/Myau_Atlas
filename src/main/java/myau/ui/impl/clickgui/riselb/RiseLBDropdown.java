package myau.ui.impl.clickgui.riselb;

import myau.property.properties.ModeProperty;
import myau.ui.impl.clickgui.modern.component.Component;
import myau.util.AnimationUtil;
import myau.util.RenderUtil;

import java.awt.Color;
import java.util.Arrays;
import java.util.List;

/**
 * Mode setting, ported from setting/common/Dropdown.svelte.
 *
 * Closed it is a single row showing name and current value; open it pushes
 * the card taller and lists the options, the selected one in accent. The
 * options panel uses the search-field surface rather than a darker popup so
 * it stays inside the card's visual box.
 */
public class RiseLBDropdown extends Component {

    private static final float ITEM_HEIGHT = 17.0f;

    private final ModeProperty property;
    private final int headerHeight;
    private boolean expanded;
    private float expandAnim;

    public RiseLBDropdown(ModeProperty property, int x, int y, int width, int height) {
        super(x, y, width, height);
        this.property = property;
        this.headerHeight = height;
    }

    public ModeProperty getProperty() {
        return property;
    }

    private List<String> modes() {
        return Arrays.asList(property.getValuePrompt().split(", "));
    }

    @Override
    public int getHeight() {
        return (int) (headerHeight + expandAnim);
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTicks, float animationProgress,
                       boolean isLast, int scrollOffset, float deltaTime) {
        if (!property.isVisible()) {
            return;
        }

        RiseLBTheme.draw(12, property.getName(), x, RiseLBTheme.textY(y, headerHeight, 12),
                RiseLBTheme.solid(RiseLBTheme.TEXT_DIM, animationProgress));

        String current = property.getModeString();
        float valueWidth = RiseLBTheme.width(12, current);
        RiseLBTheme.draw(12, current, x + width - valueWidth - 12.0f,
                RiseLBTheme.textY(y, headerHeight, 12),
                RiseLBTheme.rgba(RiseLBTheme.accent(), (int) (255 * animationProgress)));

        RiseLBTheme.chevron(x + width - 5.0f, y + headerHeight / 2.0f, 7.0f,
                RiseLBTheme.spring(expandAnim / Math.max(1.0f, modes().size() * ITEM_HEIGHT)),
                RiseLBTheme.solid(RiseLBTheme.TEXT_DIM, animationProgress));

        List<String> modes = modes();
        float target = expanded ? modes.size() * ITEM_HEIGHT : 0.0f;
        expandAnim = AnimationUtil.animateSmooth(target, expandAnim, 13.0f, deltaTime);

        if (expandAnim <= 0.5f) {
            return;
        }

        float listY = y + headerHeight;
        RenderUtil.drawRoundedRect(x, listY, width, expandAnim, RiseLBTheme.CONTROL_RADIUS,
                RiseLBTheme.fade(RiseLBTheme.FIELD_BG, animationProgress), true, true, true, true);

        RenderUtil.scissor(x, listY, width, expandAnim);
        for (int i = 0; i < modes.size(); i++) {
            float itemY = listY + i * ITEM_HEIGHT;
            boolean hovered = mouseX >= x && mouseX <= x + width
                    && mouseY >= itemY && mouseY < itemY + ITEM_HEIGHT
                    && mouseY >= listY && mouseY <= listY + expandAnim;
            boolean selected = i == property.getValue();

            if (hovered) {
                RenderUtil.drawRoundedRect(x + 2.0f, itemY + 1.0f, width - 4.0f, ITEM_HEIGHT - 2.0f, 5.0f,
                        RiseLBTheme.fade(new Color(255, 255, 255, 18).getRGB(), animationProgress),
                        true, true, true, true);
            }
            int color = selected
                    ? RiseLBTheme.rgba(RiseLBTheme.accent(), (int) (255 * animationProgress))
                    : RiseLBTheme.solid(RiseLBTheme.TEXT_DIM, animationProgress);
            RiseLBTheme.draw(12, modes.get(i), x + 8.0f,
                    RiseLBTheme.textY(itemY, ITEM_HEIGHT, 12), color);
        }
        RenderUtil.releaseScissor();
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int mouseButton) {
        return false;
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int mouseButton, int scrollOffset) {
        if (mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + headerHeight) {
            expanded = !expanded;
            return true;
        }
        if (expanded && expandAnim > 0.5f) {
            float listY = y + headerHeight;
            List<String> modes = modes();
            for (int i = 0; i < modes.size(); i++) {
                float itemY = listY + i * ITEM_HEIGHT;
                if (mouseButton == 0 && mouseX >= x && mouseX <= x + width
                        && mouseY >= itemY && mouseY < itemY + ITEM_HEIGHT) {
                    property.setValue(i);
                    expanded = false;
                    return true;
                }
            }
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
