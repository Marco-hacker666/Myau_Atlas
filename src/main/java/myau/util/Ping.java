package myau.util;

import myau.management.HitTimer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;

/**
 * Our own round trip, for every module that needs one.
 *
 * The tab list is what everything read before 2026-09-28, and on Pika it is a
 * real number (the server's keep-alive average). Hypixel does not report one:
 * it shows 1 ms, so every fight logged there said "ping 1" and every timing
 * built on it -- Clutch's pause, HitCheck's window, KillAura's lead -- worked
 * as if the server were on the LAN. Below TAB_FLOOR the tab figure is taken
 * as absent and HitTimer's measured attack-to-hurt delay is used instead;
 * with neither, -1, which every caller already treats as "no reading".
 */
public final class Ping {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int TAB_FLOOR = 2;

    private Ping() {
    }

    /** Round trip in milliseconds, or -1 when there is no trustworthy number. */
    public static int own() {
        int tab = tab();
        if (tab >= TAB_FLOOR) {
            return tab;
        }
        HitTimer timer = HitTimer.get();
        long measured = timer == null ? -1L : timer.measured();
        return measured > 0L ? (int) measured : -1;
    }

    /** The tab list's figure as the server reports it, or -1. */
    public static int tab() {
        try {
            NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
            return info != null ? info.getResponseTime() : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }
}
