package de.mcstack.craftcore;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LeaderboardMoneyTest {
  @Test void weeklyMoneyIsBalanceChangeNotGrossEarningsOrTheBalanceItself() {
    String values = WebsiteStats.LEADERBOARD_VALUES_SQL;
    String boards = WebsiteStats.LEADERBOARDS_SQL;
    assertTrue(values.startsWith("SELECT 'money' board"));
    assertTrue(values.contains("COALESCE(money,0)"));
    assertFalse(values.toLowerCase(Locale.ROOT).contains("earned"));
    assertFalse(values.contains("bank_journal"));
    assertTrue(boards.contains("v.board,'week'"));
    assertTrue(boards.contains("v.value-COALESCE((SELECT s.value FROM leaderboard_snapshots"));
    assertFalse(boards.contains("bank_journal"));
    assertFalse(boards.contains("market_events"));
  }
}
