package de.mcstack.craftcore;
import java.sql.*;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
class PlayerStatsTest {
 @Test void viewContainsVanillaTeamColorMapping() throws Exception {
  String sql=java.nio.file.Files.readString(java.nio.file.Path.of("../../scripts/v-player-stats.sql"));
  int start=sql.indexOf("CASE LOWER(REPLACE(color, '§', '&'))");
  assertTrue(start>=0);
  int end=sql.indexOf("END AS color_hex",start);
  assertTrue(end>start);
  var matcher=java.util.regex.Pattern.compile("WHEN '&([0-9a-f])' THEN '(#[0-9A-F]{6})'").matcher(sql.substring(start,end));
  java.util.Map<String,String> actual=new java.util.HashMap<>();
  while(matcher.find()) assertNull(actual.put(matcher.group(1),matcher.group(2)));
  assertEquals(java.util.Map.ofEntries(
   java.util.Map.entry("0","#000000"),java.util.Map.entry("1","#0000AA"),java.util.Map.entry("2","#00AA00"),java.util.Map.entry("3","#00AAAA"),
   java.util.Map.entry("4","#AA0000"),java.util.Map.entry("5","#AA00AA"),java.util.Map.entry("6","#FFAA00"),java.util.Map.entry("7","#AAAAAA"),
   java.util.Map.entry("8","#555555"),java.util.Map.entry("9","#5555FF"),java.util.Map.entry("a","#55FF55"),java.util.Map.entry("b","#55FFFF"),
   java.util.Map.entry("c","#FF5555"),java.util.Map.entry("d","#FF55FF"),java.util.Map.entry("e","#FFFF55"),java.util.Map.entry("f","#FFFFFF")),actual);
  assertTrue(sql.substring(start,end).contains("ELSE NULL"));
 }
 @Test void migrationAndUpsertReplaceSnapshots() throws Exception {
  try(Connection c=DriverManager.getConnection("jdbc:h2:mem:stats;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
   PlayerStats.migrate(c); PlayerStats.migrate(c);
   UUID id=UUID.randomUUID();
   PlayerStats.upsert(c,new PlayerStats.Snapshot(id,100,2,3,400));
   PlayerStats.upsert(c,new PlayerStats.Snapshot(id,120,4,5,600));
   try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT uuid,playtime_s,kills,deaths,blocks_mined,updated_at FROM player_stats")) {
    assertTrue(r.next()); assertEquals(id.toString(),r.getString(1)); assertEquals(120,r.getLong(2)); assertEquals(4,r.getLong(3)); assertEquals(5,r.getLong(4)); assertEquals(600,r.getLong(5)); assertNotNull(r.getTimestamp(6)); assertFalse(r.next());
   }
  }
 }
 @Test void worldPlaytimeKeepsServersSeparate() throws Exception {
  try(Connection c=DriverManager.getConnection("jdbc:h2:mem:worldstats;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
   PlayerStats.migrate(c); UUID id=UUID.randomUUID();
   PlayerStats.upsertPlaytime(c,id,"survival",120);
   PlayerStats.upsertPlaytime(c,id,"lobby",30);
   PlayerStats.upsertPlaytime(c,id,"lobby",45);
   try(PreparedStatement q=c.prepareStatement("SELECT server,playtime_s FROM player_playtime_world WHERE uuid=? ORDER BY server")) {
    q.setString(1,id.toString()); try(ResultSet r=q.executeQuery()) {
     assertTrue(r.next()); assertEquals("lobby",r.getString(1)); assertEquals(45,r.getLong(2));
     assertTrue(r.next()); assertEquals("survival",r.getString(1)); assertEquals(120,r.getLong(2)); assertFalse(r.next());
    }
   }
  }
 }
 @Test void survivalOnly() { assertTrue(SpawnRoute.isSurvival("Survival")); assertFalse(SpawnRoute.isSurvival("Lobby")); assertFalse(SpawnRoute.isSurvival("Server")); }
 @Test void detailSnapshotsReplaceAndRollBackTogether() throws Exception {
  try(Connection c=DriverManager.getConnection("jdbc:h2:mem:detailstats;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
   PlayerStats.migrate(c); UUID a=UUID.randomUUID(), b=UUID.randomUUID();
   PlayerStats.replaceDetails(c,a,List.of(new PlayerStats.Detail("blocks_mined","STONE",3),new PlayerStats.Detail("distance_cm","WALK_ONE_CM",125)));
   PlayerStats.replaceDetails(c,b,List.of(new PlayerStats.Detail("blocks_mined","DIRT",7)));
   c.setAutoCommit(false); PlayerStats.replaceDetails(c,a,List.of(new PlayerStats.Detail("kills_entity","ZOMBIE",1))); c.rollback();
   try(PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM player_stats_details WHERE uuid=?")) {q.setString(1,a.toString());try(ResultSet r=q.executeQuery()){assertTrue(r.next());assertEquals(2,r.getInt(1));}}
   PlayerStats.replaceDetails(c,a,List.of(new PlayerStats.Detail("blocks_mined","STONE",4))); c.commit();
   try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*),SUM(stat_value) FROM player_stats_details")){assertTrue(r.next());assertEquals(2,r.getInt(1));assertEquals(11,r.getLong(2));}
  }
 }
 @Test void vanillaBackfillIncludesCraftedUsedAndCustom() {
  Object[] parsed=PlayerStats.parseStats(UUID.randomUUID(),"{\"stats\":{\"minecraft:crafted\":{\"minecraft:stone\":2},\"minecraft:used\":{\"minecraft:stone\":3,\"minecraft:diamond_sword\":5},\"minecraft:custom\":{\"minecraft:jump\":4}}}",true,"minecraft:stone"::equals);
  @SuppressWarnings("unchecked") List<PlayerStats.Detail> details=(List<PlayerStats.Detail>)parsed[2];
  assertTrue(details.contains(new PlayerStats.Detail("craft_item","STONE",2)));
  assertTrue(details.contains(new PlayerStats.Detail("use_item","STONE",3)));
  assertTrue(details.contains(new PlayerStats.Detail("blocks_placed","STONE",3)));
  assertTrue(details.contains(new PlayerStats.Detail("use_item","DIAMOND_SWORD",5)));
  assertFalse(details.contains(new PlayerStats.Detail("blocks_placed","DIAMOND_SWORD",5)));
  assertTrue(details.contains(new PlayerStats.Detail("custom","JUMP",4)));
 }
 @Test void mariaDbDetailsAreTransactional() throws Exception {
  String password=System.getenv("CRAFTCORE_DB_PASSWORD");
  assumeTrue(password!=null && "true".equals(System.getenv("CRAFTCORE_MARIADB_TEST")));
  try(Connection c=DriverManager.getConnection("jdbc:mariadb://craftcore-db:3306/craftcore","craftcore",password); Statement s=c.createStatement()) {
   s.execute("CREATE TEMPORARY TABLE player_stats_details (uuid CHAR(36) NOT NULL, category VARCHAR(32) NOT NULL, stat_key VARCHAR(64) NOT NULL, stat_value BIGINT NOT NULL, PRIMARY KEY(uuid,category,stat_key))");
   UUID id=UUID.randomUUID(); PlayerStats.replaceDetails(c,id,List.of(new PlayerStats.Detail("distance_cm","WALK_ONE_CM",125)));
   c.setAutoCommit(false); PlayerStats.replaceDetails(c,id,List.of(new PlayerStats.Detail("blocks_mined","STONE",3))); c.rollback();
   try(ResultSet r=s.executeQuery("SELECT category,stat_value FROM player_stats_details")) {assertTrue(r.next());assertEquals("distance_cm",r.getString(1));assertEquals(125,r.getLong(2));assertFalse(r.next());}
  }
 }
}
