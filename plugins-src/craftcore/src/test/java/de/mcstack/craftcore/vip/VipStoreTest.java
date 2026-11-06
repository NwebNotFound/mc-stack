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
            s.execute("CREATE TABLE vip_worlds (id VARCHAR(36) PRIMARY KEY, owner VARCHAR(36), name VARCHAR(24), type VARCHAR(16), status ENUM('stopped','queued','starting','running','stopping','error','deleted'), slot VARCHAR(16), created_at BIGINT NOT NULL, last_started_at BIGINT)");
            s.execute("CREATE TABLE vip_world_runs (world_id VARCHAR(36), slot VARCHAR(16), started_at BIGINT, stopped_at BIGINT, stop_reason VARCHAR(32))");
            s.execute("CREATE TABLE vip_world_members (world_id VARCHAR(36), player VARCHAR(36), role ENUM('member','builder','trusted') NOT NULL, added_at BIGINT)");
            s.execute("CREATE TABLE vip_world_addons (world_id VARCHAR(36), addon VARCHAR(32), added_at BIGINT)");
            s.execute("CREATE TABLE known_players (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(16))");
        }
        return c;
    }

    @Test void inviteIdentitySurvivesDuplicateNamesAndRevokedMembershipIsRejected() throws Exception {
        try (Connection c=database()) {
            String url=c.getMetaData().getURL();
            VipStore store=new VipStore(() -> DriverManager.getConnection(url));
            UUID recipient=UUID.randomUUID(), owner=UUID.randomUUID();
            store.create(recipient,"Bauwelt","normal"); store.create(owner,"Bauwelt","normal");
            String invited=store.list(owner).get(0).id();
            try (PreparedStatement q=c.prepareStatement("INSERT INTO vip_world_members VALUES(?,?,'member',1000)")) {
                q.setString(1,invited); q.setString(2,recipient.toString()); q.executeUpdate();
            }
            VipStore.Invite notice=store.invitesSince(recipient,0).get(0);
            assertEquals(invited,notice.id());
            assertEquals(invited,store.findJoinableById(recipient,notice.id()).orElseThrow().id());
            assertNotEquals(store.list(recipient).get(0).id(),notice.id());
            try (PreparedStatement q=c.prepareStatement("DELETE FROM vip_world_members WHERE world_id=?")) {
                q.setString(1,invited); q.executeUpdate();
            }
            assertTrue(store.findJoinableById(recipient,notice.id()).isEmpty());
        }
    }

    @Test void sqlConstantsHaveExpectedParameters() throws Exception {
        Map<String, Integer> expected = Map.ofEntries(
                Map.entry("COUNT_WORLDS", 1), Map.entry("INSERT_WORLD", 7), Map.entry("LIST_WORLDS", 1),
                Map.entry("RENAME_WORLD", 3), Map.entry("SET_STATUS", 3), Map.entry("SET_RUNNING", 3),
                Map.entry("INSERT_RUN", 3), Map.entry("SET_STOPPED", 1), Map.entry("STOP_RUNS", 2),
                Map.entry("STOP_IDLE_RUNS", 2), Map.entry("FIND_WORLD", 1), Map.entry("LIST_MEMBERS", 1),
                Map.entry("COUNT_MEMBERS", 1), Map.entry("INSERT_MEMBER", 4), Map.entry("DELETE_MEMBER", 2),
                Map.entry("SET_MEMBER_ROLE", 3), Map.entry("LIST_ADDONS", 1), Map.entry("DELETE_ADDONS", 1),
                Map.entry("INSERT_ADDON", 3), Map.entry("SET_STARTED", 3), Map.entry("FIND_PLAYER", 1),
                Map.entry("FIND_OWNERS_BY_NAME", 1), Map.entry("FIND_BY_OWNER_NAME", 2), Map.entry("MARK_DELETED", 1), Map.entry("LIST_SHARED", 1), Map.entry("NEW_INVITES", 2));
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

    @Test void aDeletedWorldIsNeverResurrectedByALateStartOrStop() throws Exception {
        try (Connection c=database()) {
            String url=c.getMetaData().getURL();
            VipStore store=new VipStore(() -> DriverManager.getConnection(url),Clock.fixed(Instant.ofEpochMilli(EPOCH),ZoneOffset.UTC));
            UUID owner=UUID.randomUUID(); store.create(owner,"world","normal");
            String id=store.list(owner).get(0).id();
            assertTrue(store.markDeleted(id), "stopped world can be deleted");
            assertFalse(store.status(id,"starting",null), "a start that read the row before the delete cannot bring it back");
            store.idleStopped(id);
            try (Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT status FROM vip_worlds WHERE id='"+id+"'")) {
                assertTrue(r.next()); assertEquals("deleted", r.getString(1));
            }
            assertFalse(store.markDeleted(id), "second delete is a no-op");
        }
    }
    @Test void invitedPlayersSeeTheWorldAndGetEachInviteOnce() throws Exception {
        try (Connection c=database()) {
            String url=c.getMetaData().getURL();
            VipStore store=new VipStore(() -> DriverManager.getConnection(url),Clock.fixed(Instant.ofEpochMilli(EPOCH),ZoneOffset.UTC));
            UUID owner=UUID.randomUUID(), guest=UUID.randomUUID();
            try (Statement s=c.createStatement()) { s.execute("INSERT INTO known_players VALUES ('"+owner+"','Nani_San123'),('"+guest+"','NwebNotFound')"); }
            store.create(owner,"Bauwelt","normal");
            String id=store.list(owner).get(0).id();
            store.addMember(id, guest, "member");
            assertEquals(List.of("Bauwelt"), store.listShared(guest).stream().map(VipStore.World::name).toList(), "the invited world is listed for the guest");
            var invites=store.invitesSince(guest, EPOCH-1);
            assertEquals(1, invites.size());
            assertEquals("Nani_San123", invites.get(0).owner());
            assertTrue(store.invitesSince(guest, invites.get(0).addedAt()).isEmpty(), "told once");
            store.markDeleted(id);
            assertTrue(store.listShared(guest).isEmpty(), "a deleted world is not offered");
        }
    }

    @Test void createRespectsTheSlotLimitInsideTheInsert() throws Exception {
        // Issue #157: count and insert were two statements, so two concurrent creates both passed the check.
        try (Connection c=database()) {
            String url=c.getMetaData().getURL();
            VipStore store=new VipStore(() -> DriverManager.getConnection(url),Clock.fixed(Instant.ofEpochMilli(EPOCH),ZoneOffset.UTC));
            UUID owner=UUID.randomUUID(), other=UUID.randomUUID();
            assertTrue(store.create(owner,"one","normal",1));
            assertFalse(store.create(owner,"two","normal",1), "the second world does not fit into one slot");
            assertEquals(1, store.count(owner));
            assertTrue(store.create(other,"one","flat",1), "the limit counts per owner");
            assertTrue(store.markDeleted(store.list(owner).get(0).id()));
            assertTrue(store.create(owner,"two","void",1), "a deleted world frees its slot");
            assertEquals(List.of("two"), store.list(owner).stream().map(VipStore.World::name).toList());
            assertFalse(store.create(owner,"three","normal",0));
        }
    }

    @Test void aDeletedWorldGivesItsNameBack() throws Exception {
        // Issue #158: UNIQUE(owner,name) kept "Insel" taken by the deleted row.
        try (Connection c=database()) {
            try (Statement s=c.createStatement()) { s.execute("ALTER TABLE vip_worlds ADD CONSTRAINT owner_name UNIQUE (owner,name)"); }
            String url=c.getMetaData().getURL();
            VipStore store=new VipStore(() -> DriverManager.getConnection(url),Clock.fixed(Instant.ofEpochMilli(EPOCH),ZoneOffset.UTC));
            UUID owner=UUID.randomUUID();
            store.create(owner,"Insel","normal");
            String first=store.list(owner).get(0).id();
            assertTrue(store.markDeleted(first));
            assertTrue(store.create(owner,"Insel","flat",1), "the name is free again");
            store.create(owner,"Berg","normal");
            assertTrue(store.markDeleted(store.find(owner,"Insel").orElseThrow().id()));
            assertTrue(store.markDeleted(store.find(owner,"Berg").orElseThrow().id()), "several deleted rows never collide");
            try (Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT name FROM vip_worlds WHERE id='"+first+"'")) {
                assertTrue(r.next()); String name=r.getString(1);
                assertEquals(24, name.length()); assertFalse(VipNames.validate(name), "a placeholder never clashes with a real name");
            }
        }
    }
}
