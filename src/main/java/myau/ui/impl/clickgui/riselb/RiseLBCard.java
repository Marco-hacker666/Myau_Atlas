package myau.ui.impl.clickgui.riselb;

import myau.Myau;
import myau.module.Module;
import myau.property.Property;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.KeyProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.property.properties.TextProperty;
import myau.ui.impl.clickgui.modern.component.ColorPicker;
import myau.ui.impl.clickgui.modern.component.Component;
import myau.ui.impl.clickgui.modern.component.KeybindComponent;
import myau.ui.impl.clickgui.modern.component.TextField;
import myau.util.AnimationUtil;
import myau.util.RenderUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * One module card, ported from ModuleCard.svelte.
 *
 * Layout (SCSS): 10px radius, padding 12px top / 15px left, with 78px kept
 * clear on the right for the expand chevron and the switch. Name is 15px
 * semibold and turns accent-coloured when the module is on; the category
 * line under it is 12.5px dimmed. Settings appear below a 1px top border.
 *
 * Settings widgets are the Rise-styled ones from this package for the common
 * property types; colour and text properties fall through to the Modern
 * implementations, which is why this holds the shared Component base type.
 */
public class RiseLBCard {

    private static final float PAD_LEFT = 15.0f;
    private static final float PAD_TOP = 12.0f;
    private static final float PAD_RIGHT = 78.0f;
    private static final float HEAD_HEIGHT = 44.0f;
    private static final float SETTINGS_GAP = 10.0f;
    private static final float SWITCH_TOP = 17.0f;
    private static final float SWITCH_RIGHT = 14.0f;
    private static final float CHEVRON_RIGHT = 52.0f;

    private final Module module;
    private final String category;
    private final List<Component> settings;

    private boolean expanded;
    private float expandAnim;
    private float hoverAnim;
    private float toggleAnim;
    private float settingsHeight;

    private float x;
    private float y;
    private float width;

    public RiseLBCard(Module module, String category) {
        this.module = module;
        this.category = category;
        this.settings = new ArrayList<Component>();
        this.toggleAnim = module.isEnabled() ? 1.0f : 0.0f;
        buildSettings();
    }

    private void buildSettings() {
        settings.add(new KeybindComponent(module, 0, 0, 100, 20));
        if (Myau.propertyManager == null) {
            return;
        }
        List<Property<?>> properties = Myau.propertyManager.properties.get(module.getClass());
        if (properties == null) {
            return;
        }
        for (Property<?> property : properties) {
            if (property instanceof KeyProperty) {
                settings.add(new RiseLBKeybind((KeyProperty) property, 0, 0, 100, 20));
            } else if (property instanceof BooleanProperty) {
                settings.add(new RiseLBSwitch((BooleanProperty) property, 0, 0, 100, 20));
            } else if (property instanceof IntProperty
                    || property instanceof FloatProperty
                    || property instanceof PercentProperty) {
                settings.add(new RiseLBSlider(property, 0, 0, 100, 26));
            } else if (property instanceof ModeProperty) {
                settings.add(new RiseLBDropdown((ModeProperty) property, 0, 0, 100, 20));
            } else if (property instanceof ColorProperty) {
                settings.add(new ColorPicker((ColorProperty) property, 0, 0, 100, 60));
            } else if (property instanceof TextProperty) {
                settings.add(new TextField((TextProperty) property, 0, 0, 100, 20));
            }
        }
    }

    public Module getModule() {
        return module;
    }

    public boolean isBinding() {
        if (!expanded) {
            return false;
        }
        for (Component c : settings) {
            if (c instanceof KeybindComponent && ((KeybindComponent) c).isBinding()) {
                return true;
            }
            if (c instanceof RiseLBKeybind && ((RiseLBKeybind) c).isBinding()) {
                return true;
            }
        }
        return false;
    }

    private boolean visible(Component c) {
        if (c instanceof RiseLBKeybind) {
            return ((RiseLBKeybind) c).getProperty().isVisible();
        }
        if (c instanceof RiseLBSwitch) {
            return ((RiseLBSwitch) c).getProperty().isVisible();
        }
        if (c instanceof RiseLBSlider) {
            return ((RiseLBSlider) c).getProperty().isVisible();
        }
        if (c instanceof RiseLBDropdown) {
            return ((RiseLBDropdown) c).getProperty().isVisible();
        }
        if (c instanceof ColorPicker) {
            return ((ColorPicker) c).getProperty().isVisible();
        }
        if (c instanceof TextField) {
            return ((TextField) c).getProperty().isVisible();
        }
        return true;
    }

    public float getHeight() {
        return HEAD_HEIGHT + (settingsHeight > 0.5f ? SETTINGS_GAP + settingsHeight : 0.0f);
    }

    public void setBounds(float x, float y, float width) {
        this.x = x;
        this.y = y;
        this.width = width;
    }

    private boolean overHead(int mouseX, int mouseY) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + HEAD_HEIGHT;
    }

    private boolean compact() {
        return width < 260.0f;
    }

    public void render(int mouseX, int mouseY, float progress, float deltaTime, boolean clipped) {
        boolean hovered = !clipped && overHead(mouseX, mouseY);
        hoverAnim = AnimationUtil.animateSmooth(hovered ? 1.0f : 0.0f, hoverAnim, 12.0f, deltaTime);
        toggleAnim = AnimationUtil.animateSmooth(module.isEnabled() ? 1.0f : 0.0f, toggleAnim, 14.0f, deltaTime);
        expandAnim = AnimationUtil.animateSmooth(expanded ? 1.0f : 0.0f, expandAnim, 12.0f, deltaTime);

        float visibleSettings = 0.0f;
        if (expanded) {
            for (Component c : settings) {
                if (visible(c)) {
                    visibleSettings += c.getHeight();
                }
            }
        }
        settingsHeight = AnimationUtil.animateSmooth(visibleSettings, settingsHeight, 12.0f, deltaTime);

        float height = getHeight();

        // Card surface, lifting one pixel on hover the way the SCSS does.
        float lift = RiseLBTheme.spring(hoverAnim);
        float cardY = y - lift;
        int surface = AnimationUtil.interpolateColor(
                RiseLBTheme.solid(RiseLBTheme.CARD, progress),
                RiseLBTheme.solid(RiseLBTheme.CARD_HOVER, progress), lift);
        RenderUtil.drawRoundedRect(x, cardY, width, height, RiseLBTheme.CARD_RADIUS, surface,
                true, true, true, true);

        int nameColor = AnimationUtil.interpolateColor(
                RiseLBTheme.solid(RiseLBTheme.TEXT, progress),
                RiseLBTheme.rgba(RiseLBTheme.accent(), (int) (255 * progress)), toggleAnim);
        RiseLBTheme.draw(16, module.getName(), x + PAD_LEFT, cardY + PAD_TOP, nameColor);

        if (!compact()) {
            String sub = subtitle();
            if (sub != null) {
                RiseLBTheme.draw(12, sub, x + PAD_LEFT, cardY + PAD_TOP + RiseLBTheme.height(16) + 4.0f,
                        RiseLBTheme.solid(RiseLBTheme.TEXT_DIM, progress));
            }
        }

        if (!settings.isEmpty() && !compact()) {
            int chevronColor = expandAnim > 0.5f
                    ? RiseLBTheme.rgba(RiseLBTheme.accent(), (int) (255 * progress))
                    : RiseLBTheme.solid(RiseLBTheme.TEXT_DIM, progress);
            RiseLBTheme.chevron(x + width - CHEVRON_RIGHT + 8.0f, cardY + SWITCH_TOP + 8.0f, 9.0f,
                    RiseLBTheme.spring(expandAnim), chevronColor);
        }

        float switchY = compact()
                ? cardY + (HEAD_HEIGHT - RiseLBTheme.SWITCH_H) / 2.0f
                : cardY + SWITCH_TOP;
        RiseLBTheme.toggle(x + width - SWITCH_RIGHT - RiseLBTheme.SWITCH_W, switchY,
                RiseLBTheme.spring(toggleAnim), progress);

        if (settingsHeight <= 0.5f) {
            return;
        }

        float divider = cardY + HEAD_HEIGHT + SETTINGS_GAP / 2.0f;
        RenderUtil.drawRect(x + PAD_LEFT, divider, x + width - PAD_LEFT, divider + 1.0f,
                RiseLBTheme.fade(RiseLBTheme.BORDER, progress));

        RenderUtil.scissor(x, cardY + HEAD_HEIGHT + SETTINGS_GAP, width, settingsHeight);
        float settingY = cardY + HEAD_HEIGHT + SETTINGS_GAP;
        for (Component c : settings) {
            if (!visible(c)) {
                continue;
            }
            c.setX((int) (x + PAD_LEFT));
            c.setY((int) settingY);
            c.setWidth((int) (width - PAD_LEFT * 2.0f));
            c.render(mouseX, mouseY, 0.0f, progress, false, 0, deltaTime);
            settingY += c.getHeight();
        }
        RenderUtil.releaseScissor();
    }

    /** Description if the module has one, otherwise the category it lives in. */
    private String subtitle() {
        String description = module.getDescription();
        if (description != null && !description.isEmpty()) {
            return trim(description, width - PAD_LEFT - PAD_RIGHT);
        }
        return category;
    }

    private String trim(String text, float maxWidth) {
        if (RiseLBTheme.width(12, text) <= maxWidth) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (RiseLBTheme.width(12, sb.toString() + text.charAt(i) + "...") > maxWidth) {
                break;
            }
            sb.append(text.charAt(i));
        }
        return sb.append("...").toString();
    }

    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (overHead(mouseX, mouseY)) {
            float switchX = x + width - SWITCH_RIGHT - RiseLBTheme.SWITCH_W;
            boolean onSwitch = mouseX >= switchX - 4.0f && mouseX <= switchX + RiseLBTheme.SWITCH_W + 4.0f;
            boolean onChevron = !compact() && !settings.isEmpty()
                    && mouseX >= x + width - CHEVRON_RIGHT && mouseX <= x + width - CHEVRON_RIGHT + 18.0f;

            if (button == 0 && onSwitch) {
                module.toggle();
                return true;
            }
            if (button == 0 && onChevron) {
                expanded = !expanded;
                return true;
            }
            if (button == 1) {
                if (!settings.isEmpty()) {
                    expanded = !expanded;
                }
                return true;
            }
            if (button == 0) {
                module.toggle();
                return true;
            }
        }
        if (expanded && settingsHeight > 4.0f) {
            for (Component c : settings) {
                if (visible(c) && c.mouseClicked(mouseX, mouseY, button, 0)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void mouseReleased(int mouseX, int mouseY, int button) {
        for (Component c : settings) {
            if (visible(c)) {
                c.mouseReleased(mouseX, mouseY, button, 0);
            }
        }
    }

    public void keyTyped(char typedChar, int keyCode) {
        if (!expanded) {
            return;
        }
        for (Component c : settings) {
            if (visible(c)) {
                c.keyTyped(typedChar, keyCode);
            }
        }
    }
}
