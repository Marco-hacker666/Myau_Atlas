package myau.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Lines taken from the logs of the servers actually played (2026-10-04). */
public class MatchChatTest {

    @Test
    public void startsAreRecognised() {
        assertEquals(MatchChat.Kind.START, MatchChat.classify("                             MATCH START!", "asd3b1", null));
        assertEquals(MatchChat.Kind.START, MatchChat.classify("Game starts in 1...", "asd3b1", null));
        assertEquals(MatchChat.Kind.START, MatchChat.classify("The game starts in 1 second!", "asd3b1", null));
        assertEquals(MatchChat.Kind.NONE, MatchChat.classify("The game starts in 10 seconds!", "asd3b1", null));
        assertEquals(MatchChat.Kind.NONE, MatchChat.classify("Game starts in 2...", "asd3b1", null));
    }

    @Test
    public void someoneTypingItIsNotAnEvent() {
        assertEquals(MatchChat.Kind.NONE, MatchChat.classify("[VIP] troll: MATCH START!", "asd3b1", null));
        assertEquals(MatchChat.Kind.NONE, MatchChat.classify("[SPECTATOR] x: YOU WON", "asd3b1", null));
    }

    @Test
    public void pikaTwoLineResult() {
        String names = "                   Opponent4 0? §j 16? asd3b1";
        assertEquals(MatchChat.Kind.WIN, MatchChat.classify("                              LOSER!   WINNER!", "asd3b1", names));
        assertEquals(MatchChat.Kind.LOSS, MatchChat.classify("                              LOSER!   WINNER!", "Opponent4", names));
        assertEquals(MatchChat.Kind.NONE, MatchChat.classify("LOSER!   WINNER!", "asd3b1", null));
    }

    @Test
    public void duelOneLineResult() {
        assertEquals(MatchChat.Kind.WIN, MatchChat.classify("             Player1 WINNER!  [VIP] Opponent2", "Player1", null));
        assertEquals(MatchChat.Kind.LOSS, MatchChat.classify("                Player1   Opponent1 WINNER!", "Player1", null));
        assertEquals(MatchChat.Kind.LOSS, MatchChat.classify("         Player1   [VIP+] Opponent3 WINNER!", "Player1", null));
        assertEquals(MatchChat.Kind.NONE, MatchChat.classify("   a WINNER!  b", "Player1", null));
        /* A name that merely ends with this one is someone else. */
        assertEquals(MatchChat.Kind.LOSS, MatchChat.classify("   xx_bob WINNER!  bob", "bob", null));
    }

    @Test
    public void hypixelWin() {
        assertEquals(MatchChat.Kind.WIN, MatchChat.classify("YOU WON! Want to play again? CLICK HERE! ", "me", null));
    }
}
