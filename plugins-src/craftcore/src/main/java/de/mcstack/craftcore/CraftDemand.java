package de.mcstack.craftcore;

import java.util.HashMap;
import java.util.Map;

/** How many ingredients a craft, stonecutter take, or smelt consumes, including shift-click. */
final class CraftDemand {
  private CraftDemand() {}

  static int crafts(boolean shift, int[] matrixAmounts, int resultPerCraft, int resultMaxStack, int emptySlots, int partialSpace) {
    if (matrixAmounts == null) return 0;
    if (!shift) {
      for (int amount : matrixAmounts) if (amount > 0) return 1;
      return 0;
    }
    int limiting = Integer.MAX_VALUE;
    boolean any = false;
    for (int amount : matrixAmounts) {
      if (amount <= 0) continue;
      any = true;
      limiting = Math.min(limiting, amount);
    }
    if (!any) return 0;
    int space = Math.max(0, emptySlots) * Math.max(1, resultMaxStack) + Math.max(0, partialSpace);
    int per = Math.max(1, resultPerCraft);
    return Math.max(0, Math.min(limiting, space / per));
  }

  /** One item of each filled slot per craft. Repeated slots of the same type add up. */
  static Map<String, Long> consumed(String[] types, int crafts) {
    Map<String, Long> used = new HashMap<>();
    if (crafts <= 0 || types == null) return used;
    for (String type : types) {
      if (type == null || type.isBlank() || type.equals("AIR")) continue;
      used.merge(type, (long) crafts, Long::sum);
    }
    return used;
  }

  /**
   * Inputs removed by taking a stonecutter result. One input per batch, so two slabs still cost one stone.
   * Shift-click repeats until the input or the inventory space for the result runs out.
   * {@code destinationRoom} is how many result items a single click can accept (cursor, drop, or empty hotbar).
   */
  static int stonecutterTakes(boolean shift, int inputAmount, int resultPerTake, int resultMaxStack, int emptySlots, int partialSpace, int destinationRoom) {
    if (inputAmount <= 0 || resultPerTake <= 0) return 0;
    if (shift) {
      int space = Math.max(0, emptySlots) * Math.max(1, resultMaxStack) + Math.max(0, partialSpace);
      return Math.max(0, Math.min(inputAmount, space / resultPerTake));
    }
    return destinationRoom >= resultPerTake ? 1 : 0;
  }

  /** The input named by a stonecutter take. The result type is not the ingredient. */
  static Map<String, Long> stonecutterConsumed(String input, int takes) {
    Map<String, Long> used = new HashMap<>();
    if (takes <= 0 || input == null || input.isBlank() || input.equals("AIR")) return used;
    used.put(input, (long) takes);
    return used;
  }

  /** One furnace, blast-furnace, or smoker smelt. Fuel is not an input. */
  static Map<String, Long> smelted(String source) {
    Map<String, Long> used = new HashMap<>();
    if (source == null || source.isBlank() || source.equals("AIR")) return used;
    used.put(source, 1L);
    return used;
  }
}
