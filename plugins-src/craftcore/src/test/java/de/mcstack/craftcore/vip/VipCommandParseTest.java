package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** actor_uuid is the website's verified mc_links UUID (docs/vip-api.md, "Discord link"). */
class VipCommandParseTest {
    private static VipCommand.Parsed parse(String extra) {
        return VipCommand.parse(JsonParser.parseString("{\"request_id\":\"r1\",\"discord_id\":\"100000000000000009\",\"created_at\":1793500000000,\"action\":\"create\",\"name\":\"AdminTest\"" + extra + "}"));
    }

    @Test void readsActorUuidFromTheWebsiteItem() {
        VipCommand.Parsed p = parse(",\"actor_uuid\":\"1B776687-56B6-48A5-AD64-24D8400F1C1E\"");
        assertTrue(p.ok(), p.error());
        assertEquals("1b776687-56b6-48a5-ad64-24d8400f1c1e", p.command().actorUuid());
    }

    @Test void acceptsTheOlderMcUuidName() {
        assertEquals("1b776687-56b6-48a5-ad64-24d8400f1c1e", parse(",\"mc_uuid\":\"1b776687-56b6-48a5-ad64-24d8400f1c1e\"").command().actorUuid());
    }

    @Test void missingActorUuidStaysNull() {
        assertNull(parse("").command().actorUuid());
    }

    @Test void malformedActorUuidIsDeniedNotSkipped() {
        VipCommand.Parsed p = parse(",\"actor_uuid\":\"not-a-uuid\"");
        assertFalse(p.ok());
        assertTrue(p.deny(), "ids are valid, so it is acked denied");
        assertEquals("invalid_player", p.error());
        assertEquals("r1", p.command().requestId());
    }

    @Test void readsTheDispatchTimeLinkFlag() {
        assertEquals(Boolean.TRUE, parse(",\"linked\":true").command().linked());
        assertEquals(Boolean.FALSE, parse(",\"linked\":false").command().linked());
        assertNull(parse("").command().linked());
        assertNull(parse(",\"linked\":\"yes\"").command().linked(), "only a JSON boolean counts");
    }
}
