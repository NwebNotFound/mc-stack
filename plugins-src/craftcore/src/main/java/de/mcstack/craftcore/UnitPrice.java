package de.mcstack.craftcore;

/** Price calculations in cents for migrating a whole-stack shop price. */
final class UnitPrice {
  private UnitPrice() {}

  static long fromTotal(long total, int amount) {
    if (total < 0 || amount <= 0) throw new IllegalArgumentException("Invalid price or quantity");
    long quotient = total / amount;
    long rounded = quotient + (total % amount == 0 ? 0 : 1);
    return Math.max(1, rounded);
  }

  static long total(long unitPrice, int amount) {
    if (unitPrice < 1 || amount < 1) throw new IllegalArgumentException("Invalid price or quantity");
    return Math.multiplyExact(unitPrice, (long) amount);
  }
}
