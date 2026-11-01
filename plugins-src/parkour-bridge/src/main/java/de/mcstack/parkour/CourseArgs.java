package de.mcstack.parkour;

import java.util.*;

/** Pure parsing and completion for course arguments. */
final class CourseArgs {
 record Match(String slug,int length) {}
 private static String normalize(String value) {
  value=value.trim();
  if(value.length()>=2 && value.startsWith("\"") && value.endsWith("\"")) value=value.substring(1,value.length()-1);
  return value.replace('_',' ').trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
 }
 static String resolve(List<String> tokens,Map<String,String> names) {
  if(tokens.isEmpty()) return null;
  String name=normalize(String.join(" ",tokens));
  for(var entry:names.entrySet()) if(normalize(entry.getKey()).equals(name)) return entry.getKey();
  for(var entry:names.entrySet()) if(entry.getValue()!=null && normalize(entry.getValue()).equals(name)) return entry.getKey();
  return null;
 }
 static Match longestPrefix(List<String> tokens,Map<String,String> names) {
  for(int end=tokens.size()-1;end>0;end--) {
   String slug=resolve(tokens.subList(0,end),names);
   if(slug!=null) return new Match(slug,end);
  }
  return null;
 }
 static List<String> complete(List<String> tokens,Map<String,String> names) {
  if(tokens.isEmpty()) return List.of();
  Set<String> result=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
  String current=tokens.get(tokens.size()-1).replace("\"","").toLowerCase(Locale.ROOT);
  if(tokens.size()==1) for(String slug:names.keySet()) if(slug.toLowerCase(Locale.ROOT).startsWith(current)) result.add(slug);
  for(String display:names.values()) {
   if(display==null) continue;
   String[] words=display.trim().split("\\s+");
   int index=tokens.size()-1;
   if(index>=words.length) continue;
   boolean matches=true;
   for(int i=0;i<index;i++) if(!normalize(tokens.get(i).replace("\"","")).equals(normalize(words[i]))) { matches=false; break; }
   if(matches && words[index].toLowerCase(Locale.ROOT).startsWith(current)) result.add(words[index]);
  }
  return new ArrayList<>(result);
 }
 record Course(int id,String pkName,String slug,String display) {}
 /** Findet den Lobby-Parkour-Namen per ID, pk_name, Slug oder Anzeigename (Gross/Klein, _/Leerzeichen egal). */
 static String resolveCourse(String requested,List<Course> courses) {
  if(requested==null || requested.isBlank()) return null;
  String want=normalize(requested);
  for(Course c:courses) if(String.valueOf(c.id()).equals(want)) return c.pkName();
  for(Course c:courses) if(c.pkName()!=null && normalize(c.pkName()).equals(want)) return c.pkName();
  for(Course c:courses) if(c.slug()!=null && normalize(c.slug()).equals(want)) return c.pkName();
  for(Course c:courses) if(c.display()!=null && normalize(c.display()).equals(want)) return c.pkName();
  return null;
 }
 /** Tab-Vorschlaege: Slugs (ohne Leerzeichen), passend zum Praefix. */
 static List<String> completeCourses(String prefix,List<Course> courses) {
  String p=prefix==null?"":prefix.replace("\"","").toLowerCase(Locale.ROOT);
  Set<String> out=new TreeSet<>();
  for(Course c:courses) if(c.slug()!=null && c.slug().startsWith(p)) out.add(c.slug());
  return new ArrayList<>(out);
 }
}
