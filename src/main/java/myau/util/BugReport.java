package myau.util;

import myau.Myau;
import myau.module.Module;
import myau.property.Property;
import myau.property.properties.ModeProperty;
import myau.util.render.FramebufferCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraftforge.common.ForgeVersion;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One-click bug report (2026-10-07): everything a tester would otherwise be
 * asked for in #bug-reports, as one block of text -- client and game
 * versions, OptiFine and Fast Render, the machine, the server, the click menu,
 * the other mods, every enabled module with its settings, the tail of the
 * FlagDetector, place and Clutch logs, and the warnings and errors of the
 * game log. Copied to the clipboard and saved under config/Myau/reports.
 *
 * Everything goes through {@link Redactor} first: the player's name, UUID and
 * session token, the names on the tab list, the Windows account and home
 * folder, e-mail addresses, token-shaped strings and public IP addresses.
 * Chat from other players is left out of the game log altogether.
 */
public final class BugReport {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File MYAU_DIR = new File("./config/Myau/");
    private static final File REPORT_DIR = new File(MYAU_DIR, "reports");
    private static final File GAME_LOG = new File("./logs/latest.log");
    /* Game-log lines worth keeping: problems, and the client's / anticheat's own messages. */
    private static final Pattern LOG_KEEP = Pattern.compile(
            "WARN|ERROR|Exception|Caused by|^\\s+at |GrimAC|Vulcan|Matrix|Polar|Intave|Verus|failed |FlagDetector|Myau|Mixin",
            Pattern.CASE_INSENSITIVE);

    /* The relay (a Cloudflare Worker, report-relay/ outside the mod) that
       forwards a report to the Discord channel. Its address is not a secret:
       the Discord webhook stays on the Worker. Can be overridden with one line
       in config/Myau/report-relay.txt. */
    private static final String RELAY_URL = "https://myau-report.myau-atlas.workers.dev/report";
    private static final File RELAY_OVERRIDE = new File(MYAU_DIR, "report-relay.txt");

    /*
     * 2026-10-08: what the report is about, picked in the dialog (sent in
     * English; the Chinese labels are only what the menu shows).
     */
    public static final String[] REASONS = {"Flagged / banned", "Crash / freeze", "Lag / low FPS",
            "Module not working", "Visual / menu bug", "Other"};
    public static final String[] REASONS_ZH = {"被反作弊抓 / 被 ban", "崩潰 / 卡死", "延遲 / FPS 低",
            "模組沒作用", "畫面 / 選單問題", "其他"};

    private BugReport() {
    }

    private static String relayUrl() {
        try {
            if (RELAY_OVERRIDE.isFile()) {
                String line = new String(java.nio.file.Files.readAllBytes(RELAY_OVERRIDE.toPath()), StandardCharsets.UTF_8).trim();
                if (line.startsWith("https://")) {
                    return line;
                }
            }
        } catch (Exception ignored) {
            // Falls back to the built-in address.
        }
        return RELAY_URL;
    }

    /** What happened to a report: shown in chat on the game thread. */
    public interface Result {
        void done(boolean sent, String message, File saved);
    }

    /**
     * Builds the report on this (game) thread, saves it, and sends it to the
     * relay on a background thread. If it cannot be sent it is copied to the
     * clipboard instead, so the click is never wasted.
     */
    public static void send(String description, Result result) {
        send(null, description, result);
    }

    /**
     * 2026-10-08: the reason picked in the dialog and the player's own words;
     * the log and the config travel as two files (report.txt, config.txt).
     */
    public static void send(String reason, String description, Result result) {
        String log = buildLog(MARK_REPORT, reason, description);
        String config = buildConfig();
        String report = log + "\n" + config;
        File saved = save(report);
        String relay = relayUrl();
        if (relay.isEmpty()) {
            GuiScreen.setClipboardString(report);
            result.done(false, "no relay configured - copied to the clipboard instead", saved);
            return;
        }
        String title = title(reason, description);
        String summary = summary(reason, description);
        post(relay, "report", title, summary, log, config, (ok, message) -> mc.addScheduledTask(() -> {
            if (!ok) {
                GuiScreen.setClipboardString(report);
            }
            result.done(ok, ok ? "sent to #bug-reports" : message + " - copied to the clipboard instead", saved);
        }));
    }

    /**
     * The 30-minute log (LogUploader): the same log and config, no dialog,
     * nothing said in chat, nothing saved. Built on the game thread.
     */
    public static void sendAuto() {
        String relay = relayUrl();
        if (relay.isEmpty()) {
            return;
        }
        String head;
        String config;
        String title;
        Redactor redactor;
        try {
            /* What only the game thread may read, now; the log files (up to
               1 MB each) are read on the upload thread, so a mid-fight
               upload costs no frame time. */
            redactor = redactor();
            head = logHead(MARK_AUTO, null, null);
            config = buildConfig();
            title = redactor.apply("[" + (Myau.version == null ? "dev" : Myau.version) + "] log - " + server());
        } catch (Throwable t) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                StringBuilder sb = new StringBuilder(head);
                logFiles(sb);
                post(relay, "auto", title, "", redactor.apply(sb.toString()), config, (ok, message) -> {
                });
            } catch (Throwable ignored) {
                // The next one is in 30 minutes.
            }
        }, "Myau log upload");
        thread.setDaemon(true);
        thread.start();
    }

    private interface Sent {
        void done(boolean ok, String message);
    }

    /** POSTs to the relay on a daemon thread; answers on that thread. */
    private static void post(String relay, String kind, String title, String summary, String log, String config, Sent sent) {
        Thread thread = new Thread(() -> {
            boolean ok = false;
            String message;
            try {
                com.google.gson.JsonObject json = new com.google.gson.JsonObject();
                json.addProperty("kind", kind);
                json.addProperty("title", title);
                json.addProperty("summary", summary);
                json.addProperty("report", log);
                json.addProperty("config", config);
                byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
                java.net.HttpURLConnection http = (java.net.HttpURLConnection) new java.net.URL(relay).openConnection();
                http.setRequestMethod("POST");
                http.setConnectTimeout(8000);
                http.setReadTimeout(15000);
                http.setDoOutput(true);
                http.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                http.setRequestProperty("X-Myau-Report", "1");
                http.setRequestProperty("User-Agent", "MyauAtlas/" + (Myau.version == null ? "dev" : Myau.version));
                http.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream out = http.getOutputStream()) {
                    out.write(body);
                }
                int code = http.getResponseCode();
                java.io.InputStream in = code < 400 ? http.getInputStream() : http.getErrorStream();
                String reply = "";
                if (in != null) {
                    try (java.util.Scanner scanner = new java.util.Scanner(in, "UTF-8").useDelimiter("\\A")) {
                        reply = scanner.hasNext() ? scanner.next().trim() : "";
                    }
                }
                ok = code == 200;
                message = ok ? "sent" : "the relay said: " + (reply.isEmpty() ? "HTTP " + code : reply);
            } catch (Exception e) {
                message = "could not reach the relay (" + e.getClass().getSimpleName() + ")";
            }
            sent.done(ok, message);
        }, "Myau bug report");
        thread.setDaemon(true);
        thread.start();
    }

    private static String title(String reason, String description) {
        String what = description == null ? "" : description.trim().replace('\n', ' ');
        if (reason != null && !reason.isEmpty()) {
            what = what.isEmpty() ? reason : reason + ": " + what;
        }
        if (what.isEmpty()) {
            what = "Report";
        }
        if (what.length() > 60) {
            what = what.substring(0, 60) + "...";
        }
        return redactor().apply("[" + (Myau.version == null ? "dev" : Myau.version) + "] " + what + " - " + server());
    }

    private static String summary(String reason, String description) {
        StringBuilder sb = new StringBuilder();
        if (reason != null && !reason.isEmpty()) {
            sb.append("**Reason:** ").append(reason).append('\n');
        }
        if (description != null && !description.trim().isEmpty()) {
            sb.append("**What happened:** ").append(description.trim()).append('\n');
        }
        sb.append("**Version:** ").append(Myau.version == null ? "dev" : Myau.version)
                .append(" | **Server:** ").append(server())
                .append(" | **Menu:** ").append(setting("ClickGUI", "Style"))
                .append(" | **OptiFine:** ").append(optifine() ? (FramebufferCompat.optifineFastRender() ? "Fast Render ON" : "yes") : "no")
                .append('\n');
        sb.append("Log (report.txt) and config (config.txt) attached; names, tokens and IPs removed.");
        return redactor().apply(sb.toString());
    }

    private static File save(String text) {
        try {
            if (!REPORT_DIR.exists()) {
                REPORT_DIR.mkdirs();
            }
            File file = new File(REPORT_DIR, "report-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".txt");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                w.write(text);
            }
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    /** Builds, saves and copies the report; returns the file it was saved to, or null. */
    public static File createAndCopy() {
        String text = build(null);
        GuiScreen.setClipboardString(text);
        return save(text);
    }

    /** The relay checks for these: a report, or a 30-minute log. */
    private static final String MARK_REPORT = "**Myau Atlas bug report**";
    private static final String MARK_AUTO = "**Myau Atlas log**";

    /** Log and config together (the clipboard copy). */
    public static String build(String description) {
        return buildLog(MARK_REPORT, null, description) + "\n" + buildConfig();
    }

    /**
     * The log part (2026-10-08: longer -- 150 flag lines, 80 placement and
     * Clutch lines, 60 fight and hit lines, 250 game-log lines -- and without
     * the module settings, which are the config part now).
     */
    public static String buildLog(String mark, String reason, String description) {
        StringBuilder sb = new StringBuilder(logHead(mark, reason, description));
        logFiles(sb);
        return redactor().apply(sb.toString());
    }

    /** The part read from the game (GL strings, display, modules): game thread only. */
    private static String logHead(String mark, String reason, String description) {
        StringBuilder sb = new StringBuilder();
        sb.append(mark).append('\n');
        if (MARK_REPORT.equals(mark)) {
            sb.append("Reason: ").append(reason == null || reason.isEmpty() ? "(not given)" : reason).append('\n');
            sb.append("What happened: ").append(description == null ? "" : description.trim()).append('\n');
        } else {
            sb.append("Sent automatically every 30 minutes of play (Client Settings > auto-send-logs).\n");
        }
        sb.append("```\n");

        section(sb, "Client");
        line(sb, "Version", "Myau Atlas v" + (Myau.version == null ? "dev" : Myau.version) + " | MC 1.8.9 | Forge "
                + safe(ForgeVersion::getVersion));
        line(sb, "Click menu", setting("ClickGUI", "Style"));
        line(sb, "Java", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        line(sb, "OS", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        line(sb, "Memory", (Runtime.getRuntime().maxMemory() >> 20) + " MB max");
        line(sb, "GPU", safe(() -> GL11.glGetString(GL11.GL_RENDERER)) + " | GL " + safe(() -> GL11.glGetString(GL11.GL_VERSION)));
        line(sb, "OptiFine", optifine() ? "installed, Fast Render " + (FramebufferCompat.optifineFastRender() ? "ON" : "off") : "not installed");
        line(sb, "Framebuffers", (OpenGlHelper.isFramebufferEnabled() ? "on" : "off")
                + ", shaders " + (OpenGlHelper.shadersSupported ? "supported" : "unsupported"));
        line(sb, "Display", mc.displayWidth + "x" + mc.displayHeight + ", GUI scale " + mc.gameSettings.guiScale
                + ", " + Minecraft.getDebugFPS() + " fps");
        line(sb, "Server", server() + ", ping " + Ping.own() + " ms");
        line(sb, "Other mods", otherMods());

        List<String> on = new ArrayList<String>();
        for (Module module : Myau.moduleManager.modules.values()) {
            if (module.isEnabled()) {
                on.add(module.getName());
            }
        }
        line(sb, "Enabled modules", on.isEmpty() ? "none" : String.join(", ", on) + "  (settings: config.txt)");
        return sb.toString();
    }

    /** The part read from files: any thread. */
    private static void logFiles(StringBuilder sb) {
        tail(sb, "FlagDetector (last 150)", newest("flags-"), 150);
        tail(sb, "Placements (last 80)", newest("places-"), 80);
        tail(sb, "Clutch (last 80)", newest("clutch-"), 80);
        tail(sb, "Fights (last 60)", newest("fights-"), 60);
        tail(sb, "Hits (last 60)", newest("hits-"), 60);
        gameLog(sb, 250);
        sb.append("```\n");
    }

    /** The config part: every module, on or off, with all its settings. */
    public static String buildConfig() {
        StringBuilder sb = new StringBuilder();
        sb.append("Myau Atlas config (").append(Myau.version == null ? "dev" : Myau.version).append(")\n");
        sb.append("[x] = enabled\n\n");
        for (Module module : Myau.moduleManager.modules.values()) {
            sb.append(module.isEnabled() ? "[x] " : "[ ] ").append(module.getName()).append(": ")
                    .append(settings(module)).append('\n');
        }
        return redactor().apply(sb.toString());
    }

    // ---------------------------------------------------------------- parts

    private static void section(StringBuilder sb, String title) {
        sb.append("\n== ").append(title).append(" ==\n");
    }

    private static void line(StringBuilder sb, String key, String value) {
        sb.append(key).append(": ").append(value).append('\n');
    }

    private interface Source {
        String get() throws Exception;
    }

    private static String safe(Source source) {
        try {
            String value = source.get();
            return value == null ? "?" : value;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static boolean optifine() {
        try {
            net.minecraft.client.settings.GameSettings.class.getDeclaredField("ofFastRender");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String server() {
        if (mc.isSingleplayer()) {
            return "singleplayer";
        }
        if (mc.getCurrentServerData() == null) {
            return "not connected";
        }
        return mc.getCurrentServerData().serverIP;
    }

    private static String otherMods() {
        try {
            List<String> names = new ArrayList<String>();
            for (ModContainer mod : Loader.instance().getActiveModList()) {
                String id = mod.getModId();
                if ("mcp".equals(id) || "FML".equals(id) || "Forge".equals(id) || "forge".equals(id)) {
                    continue;
                }
                names.add(mod.getName() + " " + mod.getVersion());
            }
            return names.isEmpty() ? "none" : String.join(", ", names);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String setting(String moduleName, String propertyName) {
        Module module = Myau.moduleManager.getModule(moduleName);
        if (module == null) {
            return "?";
        }
        List<Property<?>> properties = Myau.propertyManager.properties.get(module.getClass());
        if (properties != null) {
            for (Property<?> property : properties) {
                if (property.getName().equalsIgnoreCase(propertyName)) {
                    return value(property);
                }
            }
        }
        return "?";
    }

    private static String settings(Module module) {
        List<Property<?>> properties = Myau.propertyManager.properties.get(module.getClass());
        if (properties == null || properties.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (Property<?> property : properties) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(property.getName()).append('=').append(value(property));
        }
        return sb.toString();
    }

    private static String value(Property<?> property) {
        if (property instanceof ModeProperty) {
            return ((ModeProperty) property).getModeString();
        }
        return String.valueOf(property.getValue());
    }

    /** The newest of this client's logs with this prefix, or null. */
    private static File newest(String prefix) {
        File[] files = MYAU_DIR.listFiles((dir, name) -> name.startsWith(prefix) && name.endsWith(".txt"));
        if (files == null || files.length == 0) {
            return null;
        }
        File best = files[0];
        for (File f : files) {
            if (f.lastModified() > best.lastModified()) {
                best = f;
            }
        }
        return best;
    }

    private static void tail(StringBuilder sb, String title, File file, int lines) {
        section(sb, title + (file == null ? "" : " - " + file.getName()));
        if (file == null) {
            sb.append("(none)\n");
            return;
        }
        List<String> tail = lastLines(file, lines, null);
        if (tail.isEmpty()) {
            sb.append("(empty)\n");
        }
        for (String l : tail) {
            sb.append(l).append('\n');
        }
    }

    /** Problems and client/anticheat messages only: other players' chat never goes in. */
    private static void gameLog(StringBuilder sb, int lines) {
        section(sb, "Game log (warnings, errors, client and anticheat messages)");
        List<String> tail = lastLines(GAME_LOG, lines, LOG_KEEP);
        if (tail.isEmpty()) {
            sb.append("(nothing)\n");
        }
        for (String l : tail) {
            /* Minecraft colour codes are noise in a text report. */
            sb.append(l.replaceAll("§.", "")).append('\n');
        }
    }

    /** The last lines of a file (reading at most its last 1 MB), optionally only matching ones. */
    private static List<String> lastLines(File file, int count, Pattern keep) {
        List<String> out = new ArrayList<String>();
        if (file == null || !file.isFile()) {
            return out;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            long start = Math.max(0, length - 1024 * 1024);
            byte[] bytes = new byte[(int) (length - start)];
            raf.seek(start);
            raf.readFully(bytes);
            String[] all = new String(bytes, StandardCharsets.UTF_8).split("\r?\n");
            for (int i = all.length - 1; i >= 0 && out.size() < count; i--) {
                String l = all[i];
                if (l.trim().isEmpty() || keep != null && !keep.matcher(l).find()) {
                    continue;
                }
                if (keep != null && l.contains("[CHAT]") && !isOwnMessage(l)) {
                    continue;
                }
                out.add(0, l);
            }
        } catch (Exception ignored) {
            // A missing or locked file only leaves its part of the report empty.
        }
        return out;
    }

    /** A chat line the client or the anticheat wrote, not a player. */
    private static boolean isOwnMessage(String line) {
        return line.contains("GrimAC") || line.contains("FlagDetector") || line.contains("Myau")
                || line.contains("M§6y§ea§au") || line.contains("Vulcan") || line.contains("Polar");
    }

    private static Redactor redactor() {
        List<String> names = new ArrayList<String>();
        String self = null;
        String uuid = null;
        String token = null;
        try {
            self = mc.getSession().getUsername();
            uuid = mc.getSession().getPlayerID();
            token = mc.getSession().getToken();
        } catch (Throwable ignored) {
            // No session details: nothing of them to take out.
        }
        try {
            if (mc.getNetHandler() != null) {
                for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                    if (info.getGameProfile() != null && info.getGameProfile().getName() != null) {
                        names.add(info.getGameProfile().getName());
                    }
                }
            }
        } catch (Throwable ignored) {
            // The tab list can change under us; the names collected so far still count.
        }
        return new Redactor(self, uuid, token, names, System.getProperty("user.home"), System.getProperty("user.name"));
    }
}
