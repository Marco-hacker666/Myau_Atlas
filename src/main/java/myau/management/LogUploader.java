package myau.management;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.util.BugReport;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;

/**
 * Auto-send logs (2026-10-08): every 30 minutes of play (time in a world,
 * not time with the game open) the client sends its log -- the same redacted
 * log a bug report carries -- and the config, through the report relay to the
 * log channel. Switched by "auto-send-logs" in Client Settings, on by default.
 *
 * The switch lives in config/Myau/atlas-theme.json with the menu's other
 * settings. The menu may never be opened in a session, so the value is read
 * from that file here the first time it is needed; AtlasTheme pushes every
 * later change (setEnabled). The first time logs would be sent, the player
 * is told once in chat what is sent and where to turn it off.
 */
public final class LogUploader {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int PLAY_TICKS = 30 * 60 * 20;
    private static final File THEME_FILE = new File("./config/Myau/atlas-theme.json");
    private static final File NOTICE_FILE = new File("./config/Myau/auto-send-logs-notice.txt");
    public static final String SETTING = "auto-send-logs";

    private static volatile Boolean enabled;
    private int ticks;
    private boolean noticeChecked;

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        Boolean value = enabled;
        if (value == null) {
            value = readSetting();
            enabled = value;
        }
        return value;
    }

    private static boolean readSetting() {
        if (!THEME_FILE.isFile()) {
            return true;
        }
        try (Reader reader = new FileReader(THEME_FILE)) {
            JsonElement parsed = new JsonParser().parse(reader);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                if (object.has(SETTING)) {
                    return object.get(SETTING).getAsBoolean();
                }
            }
        } catch (Exception ignored) {
            // Unreadable: the default.
        }
        return true;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE || mc.theWorld == null || mc.thePlayer == null || Myau.moduleManager == null) {
            return;
        }
        if (!isEnabled()) {
            return;
        }
        if (!this.noticeChecked) {
            this.noticeChecked = true;
            notice();
        }
        if (++this.ticks < PLAY_TICKS) {
            return;
        }
        this.ticks = 0;
        BugReport.sendAuto();
    }

    /** Once per install: what goes out, and where to switch it off. */
    private static void notice() {
        if (NOTICE_FILE.exists()) {
            return;
        }
        try {
            ChatUtil.sendFormatted(Myau.clientName + "&7Every 30 minutes of play, Myau Atlas sends its log and config"
                    + " to the developers to help fix bugs (your name, other players' names, tokens and IPs are removed)."
                    + " Turn it off in ClickGUI > Client Settings > Appearance > Privacy > auto-send-logs.");
            File dir = NOTICE_FILE.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            java.nio.file.Files.write(NOTICE_FILE.toPath(), "shown\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Shown again next time.
        }
    }
}
