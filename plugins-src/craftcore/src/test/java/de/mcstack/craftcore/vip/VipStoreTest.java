package de.mcstack.craftcore.vip;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Modifier;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VipStoreTest {
    private static final long EPOCH = 1793500000000L;

    private Connection database() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE vip_worlds (id VARCHAR(36) PRIMARY KEY, owner VARCHAR(36), name VARCHAR(24), type VARCHAR(16), status VARCHAR(16), slot VARCHAR(16), created_at BIGINT NOT NULL, last_started_at BIGINT)");
            s.execute("CREATE TABLE vip_world_runs (world_id VARCHAR(36), slot VARCHAR(16), started_at BIGINT, stopped_at BIGINT, stop_reason VARCHAR(32))");
            s.execute("CREATE TABLE vip_world_members (world_id VARCHAR(36), player VARCHAR(36), role VARCHAR(16), added_at BIGINT)");
            s.execute("CREATE TABLE vip_world_addons (world_id VARCHAR(36), addon VARCHAR(32), added_at BIGINT)");
            s.execute("CREATE TABLE known_players (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(16))");
        }
        return c;
    }

    @Test void sqlConstantsHaveExpectedParameters() throws Exception {
        Map<String, Integer> expected = Map.ofEntries(
                Map.entry("COUNT_WORLDS", 1), Map.entry("INSERT_WORLD", 5), Map.entry("LIST_WORLDS", 1),
                Map.entry("RENAME_WORLD", 3), Map.entry("SET_STATUS", 3), Map.entry("SET_RUNNING", 3),
                Map.entry("INSERT_RUN", 3), Map.entry("SET_STOPPED", 1), Map.entry("STOP_RUNS", 2),
                Map.entry("STOP_IDLE_RUNS", 2), Map.entry("FIND_WORLD", 1), Map.entry("LIST_MEMBERS", 1),
                Map.entry("COUNT_MEMBERS", 1), Map.entry("INSERT_MEMBER", 4), Map.entry("DELETE_MEMBER", 2),
                Map.entry("SET_MEMBER_ROLE", 3), Map.entry("LIST_ADDONS", 1), Map.entry("DELETE_ADDONS", 1),
                Map.entry("INSERT_ADDON", 3), Map.entry("SET_STARTED", 3), Map.entry("FIND_PLAYER", 1),
                Map.entry("FIND_BY_NAME", 1));
        assertTrue(VipStore.INSERT_WORLD.toLowerCase(Locale.ROOT).contains("created_at"));
        try (Connection c = database()) {
            int found = 0;
            for (var field : VipStore.class.getDeclaredFields()) {
                if (field.getType() != String.class || !Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers())) continue;
                found++;
                String sql = (String) field.get(null);
                assertFalse(sql.toUpperCase(Locale.ROOT).matches("(?s).*\\b(?:CURRENT_TIMESTAMP|NOW\\s*\\().*"), field.getName());
                assertTrue(expected.containsKey(field.getName()), field.getName());
                try (PreparedStatement statement = c.prepareStatement(sql)) {
                    assertEquals(expected.get(field.getName()), statement.getParameterMetaData().getParameterCount(), field.getName());
                }
            }
            assertEquals(expected.size(), found);
        }
    }

    @Test void lifecycleUsesOneClockReadingPerTimedOperation() throws Exception {
        try (Connection c = database()) {
            class CountingClock extends Clock {
                int reads;
                public ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(ZoneId zone) { return this; }
                public Instant instant() { return Instant.ofEpochMilli(millis()); }
                public long millis() { reads++; return EPOCH; }
            }
            CountingClock clock = new CountingClock();
            String url = c.getMetaData().getURL();
            VipStore store = new VipStore(() -> DriverManager.getConnection(url), clock);
            UUID owner = UUID.randomUUID();
            store.create(owner, "world", "normal");
            assertEquals(1, clock.reads);
            assertEquals(1, store.count(owner));
            assertEquals("normal", store.list(owner).get(0).type());
            String id = store.list(owner).get(0).id();
            store.rename(owner, id, "renamed");
            store.status(id, "starting", "vipworld-1");
            store.running(id, "vipworld-1");
            assertEquals(2, clock.reads);
            store.stopped(id);
            assertEquals(3, clock.reads);
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT w.created_at,w.last_started_at,r.started_at,r.stopped_at FROM vip_worlds w JOIN vip_world_runs r ON w.id=r.world_id")) {
                assertTrue(r.next());
                for (int i = 1; i <= 4; i++) assertEquals(EPOCH, r.getLong(i));
                assertFalse(r.next());
            }
        }
    }
    @Test void idleStopFreesSlotAndClosesOpenRunsAtClockTime() throws Exception {
        try (Connection c=database()) {
            String url=c.getMetaData().getURL();
            VipStore store=new VipStore(() -> DriverManager.getConnection(url),Clock.fixed(Instant.ofEpochMilli(EPOCH),ZoneOffset.UTC));
            UUID owner=UUID.randomUUID(); store.create(owner,"world","normal");
            String id=store.list(owner).get(0).id(); store.status(id,"starting","vipworld-1"); store.running(id,"vipworld-1");
            store.idleStopped(id);
            var world=store.find(owner,"world").orElseThrow(); assertEquals("stopped",world.status()); assertNull(world.slot());
            try (Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT stopped_at,stop_reason FROM vip_world_runs")) {
                assertTrue(r.next()); assertEquals(EPOCH,r.getLong(1)); assertEquals("idle",r.getString(2));
            }
        }
    }

}
