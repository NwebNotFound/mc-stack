package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Every queue action, including the denied cases the contract names. */
class VipCommandRulesTest {
    private static final UUID ACTOR = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OWNER = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
    private static final VipStore.Entry WORLD =
        new VipStore.Entry("w-1", OWNER, "Kirschtal", "normal", "stopped", null);

    private static VipCommand cmd(String action) {
        JsonObject settings = new JsonObject();
        settings.addProperty("pvp", false);
        return new VipCommand("req-1", "100000000000000001", 1_793_500_000_000L, action, "w-1",
            "Neu", "flat", "private", "trusted", "Kirschtal",
            action.equals("settings") ? settings : null, action.equals("settings"));
    }

    private static VipCommandRules.Context ctx(boolean admin, boolean owner, boolean trusted, int owned, int slots,
                                               int members, boolean taken, boolean targetOwner, boolean member) {
        return new VipCommandRules.Context(admin, owner, trusted, true, admin ? "admin" : "vip",
            owned, slots, members, taken, targetOwner, member);
    }

    @Test void createNeedsAFreeSlot() {
        VipCommand c = cmd("create");
        assertEquals("", VipCommandRules.decide(c, ctx(false, false, false, 0, 1, 0, false, false, false), null));
        assertEquals("no_free_slot",
            VipCommandRules.decide(c, ctx(false, false, false, 1, 1, 0, false, false, false), null));
        assertEquals("name_taken",
            VipCommandRules.decide(c, ctx(false, false, false, 0, 1, 0, true, false, false), null));
        assertEquals("invalid_name", VipCommandRules.decide(
            new VipCommand("r", "1", 1_793_500_000_000L, "create", null, "ab", "normal", null, null, null, null, false),
            ctx(false, false, false, 0, 1, 0, false, false, false), null));
        assertEquals("invalid_type", VipCommandRules.decide(
            new VipCommand("r", "1", 1_793_500_000_000L, "create", null, "Kirschtal", "lava", null, null, null, null, false),
            ctx(false, false, false, 0, 1, 0, false, false, false), null));
        assertEquals("no_rank", VipCommandRules.decide(c, new VipCommandRules.Context(false, false, false, true,
            "builder", 0, 0, 0, false, false, false), null));
    }

    @Test void renameAndDeleteAreOwnerOrAdminOnly() {
        assertEquals("", VipCommandRules.decide(cmd("rename"), ctx(false, true, false, 0, 1, 0, false, false, false), WORLD));
        assertEquals("", VipCommandRules.decide(cmd("rename"), ctx(true, false, false, 0, 1, 0, false, false, false), WORLD));
        assertEquals("not_owner", VipCommandRules.decide(cmd("rename"), ctx(false, false, true, 0, 1, 0, false, false, false), WORLD));
        assertEquals("name_taken", VipCommandRules.decide(cmd("rename"), ctx(false, true, false, 0, 1, 0, true, false, false), WORLD));
    }

    @Test void deleteNeedsTheTypedName() {
        assertEquals("", VipCommandRules.decide(cmd("delete"), ctx(false, true, false, 0, 1, 0, false, false, false), WORLD));
        VipCommand without = new VipCommand("r", "1", 1_793_500_000_000L, "delete", "w-1", null, null, null, null, "", null, false);
        assertEquals("invalid_name", VipCommandRules.decide(without, ctx(false, true, false, 0, 1, 0, false, false, false), WORLD));
        VipCommand wrong = new VipCommand("r", "1", 1_793_500_000_000L, "delete", "w-1", null, null, null, null, "kirschtal", null, false);
        assertEquals("invalid_name", VipCommandRules.decide(wrong, ctx(false, true, false, 0, 1, 0, false, false, false), WORLD));
        VipCommand missing = new VipCommand("r", "1", 1_793_500_000_000L, "delete", "w-1", null, null, null, null, "Kirschtal", null, false);
        assertEquals("world_not_found", VipCommandRules.decide(missing, ctx(false, true, false, 0, 1, 0, false, false, false), null));
    }

    @Test void trustedMayStartOnlyWithTheOwnerRank() {
        assertEquals("", VipCommandRules.decide(cmd("start"), ctx(false, false, true, 0, 1, 0, false, false, true), WORLD));
        assertEquals("not_trusted", VipCommandRules.decide(cmd("start"), ctx(false, false, false, 0, 1, 0, false, false, true), WORLD));
        VipCommandRules.Context noRank = new VipCommandRules.Context(false, false, true, true, "builder", 0, 0, 0, false, false, true);
        assertEquals("no_rank", VipCommandRules.decide(cmd("start"), noRank, WORLD));
        VipCommandRules.Context noTrust = new VipCommandRules.Context(false, false, true, false, "vip", 0, 1, 0, false, false, true);
        assertEquals("not_trusted", VipCommandRules.decide(cmd("start"), noTrust, WORLD));
    }

    @Test void settingsVisibilityTrustAndMembers() {
        assertEquals("", VipCommandRules.decide(cmd("settings"), ctx(false, true, false, 0, 1, 0, false, false, false), WORLD));
        assertEquals("", VipCommandRules.decide(cmd("set_visibility"), ctx(false, true, false, 0, 1, 0, false, false, false), WORLD));
        assertEquals("", VipCommandRules.decide(cmd("trust"), ctx(false, true, false, 0, 1, 3, false, false, true), WORLD));
        assertEquals("member_not_found", VipCommandRules.decide(cmd("trust"), ctx(false, true, false, 0, 1, 3, false, false, false), WORLD));
        assertEquals("already_member", VipCommandRules.decide(cmd("invite"), ctx(false, true, false, 0, 1, 3, false, false, true), WORLD));
        assertEquals("member_limit", VipCommandRules.decide(cmd("invite"),
            ctx(false, true, false, 0, 1, VipCommandRules.MAX_MEMBERS, false, false, false), WORLD));
        assertEquals("is_owner", VipCommandRules.decide(cmd("uninvite"), ctx(false, true, false, 0, 1, 3, false, true, true), WORLD));
        VipCommand badRole = new VipCommand("r", "1", 1_793_500_000_000L, "trust", "w-1", "Gast", null, null, "owner", null, null, false);
        assertEquals("invalid_role", VipCommandRules.decide(badRole,
            new VipCommandRules.Context(false, true, false, true, "vip", 0, 1, 3, false, false, true), WORLD));
    }

    @Test void settingsAreStrictForBrowserInput() throws Exception {
        JsonObject partial = new JsonObject();
        partial.addProperty("difficulty", "hard");
        VipCommand c = new VipCommand("r", "1", 1_793_500_000_000L, "settings", "w-1", null, null, null, null, null, partial, true);
        VipWorldSettings merged = VipCommandRules.settings(c, VipWorldSettings.DEFAULTS);
        assertEquals("hard", merged.difficulty());
        assertEquals("survival", merged.gamemode());

        JsonObject bad = new JsonObject();
        bad.addProperty("weather", "rain");
        VipCommand unknownKey = new VipCommand("r", "1", 1_793_500_000_000L, "settings", "w-1", null, null, null, null, null, bad, true);
        assertThrows(VipWorldSettings.InvalidSettingException.class,
            () -> VipCommandRules.settings(unknownKey, VipWorldSettings.DEFAULTS));
    }

    @Test void unknownActionsAreRejectedButDoNotCrash() {
        VipCommand c = new VipCommand("r", "1", 1_793_500_000_000L, "explode", "w-1", null, null, null, null, null, null, false);
        assertFalse(c.known());
        assertEquals("unknown_action", VipCommandRules.decide(c, ctx(false, true, false, 0, 1, 0, false, false, false), WORLD));
    }

    @Test void slotsPerRank() {
        assertEquals(1, VipCommandRules.slots("vip", 3));
        assertEquals(3, VipCommandRules.slots("mvp", 3));
        assertEquals(3, VipCommandRules.slots("admin", 0));
        assertEquals(0, VipCommandRules.slots("builder", 3));
    }
}
