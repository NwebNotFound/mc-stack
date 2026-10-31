package de.mcstack.craftcore;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

class SellMenuSwitchTest {
 @Test void offByDefault() { assertFalse(Market.sellMenuEnabled(new MemoryConfiguration())); }
 @Test void onlyOnWhenExplicitlyEnabled() {
  MemoryConfiguration c=new MemoryConfiguration(); c.set("sell-menu.enabled", true); assertTrue(Market.sellMenuEnabled(c));
  c.set("sell-menu.enabled", false); assertFalse(Market.sellMenuEnabled(c));
 }
 @Test void bundledConfigShipsOff() throws Exception {
  try(var in=Market.class.getResourceAsStream("/config.yml"); var r=new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
   assertFalse(Market.sellMenuEnabled(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(r)));
  }
 }
}