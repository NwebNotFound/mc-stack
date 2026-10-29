package de.mcstack.craftcore;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MineRushRulesTest {
  @Test void resetUsesBerlinTimeAcrossSummerAndWinter() {
    assertEquals(LocalDate.of(2026, 10, 27), MineRushRules.day(Instant.parse("2026-10-28T03:59:59Z")));
    assertEquals(LocalDate.of(2026, 10, 28), MineRushRules.day(Instant.parse("2026-10-28T04:00:00Z")));
    assertEquals(LocalDate.of(2026, 7, 1), MineRushRules.day(Instant.parse("2026-07-01T03:00:00Z")));
  }
  @Test void floorsAreDeterministicAndKeepBossesAndTwoPuzzles() {
    var instant = Instant.parse("2026-10-28T05:00:00Z");
    var floors = MineRushRules.floors(instant);
    assertEquals(floors, MineRushRules.floors(instant.plusSeconds(60)));
    assertEquals(10, floors.size());
    assertEquals(MineRushRules.Floor.BOSS, floors.get(4));
    assertEquals(MineRushRules.Floor.BOSS, floors.get(9));
    assertEquals(2, floors.stream().filter(x -> x == MineRushRules.Floor.PUZZLE).count());
  }
  @Test void rewardUsesInclusiveThresholdAndMinimum() {
    var tiers = List.of(new MineRushRules.RewardTier(60, 1000), new MineRushRules.RewardTier(120, 500));
    assertEquals(1000, MineRushRules.reward(60, tiers, 100));
    assertEquals(500, MineRushRules.reward(61, tiers, 100));
    assertEquals(100, MineRushRules.reward(121, tiers, 100));
    assertThrows(IllegalArgumentException.class, () -> MineRushRules.reward(1, List.of(new MineRushRules.RewardTier(60, 100), new MineRushRules.RewardTier(120, 200)), 0));
  }
}
