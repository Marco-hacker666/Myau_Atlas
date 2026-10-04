package myau.module.modules;

import myau.module.Module;
import myau.util.client.RawMouseHelper;
import net.java.games.input.Controller;
import net.java.games.input.ControllerEnvironment;
import net.java.games.input.Mouse;
import net.minecraft.client.Minecraft;
import net.minecraft.util.MouseHelper;

import java.lang.reflect.Constructor;

public class MouseRawInput extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static Mouse mouse;
    /* Atomic, not volatile: the input thread did deltaX += n while the client
       thread read and then zeroed it, and a movement landing between the read
       and the zero was lost. */
    private static final java.util.concurrent.atomic.AtomicInteger deltaX = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger deltaY = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile boolean running;
    private static Thread inputThread;

    private MouseHelper previousMouseHelper;

    public MouseRawInput() {
        super("MouseRawInput", false);
    }

    @Override
    public void onEnabled() {
        this.previousMouseHelper = mc.mouseHelper;
        mc.mouseHelper = new RawMouseHelper();
        startInputThread();
    }

    @Override
    public void onDisabled() {
        running = false;
        mouse = null;
        deltaX.set(0);
        deltaY.set(0);
        if (this.previousMouseHelper != null) {
            mc.mouseHelper = this.previousMouseHelper;
            this.previousMouseHelper = null;
        } else {
            mc.mouseHelper = new MouseHelper();
        }
    }

    public static int consumeDeltaX() {
        return deltaX.getAndSet(0);
    }

    public static int consumeDeltaY() {
        return deltaY.getAndSet(0);
    }

    /** A raw mouse has been found and is being read. */
    public static boolean hasMouse() {
        return running && mouse != null;
    }

    /** Drops whatever has piled up. */
    public static void discard() {
        deltaX.set(0);
        deltaY.set(0);
    }

    private static void startInputThread() {
        if (inputThread != null && inputThread.isAlive()) {
            running = true;
            return;
        }
        running = true;
        inputThread = new Thread(() -> {
            while (running) {
                try {
                    if (mouse == null) {
                        mouse = findMouse();
                    } else if (mouse.poll()) {
                        int x = (int) mouse.getX().getPollData();
                        int y = (int) mouse.getY().getPollData();
                        /* Polled either way, so the device's own count does
                           not build up; kept only while the game has the
                           mouse. In a menu, or tabbed out, the movement is the
                           cursor's, not the view's. */
                        if (mc.inGameHasFocus && mc.currentScreen == null) {
                            deltaX.addAndGet(x);
                            deltaY.addAndGet(y);
                        }
                    } else {
                        mouse = null;
                    }
                    Thread.sleep(1L);
                } catch (Throwable ignored) {
                    mouse = null;
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException ignoredInterrupted) {
                        Thread.currentThread().interrupt();
                        running = false;
                    }
                }
            }
        }, "MouseRawInput");
        inputThread.setDaemon(true);
        inputThread.start();
    }

    @SuppressWarnings("unchecked")
    private static ControllerEnvironment createDefaultEnvironment() throws ReflectiveOperationException {
        Constructor<ControllerEnvironment> constructor = (Constructor<ControllerEnvironment>) Class
                .forName("net.java.games.input.DefaultControllerEnvironment").getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Mouse findMouse() throws ReflectiveOperationException {
        Controller[] controllers = createDefaultEnvironment().getControllers();
        for (Controller controller : controllers) {
            if (controller.getType() == Controller.Type.MOUSE && controller instanceof Mouse) {
                controller.poll();
                Mouse candidate = (Mouse) controller;
                if (candidate.getX().getPollData() != 0.0F || candidate.getY().getPollData() != 0.0F) {
                    return candidate;
                }
            }
        }
        return null;
    }
}