package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class VipInvitesTest {
  @Test void noticeTextAndClickableJoin() {
    VipStore.Invite i = new VipStore.Invite("Bauwelt", "Nani_San123", 5L);
    assertEquals("Nani_San123 hat dich in die VIP-Welt \"Bauwelt\" eingeladen.", VipInvites.text(i));
    assertEquals("/vw join Bauwelt", VipInvites.joinCommand("Bauwelt"));
  }

  @Test void eachInviteIsToldOnce() {
    assertEquals(9L, VipInvites.nextMark(3L, List.of(new VipStore.Invite("a", "o", 7L), new VipStore.Invite("b", "o", 9L))));
    assertEquals(3L, VipInvites.nextMark(3L, List.of()), "no invites: the mark stays");
  }
}
