package de.mcstack.craftcore;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RankDisplayTest {
  @Test void highestKnownRankWins() {
    assertEquals("builder",RankDisplay.pick(List.of("vip","unknown","builder","mvp")).name());
    assertEquals("admin",RankDisplay.pick(List.of("ADMIN","vip")).name());
    assertEquals("default",RankDisplay.pick(List.of("unknown")).name());
    assertEquals("default",RankDisplay.pick(List.of()).name());
  }
  @Test void parsesOnlyLeadingColor() {
    assertEquals("&a",RankDisplay.parseColor("&Ahello")); assertEquals("&0",RankDisplay.parseColor("&0"));
    assertEquals("&7",RankDisplay.parseColor("hello &c")); assertEquals("&7",RankDisplay.parseColor("&lbold")); assertEquals("&7",RankDisplay.parseColor(null));
  }
  @Test void namesSortAndFit() {
    assertEquals("r000_admin",RankDisplay.teamName(RankDisplay.RANKS.get(0)));
    assertEquals("r100_default",RankDisplay.teamName(RankDisplay.RANKS.get(4)));
    assertTrue(RankDisplay.teamName(new RankDisplay.Rank("verylongrankname", "&7", 0, "x")).length()<=16);
    assertTrue(RankDisplay.teamName(Long.MAX_VALUE).length()<=16);
  }
  @Test void teamColorsAreDistinctFromRankColors() {
    assertTrue(Collections.disjoint(RankDisplay.TEAM_COLORS.values(),RankDisplay.RANKS.stream().map(RankDisplay.Rank::color).toList()));
    assertEquals(Optional.of("&9"),RankDisplay.teamColor("BlAu")); assertTrue(RankDisplay.teamColor("missing").isEmpty()); assertEquals("&f",RankDisplay.DEFAULT_TEAM_COLOR);
  }
  @Test void migrationIsIdempotentAndSetsDefault() throws Exception {
    try(var c=java.sql.DriverManager.getConnection("jdbc:h2:mem:rank;MODE=MySQL"); var s=c.createStatement()) {
      s.execute("CREATE TABLE teams (id BIGINT PRIMARY KEY)"); s.execute("INSERT INTO teams VALUES (1)");
      s.execute(CraftCore.TEAM_COLOR_MIGRATION); s.execute(CraftCore.TEAM_COLOR_MIGRATION);
      try(var r=s.executeQuery("SELECT color FROM teams WHERE id=1")) { assertTrue(r.next()); assertEquals("&f",r.getString(1)); }
    }
  }
  @Test void onlyConsoleTouchesAdmin() {
    assertFalse(RankDisplay.rankChangeAllowed(false,"admin",List.of("default")));
    assertFalse(RankDisplay.rankChangeAllowed(false,"ADMIN",List.of()));
    assertFalse(RankDisplay.rankChangeAllowed(false,"vip",List.of("admin")));
    assertTrue(RankDisplay.rankChangeAllowed(false,"mvp",List.of("vip")));
    assertTrue(RankDisplay.rankChangeAllowed(true,"admin",List.of("default")));
    assertTrue(RankDisplay.rankChangeAllowed(true,"vip",List.of("admin")));
  }
}
