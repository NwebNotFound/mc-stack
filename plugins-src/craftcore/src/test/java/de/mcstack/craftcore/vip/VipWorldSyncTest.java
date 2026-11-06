package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The /vip panel shows only what this sync publishes (website mc-vip-cmd.js cleanWorlds). */
class VipWorldSyncTest {
    private static final String OWNER = "44444444-4444-4444-8444-444444444444";
    private static final String FRIEND = "22222222-2222-4222-8222-222222222222";
    private static final String W1 = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeee1";
    private static final String W2 = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeee2";
    private static final String GONE = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeee3";

    private String url;
    private final List<String[]> posts = new ArrayList<>();
    private int reply = 200;

    @BeforeEach void database() throws SQLException {
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Connection c = DriverManager.getConnection(url);
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE vip_worlds (id VARCHAR(36) PRIMARY KEY, owner VARCHAR(36), name VARCHAR(24), type VARCHAR(16), status VARCHAR(16), slot VARCHAR(16), created_at BIGINT NOT NULL, last_started_at BIGINT, visibility VARCHAR(16), deleted_at BIGINT, settings VARCHAR(2000))");
            s.execute("CREATE TABLE vip_world_members (world_id VARCHAR(36), player VARCHAR(36), role VARCHAR(16), added_at BIGINT)");
            s.execute("CREATE TABLE vip_world_addons (world_id VARCHAR(36), addon VARCHAR(32), added_at BIGINT)");
            s.execute("CREATE TABLE known_players (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(16))");
            s.execute("CREATE TABLE player_presence (uuid VARCHAR(36), server VARCHAR(64), last_seen TIMESTAMP, online BOOLEAN)");
            s.execute("INSERT INTO player_presence VALUES ('" + FRIEND + "','vip-1',NULL,TRUE),('" + OWNER + "','vip-1',NULL,TRUE),('x','vip-1',NULL,FALSE),('y','lobby',NULL,TRUE)");
            s.execute("INSERT INTO known_players VALUES ('" + OWNER + "','NwebNotFound'),('" + FRIEND + "','Ada')");
            s.execute("INSERT INTO vip_worlds VALUES ('" + W1 + "','" + OWNER + "','Testwelt','flat','running','vip-1',100,150,'public',NULL,'{\"gamemode\":\"creative\",\"difficulty\":\"easy\",\"pvp\":true,\"keepInventory\":true,\"mobGriefing\":false,\"doDaylightCycle\":false}')");
            s.execute("INSERT INTO vip_worlds VALUES ('" + W2 + "','" + OWNER + "','Zweite','normal','queued',NULL,200,NULL,'private',NULL,NULL)");
            s.execute("INSERT INTO vip_worlds VALUES ('" + GONE + "','" + OWNER + "','Alt','normal','deleted',NULL,50,NULL,'private',60,NULL)");
            s.execute("INSERT INTO vip_world_members VALUES ('" + W1 + "','" + FRIEND + "','trusted',120)");
            s.execute("INSERT INTO vip_world_addons VALUES ('" + W1 + "','pvp',130)");
        }
    }

    private int limitLookups;

    private void exec(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) { s.execute(sql); }
    }

    private VipWorldSync sync() {
        VipStore store = new VipStore(() -> DriverManager.getConnection(url));
        return new VipWorldSync(store, new VipWebQueue.Transport() {
            public String get(String path) { throw new UnsupportedOperationException(); }
            public int post(String path, String body) { posts.add(new String[] {path, body}); return reply; }
        }, owner -> { limitLookups++; return 10; });
    }

    @Test void publishesEveryLiveWorldInTheWebsiteContract() throws Exception {
        assertTrue(sync().maybeSend(1_000L));
        assertEquals(1, posts.size());
        assertEquals("/mc-vip-worlds", posts.get(0)[0]);
        JsonArray worlds = JsonParser.parseString(posts.get(0)[1]).getAsJsonObject().getAsJsonArray("vip_worlds");
        assertEquals(2, worlds.size(), "deleted worlds are not published");

        JsonObject a = worlds.get(0).getAsJsonObject();
        assertEquals(W1, a.get("id").getAsString());
        assertEquals(OWNER, a.get("owner_uuid").getAsString());
        assertEquals("NwebNotFound", a.get("owner_name").getAsString());
        assertEquals("Testwelt", a.get("name").getAsString());
        assertEquals("flat", a.get("type").getAsString());
        assertEquals("running", a.get("status").getAsString());
        assertEquals("vip-1", a.get("slot").getAsString());
        assertEquals("friends", a.get("visibility").getAsString(), "column value public is the contract's friends");
        assertEquals(150L, a.get("last_started_at").getAsLong());
        assertEquals(2, a.get("players").getAsInt(), "online presence rows on the world's slot only");
        assertEquals(10, a.get("player_limit").getAsInt());
        assertEquals(120L, a.getAsJsonArray("invites").get(0).getAsJsonObject().get("added_at").getAsLong());
        assertEquals(1_000L, JsonParser.parseString(posts.get(0)[1]).getAsJsonObject().get("synced_at").getAsLong());
        JsonObject invite = a.getAsJsonArray("invites").get(0).getAsJsonObject();
        assertEquals(FRIEND, invite.get("uuid").getAsString());
        assertEquals("Ada", invite.get("name").getAsString());
        assertEquals("trusted", invite.get("role").getAsString());
        assertEquals("pvp", a.getAsJsonArray("addons").get(0).getAsString());
        assertEquals("creative", a.getAsJsonObject("settings").get("gamemode").getAsString());
        assertFalse(a.getAsJsonObject("settings").get("doDaylightCycle").getAsBoolean());

        JsonObject b = worlds.get(1).getAsJsonObject();
        assertEquals("starting", b.get("status").getAsString(), "queued is not a website status");
        assertEquals("private", b.get("visibility").getAsString());
        assertFalse(b.has("slot"));
        assertFalse(b.has("last_started_at"));
        assertEquals(0, b.get("players").getAsInt(), "no slot, no players");
        assertEquals("survival", b.getAsJsonObject("settings").get("gamemode").getAsString(), "defaults when no settings stored");
    }

    @Test void postsOnEveryChangeAndOtherwiseOnlyOnResync() throws Exception {
        VipWorldSync sync = sync();
        assertTrue(sync.maybeSend(1_000L), "first round always publishes");
        assertFalse(sync.maybeSend(6_000L), "unchanged list stays quiet (idempotent)");
        exec("UPDATE vip_worlds SET name='Neu' WHERE id='" + W1 + "'");
        assertTrue(sync.maybeSend(11_000L), "rename");
        exec("UPDATE vip_worlds SET status='stopped', slot=NULL WHERE id='" + W1 + "'");
        assertTrue(sync.maybeSend(16_000L), "stop");
        exec("UPDATE vip_worlds SET settings='{\"gamemode\":\"adventure\"}' WHERE id='" + W2 + "'");
        assertTrue(sync.maybeSend(21_000L), "settings");
        exec("DELETE FROM vip_world_members WHERE world_id='" + W1 + "'");
        assertTrue(sync.maybeSend(26_000L), "members");
        exec("INSERT INTO vip_worlds VALUES ('aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeee4','" + FRIEND + "','Neue','void','stopped',NULL,300,NULL,'private',NULL,NULL)");
        assertTrue(sync.maybeSend(31_000L), "create");
        exec("UPDATE vip_worlds SET status='deleted' WHERE id='" + W2 + "'");
        assertTrue(sync.maybeSend(36_000L), "delete");
        JsonArray last = JsonParser.parseString(posts.get(posts.size() - 1)[1]).getAsJsonObject().getAsJsonArray("vip_worlds");
        assertEquals(2, last.size());
        assertEquals("Neu", last.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(0, last.get(0).getAsJsonObject().getAsJsonArray("invites").size());
        assertFalse(sync.maybeSend(36_000L + VipWorldSync.RESYNC_MILLIS - 1), "nothing changed");
        assertTrue(sync.maybeSend(36_000L + VipWorldSync.RESYNC_MILLIS), "periodic resync");
        assertEquals(8, posts.size(), "1 first + 6 changes + 1 resync");
        assertTrue(limitLookups <= 4, "rank lookups are cached per owner, not repeated every round: " + limitLookups);
    }

    @Test void inGameChangeRightAfterASyncIsPublishedNextRoundNotAfterAMinute() throws Exception {
        VipWorldSync sync = sync();
        VipStore store = new VipStore(() -> DriverManager.getConnection(url));
        assertTrue(sync.maybeSend(1_000L));
        store.visibility(W1, "private");                 // the /vw path in game writes through VipStore
        store.addMember(W2, UUID.fromString(FRIEND), "member");
        assertTrue(sync.maybeSend(6_000L), "next 5 s poll round, no minute wait");
        JsonArray worlds = JsonParser.parseString(posts.get(1)[1]).getAsJsonObject().getAsJsonArray("vip_worlds");
        assertEquals("private", worlds.get(0).getAsJsonObject().get("visibility").getAsString());
        assertEquals(1, worlds.get(1).getAsJsonObject().getAsJsonArray("invites").size());
    }

    @Test void publishesAllWorldsAboveThirtyWithoutTruncation() throws Exception {
        for (int i = 0; i < 29; i++) exec("INSERT INTO vip_worlds VALUES ('bbbbbbbb-bbbb-4ccc-8ddd-" + String.format("%012d", i) + "','" + OWNER + "','W" + i + "','normal','stopped',NULL," + (1000 + i) + ",NULL,'private',NULL,NULL)");
        assertTrue(sync().maybeSend(1_000L));
        JsonArray worlds = JsonParser.parseString(posts.get(0)[1]).getAsJsonObject().getAsJsonArray("vip_worlds");
        assertEquals(31, worlds.size(), "2 fixtures + 29: every live world, the newest included");
        assertEquals("W28", worlds.get(30).getAsJsonObject().get("name").getAsString());
    }

    @Test void aboveTheContractLimitNothingIsSentInsteadOfACutList() throws Exception {
        try (Connection c = DriverManager.getConnection(url); PreparedStatement p = c.prepareStatement("INSERT INTO vip_worlds VALUES (?,?,?,'normal','stopped',NULL,?,NULL,'private',NULL,NULL)")) {
            for (int i = 0; i < VipStore.SYNC_MAX_WORLDS - 2; i++) {
                p.setString(1, "cccccccc-bbbb-4ccc-8ddd-" + String.format("%012d", i)); p.setString(2, OWNER); p.setString(3, "X" + i); p.setLong(4, 5000 + i); p.addBatch();
            }
            p.executeBatch();
        }
        VipWorldSync sync = sync();
        assertTrue(sync.maybeSend(1_000L), "exactly the limit (2 + 498) is still sent");
        assertEquals(VipStore.SYNC_MAX_WORLDS, JsonParser.parseString(posts.get(0)[1]).getAsJsonObject().getAsJsonArray("vip_worlds").size());
        exec("INSERT INTO vip_worlds VALUES ('dddddddd-bbbb-4ccc-8ddd-000000000001','" + OWNER + "','Zuviel','normal','stopped',NULL,99999,NULL,'private',NULL,NULL)");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> sync.maybeSend(6_000L));
        assertTrue(e.getMessage().startsWith("501 live VIP worlds") && e.getMessage().contains("sync skipped"), e.getMessage());
        assertEquals(1, posts.size(), "no truncated replace is posted");
    }

    @Test void brokenStoredSettingsFallBackToDefaults() throws Exception {
        exec("UPDATE vip_worlds SET settings='not json' WHERE id='" + W1 + "'");
        assertTrue(sync().maybeSend(1_000L));
        JsonObject a = JsonParser.parseString(posts.get(0)[1]).getAsJsonObject().getAsJsonArray("vip_worlds").get(0).getAsJsonObject();
        assertEquals("survival", a.getAsJsonObject("settings").get("gamemode").getAsString());
    }

    @Test void rejectedSyncThrowsAndIsRetriedNextRound() throws Exception {
        VipWorldSync sync = sync();
        reply = 401;
        assertThrows(IllegalStateException.class, () -> sync.maybeSend(1_000L));
        reply = 200;
        assertTrue(sync.maybeSend(2_000L), "a failed send does not count as sent");
    }
}
