package de.mcstack.craftcore;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnobtainableMarketTest {
  private static final List<String> NAMED = List.of(
    "BARRIER", "BEDROCK", "LIGHT",
    "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK", "COMMAND_BLOCK_MINECART",
    "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW",
    "DEBUG_STICK", "KNOWLEDGE_BOOK",
    "SPAWNER", "TRIAL_SPAWNER",
    "REINFORCED_DEEPSLATE", "BUDDING_AMETHYST", "END_PORTAL_FRAME", "PETRIFIED_OAK_SLAB",
    "FARMLAND", "DIRT_PATH", "CHORUS_PLANT", "FROGSPAWN", "PLAYER_HEAD",
    "INFESTED_STONE", "ZOMBIE_SPAWN_EGG");

  @Test void noneOfTheseCanBeBoughtOrListed() {
    YamlConfiguration cfg = new YamlConfiguration();
    for (String name : NAMED) {
      Material material = Material.matchMaterial(name);
      assertNotNull(material, name);
      boolean obtainable = SurvivalItems.obtainable(cfg, material);
      assertFalse(obtainable, name);
      assertFalse(PriceCatalog.eligible(cfg, material), name);
      assertTrue(Market.unbuyable(cfg, material), name);
      assertFalse(Market.buyEnabled(!obtainable, obtainable, false), name);
    }
    assertTrue(SurvivalItems.obtainable(cfg, Material.DIRT));
    assertTrue(SurvivalItems.obtainable(cfg, Material.IRON_SWORD));
    assertTrue(SurvivalItems.obtainable(cfg, Material.COBBLESTONE));
    assertTrue(PriceCatalog.eligible(Material.DIRT));
    assertTrue(PriceCatalog.eligible(Material.IRON_SWORD));
    cfg.set("market.unobtainable", List.of("DIRT"));
    boolean obtainable = SurvivalItems.obtainable(cfg, Material.DIRT);
    assertFalse(obtainable);
    assertFalse(PriceCatalog.eligible(cfg, Material.DIRT));
    assertFalse(Market.buyEnabled(!obtainable, obtainable, false));
    var prices = new YamlConfiguration();
    prices.set("base.DIRT", 1.0);
    prices.set("base.STONE", 1.0);
    var kept = MarketEffective.bases(prices, List.of("DIRT"));
    assertFalse(kept.containsKey(Material.DIRT));
    assertEquals(1.0, kept.get(Material.STONE));
  }

  @Test void startupRemovesUnobtainableCatalogRows() throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:h2:mem:unobtainable;MODE=MySQL"); Statement s = c.createStatement()) {
      s.execute("CREATE TABLE market_catalog(item VARCHAR(64) PRIMARY KEY, buy_price INT)");
      s.execute("CREATE TABLE market_prices(material VARCHAR(64) PRIMARY KEY, price DOUBLE)");
      s.execute("INSERT INTO market_catalog VALUES('BARRIER',1),('DIRT',1),('ZOMBIE_SPAWN_EGG',1)");
      s.execute("INSERT INTO market_prices VALUES('FARMLAND',1),('IRON_SWORD',1),('INFESTED_STONE',1)");
      int removed = SurvivalItems.purge(c, name -> !SurvivalItems.obtainable(name, java.util.List.of()));
      assertTrue(removed >= 4, "removed " + removed);
      try (ResultSet catalog = s.executeQuery("SELECT item FROM market_catalog ORDER BY item")) {
        assertTrue(catalog.next());
        assertEquals("DIRT", catalog.getString(1));
        assertFalse(catalog.next());
      }
      try (ResultSet prices = s.executeQuery("SELECT material FROM market_prices")) {
        assertTrue(prices.next());
        assertEquals("IRON_SWORD", prices.getString(1));
        assertFalse(prices.next());
      }
    }
  }

  @Test void purgeSurfacesH2MissingTablesBecauseTheyAreNotMysql1146() throws Exception {
    try (Connection empty = DriverManager.getConnection("jdbc:h2:mem:unobtainable_missing;MODE=MySQL")) {
      assertThrows(java.sql.SQLException.class, () -> SurvivalItems.purge(empty, name -> true));
    }
    try (Connection broken = DriverManager.getConnection("jdbc:h2:mem:unobtainable_badcol;MODE=MySQL"); Statement s = broken.createStatement()) {
      s.execute("CREATE TABLE market_catalog(id INT)");
      assertThrows(java.sql.SQLException.class, () -> SurvivalItems.purge(broken, name -> true));
    }
  }
}
