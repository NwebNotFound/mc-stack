package de.mcstack.craftcore;

/** Bank payout for a satchel reward never exceeds the CP paid for that pull. */
final class SatchelSell {
  private SatchelSell() {}

  static long payout(long marketCents, Long capCents) {
    if (marketCents < 0) throw new IllegalArgumentException("market");
    if (capCents == null) return marketCents;
    if (capCents < 0) throw new IllegalArgumentException("cap");
    return Math.min(marketCents, capCents);
  }

  static long stackPayout(long[] marketUnits, Long capCents) {
    long total = 0;
    for (long unit : marketUnits) total = Math.addExact(total, payout(unit, capCents));
    return total;
  }

  /** A tagged reward sells when the material has a market price, even with custom name or lore. */
  static boolean tradable(boolean tagged, boolean hasPrice, boolean vanillaEligible, boolean unsellable) {
    if (!hasPrice) return false;
    return tagged || (vanillaEligible && !unsellable);
  }
}
