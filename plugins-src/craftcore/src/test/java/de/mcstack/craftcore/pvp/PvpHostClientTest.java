package de.mcstack.craftcore.pvp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PvpHostClientTest {
    @Test void parsesStatus() {
        assertEquals("online", PvpHostClient.parseStatus("{\"status\":\"online\"}"));
        assertEquals("sleeping", PvpHostClient.parseStatus("{\"status\":\"sleeping\"}"));
        assertEquals("offline", PvpHostClient.parseStatus("garbage"));
    }
}
