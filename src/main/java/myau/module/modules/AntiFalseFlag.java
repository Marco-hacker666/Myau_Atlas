package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): an S08 position-look watchdog that can
// pause movement modules after a burst of server corrections.
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;

public class AntiFalseFlag extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty response = new ModeProperty("response", 0, new String[]{"Notify", "AutoPause"});
    public final IntProperty maxFlags = new IntProperty("max-flags", 3, 1, 10);
    public final IntProperty windowMs = new IntProperty("window-ms", 5000, 1000, 15000);
    public final BooleanProperty pauseFly = new BooleanProperty("pause-fly", true, () -> response.getValue() == 1);
    public final BooleanProperty pauseSpeed = new BooleanProperty("pause-speed", true, () -> response.getValue() == 1);
    public final BooleanProperty pauseScaffold = new BooleanProperty("pause-scaffold", true, () -> response.getValue() == 1);

    private final ArrayDeque<Long> flagTimes = new ArrayDeque<Long>();
    private final List<Module> paused = new ArrayList<Module>();
    private long resumeAt = 0L;
    private static final long RESUME_DELAY_MS = 8000L;
    private static final long ALERT_GAP_MS = 5000L;
    private long lastAlertMs = 0L;

    public AntiFalseFlag() {
        super("AntiFalseFlag", false, false, "Pauses movement modules after server position corrections.");
    }

    @Override
    public void onEnabled() {
        flagTimes.clear();
        paused.clear();
        resumeAt = 0L;
    }

    @Override
    public void onDisabled() {
        flagTimes.clear();
        this.resumeNow();
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || event.getType() != EventType.RECEIVE) return;
        if (!(event.getPacket() instanceof S08PacketPlayerPosLook)) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        long now = System.currentTimeMillis();
        flagTimes.addLast(now);
        while (!flagTimes.isEmpty() && now - flagTimes.peekFirst() > windowMs.getValue()) {
            flagTimes.pollFirst();
        }
        if (flagTimes.size() < maxFlags.getValue()) return;

        flagTimes.clear();
        if (!this.isFlagDetectorActive() && now - lastAlertMs >= ALERT_GAP_MS) {
            lastAlertMs = now;
            ChatUtil.sendFormatted("&7[&bAntiFalseFlag&7] &fPossible flag burst, playing safe.");
        }
        if (response.getValue() == 1) {
            if (pauseFly.getValue()) this.pause(Fly.class);
            if (pauseSpeed.getValue()) this.pause(Speed.class);
            if (pauseScaffold.getValue()) this.pause(Scaffold.class);
            if (!paused.isEmpty()) {
                resumeAt = now + RESUME_DELAY_MS;
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!isEnabled() || event.getType() != EventType.PRE) return;
        if (resumeAt != 0L && System.currentTimeMillis() >= resumeAt) {
            this.resumeNow();
        }
    }

    private boolean isFlagDetectorActive() {
        if (Myau.moduleManager == null) return false;
        Module detector = Myau.moduleManager.getModule(FlagDetector.class);
        return detector != null && detector.isEnabled();
    }

    private void pause(Class<? extends Module> clazz) {
        if (Myau.moduleManager == null) return;
        Module module = Myau.moduleManager.getModule(clazz);
        if (module != null && module.isEnabled()) {
            module.setEnabled(false);
            if (!paused.contains(module)) {
                paused.add(module);
            }
        }
    }

    private void resumeNow() {
        for (Module module : paused) {
            try {
                if (!module.isEnabled()) {
                    module.setEnabled(true);
                }
            } catch (Exception ignored) {
            }
        }
        paused.clear();
        resumeAt = 0L;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{response.getModeString()};
    }
}
