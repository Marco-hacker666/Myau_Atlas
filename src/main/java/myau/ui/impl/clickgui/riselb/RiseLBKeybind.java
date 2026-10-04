package myau.ui.impl.clickgui.riselb;

import myau.property.properties.KeyProperty;
import myau.ui.impl.clickgui.modern.component.Component;
import org.lwjgl.input.Keyboard;

/**
 * Binds a KeyProperty. Click to arm, then press the key; Escape, Delete or
 * Backspace clears it.
 *
 * Kept separate from KeybindComponent because that one is hard-wired to a
 * module's own toggle key, while this edits an arbitrary setting.
 */
public class RiseLBKeybind extends Component {

    private final KeyProperty property;
    private boolean binding;

    public RiseLBKeybind(KeyProperty property, int x, int y, int width, int height) {
        super(x, y, width, height);
        this.property = property;
    }

    public KeyProperty getProperty() {
        return property;
    }

    public boolean isBinding() {
        return binding;
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTicks, float animationProgress,
                       boolean isLast, int scrollOffset, float deltaTime) {
        if (!property.isVisible()) {
            return;
        }
        RiseLBTheme.draw(12, property.getName(), x, RiseLBTheme.textY(y, height, 12),
                RiseLBTheme.solid(RiseLBTheme.TEXT_DIM, animationProgress));

        String shown = binding ? "..." : property.getKeyName();
        int color = binding
                ? RiseLBTheme.rgba(RiseLBTheme.accent(), (int) (255 * animationProgress))
                : RiseLBTheme.solid(RiseLBTheme.TEXT, animationProgress);
        RiseLBTheme.draw(12, shown, x + width - RiseLBTheme.width(12, shown),
                RiseLBTheme.textY(y, height, 12), color);
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int mouseButton) {
        return false;
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int mouseButton, int scrollOffset) {
        boolean over = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        if (over && mouseButton == 0) {
            binding = !binding;
            return true;
        }
        if (binding && !over) {
            binding = false;
        }
        return false;
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int mouseButton) {
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) {
        if (!binding) {
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_DELETE
                || keyCode == Keyboard.KEY_BACK) {
            property.setValue(Keyboard.KEY_NONE);
        } else {
            property.setValue(keyCode);
        }
        binding = false;
    }
}
