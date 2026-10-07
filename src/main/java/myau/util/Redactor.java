package myau.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes what identifies a person out of text that is about to be shared
 * (2026-10-07, for the bug report): the player's own name and UUID, the
 * session token, other players' names, the Windows account and home folder,
 * e-mail addresses, anything shaped like a token, and IPv4 addresses.
 *
 * Pure string work, so it is tested without the game.
 */
public final class Redactor {
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    /* JWT-like (three dot-separated base64url runs) and long bare tokens. */
    private static final Pattern JWT = Pattern.compile("[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{8,}");
    private static final Pattern LONG_TOKEN = Pattern.compile("\\b[A-Za-z0-9_-]{40,}\\b");
    private static final Pattern IPV4 = Pattern.compile("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b");
    private static final Pattern UUID_DASHED = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");

    private final String self;
    private final String selfUuid;
    private final String token;
    private final List<String> others = new ArrayList<String>();
    private final String home;
    private final String account;

    /**
     * @param self     the player's own name, or null
     * @param selfUuid the player's own UUID, with or without dashes, or null
     * @param token    the session token, or null
     * @param others   other players' names (the tab list)
     * @param home     the OS home folder, or null
     * @param account  the OS account name, or null
     */
    public Redactor(String self, String selfUuid, String token, Collection<String> others, String home, String account) {
        this.self = blank(self) ? null : self;
        this.selfUuid = blank(selfUuid) ? null : selfUuid.replace("-", "").toLowerCase(Locale.ROOT);
        this.token = blank(token) || token.length() < 8 ? null : token;
        if (others != null) {
            for (String name : others) {
                /* Two letters would take pieces out of ordinary words. */
                if (!blank(name) && name.length() >= 3 && !name.equalsIgnoreCase(self)) {
                    this.others.add(name);
                }
            }
            /* Longest first, so a name that contains another goes whole. */
            this.others.sort((a, b) -> b.length() - a.length());
        }
        this.home = blank(home) ? null : home;
        this.account = blank(account) || account.length() < 3 ? null : account;
    }

    public String apply(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        if (this.token != null) {
            out = out.replace(this.token, "<token>");
        }
        out = JWT.matcher(out).replaceAll("<token>");
        if (this.home != null) {
            out = replaceIgnoreCase(out, this.home.replace('\\', '/'), "<home>");
            out = replaceIgnoreCase(out, this.home.replace('/', '\\'), "<home>");
        }
        out = EMAIL.matcher(out).replaceAll("<email>");
        if (this.selfUuid != null) {
            out = replaceIgnoreCase(out, dashed(this.selfUuid), "<my-uuid>");
            out = replaceIgnoreCase(out, this.selfUuid, "<my-uuid>");
        }
        out = UUID_DASHED.matcher(out).replaceAll("<uuid>");
        out = LONG_TOKEN.matcher(out).replaceAll("<token>");
        if (this.self != null) {
            out = replaceWord(out, this.self, "<me>");
        }
        for (String name : this.others) {
            out = replaceWord(out, name, "<player>");
        }
        if (this.account != null) {
            out = replaceWord(out, this.account, "<user>");
        }
        out = maskIps(out);
        return out;
    }

    /** Keeps loopback and private ranges readable as such; every other address is masked. */
    private static String maskIps(String text) {
        Matcher m = IPV4.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int a = Integer.parseInt(m.group(1));
            String replacement = a == 127 ? "127.x.x.x" : a == 10 || a == 192 || a == 172 ? "<lan-ip>" : "<ip>";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Whole-word, case-insensitive: a name inside a longer identifier is left alone. */
    private static String replaceWord(String text, String word, String with) {
        Pattern p = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(word) + "(?![A-Za-z0-9_])",
                Pattern.CASE_INSENSITIVE);
        return p.matcher(text).replaceAll(Matcher.quoteReplacement(with));
    }

    private static String replaceIgnoreCase(String text, String what, String with) {
        if (what.isEmpty()) {
            return text;
        }
        return Pattern.compile(Pattern.quote(what), Pattern.CASE_INSENSITIVE).matcher(text)
                .replaceAll(Matcher.quoteReplacement(with));
    }

    private static String dashed(String hex) {
        if (hex.length() != 32) {
            return hex;
        }
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
                + hex.substring(16, 20) + "-" + hex.substring(20);
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
