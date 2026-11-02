package de.mcstack.craftcore;

import java.util.HashMap;
import java.util.Map;

/** How many times a craft grid fires, including shift-click, and which ingredients that consumes. */
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
}
