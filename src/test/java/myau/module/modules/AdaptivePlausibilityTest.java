package myau.module.modules;

import myau.event.EventTarget;
import myau.events.Render3DEvent;
import myau.events.TickEvent;
import myau.module.Module;
import org.junit.Test;

import static org.junit.Assert.*;

/** Which modules Adaptive may ever blame (plan step 10, 2026-09-28). */
public class AdaptivePlausibilityTest {

    public static class DrawsOnly extends Module {
        public DrawsOnly() {
            super("DrawsOnly", true);
        }

        @EventTarget
        public void onRender(Render3DEvent event) {
        }
    }

    public static class DrawsAndTicks extends Module {
        public DrawsAndTicks() {
            super("DrawsAndTicks", true);
        }

        @EventTarget
        public void onRender(Render3DEvent event) {
        }

        @EventTarget
        public void onTick(TickEvent event) {
        }
    }

    /** Works through a mixin, like KeepSprint. */
    public static class MixinOnly extends Module {
        public MixinOnly() {
            super("MixinOnly", true);
        }
    }

    public static class Camera extends Module {
        public Camera() {
            super("NoHurtCam", true);
        }
    }

    public static class Filter extends Module {
        public Filter() {
            super("TargetFilter", true);
        }

        @EventTarget
        public void onTick(TickEvent event) {
        }
    }

    @Test
    public void settingsModulesAreNeverActedOn() {
        assertTrue(Adaptive.settingsOnly(new Filter()));
        assertFalse(Adaptive.settingsOnly(new DrawsAndTicks()));
    }

    @Test
    public void aModuleThatOnlyDrawsCannotCauseACorrection() {
        assertFalse(Adaptive.canCause(new DrawsOnly()));
    }

    @Test
    public void anyNonRenderHandlerMakesItASuspect() {
        assertTrue(Adaptive.canCause(new DrawsAndTicks()));
    }

    @Test
    public void mixinModulesStaySuspects() {
        assertTrue(Adaptive.canCause(new MixinOnly()));
    }

    @Test
    public void namedCosmeticModulesAreExcluded() {
        assertFalse(Adaptive.canCause(new Camera()));
    }
}
