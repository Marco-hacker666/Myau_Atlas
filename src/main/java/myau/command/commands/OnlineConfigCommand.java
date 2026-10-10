package myau.command.commands;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

// Ported from OpenSkid (GPL-3.0), itself adapted from the MiauMinus
// online-config concept.
// Loads shared JSON configs from a URL or file path, applied tolerantly per
// property. Fetches are cached locally. Nothing applies without this command.
public class OnlineConfigCommand extends Command {

    public OnlineConfigCommand() {
        super(new ArrayList<String>(Arrays.asList("onlineconfig", "onlinecfg", "ocfg", "online")));
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
                ChatUtil.sendFormatted(String.format("%sMissing source (URL, file path, or cached name)&r", Myau.clientName));
                return;
            }
            load(join(args, 2));
        } else if (sub.equals("list") || sub.equals("l") || sub.equals("cache") || sub.equals("cached")) {
            listCache();
        } else {
            ChatUtil.sendFormatted(String.format("%sInvalid argument (&o%s&r)&r", Myau.clientName, clean(args.get(1))));
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
            ChatUtil.sendFormatted(String.format("%sFailed to load online config (&c&o%s&r)&r", Myau.clientName, clean(e.getMessage())));
        }
    }

    private void report(String label, OnlineConfigApplier.Result result) {
        ChatUtil.sendFormatted(String.format("%sOnline config applied (&a&o%s&r) &7- applied &a%d&r, failed &c%d&r",
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

    private void listCache() {
        try {
            List<OnlineConfigEntry> entries = OnlineConfigCache.list();
            if (entries.isEmpty()) {
                ChatUtil.sendFormatted(String.format("%sNo cached online configs (use '&oload&r <url>' first)&r", Myau.clientName));
                return;
            }
            ChatUtil.sendFormatted(String.format("%sCached online configs:&r", Myau.clientName));
            for (OnlineConfigEntry entry : entries) {
                String command = ".onlineconfig load " + entry.getId();
                String line = String.format("&7»&r &o%s&r &7(%d bytes)&r", entry.getName(), entry.getSize());
                ChatUtil.send(new ChatComponentText(ChatColors.formatColor(line)).setChatStyle(new ChatStyle()
                        .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(command)))));
            }
        } catch (Exception e) {
            ChatUtil.sendFormatted(String.format("%sFailed to list cache (&c&o%s&r)&r", Myau.clientName, clean(e.getMessage())));
        }
    }

    private void usage(ArrayList<String> args) {
        String command = args.isEmpty() ? "onlineconfig" : args.get(0).toLowerCase(Locale.ROOT);
        ChatUtil.sendFormatted(String.format("%sUsage: .%s &oload&r <&ourl/file/cached&r> | .%s &olist&r", Myau.clientName, command, command));
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
