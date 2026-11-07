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
        assertEquals(0, store.pendingCount());
        store.holdForQueue(true);
        assertEquals(1, store.pendingCount());
        assertTrue(store.pendingFor(a).isEmpty());
        store.createAssignment("later", a, b, "one", "two");
        assertEquals(2, store.pendingCount());
        store.holdForQueue(false);
        assertEquals(1, store.pendingCount());
        assertTrue(store.markStatus("later", "pending", "failed"));
        assertEquals(0, store.pendingCount());
        assertFalse(store.consumeBuildRequest(a));
        store.requestBuild(a);
        assertTrue(store.consumeBuildRequest(a));
        assertFalse(store.consumeBuildRequest(a));
        assertTrue(store.pendingFor(a).isEmpty());
    }
    @Test void staleOpenRowsAndHoldsNoLongerKeepTheServerUp() throws Exception {
        // Issue #156: a crash mid-match or a restarted lobby left rows that blocked the idle stop forever.
        String url="jdbc:h2:mem:pvp_store_stale;DB_CLOSE_DELAY=-1";
        long now=System.currentTimeMillis();
        try (var c=DriverManager.getConnection(url); var s=c.createStatement()) {
            s.execute("CREATE TABLE pvp_assignments (match_id VARCHAR(36) PRIMARY KEY, player_a VARCHAR(36), player_b VARCHAR(36), kit_a VARCHAR(32), kit_b VARCHAR(32), status VARCHAR(16), created_at BIGINT, updated_at BIGINT)");
            String old=String.valueOf(now-PvpStore.OPEN_MAX_AGE_MS-1), oldHold=String.valueOf(now-PvpStore.HOLD_MAX_AGE_MS-1);
            UUID a=UUID.randomUUID(), b=UUID.randomUUID();
            s.execute("INSERT INTO pvp_assignments VALUES ('crashed','"+a+"','"+b+"','one','two','running',"+old+","+old+")");
            s.execute("INSERT INTO pvp_assignments VALUES ('warmup','"+a+"','"+b+"','-','-','warmup',"+oldHold+","+oldHold+")");
        }
        PvpStore store=new PvpStore(()->DriverManager.getConnection(url));
        assertEquals(0, store.pendingCount(now));
        store.holdForQueue(true);
        assertEquals(1, store.pendingCount(), "a refreshed hold counts again");
        UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        store.createAssignment("open", a, b, "one", "two");
        assertTrue(store.markStatus("open", "pending", "running"));
        assertEquals("open", store.pendingFor(a).orElseThrow().matchId());
        store.failOpen(java.util.List.of("open", "missing"));
        assertTrue(store.pendingFor(a).isEmpty(), "a server stop mid-match fails the assignment");
        assertEquals(1, store.pendingCount());
    }
}
