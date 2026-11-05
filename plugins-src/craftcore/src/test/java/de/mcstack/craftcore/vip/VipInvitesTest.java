package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class VipInvitesTest {
  @Test void noticeTextAndClickableJoin() {
    VipStore.Invite i = new VipStore.Invite("Bauwelt", "Nani_San123", 5L, "00000000-0000-0000-0000-000000000001");
    assertEquals("Nani_San123 hat dich in die VIP-Welt \"Bauwelt\" eingeladen.", VipInvites.text(i));
    assertEquals("/vw join #00000000-0000-0000-0000-000000000001", VipInvites.joinCommand(i.id()));
    assertNotEquals(VipInvites.joinCommand(i.id()), VipInvites.joinCommand("00000000-0000-0000-0000-000000000002"));
  }

  @Test void eachInviteIsToldOnce() {
    assertEquals(9L, VipInvites.nextMark(3L, List.of(new VipStore.Invite("a", "o", 7L, "a"), new VipStore.Invite("b", "o", 9L, "b"))));
    assertEquals(3L, VipInvites.nextMark(3L, List.of()), "no invites: the mark stays");
  }
}
