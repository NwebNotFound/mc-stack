package de.mcstack.craftcore;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ResourceSyncTest {
  static final Logger LOG = Logger.getLogger("test");
  @TempDir Path dir;

  @Test void configAddsMissingKeysButKeepsExistingWithoutVersionBump() throws Exception {
    Path f = dir.resolve("config.yml");
    Files.writeString(f, "config-version: 2\nmarket:\n  sell-factor: 0.6\n  own-key: 7\n");
    assertTrue(ResourceSync.syncConfig(f.toFile(), "config-version: 2\nmarket:\n  sell-factor: 0.75\n  max-sell-ratio: 0.5\n  lock-fallback: {}\n", LOG));
    var c = YamlConfiguration.loadConfiguration(f.toFile());
    assertEquals(0.6, c.getDouble("market.sell-factor"));
    assertEquals(0.5, c.getDouble("market.max-sell-ratio"));
    assertEquals(7, c.getInt("market.own-key"));
    assertTrue(c.isConfigurationSection("market.lock-fallback"));
    assertTrue(Files.exists(dir.resolve("config.yml.bak")));
    assertFalse(ResourceSync.syncConfig(f.toFile(), "config-version: 2\nmarket:\n  sell-factor: 0.75\n  max-sell-ratio: 0.5\n  lock-fallback: {}\n", LOG));
  }
  @Test void configVersionBumpOverwritesExistingKeysAndKeepsBackup() throws Exception {
    Path f = dir.resolve("config.yml");
    String old = "market:\n  sell-factor: 0.25\n  own-key: 7\n";
    Files.writeString(f, old);
    assertTrue(ResourceSync.syncConfig(f.toFile(), ResourceSync.resource("config.yml"), LOG));
    var c = YamlConfiguration.loadConfiguration(f.toFile());
    assertEquals(0.75, c.getDouble("market.sell-factor"));
    assertEquals(0.5, c.getDouble("market.max-sell-ratio"));
    assertEquals(2, c.getInt("config-version"));
    assertEquals(7, c.getInt("market.own-key"));
    assertFalse(c.getBoolean("market.buyback_caps.enabled", true));
    assertEquals(old, Files.readString(dir.resolve("config.yml.bak")));
  }
  @Test void pricesReplacedOnlyWhenJarVersionIsNewer() throws Exception {
    Path f = dir.resolve("prices-base.yml");
    String old = "base:\n  DIAMOND: 100\n";
    Files.writeString(f, old);
    String jar = ResourceSync.resource("prices-base.yml");
    assertTrue(ResourceSync.syncPrices(f.toFile(), jar, LOG));
    assertEquals(jar, Files.readString(f, StandardCharsets.UTF_8));
    assertEquals(old, Files.readString(dir.resolve("prices-base.yml.bak")));
    assertEquals(400, YamlConfiguration.loadConfiguration(f.toFile()).getDouble("base.DIAMOND"));
    Files.writeString(f, "version: 2\nbase:\n  DIAMOND: 123\n");
    assertFalse(ResourceSync.syncPrices(f.toFile(), jar, LOG));
    Files.writeString(f, "version: 3\nbase:\n  DIAMOND: 123\n");
    assertFalse(ResourceSync.syncPrices(f.toFile(), jar, LOG));
    assertEquals(123, YamlConfiguration.loadConfiguration(f.toFile()).getDouble("base.DIAMOND"));
  }
  @Test void missingFilesAreCreatedFromJar() throws Exception {
    Path f = dir.resolve("sub/prices-base.yml");
    assertTrue(ResourceSync.syncPrices(f.toFile(), ResourceSync.resource("prices-base.yml"), LOG));
    assertEquals(2, YamlConfiguration.loadConfiguration(f.toFile()).getInt("version"));
  }
  @Test void umlautsSurviveCompiledMessagesAndConfigMerge() throws Exception {
    assertEquals("Nur f\u00fcr Spieler.", CraftCore.PLAYER_ONLY_MESSAGE);
    Path f = dir.resolve("umlaut.yml");
    String old = "config-version: 1\nmessage: 'F\u00fcr Gr\u00f6\u00dfe'\n";
    Files.writeString(f, old, StandardCharsets.UTF_8);
    assertTrue(ResourceSync.syncConfig(f.toFile(), "config-version: 1\nadded: 'sch\u00f6n'\n", LOG));
    YamlConfiguration reloaded = YamlConfiguration.loadConfiguration(f.toFile());
    assertEquals("F\u00fcr Gr\u00f6\u00dfe", reloaded.getString("message"));
    assertEquals("sch\u00f6n", reloaded.getString("added"));
    assertEquals(old, Files.readString(dir.resolve("umlaut.yml.bak"), StandardCharsets.UTF_8));
  }
}
