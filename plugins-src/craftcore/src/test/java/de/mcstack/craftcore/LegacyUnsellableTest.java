package de.mcstack.craftcore;

import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A stale {@code market.unsellable} default must be cleared once, admin entries must survive. */
class LegacyUnsellableTest {
  @Test void staleDefaultListIsClearedOnce() {
    YamlConfiguration config = new YamlConfiguration();
    config.set("market.unsellable", ResourceSync.LEGACY_UNSELLABLE);
    assertTrue(ResourceSync.clearLegacyUnsellable(config));
    assertTrue(config.getStringList("market.unsellable").isEmpty());
    assertFalse(ResourceSync.clearLegacyUnsellable(config), "die Migration laeuft nur einmal");
  }

  @Test void orderAndCaseOfTheStaleListDoNotMatter() {
    YamlConfiguration config = new YamlConfiguration();
    config.set("market.unsellable", List.of("sand", "DIRT", "cobblestone", "cobbled_deepslate",
      "coarse_dirt", "rooted_dirt", "netherrack", "rotten_flesh", "kelp", "dried_kelp", "gravel"));
    assertTrue(ResourceSync.clearLegacyUnsellable(config));
    assertTrue(config.getStringList("market.unsellable").isEmpty());
  }

  @Test void adminListSurvives() {
    YamlConfiguration config = new YamlConfiguration();
    config.set("market.unsellable", List.of("SAND", "DIRT"));
    assertFalse(ResourceSync.clearLegacyUnsellable(config));
    assertEquals(List.of("SAND", "DIRT"), config.getStringList("market.unsellable"));
  }

  @Test void shippedConfigIsAlreadyEmpty() {
    YamlConfiguration empty = new YamlConfiguration();
    assertFalse(ResourceSync.clearLegacyUnsellable(empty));
    assertFalse(ResourceSync.clearLegacyUnsellable(ArbitrageTest.load("config.yml")));
  }
}
