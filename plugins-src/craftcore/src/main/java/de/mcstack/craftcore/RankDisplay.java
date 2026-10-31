package de.mcstack.craftcore;

import java.util.*;

public final class RankDisplay {
  public record Rank(String name, String color, int weight, String label) {}
  public static final List<Rank> RANKS = List.of(new Rank("admin", "&c", 100, "Admin"), new Rank("builder", "&a", 80, "Builder"), new Rank("mvp", "&d", 60, "MVP"), new Rank("vip", "&6", 40, "VIP"), new Rank("default", "&7", 0, "Spieler"));
  public static final Map<String,String> TEAM_COLORS;
  public static final String DEFAULT_TEAM_COLOR = "&f";
  static {
    Map<String,String> colors = new LinkedHashMap<>();
    colors.put("weiss", "&f"); colors.put("blau", "&9"); colors.put("dunkelblau", "&1"); colors.put("aqua", "&b"); colors.put("tuerkis", "&3"); colors.put("gelb", "&e"); colors.put("lila", "&5"); colors.put("dunkelgrau", "&8"); colors.put("schwarz", "&0"); colors.put("dunkelrot", "&4"); colors.put("dunkelgruen", "&2");
    TEAM_COLORS = Collections.unmodifiableMap(colors);
  }
  private RankDisplay() {}
  public static Rank pick(Collection<String> groups) { return RANKS.stream().filter(r -> groups.stream().anyMatch(g -> r.name().equalsIgnoreCase(g))).max(Comparator.comparingInt(Rank::weight)).orElse(RANKS.get(RANKS.size()-1)); }
  public static String parseColor(String prefix) { return prefix != null && prefix.length() >= 2 && prefix.charAt(0) == '&' && "0123456789abcdef".indexOf(Character.toLowerCase(prefix.charAt(1))) >= 0 ? "&" + Character.toLowerCase(prefix.charAt(1)) : "&7"; }
  public static String teamName(Rank rank) { return truncate(String.format(Locale.ROOT,"r%03d_%s",100-rank.weight(),rank.name())); }
  public static String teamName(long id) { return truncate("t" + id); }
  private static String truncate(String name) { return name.substring(0, Math.min(16,name.length())); }
  public static Optional<String> teamColor(String name) { return Optional.ofNullable(name == null ? null : TEAM_COLORS.get(name.toLowerCase(Locale.ROOT))); }
}
