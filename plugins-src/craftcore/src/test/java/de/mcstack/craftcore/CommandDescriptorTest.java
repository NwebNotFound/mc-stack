package de.mcstack.craftcore;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CommandDescriptorTest {
 @Test void playerAliasesAndAdminPermissionsAreRegisteredWithoutOverridingVanilla() {
  var config=YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8));
  assertTrue(config.getStringList("commands.shop.aliases").contains("markt"));
  assertTrue(config.getStringList("commands.balance.aliases").containsAll(java.util.List.of("bal","geld")));
  assertTrue(config.getStringList("commands.sidebar.aliases").contains("sb"));
  assertEquals("craftcore.admin",config.getString("commands.cc.permission"));
  assertEquals("craftcore.portal.admin",config.getString("commands.ccportal.permission"));
  assertEquals("craftcore.launch.admin",config.getString("commands.cclaunch.permission"));
  assertFalse(config.contains("commands.scoreboard"));
 }
}
