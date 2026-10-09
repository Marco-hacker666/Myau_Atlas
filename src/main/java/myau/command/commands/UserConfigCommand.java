package myau.command.commands;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import myau.Myau;
import myau.command.Command;
import myau.config.online.OnlineConfigApplier;
import myau.config.online.OnlineConfigCache;
import myau.config.online.OnlineConfigEntry;
import myau.config.online.OnlineConfigFetcher;
import myau.enums.ChatColors;
import myau.util.ChatUtil;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// Ported from OpenSkid (GPL-3.0), itself adapted from the MiauMinus
// online-config concept.
// Named user configs with URL-plus-cache semantics. Publish snapshots the
// current settings to the local cache. There is no account system. Names are
// local only. Applying always reports what changed.
public class UserConfigCommand extends Command {

    public UserConfigCommand() {
        super(new ArrayList<String>(Arrays.asList("userconfig", "usercfg", "ucfg")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (args.size() < 2) {
            usage(args);
            return;
        }
        String sub = args.get(1).toLowerCase(Locale.ROOT);
        if (sub.equals("load") || sub.equals("fetch") || sub.equals("apply") || sub.equals("get")) {
            if (args.size() < 3) {
                ChatUtil.sendFormatted(String.format("%sMissing name (cached name, URL, or file path)&r", Myau.clientName));
                return;
            }
            load(join(args, 2));
        } else if (sub.equals("save") || sub.equals("publish") || sub.equals("share")) {
            if (args.size() < 3) {
                ChatUtil.sendFormatted(String.format("%sMissing name (e.g. '.userconfig save mymode')&r", Myau.clientName));
                return;
            }
            publish(join(args, 2));
        } else if (sub.equals("list") || sub.equals("l")) {
            listCache();
        } else if (sub.equals("info")) {
            if (args.size() < 3) {
                ChatUtil.sendFormatted(String.format("%sMissing name&r", Myau.clientName));
                return;
            }
            info(join(args, 2));
        } else {
            ChatUtil.sendFormatted(String.format("%sInvalid argument (&o%s&r)&r", Myau.clientName, clean(args.get(1))));
        }
    }

    private void publish(String name) {
        try {
            File file = OnlineConfigCache.snapshotCurrent(name);
            ChatUtil.sendFormatted(String.format("%sPublished user config (&a&o%s&r) to local cache&r", Myau.clientName, clean(OnlineConfigCache.sanitize(name))));
            ChatUtil.sendFormatted(String.format("&7File: &f%s&r", clean(file.getPath())));
            ChatUtil.sendFormatted("&7Share the JSON file or host it at a URL. Others load it with '&ouserconfig load <url>&r'. No account needed.&r");
        } catch (Exception e) {
            ChatUtil.sendFormatted(String.format("%sFailed to publish user config (&c&o%s&r)&r", Myau.clientName, clean(e.getMessage())));
        }
    }

    private void load(String source) {
        try {
            String json;
            String label;
            boolean url = OnlineConfigFetcher.looksLikeUrl(source);
            boolean fileExists = new File(source).isFile();
            if (!url && !fileExists && OnlineConfigCache.has(source)) {
                json = OnlineConfigCache.load(source);
                label = OnlineConfigCache.sanitize(source) + " (cache)";
            } else {
                json = OnlineConfigFetcher.fetch(source);
                label = source;
                try {
                    OnlineConfigCache.save(OnlineConfigCache.sanitize(source), json);
                } catch (Exception e) {
                    ChatUtil.sendFormatted(String.format("%sNote: could not cache download (&o%s&r)&r", Myau.clientName, clean(e.getMessage())));
                }
            }
            OnlineConfigApplier.Result result = new OnlineConfigApplier().apply(json);
            report(label, result);
        } catch (Exception e) {
            ChatUtil.sendFormatted(String.format("%sFailed to load user config (&c&o%s&r)&r", Myau.clientName, clean(e.getMessage())));
        }
    }

    private void report(String label, OnlineConfigApplier.Result result) {
        ChatUtil.sendFormatted(String.format("%sUser config applied (&a&o%s&r) &7- applied &a%d&r, failed &c%d&r",
                Myau.clientName, clean(label), result.applied, result.failed));
        if (!result.changed.isEmpty()) {
            int show = Math.min(8, result.changed.size());
            String extra = result.changed.size() > show ? String.format(" &7(+%d more)&r", result.changed.size() - show) : "&r";
            ChatUtil.sendFormatted(String.format("&7Changed: &f%s%s", clean(join(result.changed.subList(0, show), ", ")), extra));
        }
        if (!result.failedNames.isEmpty()) {
            int show = Math.min(5, result.failedNames.size());
            ChatUtil.sendFormatted(String.format("&cSkipped: &f%s&r", clean(join(result.failedNames.subList(0, show), ", "))));
        }
    }

    private void info(String name) {
        try {
            OnlineConfigEntry entry = OnlineConfigCache.find(name);
            if (entry == null) {
                ChatUtil.sendFormatted(String.format("%sUser config not found (&o%s&r)&r", Myau.clientName, clean(name)));
                return;
            }
            String date = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(entry.getCachedAt()));
            ChatUtil.sendFormatted(String.format("%sUser config info:&r", Myau.clientName));
            ChatUtil.sendFormatted(String.format("&fName: &a%s&r", clean(entry.getName())));
            ChatUtil.sendFormatted(String.format("&fSize: &b%d bytes&r", entry.getSize()));
            ChatUtil.sendFormatted(String.format("&fCached: &b%s&r", date));
            try {
                JsonElement parsed = new JsonParser().parse(OnlineConfigCache.load(entry.getId()));
                if (parsed != null && parsed.isJsonObject()) {
                    ChatUtil.sendFormatted(String.format("&fModules in file: &e%d&r", parsed.getAsJsonObject().entrySet().size()));
                }
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            ChatUtil.sendFormatted(String.format("%sFailed to read user config (&c&o%s&r)&r", Myau.clientName, clean(e.getMessage())));
        }
    }

    private void listCache() {
        try {
            List<OnlineConfigEntry> entries = OnlineConfigCache.list();
            if (entries.isEmpty()) {
                ChatUtil.sendFormatted(String.format("%sNo user configs (use '&osave&r <name>' or '&oload&r <url>')&r", Myau.clientName));
                return;
            }
            ChatUtil.sendFormatted(String.format("%sUser configs:&r", Myau.clientName));
            for (OnlineConfigEntry entry : entries) {
                String command = ".userconfig load " + entry.getId();
                String line = String.format("&7»&r &o%s&r &7(%d bytes)&r", entry.getName(), entry.getSize());
                ChatUtil.send(new ChatComponentText(ChatColors.formatColor(line)).setChatStyle(new ChatStyle()
                        .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(command)))));
            }
        } catch (Exception e) {
            ChatUtil.sendFormatted(String.format("%sFailed to list user configs (&c&o%s&r)&r", Myau.clientName, clean(e.getMessage())));
        }
    }

    private void usage(ArrayList<String> args) {
        String command = args.isEmpty() ? "userconfig" : args.get(0).toLowerCase(Locale.ROOT);
        ChatUtil.sendFormatted(String.format("%sUsage: .%s &oload&r <&oname/url&r> | .%s &osave&r <&oname&r> | .%s &olist&r | .%s &oinfo&r <&oname&r",
                Myau.clientName, command, command, command, command));
    }

    private static String join(List<String> parts, String separator) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) {
                out.append(separator);
            }
            out.append(part);
        }
        return out.toString();
    }

    private static String join(ArrayList<String> args, int from) {
        return join(args.subList(from, args.size()), " ").trim();
    }

    private static String clean(String value) {
        if (value == null || value.isEmpty()) {
            return "unknown";
        }
        return value.replace("&", "").replace("§", "");
    }
}
