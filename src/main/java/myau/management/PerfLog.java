package myau.management;

import myau.event.EventManager;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Where the client's own time goes, once a minute, to
 * config/Myau/perf-<stamp>.txt.
 *
 * Asked for as "optimise the client" (2026-09-25). Nothing had ever been
 * measured, and the one guess that could be checked -- the event bus calling
 * every module's handlers by reflection -- came to well under a millisecond
 * a second. So this measures first: the time in every handler that ran, by
 * handler and by event, with the frame rate beside it, only while in a world.
 * The handlers that come out on top are what is worth making faster.
 */
public class PerfLog {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File LOG_DIR = new File("./config/Myau/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss");
    private static final int WINDOW_TICKS = 1200;
    private static final int TOP = 20;

    private File target;
    private int ticks;
    private long windowStart;
    private long fpsSum;

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.POST) {
            return;
        }
        long now = System.nanoTime();
        if (this.ticks == 0) {
            this.windowStart = now;
            this.fpsSum = 0L;
            /* Whatever piled up before the window (the menu, loading) is not it. */
            EventManager.takeProfile(1.0, 0);
        }
        this.ticks++;
        this.fpsSum += Minecraft.getDebugFPS();
        if (this.ticks < WINDOW_TICKS) {
            return;
        }
        double seconds = (now - this.windowStart) / 1e9;
        List<String> lines = EventManager.takeProfile(seconds, TOP);
        write(String.format("window %.1fs | fps avg %d | %s", seconds, this.fpsSum / this.ticks,
                mc.getCurrentServerData() == null ? "local" : mc.getCurrentServerData().serverIP), lines);
        this.ticks = 0;
    }

    private void write(String header, List<String> lines) {
        if (this.target == null) {
            this.target = new File(LOG_DIR, "perf-" + FILE_STAMP.format(new Date()) + ".txt");
        }
        List<String> out = new java.util.ArrayList<String>();
        out.add(LINE_STAMP.format(new Date()) + "  " + header);
        out.addAll(lines);
        out.add("");
        myau.util.AsyncLog.append(this.target, out);
    }
}
