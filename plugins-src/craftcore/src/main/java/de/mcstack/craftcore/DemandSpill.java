package de.mcstack.craftcore;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;

/** Demand that could not reach the database at shutdown. Merged into one YAML file, replayed on the next start. */
final class DemandSpill {
  private final File file;

  DemandSpill(File file) { this.file = file; }

  synchronized void save(Map<String, Long> batch) throws Exception {
    Map<String, Long> merged = read();
    batch.forEach((k, v) -> merged.merge(k, v, Long::sum));
    YamlConfiguration out = new YamlConfiguration();
    merged.forEach((k, v) -> { if (v != 0) out.set(k, v); });
    File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
    out.save(tmp);
    if (file.exists() && !file.delete()) throw new java.io.IOException("cannot replace " + file);
    if (!tmp.renameTo(file)) throw new java.io.IOException("cannot write " + file);
  }

  /** Reads the spill and moves it aside ({@code .replayed}); the caller adds the result to the ledger. */
  synchronized Map<String, Long> takeAll() {
    Map<String, Long> out = read();
    if (file.exists()) {
      File done = new File(file.getParentFile(), file.getName() + ".replayed");
      if (done.exists()) done.delete();
      file.renameTo(done);
    }
    return out;
  }

  private Map<String, Long> read() {
    Map<String, Long> out = new HashMap<>();
    if (!file.exists()) return out;
    YamlConfiguration in = YamlConfiguration.loadConfiguration(file);
    for (String key : in.getKeys(false)) { long v = in.getLong(key); if (v != 0) out.put(key, v); }
    return out;
  }
}
