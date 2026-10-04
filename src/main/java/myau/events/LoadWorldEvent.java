package myau.events;

import myau.event.events.Event;
import net.minecraft.client.multiplayer.WorldClient;

/**
 * Minecraft.loadWorld was called: a world was loaded, or (world == null) the
 * current one was unloaded -- disconnect, quit to menu, or exit.
 */
public class LoadWorldEvent implements Event {
    private final WorldClient world;

    public LoadWorldEvent() {
        this(null);
    }

    public LoadWorldEvent(WorldClient world) {
        this.world = world;
    }

    /** The world being loaded; null when the current one is being unloaded. */
    public WorldClient getWorld() {
        return this.world;
    }

    public boolean isUnload() {
        return this.world == null;
    }
}
