package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * End-to-end over the real store (H2) with fake host and ranks: who may start, and what delete does
 * to a running world. These are the cases a hand-built Context cannot prove.
 */
class VipWebCommandsTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TRUSTED = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MEMBER = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID ADMIN = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID NORANK = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final String WORLD = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    private static final long NOW = 1_793_500_600_000L;

    private Connection db;
    private String url;
    private final Map<UUID, String> ranks = new HashMap<>();
    private final List<String> hostStarts = new ArrayList<>();
    private final List<String> hostStops = new ArrayList<>();
    private VipHostClient.Outcome stopOutcome = VipHostClient.Outcome.STOPPED;

    private void database() throws SQLException {
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        db = DriverManager.getConnection(url);
        try (Statement s = db.createStatement()) {
            s.execute("CREATE TABLE vip_worlds (id VARCHAR(36) PRIMARY KEY, owner VARCHAR(36), name VARCHAR(24), type VARCHAR(16), status VARCHAR(16), slot VARCHAR(16), created_at BIGINT NOT NULL, last_started_at BIGINT, visibility VARCHAR(16), deleted_at BIGINT, settings VARCHAR(2000))");
            s.execute("CREATE TABLE vip_world_runs (world_id VARCHAR(36), slot VARCHAR(16), started_at BIGINT, stopped_at BIGINT, stop_reason VARCHAR(32))");
            s.execute("CREATE TABLE vip_world_members (world_id VARCHAR(36), player VARCHAR(36), role VARCHAR(16), added_at BIGINT)");
            s.execute("CREATE TABLE vip_world_addons (world_id VARCHAR(36), addon VARCHAR(32), added_at BIGINT)");
            s.execute("CREATE TABLE vip_world_audit (id BIGINT AUTO_INCREMENT PRIMARY KEY, world_id VARCHAR(36), action VARCHAR(24), admin_uuid VARCHAR(36), admin_name VARCHAR(16), owner_uuid VARCHAR(36), owner_name VARCHAR(16), detail VARCHAR(64), created_at BIGINT)");
            s.execute("CREATE TABLE known_players (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(16))");
            s.execute("CREATE TABLE discord_links (uuid VARCHAR(36) PRIMARY KEY, discord_id VARCHAR(20))");
            link(OWNER, "100000000000000001", "Ada");
            link(TRUSTED, "100000000000000002", "Steve");
            link(MEMBER, "100000000000000003", "Alex");
            link(ADMIN, "100000000000000004", "AdminNils");
            link(NORANK, "100000000000000005", "OhneRang");
        }
        ranks.clear();
        ranks.put(OWNER, "vip");
        ranks.put(MEMBER, "vip");
        ranks.put(ADMIN, "admin");
        hostStarts.clear();
        hostStops.clear();
        stopOutcome = VipHostClient.Outcome.STOPPED;
    }

    private void link(UUID uuid, String discordId, String name) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("INSERT INTO discord_links(uuid,discord_id) VALUES (?,?)")) {
            s.setString(1, uuid.toString());
            s.setString(2, discordId);
            s.executeUpdate();
        }
        try (PreparedStatement s = db.prepareStatement("INSERT INTO known_players(uuid,name) VALUES (?,?)")) {
            s.setString(1, uuid.toString());
            s.setString(2, name);
            s.executeUpdate();
        }
    }

    private void world(UUID owner, String status, String slot) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("INSERT INTO vip_worlds(id,owner,name,type,status,slot,created_at,visibility) VALUES (?,?,?,?,?,?,?,'private')")) {
            s.setString(1, WORLD);
            s.setString(2, owner.toString());
            s.setString(3, "Kirschtal");
            s.setString(4, "normal");
            s.setString(5, status);
            s.setString(6, slot);
            s.setLong(7, NOW - 1000);
            s.executeUpdate();
        }
    }

    private void member(UUID player, String role) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("INSERT INTO vip_world_members(world_id,player,role,added_at) VALUES (?,?,?,?)")) {
            s.setString(1, WORLD);
            s.setString(2, player.toString());
            s.setString(3, role);
            s.setLong(4, NOW - 1000);
            s.executeUpdate();
        }
    }

    private VipWebCommands commands() throws SQLException {
        // Jede Abfrage oeffnet ihre eigene Verbindung: die Store-Methoden schliessen sie wieder.
        VipStore store = new VipStore(() -> DriverManager.getConnection(url));
        return new VipWebCommands(store,
            new VipWebCommands.Host() {
                public VipHostClient.Result start(String id) { hostStarts.add(id); return new VipHostClient.Result(VipHostClient.Outcome.ONLINE, "vipworld-1"); }
                public VipHostClient.Result stop(String slot) { hostStops.add(slot); return new VipHostClient.Result(stopOutcome, null); }
            },
            ranks::get, () -> DriverManager.getConnection(url), 3, true);
    }

    private static VipCommand command(String action, String discordId, String confirm) {
        return new VipCommand("req-" + action, discordId, NOW - 1000, action, WORLD, null, null, null, null,
            confirm, null, false);
    }

    @Test void trustedMemberWithoutRankStartsTheWorld() throws Exception {
        database();
        world(OWNER, "stopped", null);
        member(TRUSTED, "trusted");
        VipWebQueue.Outcome out = commands().apply(command("start", "100000000000000002", null));
        assertEquals("done", out.status(), out.reason());
        assertEquals(List.of(WORLD), hostStarts);
        assertEquals("running", status());
    }

    @Test void plainMemberCannotStart() throws Exception {
        database();
        world(OWNER, "stopped", null);
        member(MEMBER, "member");
        VipWebQueue.Outcome out = commands().apply(command("start", "100000000000000003", null));
        assertEquals("denied", out.status());
        assertEquals("not_trusted", out.reason());
        assertTrue(hostStarts.isEmpty());
    }

    @Test void ownerWithoutRankCannotStart() throws Exception {
        database();
        world(NORANK, "stopped", null);
        VipWebQueue.Outcome out = commands().apply(command("start", "100000000000000005", null));
        assertEquals("denied", out.status());
        assertEquals("no_rank", out.reason());
        assertTrue(hostStarts.isEmpty());
    }

    @Test void deletingARunningWorldStopsTheServerFirst() throws Exception {
        database();
        world(OWNER, "running", "vipworld-2");
        VipWebQueue.Outcome out = commands().apply(command("delete", "100000000000000001", "Kirschtal"));
        assertEquals("done", out.status(), out.reason());
        assertEquals(List.of("vipworld-2"), hostStops);
        assertEquals("deleted", status());
    }

    @Test void deleteKeepsTheWorldWhenTheStopFails() throws Exception {
        database();
        world(OWNER, "running", "vipworld-2");
        stopOutcome = VipHostClient.Outcome.ERROR;
        VipWebQueue.Outcome out = commands().apply(command("delete", "100000000000000001", "Kirschtal"));
        assertEquals("failed", out.status());
        assertEquals("host_unavailable", out.reason());
        assertEquals("running", status());
    }

    @Test void adminDeleteWithoutAuditTableChangesNothing() throws Exception {
        database();
        world(OWNER, "stopped", null);
        try (Statement s = db.createStatement()) { s.execute("DROP TABLE vip_world_audit"); }
        VipWebQueue.Outcome out = commands().apply(command("delete", "100000000000000004", "Kirschtal"));
        assertEquals("failed", out.status());
        assertEquals("audit_failed", out.reason());
        assertEquals("stopped", status(), "die Welt bleibt ohne Audit-Eintrag bestehen");
    }

    @Test void ownerDeleteNeedsNoAuditRow() throws Exception {
        database();
        world(OWNER, "stopped", null);
        try (Statement s = db.createStatement()) { s.execute("DROP TABLE vip_world_audit"); }
        VipWebQueue.Outcome out = commands().apply(command("delete", "100000000000000001", "Kirschtal"));
        assertEquals("done", out.status(), out.reason());
        assertEquals("deleted", status());
    }

    private String status() throws SQLException {
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT status FROM vip_worlds WHERE id='" + WORLD + "'")) {
            return r.next() ? r.getString(1) : null;
        }
    }
}
