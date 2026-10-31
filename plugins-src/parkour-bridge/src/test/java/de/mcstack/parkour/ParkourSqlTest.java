package de.mcstack.parkour;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ParkourSqlTest {
 @Test void courseIdIsLowercase() { assertEquals("test", ParkourSql.course(" Test ")); assertEquals("lobby_run", ParkourSql.course("LOBBY_RUN")); assertNull(ParkourSql.course(" ")); assertNull(ParkourSql.course(null)); assertEquals(64, ParkourSql.course("x".repeat(80)).length()); }
 @Test void courseSlugUsesConsistentSeparators() {
  assertEquals("cave_rush",ParkourSql.course("Cave Rush")); assertEquals("cave_rush",ParkourSql.course("cave  rush"));
  assertEquals("cave_rush",ParkourSql.course("-Cave-Rush-")); assertEquals("kurs1",ParkourSql.course("kurs1")); assertNull(ParkourSql.course("---"));
 }
 @Test void iconAndDisplayFallback() {
  assertEquals("diamond_sword",ParkourSql.icon("minecraft:DIAMOND_SWORD")); assertEquals("paper",ParkourSql.icon("PAPER"));
  assertNull(ParkourSql.icon("other:paper")); assertNull(ParkourSql.icon("a-b")); assertNull(ParkourSql.icon("x".repeat(49))); assertNull(ParkourSql.icon(""));
  assertEquals("cave_rush",ParkourSql.displayName("cave_rush"," ")); assertEquals("Cave Rush",ParkourSql.displayName("cave_rush","Cave Rush"));
 }
 @Test void courseMigrationIsIdempotentAndViewHasExactColumns() throws Exception {
  try(var c=java.sql.DriverManager.getConnection("jdbc:h2:mem:courses;MODE=MariaDB;DB_CLOSE_DELAY=-1"); var s=c.createStatement()) {
   s.execute("CREATE TABLE parkour_times (course VARCHAR(64))"); s.execute("INSERT INTO parkour_times VALUES ('cave_rush'),('other')");
   for(int i=0;i<2;i++) {
    for(String sql:List.of(ParkourSql.CREATE_COURSES,ParkourSql.ADD_DISPLAY,ParkourSql.ADD_ICON,ParkourSql.SEED_COURSES,ParkourSql.CAVE_NAME,ParkourSql.VIEW_COURSES)) s.execute(sql);
    if(i==0) s.execute("UPDATE parkour_courses SET display_name='Custom', icon='stone' WHERE slug='other'");
   }
   try(var r=s.executeQuery("SELECT * FROM v_parkour_courses ORDER BY slug")) {
    assertEquals(3,r.getMetaData().getColumnCount()); assertEquals("SLUG",r.getMetaData().getColumnLabel(1)); assertEquals("COURSE_NAME",r.getMetaData().getColumnLabel(2)); assertEquals("COURSE_ICON",r.getMetaData().getColumnLabel(3));
    assertTrue(r.next()); assertEquals("Cave Rush",r.getString(2)); assertEquals("paper",r.getString(3)); assertTrue(r.next()); assertEquals("Custom",r.getString(2)); assertEquals("stone",r.getString(3)); assertFalse(r.next());
   }
  }
 }
 @Test void floorCacheLoadsOncePerCourseAndReloadsAfterClear() throws Exception {
  ParkourSql.FloorCache cache=new ParkourSql.FloorCache(); int[] calls={0};
  ParkourSql.FloorLoader load=()->{ calls[0]++; return -52.0; };
  assertEquals(-52.0,cache.get("a",load)); assertEquals(-52.0,cache.get("a",load)); assertEquals(1,calls[0]);
  cache.get("b",load); assertEquals(2,calls[0]); cache.clear(); cache.get("a",load); assertEquals(3,calls[0]);
  assertNull(cache.get("missing",()->{ calls[0]++; return null; })); assertNull(cache.get("missing",load)); assertEquals(4,calls[0]);
 }
 @Test void courseFloorMatchesCaseButNotSeparators() {
  assertEquals(-60.0,ParkourSql.courseFloor(Map.of("Cave Rush",-60),"cave rush"));
  assertNull(ParkourSql.courseFloor(Map.of("cave_rush",-60),"Cave Rush"));
  assertNull(ParkourSql.courseFloor(Map.of("Cave Rush",-60),"Cave_Rush"));
 }
 @Test void reloadClearUsesNewCourseFloorOverride() throws Exception {
  ParkourSql.FloorCache cache=new ParkourSql.FloorCache(); Map<String,Integer> overrides=new HashMap<>();
  overrides.put("Cave Rush",-60);
  ParkourSql.FloorLoader load=()->ParkourSql.courseFloor(overrides,"Cave Rush");
  assertEquals(-60.0,cache.get("Cave Rush",load));
  overrides.put("Cave Rush",-70);
  assertEquals(-60.0,cache.get("Cave Rush",load));
  cache.clear();
  assertEquals(-70.0,cache.get("Cave Rush",load));
 }
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
 @Test void upsertKeepsBestPerPlayerAndCourse() { assertTrue(ParkourSql.UPSERT.contains("(uuid,course,course_name,name,best_ms,updated_at)")); assertTrue(ParkourSql.UPSERT.contains("LEAST(best_ms,VALUES(best_ms))")); }
 @Test void oldRowsRemainReadableAndSorted() { assertTrue(ParkourSql.ADD_COURSE_NAME.contains("IF NOT EXISTS")); assertTrue(ParkourSql.TOP.contains("ORDER BY best_ms ASC")); }
 @Test void boardsPerCourse() {
  List<ParkourSql.Entry> e = new ArrayList<>(); for (int i = 1; i <= 12; i++) e.add(new ParkourSql.Entry("p" + i, i * 1000L));
  String b = ParkourSql.board("test", e);
  assertTrue(b.startsWith("test\nTop 10\n1. p1  0:01.000"));
  assertTrue(b.contains("\n10. p10  0:10.000")); assertFalse(b.contains("11."));
  assertEquals("test\nTop 10\nNoch keine Bestzeiten", ParkourSql.board("test", List.of()));
  Map<String, List<ParkourSql.Entry>> m = new TreeMap<>(); m.put("a", List.of(new ParkourSql.Entry("x", 1))); m.put("b", List.of());
  assertEquals("a\nTop 10\n1. x  0:00.001\n\nb\nTop 10\nNoch keine Bestzeiten", ParkourSql.allBoards(m));
  assertEquals("Parkour - Top 10\nNoch keine Bestzeiten", ParkourSql.allBoards(Map.of()));
 }
 @Test void floorIsLowestCoursePointMinusDistance() {
  assertEquals(-48.0, ParkourSql.locY("world,107.0,-48.0,95.0,0.0,0.0"));
  assertNull(ParkourSql.locY(null)); assertNull(ParkourSql.locY("kaputt")); assertNull(ParkourSql.locY("w,1,x,3"));
  assertEquals(-52.0, ParkourSql.floor(Arrays.asList(-32.0, -48.0, -43.0, null), 4));
  assertEquals(-33.0, ParkourSql.floor(List.of(-32.0), 0));
  assertNull(ParkourSql.floor(List.of(), 4));
 }
}
