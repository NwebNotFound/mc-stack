package de.mcstack.craftcore.pvp;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LobbyPvpTest {
    @Test void buildStartGuardRejectsConcurrentStartsAndAllowsRetry() {
        AtomicBoolean starting = new AtomicBoolean();
        assertTrue(LobbyPvp.tryStartBuild(starting));
        assertFalse(LobbyPvp.tryStartBuild(starting));
        starting.set(false);
        assertTrue(LobbyPvp.tryStartBuild(starting));
    }
    @Test void queueStatusShowsPositionOrJoinPrompt() {
        assertEquals(List.of("Platz 2 von 3"), LobbyPvp.queueStatus(2, 3));
        assertEquals(List.of("In der Queue: 0", "Waehle ein Kit oder klicke hier"), LobbyPvp.queueStatus(0, 0));
        assertEquals(List.of("In der Queue: 3", "Waehle ein Kit oder klicke hier"), LobbyPvp.queueStatus(0, 3));
    }
    @Test void queueReminderIncludesPosition() {
        assertEquals("Ein PvP-Match laeuft gerade - du bist als Naechstes dran (Platz 3).", LobbyPvp.queueReminder(3));
    }
    @Test void transferWaitsUntilContainerIsOnlineAndPingAnswers() {
        assertEquals(LobbyPvp.Transfer.WAIT, LobbyPvp.transferStep("online", false, 10, 180));
        assertEquals(LobbyPvp.Transfer.WAIT, LobbyPvp.transferStep("starting", true, 44, 180));
        assertEquals(LobbyPvp.Transfer.CONNECT, LobbyPvp.transferStep("online", true, 46, 180));
        assertEquals(LobbyPvp.Transfer.FAIL, LobbyPvp.transferStep("online", false, 180, 180));
        assertEquals(LobbyPvp.Transfer.FAIL, LobbyPvp.transferStep("offline", false, 181, 180));
    }
    @Test void playersStillOnLobbyFailTheTransfer() {
        assertTrue(LobbyPvp.stillOnLobby(true, true));
        assertTrue(LobbyPvp.stillOnLobby(true, false));
        assertTrue(LobbyPvp.stillOnLobby(false, true));
        assertFalse(LobbyPvp.stillOnLobby(false, false));
    }
}
