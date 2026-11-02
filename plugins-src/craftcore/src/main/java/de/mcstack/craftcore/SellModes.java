package de.mcstack.craftcore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryAction;

/** Parsing, tab completion, and stack selection for /sell. No inventory I/O. */
final class SellModes {
  enum Kind { GUI, HAND, ITEM, ALL, ALL_CONFIRM }
  record Parsed(Kind kind, Material material, Integer amount, String error) {}
  record Slot(int index, String material, int amount) {}
  record Piece(int index, String material, int amount) {}

  static final long SELL_ALL_TTL_MS = 30_000L;
  static final int DEPOSIT_SLOTS = 45;
  static final int PREVIEW_SLOT = 48;
  static final int CONFIRM_SLOT = 49;
  static final int CLOSE_SLOT = 53;

  private SellModes() {}

  static Parsed parse(String[] args) {
    if (args == null || args.length == 0) return new Parsed(Kind.GUI, null, null, null);
    String head = args[0].toLowerCase(Locale.ROOT);
    if (head.equals("gui") || head.equals("menu")) {
      if (args.length != 1) return error("Verwendung: /sell gui");
      return new Parsed(Kind.GUI, null, null, null);
    }
    if (head.equals("hand")) {
      if (args.length > 2) return error("Verwendung: /sell hand [Anzahl]");
      Integer count = args.length == 2 ? amount(args[1]) : null;
      if (args.length == 2 && count == null) return error("Anzahl muss eine positive Zahl sein.");
      return new Parsed(Kind.HAND, null, count, null);
    }
    if (head.equals("all") || head.equals("alles")) {
      if (args.length == 1) return new Parsed(Kind.ALL, null, null, null);
      if (args.length == 2 && (args[1].equalsIgnoreCase("confirm") || args[1].equalsIgnoreCase("bestaetigen")))
        return new Parsed(Kind.ALL_CONFIRM, null, null, null);
      return error("Verwendung: /sell all bestaetigen");
    }
    if (args.length > 2) return error("Verwendung: /sell <item> [Anzahl]");
    Material material = Material.matchMaterial(args[0].replace(' ', '_'));
    if (material == null || !SurvivalItems.obtainable(material.name(), List.of())) return error("Unbekanntes Item: " + args[0]);
    Integer count = args.length == 2 ? amount(args[1]) : null;
    if (args.length == 2 && count == null) return error("Anzahl muss eine positive Zahl sein.");
    return new Parsed(Kind.ITEM, material, count, null);
  }

  private static Parsed error(String message) { return new Parsed(null, null, null, message); }

  /** Positive integer up to 7 digits. Leading zeros are rejected. */
  static Integer amount(String raw) {
    if (raw == null || !raw.matches("[1-9]\\d{0,6}")) return null;
    return Integer.parseInt(raw);
  }

  static List<String> complete(String[] args) {
    if (args == null || args.length == 0) return List.of("hand", "all", "gui");
    if (args.length == 1) {
      List<String> out = new ArrayList<>();
      String prefix = args[0].toLowerCase(Locale.ROOT);
      for (String word : List.of("hand", "all", "gui")) if (word.startsWith(prefix)) out.add(word);
      if (!prefix.isEmpty()) for (Material material : Material.values()) {
        if (!SurvivalItems.obtainable(material.name(), List.of())) continue;
        String name = material.name().toLowerCase(Locale.ROOT);
        if (name.startsWith(prefix)) out.add(name);
      }
      return out;
    }
    if (args.length == 2) {
      String head = args[0].toLowerCase(Locale.ROOT);
      if (head.equals("all") || head.equals("alles")) return UserMenu.complete(args[1], "bestaetigen", "confirm");
      if (head.equals("hand") || itemName(args[0])) return UserMenu.complete(args[1], "1", "64");
    }
    return List.of();
  }

  private static boolean itemName(String raw) {
    Material material = Material.matchMaterial(raw);
    return material != null && SurvivalItems.obtainable(material.name(), List.of());
  }

  static List<Integer> chunks(int amount, int maxPerTrade) {
    if (amount < 1 || maxPerTrade < 1) throw new IllegalArgumentException("amount");
    List<Integer> out = new ArrayList<>();
    int left = amount;
    while (left > 0) {
      int n = Math.min(left, maxPerTrade);
      out.add(n);
      left -= n;
    }
    return out;
  }

  /** {@code material} null keeps every slot. {@code wanted} null keeps every matching item. Each piece is at most {@code maxPerTrade}. */
  static List<Piece> take(List<Slot> slots, String material, Integer wanted, int maxPerTrade) {
    int left = wanted == null ? Integer.MAX_VALUE : wanted;
    List<Piece> out = new ArrayList<>();
    if (slots == null || left <= 0) return out;
    for (Slot slot : slots) {
      if (left <= 0) break;
      if (slot == null || slot.material() == null || slot.amount() <= 0) continue;
      if (material != null && !slot.material().equalsIgnoreCase(material)) continue;
      int take = (int) Math.min(slot.amount(), (long) left);
      for (int chunk : chunks(take, maxPerTrade)) out.add(new Piece(slot.index(), slot.material(), chunk));
      left -= take;
    }
    return out;
  }

  /** A confirm command is valid only inside the preview window. */
  static boolean sellAllOpen(Long issuedAtMillis, long nowMillis) {
    if (issuedAtMillis == null) return false;
    long age = nowMillis - issuedAtMillis;
    return age >= 0 && age <= SELL_ALL_TTL_MS;
  }

  /** Double-click collect, and any click on the button row, can pull glass, wool, or the barrier. */
  static boolean pullsButtons(InventoryAction action, int rawSlot, int topSize) {
    if (action == InventoryAction.COLLECT_TO_CURSOR) return true;
    return rawSlot >= DEPOSIT_SLOTS && rawSlot < topSize;
  }

  /** The slot still holds the same material and meta, with enough items left. */
  static boolean sameOffer(boolean present, boolean sameType, boolean sameMeta, int have, int need) {
    return present && sameType && sameMeta && have >= need && need >= 1;
  }

  /** Deposit slots go back to the player. Control buttons do not. */
  static List<Slot> leftovers(List<Slot> contents) {
    List<Slot> out = new ArrayList<>();
    if (contents == null) return out;
    for (Slot slot : contents) if (slot != null && slot.index() >= 0 && slot.index() < DEPOSIT_SLOTS && slot.amount() > 0 && slot.material() != null) out.add(slot);
    return out;
  }
}
