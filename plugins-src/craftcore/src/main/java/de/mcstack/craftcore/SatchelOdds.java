package de.mcstack.craftcore;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Server-side satchel rolls. Weights and pity never leave this class. */
final class SatchelOdds {
  enum Rarity {
    C, UC, R, SR, SSR, SSR_PLUS;

    boolean atLeast(Rarity floor) { return ordinal() >= floor.ordinal(); }

    static Rarity parse(String raw) {
      if (raw == null) return null;
      String s = raw.trim().toUpperCase(Locale.ROOT).replace(" ", "");
      return switch (s) {
        case "C" -> C;
        case "UC" -> UC;
        case "R" -> R;
        case "SR" -> SR;
        case "SSR" -> SSR;
        case "SSR+", "SSR_PLUS", "SSRPLUS" -> SSR_PLUS;
        default -> null;
      };
    }

    String label() { return this == SSR_PLUS ? "SSR+" : name(); }
  }

  record Entry(int id, Rarity rarity, int weight) {}
  record Pity(int sinceSr, int sinceSsr) {
    Pity { if (sinceSr < 0 || sinceSsr < 0) throw new IllegalArgumentException("pity"); }
  }
  record Outcome(Entry entry, Pity pity) {}
  record Share(Rarity rarity, long weight, long total) {
    int basisPoints() { return total <= 0 ? 0 : (int) (weight * 10_000 / total); }
    String percent() { int bp = basisPoints(); return (bp / 100) + "." + (bp / 10 % 10) + (bp % 10) + "%"; }
  }

  private SatchelOdds() {}

  static int defaultWeight(Rarity rarity) {
    return switch (rarity) {
      case C -> 500; case UC -> 250; case R -> 120; case SR -> 40; case SSR -> 8; case SSR_PLUS -> 2;
    };
  }

  static List<Share> shares(List<Entry> entries) {
    long total = 0;
    Map<Rarity, Long> by = new EnumMap<>(Rarity.class);
    for (Rarity rarity : Rarity.values()) by.put(rarity, 0L);
    for (Entry entry : entries) {
      if (entry.weight() <= 0) throw new IllegalArgumentException("weight");
      total += entry.weight();
      by.merge(entry.rarity(), (long) entry.weight(), Long::sum);
    }
    List<Share> shares = new ArrayList<>();
    for (Rarity rarity : Rarity.values()) shares.add(new Share(rarity, by.get(rarity), total));
    return List.copyOf(shares);
  }

  static List<Outcome> pull(List<Entry> pool, Pity start, int count, int srEvery, int ssrEvery, Random rng) {
    if (count < 1) throw new IllegalArgumentException("count");
    List<Outcome> rolls = new ArrayList<>(count);
    Pity pity = start;
    for (int i = 0; i < count; i++) {
      Outcome outcome = roll(pool, pity, srEvery, ssrEvery, rng);
      rolls.add(outcome);
      pity = outcome.pity();
    }
    return List.copyOf(rolls);
  }

  static Outcome roll(List<Entry> pool, Pity pity, int srEvery, int ssrEvery, Random rng) {
    if (pool.isEmpty()) throw new IllegalArgumentException("empty");
    if (srEvery < 1 || ssrEvery < 1) throw new IllegalArgumentException("pity");
    Rarity floor = null;
    if (pity.sinceSsr() + 1 >= ssrEvery) floor = Rarity.SSR;
    else if (pity.sinceSr() + 1 >= srEvery) floor = Rarity.SR;
    List<Entry> choices = floor == null ? pool : atLeast(pool, floor);
    if (choices.isEmpty()) choices = best(pool);
    Entry picked = weighted(choices, rng);
    Pity next = picked.rarity().atLeast(Rarity.SSR) ? new Pity(0, 0)
        : picked.rarity().atLeast(Rarity.SR) ? new Pity(0, pity.sinceSsr() + 1)
        : new Pity(pity.sinceSr() + 1, pity.sinceSsr() + 1);
    return new Outcome(picked, next);
  }

  private static List<Entry> atLeast(List<Entry> pool, Rarity floor) {
    List<Entry> choices = new ArrayList<>();
    for (Entry entry : pool) if (entry.rarity().atLeast(floor)) choices.add(entry);
    return choices;
  }

  private static List<Entry> best(List<Entry> pool) {
    Rarity best = Rarity.C;
    for (Entry entry : pool) if (entry.rarity().ordinal() > best.ordinal()) best = entry.rarity();
    return atLeast(pool, best);
  }

  private static Entry weighted(List<Entry> choices, Random rng) {
    long total = 0;
    for (Entry entry : choices) {
      if (entry.weight() <= 0) throw new IllegalArgumentException("weight");
      total += entry.weight();
    }
    long roll = rng.nextLong(total);
    for (Entry entry : choices) {
      roll -= entry.weight();
      if (roll < 0) return entry;
    }
    return choices.get(choices.size() - 1);
  }
}
