package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.Render2DEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.util.MatchChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S45PacketTitle;
import net.minecraft.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.Properties;

/**
 * A small HUD: how long has been played and how many games (2026-10-04).
 *
 * Time counts while in a world (any server, or singleplayer), for this launch
 * and for today. Games are read from chat by util/MatchChat -- a start, or a
 * result with no start seen -- with wins and losses where the server says
 * which. Today's and all-time numbers are kept in
 * config/Myau/playtracker.txt (saved every minute, after every result and at
 * shutdown), so they survive a restart; a new day starts today's from zero.
 *
 * It records whether or not the HUD is shown; switching the module off only
 * hides it.
 */
public class PlayTracker extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File FILE = new File("./config/Myau/playtracker.txt");
    private static final long SAVE_EVERY_MS = 60000L;
    /* "MATCH START!" and "Game starts in 1..." are one game, five seconds apart. */
    private static final long START_DEDUPE_MS = 15000L;
    /* Hypixel says it in chat and in a title. */
    private static final long RESULT_DEDUPE_MS = 5000L;
    /* A gap longer than this (a freeze, a long load) is not counted as play. */
    private static final long MAX_TICK_GAP_MS = 1000L;

    public final IntProperty x = new IntProperty("x", 5, 0, 2000);
    public final IntProperty y = new IntProperty("y", 120, 0, 2000);
    public final BooleanProperty showToday = new BooleanProperty("show-today", true);
    public final BooleanProperty showResults = new BooleanProperty("show-wins", true);
    public final IntProperty backgroundAlpha = new IntProperty("background-alpha", 120, 0, 255);
    /* A third line when the server looks to be cutting this client's hits
       (FlagDetector's mitigation check, 2026-10-04); "mitigation-always"
       shows it as "none" the rest of the time. */
    public final BooleanProperty showMitigation = new BooleanProperty("show-mitigation", true);
    public final BooleanProperty mitigationAlways = new BooleanProperty("mitigation-always", false,
            this.showMitigation::getValue);

    private long sessionMs;
    private int sessionGames;
    private int sessionWins;
    private int sessionLosses;

    private String day;
    private long todayMs;
    private int todayGames;
    private int todayWins;
    private int todayLosses;
    private long totalMs;
    private int totalGames;
    private int totalWins;
    private int totalLosses;

    private boolean loaded;
    private boolean dirty;
    private long lastTickAt;
    private long lastSaveAt;

    private boolean inGame;
    private long gameStartedAt;
    private long lastResultAt;
    private String lastNameLine;

    public PlayTracker() {
        super("PlayTracker", true, false, "Small HUD: time played and games played (this launch and today)");
        myau.management.Shutdown.register(myau.management.Shutdown.Stage.SAVE_STATE, "PlayTracker", this::save);
    }

    // ---------------------------------------------------------------- counting

    @EventTarget(whenDisabled = true)
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        load();
        rollDay();
        long now = System.currentTimeMillis();
        if (mc.theWorld != null && mc.thePlayer != null && this.lastTickAt > 0L) {
            long gap = now - this.lastTickAt;
            if (gap > 0L && gap <= MAX_TICK_GAP_MS) {
                this.sessionMs += gap;
                this.todayMs += gap;
                this.totalMs += gap;
                this.dirty = true;
            }
        }
        this.lastTickAt = now;
        if (this.dirty && now - this.lastSaveAt >= SAVE_EVERY_MS) {
            save();
        }
    }

    @EventTarget(whenDisabled = true)
    public void onPacket(PacketEvent event) {
        if (event.getType() != EventType.RECEIVE) {
            return;
        }
        if (event.getPacket() instanceof S02PacketChat) {
            S02PacketChat packet = (S02PacketChat) event.getPacket();
            if (packet.getType() == 2 || packet.getChatComponent() == null) {
                return;
            }
            final String line = StringUtils.stripControlCodes(packet.getChatComponent().getUnformattedText());
            mc.addScheduledTask(() -> onChat(line));
        } else if (event.getPacket() instanceof S45PacketTitle) {
            S45PacketTitle packet = (S45PacketTitle) event.getPacket();
            if (packet.getMessage() == null) {
                return;
            }
            final String title = StringUtils.stripControlCodes(packet.getMessage().getUnformattedText()).trim();
            mc.addScheduledTask(() -> {
                if ("VICTORY!".equalsIgnoreCase(title)) {
                    result(true);
                } else if ("GAME OVER!".equalsIgnoreCase(title)) {
                    result(false);
                }
            });
        }
    }

    private void onChat(String line) {
        if (mc.thePlayer == null) {
            return;
        }
        load();
        String me = mc.thePlayer.getName();
        switch (MatchChat.classify(line, me, this.lastNameLine)) {
            case START:
                started();
                break;
            case WIN:
                result(true);
                break;
            case LOSS:
                result(false);
                break;
            default:
                if (MatchChat.namesMe(line, me)) {
                    this.lastNameLine = line;
                }
        }
    }

    private void started() {
        long now = System.currentTimeMillis();
        if (this.inGame && now - this.gameStartedAt < START_DEDUPE_MS) {
            return;
        }
        this.inGame = true;
        this.gameStartedAt = now;
        countGame();
    }

    private void result(boolean win) {
        long now = System.currentTimeMillis();
        if (now - this.lastResultAt < RESULT_DEDUPE_MS) {
            return;
        }
        this.lastResultAt = now;
        if (!this.inGame) {
            /* A result with no start seen: the game still happened. */
            countGame();
        }
        this.inGame = false;
        this.lastNameLine = null;
        if (win) {
            this.sessionWins++;
            this.todayWins++;
            this.totalWins++;
        } else {
            this.sessionLosses++;
            this.todayLosses++;
            this.totalLosses++;
        }
        this.dirty = true;
        save();
    }

    private void countGame() {
        this.sessionGames++;
        this.todayGames++;
        this.totalGames++;
        this.dirty = true;
    }

    private void rollDay() {
        String today = LocalDate.now().toString();
        if (!today.equals(this.day)) {
            this.day = today;
            this.todayMs = 0L;
            this.todayGames = 0;
            this.todayWins = 0;
            this.todayLosses = 0;
            this.dirty = true;
        }
    }

    // ------------------------------------------------------------------- file

    private void load() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        if (!FILE.isFile()) {
            return;
        }
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(FILE)) {
            p.load(in);
            this.day = p.getProperty("day");
            this.todayMs = Long.parseLong(p.getProperty("today-ms", "0"));
            this.todayGames = Integer.parseInt(p.getProperty("today-games", "0"));
            this.todayWins = Integer.parseInt(p.getProperty("today-wins", "0"));
            this.todayLosses = Integer.parseInt(p.getProperty("today-losses", "0"));
            this.totalMs = Long.parseLong(p.getProperty("total-ms", "0"));
            this.totalGames = Integer.parseInt(p.getProperty("total-games", "0"));
            this.totalWins = Integer.parseInt(p.getProperty("total-wins", "0"));
            this.totalLosses = Integer.parseInt(p.getProperty("total-losses", "0"));
        } catch (Exception ignored) {
            /* A damaged file starts the counts over rather than the game failing. */
        }
    }

    private synchronized void save() {
        if (!this.loaded) {
            return;
        }
        this.lastSaveAt = System.currentTimeMillis();
        this.dirty = false;
        Properties p = new Properties();
        p.setProperty("day", this.day == null ? LocalDate.now().toString() : this.day);
        p.setProperty("today-ms", Long.toString(this.todayMs));
        p.setProperty("today-games", Integer.toString(this.todayGames));
        p.setProperty("today-wins", Integer.toString(this.todayWins));
        p.setProperty("today-losses", Integer.toString(this.todayLosses));
        p.setProperty("total-ms", Long.toString(this.totalMs));
        p.setProperty("total-games", Integer.toString(this.totalGames));
        p.setProperty("total-wins", Integer.toString(this.totalWins));
        p.setProperty("total-losses", Integer.toString(this.totalLosses));
        try {
            File dir = FILE.getParentFile();
            if (dir != null && !dir.isDirectory()) {
                dir.mkdirs();
            }
            try (OutputStream out = new FileOutputStream(FILE)) {
                p.store(out, "PlayTracker: time in ms");
            }
        } catch (Exception ignored) {
        }
    }

    // --------------------------------------------------------------------- HUD

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.gameSettings.showDebugInfo) {
            return;
        }
        String time = "§b遊玩 §f" + clock(this.sessionMs)
                + (this.showToday.getValue() ? " §7(今天 " + clock(this.todayMs) + ")" : "");
        String games = "§b場數 §f" + this.sessionGames
                + (this.showResults.getValue()
                ? " §a勝" + this.sessionWins + " §c敗" + this.sessionLosses : "")
                + (this.showToday.getValue() ? " §7(今天 " + this.todayGames + ")" : "");
        String mitigation = this.showMitigation.getValue() ? mitigationLine() : null;
        int left = this.x.getValue();
        int top = this.y.getValue();
        int width = Math.max(mc.fontRendererObj.getStringWidth(time), mc.fontRendererObj.getStringWidth(games));
        if (mitigation != null) {
            width = Math.max(width, mc.fontRendererObj.getStringWidth(mitigation));
        }
        int line = mc.fontRendererObj.FONT_HEIGHT + 2;
        int lines = mitigation != null ? 3 : 2;
        int alpha = this.backgroundAlpha.getValue();
        if (alpha > 0) {
            Gui.drawRect(left - 3, top - 3, left + width + 3, top + line * lines + 1, alpha << 24);
        }
        mc.fontRendererObj.drawStringWithShadow(time, left, top, 0xFFFFFF);
        mc.fontRendererObj.drawStringWithShadow(games, left, top + line, 0xFFFFFF);
        if (mitigation != null) {
            mc.fontRendererObj.drawStringWithShadow(mitigation, left, top + line * 2, 0xFFFFFF);
        }
    }

    /**
     * "Damage cut" from FlagDetector: recent hits far under what vanilla must
     * do, or a run of hits the server ignored. Null when there is nothing to
     * say and mitigation-always is off, or FlagDetector is off.
     */
    private String mitigationLine() {
        FlagDetector detector = (FlagDetector) myau.Myau.moduleManager.modules.get(FlagDetector.class);
        if (detector == null || !detector.isEnabled() || !detector.detectLowDamage.getValue()) {
            return this.mitigationAlways.getValue() ? "§b減傷 §8(FlagDetector 關)" : null;
        }
        myau.util.DamageWatch.Mitigation m = detector.mitigation();
        if (m.active) {
            return String.format("§c! 減傷 §f%.1f§7/§f%.1f hp §8(%d/%d 下)",
                    m.dealt, m.expected, m.low, m.of);
        }
        int dropped = detector.droppedRun();
        if (dropped >= 2) {
            return "§c! 打不到 §f" + dropped + "§7 下沒反應";
        }
        return this.mitigationAlways.getValue() ? "§b減傷 §a無" : null;
    }

    /** h:mm:ss */
    private static String clock(long ms) {
        long s = ms / 1000L;
        return String.format("%d:%02d:%02d", s / 3600L, s / 60L % 60L, s % 60L);
    }
}
