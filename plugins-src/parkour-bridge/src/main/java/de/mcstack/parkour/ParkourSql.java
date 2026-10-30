package de.mcstack.parkour;

import java.util.*;

/** SQL und reine Logik fuer parkour_times (Bestzeit pro Spieler und Kurs). */
final class ParkourSql {
 private ParkourSql() {}
 static final String CREATE = "CREATE TABLE IF NOT EXISTS parkour_times (uuid CHAR(36) NOT NULL, course VARCHAR(64) NOT NULL, name VARCHAR(16) NOT NULL, best_ms BIGINT NOT NULL, updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY (uuid, course))";
 /** Kurs fuer Altzeilen ohne Kurs (Tabelle war bisher pro Spieler). */
 static final String LEGACY_COURSE = "lobby";
 static final String ADD_COURSE = "ALTER TABLE parkour_times ADD COLUMN IF NOT EXISTS course VARCHAR(64) NOT NULL DEFAULT '" + LEGACY_COURSE + "' AFTER uuid";
 static final String PK_COLUMNS = "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='parkour_times' AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION";
 static final String REKEY = "ALTER TABLE parkour_times DROP PRIMARY KEY, ADD PRIMARY KEY (uuid, course)";
 static final String UPSERT = "INSERT INTO parkour_times (uuid,course,name,best_ms,updated_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE name=IF(VALUES(best_ms)<best_ms,VALUES(name),name), updated_at=IF(VALUES(best_ms)<best_ms,VALUES(updated_at),updated_at), best_ms=LEAST(best_ms,VALUES(best_ms))";
 static final String TOP = "SELECT course,name,best_ms FROM (SELECT course,name,best_ms,ROW_NUMBER() OVER (PARTITION BY course ORDER BY best_ms ASC, updated_at ASC, uuid ASC) rn FROM parkour_times WHERE best_ms>0) t WHERE rn<=10 ORDER BY course, rn";

 /** Migrationsschritte nach ADD_COURSE: Rekey nur, wenn der PK noch nicht (uuid, course) ist. Loescht nie Daten. */
 static List<String> migration(List<String> pkColumns) {
  List<String> pk = new ArrayList<>(); for (String c : pkColumns) pk.add(c.toLowerCase(Locale.ROOT));
  return pk.equals(List.of("uuid", "course")) ? List.of() : List.of(REKEY);
 }
 /** Kurs-ID aus Lobby-Parkour: kleingeschrieben, getrimmt, max. 64 Zeichen; null bei leerem Namen. */
 static String course(String parkourName) {
  if (parkourName == null || parkourName.isBlank()) return null;
  String c = parkourName.trim().toLowerCase(Locale.ROOT);
  return c.length() > 64 ? c.substring(0, 64) : c;
 }
 record Entry(String name, long ms) {}
 static String board(String course, List<Entry> entries) {
  StringBuilder t = new StringBuilder("Parkour ").append(course).append(" - Top 10");
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
