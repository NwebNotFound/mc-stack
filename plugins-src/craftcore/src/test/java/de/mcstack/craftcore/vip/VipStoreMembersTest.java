package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class VipStoreMembersTest {
    private static final long EPOCH = 1793500000000L;

    private Connection database() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE vip_worlds (id VARCHAR(36) PRIMARY KEY, owner VARCHAR(36), name VARCHAR(24), type VARCHAR(16), status VARCHAR(16), slot VARCHAR(16), created_at BIGINT NOT NULL, last_started_at BIGINT)");
            s.execute("CREATE TABLE vip_world_members (world_id VARCHAR(36), player VARCHAR(36), role VARCHAR(16), added_at BIGINT, PRIMARY KEY (world_id, player))");
            s.execute("CREATE TABLE vip_world_addons (world_id VARCHAR(36), addon VARCHAR(32), added_at BIGINT, PRIMARY KEY (world_id, addon))");
            s.execute("CREATE TABLE vip_world_runs (world_id VARCHAR(36), slot VARCHAR(16), started_at BIGINT, stopped_at BIGINT, stop_reason VARCHAR(32))");
            s.execute("CREATE TABLE known_players (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(16))");
        }
        return c;
    }

    private static Clock advancing(AtomicLong millis) {
        return new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(millis()); }
            public long millis() { return millis.getAndAdd(1000); }
        };
    }

    @Test void membersAreStoredResolvedAndRemoved() throws Exception {
        try (Connection c = database()) {
            String url = c.getMetaData().getURL();
            VipStore store = new VipStore(() -> DriverManager.getConnection(url), advancing(new AtomicLong(EPOCH)));
            UUID owner = UUID.randomUUID();
            UUID friend = UUID.randomUUID();
            store.create(owner, "insel", "normal");
            String id = store.list(owner).get(0).id();
            try (PreparedStatement s = c.prepareStatement("INSERT INTO known_players (uuid,name) VALUES (?,?)")) {
                s.setString(1, friend.toString()); s.setString(2, "Steve"); s.executeUpdate();
            }
            assertTrue(store.members(id).isEmpty());
            store.addMember(id, friend, "member");
            List<VipStore.Member> members = store.members(id);
            assertEquals(1, members.size());
            assertEquals(friend, members.get(0).player());
            assertEquals("Steve", members.get(0).name());
            assertEquals("member", members.get(0).role());
            assertEquals(1, store.memberCount(id));
            assertTrue(store.setMemberRole(id, friend, "trusted"));
            assertEquals("trusted", store.members(id).get(0).role());
            assertTrue(store.removeMember(id, friend));
            assertFalse(store.removeMember(id, friend));
            assertEquals(0, store.memberCount(id));
        }
    }

    @Test void addonsReplaceTheWholeSelection() throws Exception {
        try (Connection c = database()) {
            String url = c.getMetaData().getURL();
            VipStore store = new VipStore(() -> DriverManager.getConnection(url), advancing(new AtomicLong(EPOCH)));
            UUID owner = UUID.randomUUID();
            store.create(owner, "insel", "normal");
            String id = store.list(owner).get(0).id();
            store.setAddons(id, List.of("keep-inventory", "pvp"));
            assertEquals(List.of("keep-inventory", "pvp"), store.addons(id));
            store.setAddons(id, List.of("peaceful"));
            assertEquals(List.of("peaceful"), store.addons(id));
            store.setAddons(id, List.of());
            assertTrue(store.addons(id).isEmpty());
        }
    }

    @Test void startedMarksRunningAndOpensOneRun() throws Exception {
        try (Connection c = database()) {
            String url = c.getMetaData().getURL();
            AtomicLong millis = new AtomicLong(EPOCH);
            VipStore store = new VipStore(() -> DriverManager.getConnection(url), advancing(millis));
            UUID owner = UUID.randomUUID();
            store.create(owner, "insel", "normal");
            String id = store.list(owner).get(0).id();
            store.status(id, "starting", null);
            store.started(id, "vipworld-1");
            VipStore.Entry entry = store.findById(id).orElseThrow();
            assertEquals("running", entry.status());
            assertEquals("vipworld-1", entry.slot());
            assertEquals(owner, entry.owner());
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT slot,started_at FROM vip_world_runs")) {
                assertTrue(r.next());
                assertEquals("vipworld-1", r.getString(1));
                assertFalse(r.next());
            }
        }
    }

    @Test void findsWorldByIdAndNameAndResolvesPlayers() throws Exception {
        try (Connection c = database()) {
            String url = c.getMetaData().getURL();
            VipStore store = new VipStore(() -> DriverManager.getConnection(url), advancing(new AtomicLong(EPOCH)));
            UUID owner = UUID.randomUUID();
            store.create(owner, "insel", "flat");
            String id = store.list(owner).get(0).id();
            VipStore.Entry byId = store.findById(id).orElseThrow();
            assertEquals("insel", byId.name());
            assertEquals("flat", byId.type());
            assertEquals(id, store.findByName("insel").orElseThrow().id());
            assertTrue(store.findById(UUID.randomUUID().toString()).isEmpty());
            assertTrue(store.findByName("fehlt").isEmpty());
            assertTrue(store.player("Steve").isEmpty());
            try (PreparedStatement s = c.prepareStatement("INSERT INTO known_players (uuid,name) VALUES (?,?)")) {
                s.setString(1, owner.toString()); s.setString(2, "Nils"); s.executeUpdate();
            }
            assertEquals(owner, store.player("Nils").orElseThrow());
        }
    }
}
