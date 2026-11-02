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
}
