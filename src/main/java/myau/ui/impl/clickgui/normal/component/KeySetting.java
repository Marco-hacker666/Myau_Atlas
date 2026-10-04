package myau.ui.impl.clickgui.normal.component;

import lombok.Getter;
import myau.property.properties.KeyProperty;
import myau.ui.impl.clickgui.normal.MaterialTheme;
import myau.util.font.FontManager;
import org.lwjgl.input.Keyboard;

public class KeySetting extends Component {
    private final KeyProperty property;
    @Getter
    private boolean binding;

    public KeySetting(KeyProperty property, int x, int y, int width, int height) {
        super(x, y, width, height);
        this.property = property;
        this.binding = false;
    }

    public KeyProperty getProperty() {
        return this.property;
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTicks, float animationProgress, boolean isLast, int scrollOffset, float deltaTime) {
        float easedProgress = 1.0f - (float) Math.pow(1.0f - animationProgress, 4);
        if (easedProgress <= 0) return;
        int scrolledY = y - scrollOffset;
        int alpha = (int) (255 * easedProgress);
        if (easedProgress > 0.9f) {
            int textColor = MaterialTheme.getRGBWithAlpha(MaterialTheme.TEXT_COLOR, alpha);
            int valueColor;
            if (binding) {
                valueColor = MaterialTheme.getRGBWithAlpha(MaterialTheme.PRIMARY_COLOR, alpha);
            } else {
                valueColor = MaterialTheme.getRGBWithAlpha(MaterialTheme.TEXT_COLOR_SECONDARY, alpha);
            }
            String nameText = property.getName();
            String bindText;
            if (binding) {
                bindText = "...";
            } else {
                bindText = property.getKeyName();
            }
            float textY = scrolledY + (height - 8) / 2f;
            if (FontManager.productSans16 != null) {
                FontManager.productSans16.drawString(nameText, x + 6, textY, textColor);
                float w = (float) FontManager.productSans16.getStringWidth(bindText);
                FontManager.productSans16.drawString(bindText, x + width - w - 6, textY, valueColor);
            } else {
                mc.fontRendererObj.drawStringWithShadow(nameText, x + 6, scrolledY + 6, textColor);
                mc.fontRendererObj.drawStringWithShadow(bindText, x + width - mc.fontRendererObj.getStringWidth(bindText) - 6, scrolledY + 6, valueColor);
            }
        }
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int mouseButton) {
        return false;
    }

    public boolean mouseClicked(int mouseX, int mouseY, int mouseButton, int scrollOffset) {
        if (isMouseOver(mouseX, mouseY, scrollOffset) && mouseButton == 0) {
            this.binding = !this.binding;
            return true;
        }
        if (this.binding && !isMouseOver(mouseX, mouseY, scrollOffset)) {
            this.binding = false;
        }
        return false;
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) {
        if (this.binding) {
            if (keyCode == Keyboard.KEY_DELETE || keyCode == Keyboard.KEY_BACK || keyCode == Keyboard.KEY_ESCAPE) {
                property.setValue(Keyboard.KEY_NONE);
            } else {
                property.setValue(keyCode);
            }
            this.binding = false;
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int mouseButton) {
    }
}
