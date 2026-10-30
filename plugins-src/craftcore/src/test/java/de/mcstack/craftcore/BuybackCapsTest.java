package de.mcstack.craftcore;

import java.sql.*;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuybackCapsTest {
  @Test void shippedDefaultIsOffAndHasNoEffect() throws Exception {
    var cfg = ArbitrageTest.load("config.yml");
    assertFalse(BuybackCaps.enabled(cfg));
    assertEquals(900000, BuybackCaps.capCents(cfg, Material.BEACON, 900000));
    try (Connection c = DriverManager.getConnection("jdbc:h2:mem:cap0_" + UUID.randomUUID() + ";MODE=MySQL")) {
      for (int i = 0; i < 5; i++) assertTrue(BuybackCaps.reserve(c, cfg, "u:p", Material.ELYTRA, "2026-10-30"));
    }
  }
  @Test void enabledCapsPriceAndOnePerDayWithoutLocking() throws Exception {
    var cfg = ArbitrageTest.load("config.yml"); cfg.set("market.buyback_caps.enabled", true);
    for (Material m : new Material[]{Material.BEACON, Material.ELYTRA, Material.TOTEM_OF_UNDYING}) {
      assertEquals(150000, BuybackCaps.capCents(cfg, m, 900000));
      assertEquals(20000, BuybackCaps.capCents(cfg, m, 20000));
      assertEquals(1, BuybackCaps.perDay(cfg, m));
    }
    assertEquals(500, BuybackCaps.capCents(cfg, Material.DIAMOND, 500));
    try (Connection c = DriverManager.getConnection("jdbc:h2:mem:cap1_" + UUID.randomUUID() + ";MODE=MySQL")) {
      assertTrue(BuybackCaps.reserve(c, cfg, "u:p", Material.BEACON, "2026-10-30"));
      assertFalse(BuybackCaps.reserve(c, cfg, "u:p", Material.BEACON, "2026-10-30"));
      assertTrue(BuybackCaps.reserve(c, cfg, "u:p", Material.BEACON, "2026-10-31"));
      assertTrue(BuybackCaps.reserve(c, cfg, "u:q", Material.BEACON, "2026-10-30"));
      assertTrue(BuybackCaps.reserve(c, cfg, "u:p", Material.ELYTRA, "2026-10-30"));
      assertTrue(BuybackCaps.reserve(c, cfg, "u:p", Material.DIAMOND, "2026-10-30"));
      assertTrue(BuybackCaps.reserve(c, cfg, "u:p", Material.DIAMOND, "2026-10-30"));
    }
  }
}
