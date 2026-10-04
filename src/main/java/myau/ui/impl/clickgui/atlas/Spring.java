package myau.ui.impl.clickgui.atlas;

/**
 * A damped spring, which is what makes the menu feel like jelly rather than
 * like a timer.
 *
 * The exponential easing the menu used before always arrives from one side
 * and slows to a stop. A spring can overshoot and come back, and it carries
 * velocity -- so a pill that is moving fast can be stretched along its path
 * by exactly how fast it is moving, and one that is sent somewhere new while
 * still travelling bends towards it instead of restarting.
 *
 * Integrated in fixed sub-steps of four milliseconds, so the motion is the
 * same at 30 fps and at 260, and a long frame cannot make it explode.
 */
final class Spring {

    private static final float STEP = 0.004F;

    /** Multiplies every spring's damping: under 1 bounces more. Set by the theme. */
    static float dampingScale = 1.0F;

    float value;
    float velocity;
    float target;
    private final float stiffness;
    private final float damping;

    /**
     * Stiffness 380 with damping 26 overshoots by about six percent and has
     * settled in under half a second: lively without being cartoonish.
     */
    Spring(float value, float stiffness, float damping) {
        this.value = value;
        this.target = value;
        this.stiffness = stiffness;
        this.damping = damping;
    }

    float step(float delta) {
        int steps = Math.max(1, (int) Math.ceil(delta / STEP));
        float h = delta / steps;
        for (int i = 0; i < steps; i++) {
            float accel = -this.stiffness * (this.value - this.target)
                    - this.damping * dampingScale * this.velocity;
            this.velocity += accel * h;
            this.value += this.velocity * h;
        }
        if (Math.abs(this.value - this.target) < 0.001F && Math.abs(this.velocity) < 0.01F) {
            this.value = this.target;
            this.velocity = 0.0F;
        }
        return this.value;
    }

    /** Jumps without animating, for the first frame something is seen. */
    void snap(float to) {
        this.value = to;
        this.target = to;
        this.velocity = 0.0F;
    }
}
