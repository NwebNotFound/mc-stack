package de.mcstack.parkour;

import java.util.*;

/** Pure parsing and formatting for parkour administration. */
final class ParkourAdmin {
 private ParkourAdmin() {}
 static String course(String[] args,int start,int end) {
  String value=String.join(" ",Arrays.copyOfRange(args,start,end)).trim();
  if(value.length()>=2 && value.startsWith("\"") && value.endsWith("\"")) value=value.substring(1,value.length()-1).trim();
  return value.isEmpty() || value.indexOf('"')>=0 ? null : value;
 }
 static boolean confirmed(String[] args) { return args.length>0 && args[args.length-1].equalsIgnoreCase("confirm"); }
 static Set<String> variants(String name) {
  String slug=ParkourSql.course(name); if(slug==null) return Set.of();
  return new LinkedHashSet<>(List.of(slug,slug.replace('_',' '),name.trim().toLowerCase(Locale.ROOT)));
 }
 static String format(long ms) { return TimeFormat.format(ms); }
}
