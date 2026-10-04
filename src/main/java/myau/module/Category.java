package myau.module;

/**
 * The menu's categories, after LiquidBounce's (2026-09-28): its Combat,
 * Player, Movement, Render, World, Misc and Exploit, and nextgen's Client --
 * here the client's own tools: the menus, the diagnostics, and the modules
 * that learn and tune. Theme is this client's own, for the colour groups.
 * Legit groups safe, client-side utilities and cosmetics in a separate menu.
 */
public enum Category {
    COMBAT("Combat"),
    PLAYER("Player"),
    MOVEMENT("Movement"),
    RENDER("Render"),
    WORLD("World"),
    MISC("Misc"),
    EXPLOIT("Exploit"),
    CLIENT("Client"),
    THEME("Theme"),
    LEGIT("Legit");

    private final String displayName;

    Category(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return this.displayName;
    }
}
