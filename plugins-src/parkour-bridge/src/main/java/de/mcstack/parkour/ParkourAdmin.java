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
 record Target(CourseArgs.Course course, String player) {}
 /** Course from {@link CourseArgs#resolveCourse}. A trailing token is the player only when the full text is not a course. */
 static CourseArgs.Course match(String requested, List<CourseArgs.Course> courses) {
  String name=CourseArgs.resolveCourse(requested, courses==null?List.of():courses);
  if(name==null || courses==null) return null;
  for(CourseArgs.Course course:courses) if(name.equals(course.pkName())) return course;
  return null;
 }
 static Target select(String[] args, int end, boolean allowPlayer, List<CourseArgs.Course> courses) {
  CourseArgs.Course found=match(course(args,1,end), courses);
  if(found!=null || !allowPlayer || end<=2) return new Target(found, null);
  found=match(course(args,1,end-1), courses);
  return new Target(found, found==null?null:args[end-1].replace("\"",""));
 }
 /** Course tokens use {@link CourseArgs#completeCourses}. After a resolved course, reset/removetime suggest players and confirm. */
 static List<String> complete(String[] args, List<CourseArgs.Course> courses, Map<String,List<String>> players) {
  if(args.length<2) return List.of();
  String action=args[0].toLowerCase(Locale.ROOT);
  if(action.equals("resetall")) return args.length==2 && "confirm".startsWith(args[1].toLowerCase(Locale.ROOT))?List.of("confirm"):List.of();
  if(!(action.equals("times") || action.equals("reset") || action.equals("removetime") || action.equals("setboard"))) return List.of();
  if(args.length==2) return CourseArgs.completeCourses(args[1], courses==null?List.of():courses);
  if(!(action.equals("reset") || action.equals("removetime"))) return List.of();
  String prefix=args[args.length-1].replace("\"","").toLowerCase(Locale.ROOT);
  CourseArgs.Course found=match(course(args,1,args.length-1), courses);
  boolean playerAlready=false;
  if(found==null && action.equals("reset") && args.length>=4) { found=match(course(args,1,args.length-2), courses); playerAlready=found!=null; }
  if(found==null || found.slug()==null) return List.of();
  List<String> result=new ArrayList<>();
  if(action.equals("reset") && "confirm".startsWith(prefix)) result.add("confirm");
  if(!playerAlready && players!=null) for(String player:players.getOrDefault(found.slug(), List.of())) if(player!=null && player.toLowerCase(Locale.ROOT).startsWith(prefix) && !result.contains(player)) result.add(player);
  return result;
 }
 static Set<String> variants(String name) {
  String slug=ParkourSql.course(name); if(slug==null) return Set.of();
  return new LinkedHashSet<>(List.of(slug,slug.replace('_',' '),name.trim().toLowerCase(Locale.ROOT)));
 }
 static String format(long ms) { return TimeFormat.format(ms); }
}
