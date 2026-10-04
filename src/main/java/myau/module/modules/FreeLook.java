package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MathHelper;
import org.lwjgl.input.Keyboard;

/**
 * Detaches the camera from the body while a key is held.
 *
 * The mouse is intercepted at {@code Entity.setAngles}, the one place where its
 * movement becomes body rotation, so the yaw and pitch this client sends, the
 * head other players see, and the direction attacks are aimed all keep pointing
 * wherever the body was already facing. Nothing here reaches the network.
 *
 * Holding rather than toggling is what makes it usable: the point is to glance
 * behind while still running forwards, and a glance is bounded by how long the
 * key is down. Enabling the module only arms it -- the key decides when it is
 * actually looking, which is why the camera is seeded and the perspective
 * switched on the key's edges rather than on the module's.
 */
public class FreeLook extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    /* The module's own bind is the key: pressing it switches the module on the
       way every other module works, and releasing it switches it back off. That
       leaves one key to think about rather than two, and it means the module
       genuinely goes off -- disappearing from the array list -- rather than
       sitting enabled and idle between glances. */
    public final BooleanProperty holdMode = new BooleanProperty("hold-mode", true);
    public final BooleanProperty thirdPerson = new BooleanProperty("third-person", true);

    private float cameraYaw;
    private float cameraPitch;
    /* The camera is oriented with the same interpolation the player's own
       rotation uses, so it needs a previous value of its own. Without one the
       renderer would interpolate between the camera angle and the body angle
       and the view would swing back towards the body every frame. */
    private float prevCameraYaw;
    private float prevCameraPitch;

    private boolean active;
    private int restorePerspective;

    public FreeLook() {
        super("FreeLook", false, false,
                "Look around without turning your body, while the key is held");
    }

    @Override
    public void onDisabled() {
        this.stop();
    }

    /** With no bind there is nothing to hold, so the module itself is the switch. */
    private boolean holding() {
        int key = this.getKey();
        return key == Keyboard.KEY_NONE || Keyboard.isKeyDown(key);
    }

    @EventTarget(whenDisabled = true)
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        /* A held key still reads as held behind an open inventory or the click
           GUI, where the mouse belongs to the screen rather than to looking. */
        if (!this.isEnabled()) {
            if (this.active) {
                this.stop();
            }
            return;
        }
        /* Releasing switches the whole module off, not just the camera. The
           press that turned it on came from the same key, so the next press
           turns it on again -- and in between there is nothing enabled to
           betray that the module exists. */
        if (this.holdMode.getValue() && !this.holding()) {
            this.stop();
            this.setEnabled(false);
            return;
        }
        boolean wanted = mc.thePlayer != null && mc.currentScreen == null && this.holding();
        if (wanted && !this.active) {
            this.start();
        } else if (!wanted && this.active) {
            this.stop();
        }
    }

    private void start() {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }
        this.cameraYaw = player.rotationYaw;
        this.cameraPitch = player.rotationPitch;
        this.prevCameraYaw = this.cameraYaw;
        this.prevCameraPitch = this.cameraPitch;
        this.restorePerspective = mc.gameSettings.thirdPersonView;
        if (this.thirdPerson.getValue() && mc.gameSettings.thirdPersonView == 0) {
            mc.gameSettings.thirdPersonView = 1;
        }
        this.active = true;
    }

    private void stop() {
        if (!this.active) {
            return;
        }
        this.active = false;
        /* Whatever perspective was in use before is put back rather than
           assuming first person: the player may have been in third person
           already and would not expect this to change that. */
        if (this.thirdPerson.getValue()) {
            mc.gameSettings.thirdPersonView = this.restorePerspective;
        }
    }

    public boolean isFreeLooking() {
        return this.active && mc.thePlayer != null;
    }

    /**
     * Takes the mouse movement the player's own rotation would have received.
     *
     * The 0.15 factor and the pitch clamp are vanilla's, reproduced here rather
     * than shared, because the point of this module is that the body never sees
     * these deltas at all.
     */
    public void updateCamera(float yawDelta, float pitchDelta) {
        this.prevCameraYaw = this.cameraYaw;
        this.prevCameraPitch = this.cameraPitch;
        this.cameraYaw += yawDelta * 0.15F;
        this.cameraPitch = MathHelper.clamp_float(this.cameraPitch - pitchDelta * 0.15F, -90.0F, 90.0F);
    }

    public float getCameraYaw() {
        return this.cameraYaw;
    }

    public float getCameraPitch() {
        return this.cameraPitch;
    }

    public float getPrevCameraYaw() {
        return this.prevCameraYaw;
    }

    public float getPrevCameraPitch() {
        return this.prevCameraPitch;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.holdMode.getValue() ? "hold" : "toggle"};
    }
}
