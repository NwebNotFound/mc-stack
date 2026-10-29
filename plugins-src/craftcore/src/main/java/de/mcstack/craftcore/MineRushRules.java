package de.mcstack.craftcore;

import java.time.*;
import java.util.*;

/** Pure rules for MineRush; no server state or persistent data is changed here. */
final class MineRushRules {
  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
  enum Floor { FIGHT, PUZZLE, BOSS }
  record RewardTier(long maxSeconds, long cents) {}

  static LocalDate day(Instant instant) {
    ZonedDateTime local = instant.atZone(BERLIN);
    return local.toLocalTime().isBefore(LocalTime.of(5, 0))
        ? local.toLocalDate().minusDays(1) : local.toLocalDate();
  }

  static List<Floor> floors(Instant instant) {
    List<Floor> floors = new ArrayList<>(Collections.nCopies(10, Floor.FIGHT));
    floors.set(4, Floor.BOSS);
    floors.set(9, Floor.BOSS);
    List<Integer> choices = new ArrayList<>(List.of(0, 1, 2, 3, 5, 6, 7, 8));
    Collections.shuffle(choices, new Random(day(instant).toEpochDay()));
    floors.set(choices.get(0), Floor.PUZZLE);
    floors.set(choices.get(1), Floor.PUZZLE);
    return List.copyOf(floors);
  }

  static long reward(long elapsedSeconds, List<RewardTier> tiers, long minimumCents) {
    if (elapsedSeconds < 0 || minimumCents < 0 || tiers.isEmpty()) throw new IllegalArgumentException();
    long previousTime = -1, previousReward = Long.MAX_VALUE;
    for (RewardTier tier : tiers) {
      if (tier.maxSeconds() <= previousTime || tier.cents() < minimumCents || tier.cents() > previousReward)
        throw new IllegalArgumentException("Invalid reward tiers");
      previousTime = tier.maxSeconds();
      previousReward = tier.cents();
    }
    for (RewardTier tier : tiers) if (elapsedSeconds <= tier.maxSeconds()) return tier.cents();
    return minimumCents;
  }
  private MineRushRules() {}
}
