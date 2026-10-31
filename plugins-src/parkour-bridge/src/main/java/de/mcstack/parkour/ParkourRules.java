package de.mcstack.parkour;

import java.util.Set;
import java.util.Collection;

/** Reine Regeln fuer Parkour-Ruecksetzungen (ohne Bukkit, testbar). */
final class ParkourRules {
 private ParkourRules() {}
 static boolean validWaterMode(String raw) {
  return raw!=null && Set.of("feet","touch","off").contains(raw.trim().toLowerCase(java.util.Locale.ROOT));
 }
 static String waterMode(String raw) {
  return validWaterMode(raw) ? raw.trim().toLowerCase(java.util.Locale.ROOT) : "feet";
 }
 static double waterSurface(int level) { return level>=8 ? 1.0 : (8-level)/9.0; }
 static boolean waterReset(String mode, boolean touching, boolean feetWater, int level, double yInBlock) {
  if("off".equalsIgnoreCase(mode)) return false;
  if("touch".equalsIgnoreCase(mode)) return touching || feetWater;
  return feetWater && yInBlock<waterSurface(level);
 }
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
 /** Bloecke, auf denen eine Landung keinen (vollen) Fallschaden verursacht. */
 static final Set<String> SOFT = Set.of("slime_block", "hay_block", "honey_block", "powder_snow", "water", "bubble_column", "cobweb", "scaffolding", "sweet_berry_bush");
 static boolean softLanding(String blockKey, boolean bed) { return bed || (blockKey != null && SOFT.contains(blockKey)); }
 static double[][] footprint(double x, double z) { return new double[][]{{x,z},{x-0.299,z-0.299},{x-0.299,z+0.299},{x+0.299,z-0.299},{x+0.299,z+0.299}}; }
 static boolean softLandingAny(Collection<String> blockKeys, boolean anyBed) { return anyBed || blockKeys.stream().anyMatch(key->softLanding(key,false)); }
 static boolean inGrace(Long until, long now) { return until!=null && now<until; }
 static boolean hardLandingReset(double drop, double limit, boolean soft, boolean grace) { return limit > 0 && drop > limit && !soft && !grace; }
 static boolean fallResets(double drop, double limit, String blockKey, boolean bed) { return hardLandingReset(drop, limit, softLanding(blockKey, bed), false); }
}
