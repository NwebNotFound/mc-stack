package de.mcstack.parkour;

import java.util.Set;

/** Reine Regeln fuer Parkour-Ruecksetzungen (ohne Bukkit, testbar). */
final class ParkourRules {
 private ParkourRules() {}
 static boolean isCourseCommand(String command) {
  String label=command.stripLeading().split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
  if(label.startsWith("/")) label=label.substring(1);
  int colon=label.indexOf(':'); if(colon>=0) label=label.substring(colon+1);
  return label.equals("parkour") || label.equals("lpk") || label.equals("lobby-parkour") || label.equals("reload");
 }
 static Double nextPeak(Double peak, double from, double to, boolean grounded, boolean climbable) {
  if(grounded || climbable) return null;
  double current=Math.max(from,to); return peak==null ? current : Math.max(peak,current);
 }
 /** Vanilla: ab mehr als 3 Bloecken Fallhoehe gibt es Fallschaden. */
 static final double SAFE_FALL = 3.0;
 /** Bloecke, auf denen eine Landung keinen (vollen) Fallschaden verursacht. */
 static final Set<String> SOFT = Set.of("slime_block", "hay_block", "honey_block", "powder_snow", "water", "bubble_column", "cobweb", "scaffolding", "sweet_berry_bush");
 static boolean softLanding(String blockKey, boolean bed) { return bed || (blockKey != null && SOFT.contains(blockKey)); }
 /** Ruecksetzen, wenn die Landung echten Fallschaden verursachen wuerde. */
 static boolean fallResets(double drop, String blockKey, boolean bed) { return drop > SAFE_FALL && !softLanding(blockKey, bed); }
}