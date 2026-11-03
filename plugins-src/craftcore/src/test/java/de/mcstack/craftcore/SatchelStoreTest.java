package de.mcstack.craftcore;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SatchelStoreTest {
  @Test void createAddAndPityRoundTrip() throws Exception {
    try (var c = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1")) {
      SatchelStore.prepare(c);
      SatchelStore.create(c, "Blocks", 5000, 10, 50);
      SatchelStore.addItem(c, "Blocks", "SSR+", 2, new byte[]{1, 2, 3});
      var satchel = SatchelStore.find(c, "Blocks").orElseThrow();
      assertEquals(5000, satchel.priceCents());
      assertTrue(satchel.enabled());
      assertEquals(10, satchel.pitySr());
      var items = SatchelStore.items(c, "Blocks");
      assertEquals(1, items.size());
      assertEquals("SSR+", items.get(0).rarity());
      assertArrayEquals(new byte[]{1, 2, 3}, items.get(0).bytes());
      String player = UUID.randomUUID().toString();
      assertEquals(new SatchelOdds.Pity(0, 0), SatchelStore.lockPity(c, player, "Blocks"));
      SatchelStore.savePity(c, player, "Blocks", new SatchelOdds.Pity(4, 9));
      assertEquals(new SatchelOdds.Pity(4, 9), SatchelStore.lockPity(c, player, "Blocks"));
      assertEquals(List.of(satchel.name()), SatchelStore.enabled(c).stream().map(SatchelStore.Satchel::name).toList());
    }
  }

  @Test void buyKeepsTheRewardWhenNobodyIsOnlineToReceiveIt() throws Exception {
    try (var c = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1")) {
      c.createStatement().execute("CREATE TABLE accounts(uuid VARCHAR(36) PRIMARY KEY, balance BIGINT NOT NULL)");
      SatchelStore.prepare(c);
      SatchelStore.create(c, "Blocks", 5000, 10, 50);
      SatchelStore.addItem(c, "Blocks", "C", 1, new byte[]{9, 9});
      String player = "player-offline";
      c.createStatement().execute("INSERT INTO accounts VALUES('" + player + "', 8000)");
      var broke = SatchelStore.buy(c, player, "Blocks", 10, new java.util.Random(1));
      assertFalse(broke.ok());
      assertEquals(0, count(c, "SELECT COUNT(*) FROM satchel_claims"));
      assertEquals(8000, count(c, "SELECT balance FROM accounts WHERE uuid='" + player + "'"));
      var bought = SatchelStore.buy(c, player, "Blocks", 1, new java.util.Random(1));
      assertTrue(bought.ok());
      assertEquals(3000, count(c, "SELECT balance FROM accounts WHERE uuid='" + player + "'"));
      assertEquals(1, bought.claims().size());
      assertEquals(5000, bought.claims().get(0).capCents());
      assertEquals(1, count(c, "SELECT COUNT(*) FROM satchel_claims WHERE player='" + player + "' AND status='offen'"));
      assertTrue(SatchelStore.take(c, bought.claims().get(0).id(), player));
      assertFalse(SatchelStore.take(c, bought.claims().get(0).id(), player));
      assertEquals(0, SatchelStore.openClaims(c, player).size());
    }
  }

  private static long count(java.sql.Connection c, String sql) throws Exception {
    try (var r = c.createStatement().executeQuery(sql)) { r.next(); return r.getLong(1); }
  }
}
