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
            s.execute("INSERT INTO known_players VALUES ('" + OWNER + "','NwebNotFound'),('" + FRIEND + "','Ada')");
            s.execute("INSERT INTO vip_worlds VALUES ('" + W1 + "','" + OWNER + "','Testwelt','flat','running','vip-1',100,150,'public',NULL,'{\"gamemode\":\"creative\",\"difficulty\":\"easy\",\"pvp\":true,\"keepInventory\":true,\"mobGriefing\":false,\"doDaylightCycle\":false}')");
            s.execute("INSERT INTO vip_worlds VALUES ('" + W2 + "','" + OWNER + "','Zweite','normal','queued',NULL,200,NULL,'private',NULL,NULL)");
            s.execute("INSERT INTO vip_worlds VALUES ('" + GONE + "','" + OWNER + "','Alt','normal','deleted',NULL,50,NULL,'private',60,NULL)");
            s.execute("INSERT INTO vip_world_members VALUES ('" + W1 + "','" + FRIEND + "','trusted',120)");
            s.execute("INSERT INTO vip_world_addons VALUES ('" + W1 + "','pvp',130)");
        }
    }

    private VipWorldSync sync() {
        VipStore store = new VipStore(() -> DriverManager.getConnection(url));
        return new VipWorldSync(store, new VipWebQueue.Transport() {
            public String get(String path) { throw new UnsupportedOperationException(); }
            public int post(String path, String body) { posts.add(new String[] {path, body}); return reply; }
        });
    }

    @Test void publishesEveryLiveWorldInTheWebsiteContract() throws Exception {
        assertTrue(sync().maybeSend(1_000L, 0));
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
        assertEquals("survival", b.getAsJsonObject("settings").get("gamemode").getAsString(), "defaults when no settings stored");
    }

    @Test void sendsAfterAnsweredCommandsAndOtherwiseOncePerMinute() throws Exception {
        VipWorldSync sync = sync();
        assertTrue(sync.maybeSend(1_000L, 0), "first round always publishes");
        assertFalse(sync.maybeSend(2_000L, 0), "idle round within a minute stays quiet");
        assertTrue(sync.maybeSend(3_000L, 1), "a create/rename/delete is visible right after the round");
        assertFalse(sync.maybeSend(3_000L + VipWorldSync.EVERY_MILLIS - 1, 0));
        assertTrue(sync.maybeSend(3_000L + VipWorldSync.EVERY_MILLIS, 0));
        assertEquals(3, posts.size());
    }

    @Test void rejectedSyncThrowsAndIsRetriedNextRound() throws Exception {
        VipWorldSync sync = sync();
        reply = 401;
        assertThrows(IllegalStateException.class, () -> sync.maybeSend(1_000L, 0));
        reply = 200;
        assertTrue(sync.maybeSend(2_000L, 0), "a failed send does not count as sent");
    }
}
