package myau.util.client;

import myau.module.modules.MouseRawInput;
import net.minecraft.util.MouseHelper;

public class RawMouseHelper extends MouseHelper {
    /*
     * LWJGL's own delta is always read, so it is drained every frame. It
     * used to be read only on frames the raw mouse had nothing, and then it
     * returned everything that had piled up since the last read -- movement
     * the raw delta had already applied -- and the view turned twice for one
     * motion. With a raw mouse found, only the raw delta counts; until one is,
     * LWJGL's is used as vanilla does.
     */
    @Override
    public void mouseXYChange() {
        super.mouseXYChange();
        if (!MouseRawInput.hasMouse()) {
            return;
        }
        this.deltaX = MouseRawInput.consumeDeltaX();
        this.deltaY = -MouseRawInput.consumeDeltaY();
    }

    /* Back in the game from a menu: what the mouse did in the menu is not a
       turn, and applying it in one frame snapped the view. */
    @Override
    public void grabMouseCursor() {
        MouseRawInput.discard();
        super.grabMouseCursor();
    }
}
