package de.mcstack.craftcore;

/** A capped satchel reward cannot be crafted, smithed, or smelted, or the sell cap would fall off. */
final class SatchelCraft {
  private SatchelCraft() {}

  static boolean usesCapped(boolean... inputsTagged) {
    for (boolean tagged : inputsTagged) if (tagged) return true;
    return false;
  }
}
