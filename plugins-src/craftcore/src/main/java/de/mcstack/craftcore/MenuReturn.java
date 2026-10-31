package de.mcstack.craftcore;

import java.util.List;

/** Transfers each item to inventory first and sends only the remainder to overflow. */
final class MenuReturn {
  interface Target<T> { List<T> add(T item); }
  interface Overflow<T> { void accept(T item); }
  static <T> void giveBack(Iterable<T> items, Target<T> target, Overflow<T> overflow) {
    for(T item:items) for(T remaining:target.add(item)) overflow.accept(remaining);
  }
}
