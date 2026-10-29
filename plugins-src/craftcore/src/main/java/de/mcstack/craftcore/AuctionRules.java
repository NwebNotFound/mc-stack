package de.mcstack.craftcore;

/** Monetary and time rules for auctions; all prices are whole CP cents. */
final class AuctionRules {
  static final long HOUR = 3_600_000L;
  static final long EXTENSION = 120_000L;
  private AuctionRules() {}

  static boolean validDuration(long duration) {
    return duration >= HOUR && duration <= 72 * HOUR;
  }

  static long minimumNext(long current) {
    if (current < 0) throw new IllegalArgumentException("negative bid");
    long fivePercent = current / 20 + (current % 20 == 0 ? 0 : 1);
    return Math.addExact(current, Math.max(100, fivePercent));
  }

  static long extendedEnd(long now, long end) {
    if (now >= end) throw new IllegalArgumentException("auction ended");
    return end - now <= EXTENSION ? Math.addExact(end, EXTENSION) : end;
  }
}
