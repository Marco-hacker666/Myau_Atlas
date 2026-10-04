package myau.util;

import java.util.regex.Pattern;

/**
 * What a server chat line says about a game: one starting, this player
 * winning or losing, or nothing (2026-10-04, for PlayTracker).
 *
 * Read off the chat of the servers actually played, from the logs:
 *   Pika practice  "MATCH START!" ... "Game starts in 1..."; at the end a row
 *                  of the two names and, on the next line, "LOSER!   WINNER!"
 *                  in the same left/right order.
 *   duels          "Player1 WINNER!  Opponent1" -- the label follows
 *                  the winner's name.
 *   Hypixel        "The game starts in 1 second" ... "YOU WON! Want to play
 *                  again?"; titles "VICTORY!" / "GAME OVER!" are handled by
 *                  the caller.
 * Lines with ": " are someone talking and are never read as an event, so a
 * player typing "MATCH START!" counts for nothing.
 *
 * Pure: strings in, answer out.
 */
public final class MatchChat {

    public enum Kind { NONE, START, WIN, LOSS }

    private static final Pattern START = Pattern.compile(
            "(?i)^(match start!|(the )?game starts in 1( second\\b|\\.\\.\\.).*)$");

    private MatchChat() {
    }

    /**
     * @param line         the chat line, colour codes stripped
     * @param me           this player's name
     * @param lastNameLine the last earlier line naming this player, for Pika's
     *                     two-line result; may be null
     */
    public static Kind classify(String line, String me, String lastNameLine) {
        if (line == null || me == null || me.isEmpty()) {
            return Kind.NONE;
        }
        String text = line.trim();
        if (text.isEmpty() || text.contains(": ")) {
            return Kind.NONE;
        }
        if (START.matcher(text).matches()) {
            return Kind.START;
        }
        if (text.toUpperCase().startsWith("YOU WON")) {
            return Kind.WIN;
        }
        int winner = text.indexOf("WINNER!");
        if (winner < 0) {
            return Kind.NONE;
        }
        int loser = text.indexOf("LOSER!");
        if (loser >= 0) {
            /* Pika: the labels row; which side this player is on is in the
               names row before it. */
            if (lastNameLine == null) {
                return Kind.NONE;
            }
            String names = lastNameLine.trim();
            int at = names.indexOf(me);
            if (at < 0) {
                return Kind.NONE;
            }
            boolean meLeft = at + me.length() / 2.0 < names.length() / 2.0;
            boolean winnerLeft = winner < loser;
            return meLeft == winnerLeft ? Kind.WIN : Kind.LOSS;
        }
        /* Duels: the winner is the name right before the label. */
        if (!text.contains(me)) {
            return Kind.NONE;
        }
        String before = text.substring(0, winner).trim();
        return before.endsWith(me) && (before.length() == me.length()
                || !Character.isLetterOrDigit(before.charAt(before.length() - me.length() - 1))
                && before.charAt(before.length() - me.length() - 1) != '_')
                ? Kind.WIN : Kind.LOSS;
    }

    /** A line naming this player that a Pika labels row may refer back to. */
    public static boolean namesMe(String line, String me) {
        return line != null && me != null && !me.isEmpty() && line.contains(me) && !line.contains(": ")
                && !line.contains("WINNER!") && !line.contains("LOSER!");
    }
}
