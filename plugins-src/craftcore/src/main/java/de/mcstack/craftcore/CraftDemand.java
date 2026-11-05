package de.mcstack.craftcore;

import java.util.HashMap;
import java.util.Map;

/**
 * Net demand of one craft, stonecutter take or smelt: every consumed ingredient counts {@code +1}, every produced
 * result counts {@code -1}. A reversible pair (9 ingots -> block -> 9 ingots) therefore nets to zero, so crafting
 * back and forth cannot pump the price of either side.
 */
final class CraftDemand {
  private CraftDemand() {}

  /** How many crafts one click performs. Shift-click repeats while the smallest filled slot and the inventory allow. */
  /**
   * {@code destinationRoom} is how many result items a non-shift click can actually take (cursor of the same item with
   * space, drop, empty hotbar slot); 0 for a click that does nothing, so it adds no demand.
   */
  static int crafts(boolean shift, int[] matrixAmounts, int resultPerCraft, int resultMaxStack, int emptySlots, int partialSpace, int destinationRoom) {
    if (matrixAmounts == null) return 0;
    int limiting = Integer.MAX_VALUE;
    boolean any = false;
    for (int amount : matrixAmounts) {
      if (amount <= 0) continue;
      any = true;
      limiting = Math.min(limiting, amount);
    }
    if (!any) return 0;
    if (!shift) return destinationRoom >= Math.max(1, resultPerCraft) ? 1 : 0;
    long space = (long) Math.max(0, emptySlots) * Math.max(1, resultMaxStack) + Math.max(0, partialSpace);
    return (int) Math.max(0, Math.min(limiting, space / Math.max(1, resultPerCraft)));
  }

  /** One of each filled slot per craft, minus the produced result. */
  static Map<String, Long> craftDelta(String[] types, int crafts, String result, int resultPerCraft) {
    Map<String, Long> delta = new HashMap<>();
    if (crafts <= 0 || types == null) return delta;
    for (String type : types) if (real(type)) delta.merge(type, (long) crafts, Long::sum);
    produce(delta, result, (long) crafts * Math.max(1, resultPerCraft));
    return delta;
  }

  /**
   * Inputs removed by taking a stonecutter result. One input per take, so two slabs still cost one stone.
   * {@code destinationRoom} is how many result items a single non-shift click can accept.
   */
  static int stonecutterTakes(boolean shift, int inputAmount, int resultPerTake, int resultMaxStack, int emptySlots, int partialSpace, int destinationRoom) {
    if (inputAmount <= 0 || resultPerTake <= 0) return 0;
    if (!shift) return destinationRoom >= resultPerTake ? 1 : 0;
    long space = (long) Math.max(0, emptySlots) * Math.max(1, resultMaxStack) + Math.max(0, partialSpace);
    return (int) Math.max(0, Math.min(inputAmount, space / resultPerTake));
  }

  static Map<String, Long> stonecutterDelta(String input, int takes, String result, int resultPerTake) {
    Map<String, Long> delta = new HashMap<>();
    if (takes <= 0 || !real(input)) return delta;
    delta.put(input, (long) takes);
    produce(delta, result, (long) takes * Math.max(1, resultPerTake));
    return delta;
  }

  /** One furnace, blast-furnace or smoker smelt. Fuel is not an input. */
  static Map<String, Long> smeltDelta(String source, String result, int resultAmount) {
    Map<String, Long> delta = new HashMap<>();
    if (!real(source)) return delta;
    delta.put(source, 1L);
    produce(delta, result, Math.max(1, resultAmount));
    return delta;
  }

  private static void produce(Map<String, Long> delta, String result, long amount) {
    if (!real(result)) return;
    delta.merge(result, -amount, Long::sum);
    if (delta.get(result) == 0L) delta.remove(result);
  }

  private static boolean real(String type) {
    return type != null && !type.isBlank() && !type.equals("AIR");
  }
}
