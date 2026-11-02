package de.mcstack.craftcore;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Paper ueberschreibt Dateien im Datenordner nicht (saveDefaultConfig/saveResource).
 * config.yml: fehlende Keys aus dem Jar ergaenzen; vorhandene Keys nur ueberschreiben, wenn
 * {@code config-version} im Jar hoeher ist. prices-base.yml: komplett ersetzen, wenn {@code version}
 * im Jar hoeher ist. Vor jeder Aenderung wird eine .bak-Kopie angelegt.
 */
final class ResourceSync {
  private ResourceSync() {}

  /** @return true, wenn current geaendert wurde. */
  static boolean mergeConfig(YamlConfiguration current, YamlConfiguration defaults) {
    boolean bump = defaults.getInt("config-version", 0) > current.getInt("config-version", 0);
    boolean changed = false;
    for (String key : defaults.getKeys(true)) {
      if (defaults.isConfigurationSection(key)) {
        if (!current.contains(key)) { current.createSection(key); changed = true; }
        continue;
      }
      if (!current.contains(key) || (bump && !java.util.Objects.equals(current.get(key), defaults.get(key)))) {
        if (current.isConfigurationSection(key) && !bump) continue;
        current.set(key, defaults.get(key)); changed = true;
      }
    }
    return changed;
  }

  /**
   * Older builds shipped this list as the {@code market.unsellable} default, so dirt, sand, gravel,
   * cobblestone and stone stayed unsellable after the design changed. {@link #mergeConfig} never
   * removes a value without a config-version bump, so the stale list is cleared once here.
   */
  static final List<String> LEGACY_UNSELLABLE = List.of(
      "COBBLESTONE", "COBBLED_DEEPSLATE", "DIRT", "COARSE_DIRT", "ROOTED_DIRT",
      "NETHERRACK", "ROTTEN_FLESH", "KELP", "DRIED_KELP", "GRAVEL", "SAND");

  /** @return true when the legacy default was still in place and has just been removed. */
  static boolean clearLegacyUnsellable(YamlConfiguration current) {
    if (current.getBoolean("migrations.legacy-unsellable-cleared", false)) return false;
    List<String> configured = current.getStringList("market.unsellable").stream()
        .map(raw -> raw == null ? "" : raw.toUpperCase(java.util.Locale.ROOT)).toList();
    if (configured.size() != LEGACY_UNSELLABLE.size()
        || !new java.util.HashSet<>(configured).equals(new java.util.HashSet<>(LEGACY_UNSELLABLE))) return false;
    current.set("market.unsellable", List.of());
    current.set("migrations.legacy-unsellable-cleared", true);
    return true;
  }

  static boolean syncConfig(File file, String jarYaml, Logger log) throws IOException {
    if (!file.exists()) { Files.createDirectories(file.toPath().getParent()); Files.writeString(file.toPath(), jarYaml, StandardCharsets.UTF_8); return true; }
    YamlConfiguration current = YamlConfiguration.loadConfiguration(file), defaults = new YamlConfiguration();
    try { defaults.loadFromString(jarYaml); } catch (org.bukkit.configuration.InvalidConfigurationException e) { throw new IOException(e); }
    int from = current.getInt("config-version", 0), to = defaults.getInt("config-version", 0);
    boolean merged = mergeConfig(current, defaults);
    boolean migrated = clearLegacyUnsellable(current);
    if (!merged && !migrated) return false;
    backup(file);
    current.save(file);
    if (migrated) log.info("config.yml: alte market.unsellable-Standardliste entfernt (Erde, Sand, Kies und Stein sind jetzt verkaufbar)");
    log.info("config.yml aktualisiert (config-version " + from + " -> " + Math.max(from, to) + ", Backup: " + file.getName() + ".bak)");
    return true;
  }

  static boolean syncPrices(File file, String jarYaml, Logger log) throws IOException {
    if (!file.exists()) { Files.createDirectories(file.toPath().getParent()); Files.writeString(file.toPath(), jarYaml, StandardCharsets.UTF_8); return true; }
    YamlConfiguration jar = new YamlConfiguration();
    try { jar.loadFromString(jarYaml); } catch (org.bukkit.configuration.InvalidConfigurationException e) { throw new IOException(e); }
    int have = YamlConfiguration.loadConfiguration(file).getInt("version", 0), want = jar.getInt("version", 0);
    if (want <= have) return false;
    backup(file);
    Files.writeString(file.toPath(), jarYaml, StandardCharsets.UTF_8);
    log.info("prices-base.yml ersetzt (version " + have + " -> " + want + ", Backup: " + file.getName() + ".bak)");
    return true;
  }

  static void backup(File file) throws IOException {
    Files.copy(file.toPath(), file.toPath().resolveSibling(file.getName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
  }

  static String resource(String name) throws IOException {
    try (InputStream in = ResourceSync.class.getResourceAsStream("/" + name)) {
      if (in == null) throw new FileNotFoundException(name);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  static void sync(CraftCore core) {
    try {
      syncConfig(new File(core.getDataFolder(), "config.yml"), resource("config.yml"), core.getLogger());
      syncPrices(new File(core.getDataFolder(), "prices-base.yml"), resource("prices-base.yml"), core.getLogger());
      core.reloadConfig();
    } catch (IOException e) { core.getLogger().warning("Ressourcen-Abgleich fehlgeschlagen, alte Dateien bleiben: " + e.getMessage()); }
  }
}
