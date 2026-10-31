package de.mcstack.craftcore.pvp;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PvpMatchTest {
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private PvpMatch match() { return new PvpMatch("match", a, b, "kitA", "kitB", new Random(1), 100); }

    @Test void kitsAndTwoZero() {
        PvpMatch m = match();
        assertEquals("kitA", m.kitRound1());
        assertEquals("kitA", m.currentKit());
        assertEquals(new PvpMatch.RoundRecord(1, "kitA", a, 50), m.death(b, 150));
        assertFalse(m.finished());
        assertEquals(2, m.roundNo());
        assertEquals("kitB", m.currentKit());
        m.startRound(200);
        assertEquals(200, m.roundStartedAt());
        m.death(b, 250);
        assertTrue(m.finished());
        assertEquals(a, m.matchWinner());
        assertEquals(b, m.matchLoser());
        assertNull(m.death(b, 300));
        assertThrows(UnsupportedOperationException.class, () -> m.rounds().clear());
    }

    @Test void oneOneThenThirdRound() {
        PvpMatch m = match();
        m.death(b, 110);
        m.startRound(200);
        m.death(a, 210);
        assertFalse(m.finished());
        assertEquals(3, m.roundNo());
        assertTrue(List.of("kitA", "kitB").contains(m.currentKit()));
        m.startRound(300);
        m.death(a, 310);
        assertEquals(b, m.matchWinner());
        assertEquals(a, m.matchLoser());
    }

    @Test void timeoutAndMajorityAfterDraws() {
        PvpMatch m = match();
        m.death(b, 110);
        m.startRound(200);
        assertNull(m.tick(200 + PvpMatch.ROUND_LIMIT_MS - 1));
        assertNull(m.tick(200 + PvpMatch.ROUND_LIMIT_MS).winner());
        assertFalse(m.finished());
        m.startRound(400_000);
        assertNull(m.tick(400_000 + PvpMatch.ROUND_LIMIT_MS).winner());
        assertEquals(a, m.matchWinner());
        assertEquals(b, m.matchLoser());
        assertNull(m.tick(800_000));
    }

    @Test void allDrawsAndTiedWins() {
        PvpMatch m = match();
        for (int i = 0; i < 3; i++) {
            m.startRound(i * 400_000L);
            m.tick(i * 400_000L + PvpMatch.ROUND_LIMIT_MS);
        }
        assertTrue(m.finished());
        assertNull(m.matchWinner());
        assertNull(m.matchLoser());
        PvpMatch tied = match();
        tied.death(b, 110);
        tied.death(a, 120);
        tied.tick(100 + PvpMatch.ROUND_LIMIT_MS);
        assertTrue(tied.finished());
        assertNull(tied.matchWinner());
    }

    @Test void disconnectDecidesMatchEvenAgainstPriorWin() {
        PvpMatch m = match();
        m.death(b, 110);
        m.startRound(200);
        assertEquals(b, m.disconnect(a, 250).winner());
        assertTrue(m.finished());
        assertEquals(b, m.matchWinner());
        assertEquals(a, m.matchLoser());
        assertNull(m.disconnect(a, 260));
    }

    @Test void unknownPlayersAreRejected() {
        PvpMatch m = match();
        assertThrows(IllegalArgumentException.class, () -> m.death(UUID.randomUUID(), 110));
        assertThrows(IllegalArgumentException.class, () -> m.disconnect(UUID.randomUUID(), 110));
        assertTrue(m.rounds().isEmpty());
    }
}
