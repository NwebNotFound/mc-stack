package de.mcstack.parkour;

import java.util.*;

/** SQL und reine Logik fuer parkour_times (Bestzeit pro Spieler und Kurs). */
final class ParkourSql {
 private ParkourSql() {}
 @FunctionalInterface interface FloorLoader { Double load() throws java.sql.SQLException; }
 static final class FloorCache {
  private final Map<String, Optional<Double>> values=new HashMap<>();
  Double get(String name, FloorLoader loader) throws java.sql.SQLException {
   if(!values.containsKey(name)) values.put(name,Optional.ofNullable(loader.load()));
   return values.get(name).orElse(null);
  }
  void clear() { values.clear(); }
 }
 static final String CREATE = "CREATE TABLE IF NOT EXISTS parkour_times (uuid CHAR(36) NOT NULL, course VARCHAR(64) NOT NULL, name VARCHAR(16) NOT NULL, best_ms BIGINT NOT NULL, updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY (uuid, course))";
 /** Kurs fuer Altzeilen ohne Kurs (Tabelle war bisher pro Spieler). */
 static final String LEGACY_COURSE = "lobby";
 static final String ADD_COURSE = "ALTER TABLE parkour_times ADD COLUMN IF NOT EXISTS course VARCHAR(64) NOT NULL DEFAULT '" + LEGACY_COURSE + "' AFTER uuid";
 static final String ADD_COURSE_NAME = "ALTER TABLE parkour_times ADD COLUMN IF NOT EXISTS course_name VARCHAR(64) NULL AFTER course";
 static final String CREATE_COURSES = "CREATE TABLE IF NOT EXISTS parkour_courses (slug VARCHAR(64) NOT NULL PRIMARY KEY, display_name VARCHAR(64) NULL, icon VARCHAR(48) NOT NULL DEFAULT 'paper')";
 static final String ADD_DISPLAY = "ALTER TABLE parkour_courses ADD COLUMN IF NOT EXISTS display_name VARCHAR(64) NULL";
 static final String ADD_ICON = "ALTER TABLE parkour_courses ADD COLUMN IF NOT EXISTS icon VARCHAR(48) NOT NULL DEFAULT 'paper'";
 static final String SEED_COURSES = "INSERT INTO parkour_courses (slug) SELECT DISTINCT t.course FROM parkour_times t WHERE t.course IS NOT NULL AND t.course <> '' AND NOT EXISTS (SELECT 1 FROM parkour_courses c WHERE c.slug=t.course)";
 static final String CAVE_NAME = "UPDATE parkour_courses SET display_name='Cave Rush' WHERE slug='cave_rush' AND (display_name IS NULL OR TRIM(display_name)='')";
 static final String VIEW_COURSES = "CREATE OR REPLACE VIEW v_parkour_courses AS SELECT slug, COALESCE(NULLIF(TRIM(display_name),''),slug) AS course_name, COALESCE(NULLIF(TRIM(icon),''),'paper') AS course_icon FROM parkour_courses";
 static String displayName(String slug,String name) { return name==null || name.isBlank()?slug:name; }
 static String icon(String input) {
  if(input==null) return null; String value=input.toLowerCase(Locale.ROOT);
  if(value.startsWith("minecraft:")) value=value.substring(10);
  return value.matches("[a-z0-9_]{1,48}")?value:null;
 }
 static final String PK_COLUMNS = "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='parkour_times' AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION";
 static final String REKEY = "ALTER TABLE parkour_times DROP PRIMARY KEY, ADD PRIMARY KEY (uuid, course)";
 static final String UPSERT = "INSERT INTO parkour_times (uuid,course,course_name,name,best_ms,updated_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE course_name=VALUES(course_name), name=IF(VALUES(best_ms)<best_ms,VALUES(name),name), updated_at=IF(VALUES(best_ms)<best_ms,VALUES(updated_at),updated_at), best_ms=LEAST(best_ms,VALUES(best_ms))";
 static final String TOP = "SELECT course,uuid,name,best_ms FROM parkour_times WHERE best_ms>0 ORDER BY best_ms ASC, updated_at ASC, uuid ASC";

 /** Migrationsschritte nach ADD_COURSE: Rekey nur, wenn der PK noch nicht (uuid, course) ist. Loescht nie Daten. */
 static List<String> migration(List<String> pkColumns) {
  List<String> pk = new ArrayList<>(); for (String c : pkColumns) pk.add(c.toLowerCase(Locale.ROOT));
  return pk.equals(List.of("uuid", "course")) ? List.of() : List.of(REKEY);
 }
 /** Kurs-ID aus Lobby-Parkour: kleingeschrieben, getrimmt, max. 64 Zeichen; null bei leerem Namen. */
 static String course(String parkourName) {
  if (parkourName == null || parkourName.isBlank()) return null;
  String c = parkourName.toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_").replaceAll("^_+|_+$", "");
  if (c.isEmpty()) return null;
  return c.length() > 64 ? c.substring(0, 64) : c;
 }
 /** Kursargument als Rest oder in Anfuehrungszeichen; Punkte sind in Bukkit-Konfigurationspfaden reserviert. */
 static String argument(String[] args) {
  String value=String.join(" ", Arrays.copyOfRange(args,1,args.length)).trim();
  if(value.length()>=2 && value.startsWith("\"") && value.endsWith("\"")) value=value.substring(1,value.length()-1).trim();
  return value.isEmpty() || value.length()>64 || value.indexOf('.')>=0 || value.indexOf('\"')>=0 ? null : value;
 }
 static String boardKey(String name) { return name.trim().toLowerCase(Locale.ROOT); }
 /** Y aus Lobby-Parkour-Ort "world,x,y,z,yaw,pitch"; null wenn unlesbar. */
 static Double locY(String loc) {
  if (loc == null) return null; String[] p = loc.split(",");
  try { return p.length >= 4 ? Double.parseDouble(p[2].trim()) : null; } catch (NumberFormatException e) { return null; }
 }
 /** Rueckholgrenze: tiefster Kurspunkt minus Abstand (mind. 1); null ohne Kurspunkte. */
 static Double floor(List<Double> ys, double distance) {
  Double min = null; for (Double y : ys) if (y != null && (min == null || y < min)) min = y;
  return min == null ? null : min - Math.max(1, distance);
 }
 record Entry(String name, long ms) {} static String board(String course, List<Entry> entries) {
  StringBuilder t = new StringBuilder(course).append("\nTop 10");
  for (int i = 0; i < Math.min(10, entries.size()); i++) t.append("\n").append(i + 1).append(". ").append(entries.get(i).name()).append("  ").append(TimeFormat.format(entries.get(i).ms()));
  if (entries.isEmpty()) t.append("\nNoch keine Bestzeiten");
  return t.toString();
 }
 /** Sammeltafel: alle Kurse untereinander. */
 static String allBoards(Map<String, List<Entry>> byCourse) {
  if (byCourse.isEmpty()) return "Parkour - Top 10\nNoch keine Bestzeiten";
  StringJoiner j = new StringJoiner("\n\n"); byCourse.forEach((c, e) -> j.add(board(c, e))); return j.toString();
 }
}
