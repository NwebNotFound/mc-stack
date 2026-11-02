package de.mcstack.craftcore;

import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketSnapshotOscillationTest {
  @Test void writersDoNotShareColumns() throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:h2:mem:osc_" + UUID.randomUUID() + ";MODE=MySQL"); Statement s = c.createStatement()) {
      s.execute("CREATE TABLE market_catalog(item VARCHAR(64) PRIMARY KEY,buy_price DECIMAL(20,2),sell_price DECIMAL(20,2),base_price DECIMAL(20,4),updated_at BIGINT)");
      s.execute("CREATE TABLE market_price_history(item VARCHAR(64),hour_ts BIGINT,avg_price DECIMAL(20,4),min_price DECIMAL(20,4),max_price DECIMAL(20,4),volume_qty BIGINT,quote_price DECIMAL(20,4),PRIMARY KEY(item,hour_ts))");
      s.execute("CREATE TABLE market_events(id BIGINT AUTO_INCREMENT PRIMARY KEY,item VARCHAR(64),qty INT,unit_price DECIMAL(20,4),total_cp DECIMAL(20,2),source VARCHAR(8),ts BIGINT)");
      s.execute("INSERT INTO market_catalog VALUES('STONE',2,1,1,0)");
      WebsiteStats.snapshot(c, Instant.ofEpochSecond(7300));
      s.execute("UPDATE market_catalog SET sell_price=1.5 WHERE item='STONE'");
      WebsiteStats.snapshot(c, Instant.ofEpochSecond(7400));
      try (ResultSet r = s.executeQuery("SELECT quote_price, avg_price FROM market_price_history WHERE item='STONE'")) {
        assertTrue(r.next());
        assertEquals(1, r.getDouble(1));
        assertEquals(1, r.getDouble(2));
      }
      s.execute("INSERT INTO market_events(item,qty,unit_price,total_cp,source,ts) VALUES('STONE',4,0.5,2,'bank',7300)");
      WebsiteStats.maintainMarket(c, Instant.ofEpochSecond(7400));
      try (ResultSet r = s.executeQuery("SELECT quote_price, avg_price, volume_qty FROM market_price_history WHERE item='STONE'")) {
        assertTrue(r.next());
        assertEquals(1, r.getDouble(1));
        assertEquals(0.5, r.getDouble(2));
        assertEquals(4, r.getLong(3));
      }
      for (int i = 0; i < 10; i++) {
        WebsiteStats.snapshot(c, Instant.ofEpochSecond(7500));
        WebsiteStats.maintainMarket(c, Instant.ofEpochSecond(7500));
      }
      try (ResultSet r = s.executeQuery("SELECT quote_price, avg_price, volume_qty FROM market_price_history WHERE item='STONE'")) {
        assertTrue(r.next());
        assertEquals(1, r.getDouble(1));
        assertEquals(0.5, r.getDouble(2));
        assertEquals(4, r.getLong(3));
      }
      assertTrue(WebsiteStats.CHANGE_24H_SQL.contains("quote_price"));
      assertFalse(WebsiteStats.CHANGE_24H_SQL.contains("avg_price"));
    }
  }
}
