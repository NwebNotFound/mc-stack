package de.mcstack.craftcore.pvp;

import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PvpStoreTest {
    @Test void assignmentCanBeLookedUpAndCancelled() throws Exception {
        String url="jdbc:h2:mem:pvp_store;DB_CLOSE_DELAY=-1";
        try (var c=DriverManager.getConnection(url); var s=c.createStatement()) {
            s.execute("CREATE TABLE pvp_assignments (match_id VARCHAR(36) PRIMARY KEY, player_a VARCHAR(36), player_b VARCHAR(36), kit_a VARCHAR(32), kit_b VARCHAR(32), status VARCHAR(16), created_at BIGINT, updated_at BIGINT)");
        }
        PvpStore store=new PvpStore(()->DriverManager.getConnection(url));
        UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        store.createAssignment("match",a,b,"one","two");
        var assignment=store.pendingFor(b).orElseThrow();
        assertEquals("match",assignment.matchId());
        assertEquals(a,assignment.a()); assertEquals(b,assignment.b());
        assertEquals("one",assignment.kitA()); assertEquals("two",assignment.kitB());
        assertEquals("pending",assignment.status());
        assertTrue(store.markStatus("match","pending","failed"));
        assertFalse(store.markStatus("match","pending","failed"));
        assertTrue(store.pendingFor(a).isEmpty());
    }
}
