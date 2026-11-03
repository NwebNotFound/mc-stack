package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class VipMemberRulesTest {
    @Test void onlyOwnerOrAdminInvites() {
        assertTrue(VipMemberRules.invite(true, false, false, 0).allowed());
        assertTrue(VipMemberRules.invite(false, true, false, 0).allowed());
        assertEquals("not_owner", VipMemberRules.invite(false, false, false, 0).reason());
        assertEquals("already_member", VipMemberRules.invite(true, false, true, 0).reason());
        assertEquals("member_limit", VipMemberRules.invite(true, false, false, VipMemberRules.MAX_MEMBERS).reason());
    }

    @Test void ownerCannotBeKicked() {
        assertTrue(VipMemberRules.kick(true, false, false, false).allowed());
        assertEquals("not_owner", VipMemberRules.kick(false, false, false, false).reason());
        assertEquals("is_owner", VipMemberRules.kick(true, false, true, false).reason());
        assertEquals("is_owner", VipMemberRules.kick(true, false, false, true).reason());
        assertTrue(VipMemberRules.kick(false, true, false, false).allowed());
    }

    @Test void trustedMayStartOnlyWhenAllowed() {
        assertTrue(VipMemberRules.mayStart(true, false, false, true).allowed());
        assertTrue(VipMemberRules.mayStart(false, true, false, false).allowed());
        assertTrue(VipMemberRules.mayStart(false, false, true, true).allowed());
        assertEquals("not_trusted", VipMemberRules.mayStart(false, false, true, false).reason());
        assertEquals("not_owner", VipMemberRules.mayStart(false, false, false, true).reason());
    }

    @Test void manageRequiresOwnerOrAdmin() {
        assertTrue(VipMemberRules.mayManage(true, false).allowed());
        assertTrue(VipMemberRules.mayManage(false, true).allowed());
        assertFalse(VipMemberRules.mayManage(false, false).allowed());
    }

    @Test void rolesAndNames() {
        assertEquals(VipMemberRules.Role.TRUSTED, VipMemberRules.role("trusted"));
        assertEquals(VipMemberRules.Role.MEMBER, VipMemberRules.role("member"));
        assertEquals(VipMemberRules.Role.MEMBER, VipMemberRules.role(null));
        assertEquals("trusted", VipMemberRules.roleName(VipMemberRules.Role.TRUSTED));
        assertEquals("member", VipMemberRules.roleName(VipMemberRules.Role.MEMBER));
        assertEquals("Steve", VipMemberRules.cleanName("  \u00a7cSteve\u00a7r  "));
        assertTrue(VipMemberRules.validName("Steve_1"));
        assertFalse(VipMemberRules.validName("ab"));
        assertFalse(VipMemberRules.validName("bad name"));
    }
}
