package myau.events;

import myau.event.events.Event;
import myau.event.types.EventType;

public class UpdateEvent implements Event {
    private final EventType type;
    private final float yaw;
    private final float pitch;
    private float newYaw;
    private float newPitch;
    private float prevYaw;
    private int lastPriority = -1;
    private int priority = -1;
    private boolean rotated = false;

    public UpdateEvent(EventType type, float yaw, float pitch, float newYaw, float newPitch) {
        this.type = type;
        this.yaw = yaw;
        this.pitch = pitch;
        this.newYaw = newYaw;
        this.newPitch = newPitch;
        this.prevYaw = newYaw;
    }

    public EventType getType() {
        return this.type;
    }

    public float getYaw() {
        return this.yaw;
    }

    public float getPitch() {
        return this.pitch;
    }

    public float getNewYaw() {
        return this.newYaw;
    }

    public float getNewPitch() {
        return this.newPitch;
    }

    public float getPreYaw() {
        return this.prevYaw;
    }

    public int isRotating() {
        return this.priority;
    }

    public boolean isRotated() {
        return this.rotated;
    }

    public void setRotation(float yaw, float pitch, int priority) {
        if (this.type == EventType.PRE && this.lastPriority <= priority) {
            this.newYaw = yaw;
            this.newPitch = pitch;
            this.lastPriority = priority;
            this.rotated = true;
        }
    }

    /* Ties go to the last caller, exactly as in setRotation. With `<` here the
       first caller at a priority kept the movement yaw while the last one
       took the packet, so two modules at one priority (KillAura runs LOW,
       after AimAssist/ThrowAura/ChestAura/Speed at 1; Velocity after
       PlainAura at 2) moved by one yaw and reported the other --
       ENGINEERING-NOTES 4.12. */
    public void setPervRotation(float yaw, int priority) {
        if (this.type == EventType.PRE && this.priority <= priority) {
            this.prevYaw = yaw;
            this.priority = priority;
            this.rotated = true;
        }
    }
}
