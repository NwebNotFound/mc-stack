package de.mcstack.parkour;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ParkourSqlTest {
 @Test void courseIdIsLowercase() { assertEquals("test", ParkourSql.course(" Test ")); assertEquals("lobby_run", ParkourSql.course("LOBBY_RUN")); assertNull(ParkourSql.course(" ")); assertNull(ParkourSql.course(null)); assertEquals(64, ParkourSql.course("x".repeat(80)).length()); }
 @Test void migrationRekeysOldTableOnlyOnce() {
  assertEquals(List.of(ParkourSql.REKEY), ParkourSql.migration(List.of("uuid")));
  assertEquals(List.of(ParkourSql.REKEY), ParkourSql.migration(List.of()));
  assertEquals(List.of(), ParkourSql.migration(List.of("UUID", "COURSE")));
 }
 @Test void migrationNeverDeletesData() {
  for (String s : List.of(ParkourSql.CREATE, ParkourSql.ADD_COURSE, ParkourSql.REKEY, ParkourSql.UPSERT, ParkourSql.TOP)) { String u = s.toUpperCase(Locale.ROOT); assertFalse(u.contains("DROP TABLE") || u.contains("DELETE") || u.contains("TRUNCATE") || u.contains("DROP COLUMN"), s); }
  assertTrue(ParkourSql.CREATE.contains("PRIMARY KEY (uuid, course)"));
  assertTrue(ParkourSql.ADD_COURSE.contains("IF NOT EXISTS") && ParkourSql.ADD_COURSE.contains("NOT NULL DEFAULT 'lobby'"));
 }
 @Test void upsertKeepsBestPerPlayerAndCourse() { assertTrue(ParkourSql.UPSERT.contains("(uuid,course,name,best_ms,updated_at)")); assertTrue(ParkourSql.UPSERT.contains("LEAST(best_ms,VALUES(best_ms))")); }
 @Test void topIsTenPerCourse() { assertTrue(ParkourSql.TOP.contains("PARTITION BY course")); assertTrue(ParkourSql.TOP.contains("rn<=10")); }
 @Test void boardsPerCourse() {
  List<ParkourSql.Entry> e = new ArrayList<>(); for (int i = 1; i <= 12; i++) e.add(new ParkourSql.Entry("p" + i, i * 1000L));
  String b = ParkourSql.board("test", e);
  assertTrue(b.startsWith("Parkour test - Top 10\n1. p1  0:01.000"));
  assertTrue(b.contains("\n10. p10  0:10.000")); assertFalse(b.contains("11."));
  assertEquals("Parkour test - Top 10\nNoch keine Bestzeiten", ParkourSql.board("test", List.of()));
  Map<String, List<ParkourSql.Entry>> m = new TreeMap<>(); m.put("a", List.of(new ParkourSql.Entry("x", 1))); m.put("b", List.of());
  assertEquals("Parkour a - Top 10\n1. x  0:00.001\n\nParkour b - Top 10\nNoch keine Bestzeiten", ParkourSql.allBoards(m));
  assertEquals("Parkour - Top 10\nNoch keine Bestzeiten", ParkourSql.allBoards(Map.of()));
 }
}
