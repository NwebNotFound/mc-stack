package de.mcstack.craftcore;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TeamTeleportRulesTest {
  @Test void requestIsBoundToRecipientAndExpires(){
    UUID target=UUID.randomUUID();
    assertTrue(TeamTeleportRules.recipient(target,target,60000,59999));
    assertFalse(TeamTeleportRules.recipient(target,UUID.randomUUID(),60000,59999));
    assertFalse(TeamTeleportRules.recipient(target,target,60000,60000));
  }
  @Test void combatEndsExactlyTenSecondsAfterDamage(){
    assertFalse(TeamTeleportRules.inCombat(0,1000));
    assertTrue(TeamTeleportRules.inCombat(1000,10999));
    assertFalse(TeamTeleportRules.inCombat(1000,11000));
  }
  @Test void cooldownEndsAtExpiry(){assertTrue(TeamTeleportRules.active(300000,299999));assertFalse(TeamTeleportRules.active(300000,300000));}
}
