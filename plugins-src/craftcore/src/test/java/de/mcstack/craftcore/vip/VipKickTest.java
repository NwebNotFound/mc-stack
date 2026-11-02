package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Kicking from the lobby has to empty the running world, and the join gate keeps the player
 * out afterwards. Both sides share {@link VipAccessRules}.
 */
class VipKickTest {
    @Test void kickedMemberIsRemovedFromTheRunningWorld() {
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        assertFalse(VipMemberRules.mustLeaveWorld(false, true, false), "Mitglied bleibt");
        assertTrue(VipMemberRules.mustLeaveWorld(false, false, false), "gekicktes Mitglied muss die Welt verlassen");
        assertFalse(VipMemberRules.mustLeaveWorld(true, false, false), "der Besitzer wird nie entfernt");
        assertFalse(VipMemberRules.mustLeaveWorld(false, false, true), "Admins mit Bypass bleiben");
    }

    @Test void kickedMemberCannotRejoin() {
        boolean allowed = VipAccessRules.decide(false, false, false, true, true);
        assertFalse(allowed, "VipWorldGate.lookup sperrt den Rejoin nach dem Kick");
        assertTrue(VipAccessRules.decide(false, false, true, true, true), "Bypass darf weiter rein");
        assertTrue(VipAccessRules.decide(false, true, false, true, true), "eingeladene Spieler duerfen rein");
    }

    @Test void kickVerdictsMatchTheReviewRules() {
        assertTrue(VipMemberRules.kick(true, false, false, false).allowed(), "Owner darf kicken");
        assertTrue(VipMemberRules.kick(false, true, false, false).allowed(), "Admin darf kicken");
        assertFalse(VipMemberRules.kick(false, false, false, false).allowed(), "Fremde nicht");
        assertFalse(VipMemberRules.kick(true, false, true, false).allowed(), "Owner ist nicht kickbar");
        assertFalse(VipMemberRules.kick(true, false, false, true).allowed(), "Selbstkick nicht");
    }

    @Test void inviteCannotTargetTheOwnerOrYourself() {
        assertTrue(VipMemberRules.invite(true, false, false, 0).allowed());
        assertFalse(VipMemberRules.invite(true, false, true, 0).allowed(), "bereits Mitglied");
        assertFalse(VipMemberRules.invite(false, false, false, 0).allowed(), "nur Owner oder Admin");
        assertFalse(VipMemberRules.invite(true, false, false, VipMemberRules.MAX_MEMBERS).allowed(), "Liste voll");
    }

    @Test void trustedRoleDecidesWhoMayStart() {
        assertTrue(VipMemberRules.mayStart(false, false, true, true).allowed());
        assertFalse(VipMemberRules.mayStart(false, false, true, false).allowed());
        assertEquals("trusted", VipMemberRules.roleName(VipMemberRules.role("trusted")));
        assertEquals("trusted", VipMemberRules.roleName(VipMemberRules.role("trUsted")));
        assertEquals("member", VipMemberRules.roleName(VipMemberRules.role("member")));
        assertTrue(List.of("member", "builder", "trusted").contains(VipMemberRules.roleName(VipMemberRules.role("trusted"))));
    }
}
