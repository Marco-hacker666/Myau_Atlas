package myau.module;

import myau.Myau;
import myau.module.modules.HUD;
import myau.util.KeyBindUtil;

public abstract class Module {
    protected final String name;
    protected final String description;
    protected final boolean defaultEnabled;
    protected final int defaultKey;
    protected final boolean defaultHidden;
    protected boolean enabled;
    protected int key;
    protected boolean hidden;

    public Module(String name, boolean enabled) {
        this(name, enabled, false, "");
    }

    public Module(String name, boolean enabled, boolean hidden) {
        this(name, enabled, hidden, "");
    }

    public Module(String name, boolean enabled, boolean hidden, String description) {
        this.name = name;
        this.description = description;
        this.enabled = this.defaultEnabled = enabled;
        this.key = this.defaultKey = 0;
        this.hidden = this.defaultHidden = hidden;
    }

    public String getName() {
        return this.name;
    }

    /* The description the menus show (ModuleDocs, 2026-10-04): a Chinese one
       written for every module, over the module's own, which most lacked. */
    private String shownDescription;
    private String shownDescriptionEn;

    /** In the menu's language (ModuleDocs.isEnglish): the docs' text, else the module's own. */
    public String getDescription() {
        String shown = ModuleDocs.isEnglish() ? this.shownDescriptionEn : this.shownDescription;
        return shown != null ? shown : this.description;
    }

    public void setShownDescription(String description) {
        this.shownDescription = description;
    }

    public void setShownDescriptionEn(String description) {
        this.shownDescriptionEn = description;
    }

    public String formatModule() {
        return String.format(
                "%s%s &r(%s&r)",
                this.key == 0 ? "" : String.format("&l[%s] &r", KeyBindUtil.getKeyName(this.key)),
                this.name,
                this.enabled ? "&a&lON" : "&c&lOFF"
        );
    }

    public String[] getSuffix() {
        return new String[0];
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            if (enabled) {
                this.onEnabled();
            } else {
                this.onDisabled();
            }
        }
    }

    public boolean toggle() {
        boolean enabled = !this.enabled;
        this.setEnabled(enabled);
        if (this.enabled == enabled) {
            if (((HUD) Myau.moduleManager.modules.get(HUD.class)).toggleSound.getValue()) {
                Myau.moduleManager.playSound();
            }

            // Add a transient in-game notification for toggles
            try {
                if (Myau.notificationManager != null) {
                    String action = this.enabled ? "was toggled successfully" : "was untoggled successfully";
                    // green for enabled, red for disabled
                    int color = this.enabled ? 0x00FF00 : 0xFF0000;
                    Myau.notificationManager.add(this.getName() + " " + action, color);
                }
            } catch (Exception ignored) {
            }

            return true;
        } else {
            return false;
        }
    }

    public int getKey() {
        return this.key;
    }

    public void setKey(int integer) {
        this.key = integer;
    }

    public boolean isHidden() {
        return this.hidden;
    }

    public void setHidden(boolean boolean1) {
        this.hidden = boolean1;
    }

    public void onEnabled() {
    }

    public void onDisabled() {
    }

    public void verifyValue(String string) {
    }

    public boolean shouldKeepSprint() {
        return false;
    }
}
