package de.mcstack.craftcore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Journal of demand batches that could not reach the database at shutdown. Each save creates a <em>new</em> file in
 * {@code dir} (never replaces or deletes one). Replay applies a file's batches through an idempotent writer first and
 * only then renames the file to {@code .applied}; a failed rename is harmless because the batch ids are already
 * recorded and a second replay skips them. Files are only created after an abnormal shutdown, so they stay few.
 */
final class DemandSpill {
  interface Apply { void apply(DemandFlusher.Batch batch) throws Exception; }

  private final File dir;

  DemandSpill(File dir) { this.dir = dir; }

  synchronized void save(List<DemandFlusher.Batch> batches) throws IOException {
    if (batches.isEmpty()) return;
    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
    YamlConfiguration out = new YamlConfiguration();
    for (DemandFlusher.Batch b : batches) b.delta().forEach((item, v) -> out.set(b.id() + "." + item, v));
    String name = "demand-" + System.currentTimeMillis() + "-" + batches.get(0).id();
    File tmp = new File(dir, name + ".tmp");
    out.save(tmp);
    // ATOMIC_MOVE onto a fresh name: a crash leaves either nothing or a complete .yml, never a half file.
    Files.move(tmp.toPath(), new File(dir, name + ".yml").toPath(), StandardCopyOption.ATOMIC_MOVE);
  }

  /** Applies every pending journal file; returns the number of batches applied or skipped as already applied. */
  synchronized int replay(Apply apply) throws Exception {
    File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
    if (files == null) return 0;
    Arrays.sort(files);
    int count = 0;
    for (File f : files) {
      for (DemandFlusher.Batch b : read(f)) { apply.apply(b); count++; }
      // Only after every batch of the file is committed. Rename failure: next start re-applies, ids skip.
      try { Files.move(f.toPath(), new File(dir, f.getName() + ".applied").toPath(), StandardCopyOption.ATOMIC_MOVE); }
      catch (IOException ignored) {}
    }
    return count;
  }

  static List<DemandFlusher.Batch> read(File f) {
    YamlConfiguration in = YamlConfiguration.loadConfiguration(f);
    List<DemandFlusher.Batch> out = new ArrayList<>();
    for (String id : in.getKeys(false)) {
      ConfigurationSection sec = in.getConfigurationSection(id);
      if (sec == null) continue;
      Map<String, Long> delta = new HashMap<>();
      for (String item : sec.getKeys(false)) { long v = sec.getLong(item); if (v != 0) delta.put(item, v); }
      if (!delta.isEmpty()) out.add(new DemandFlusher.Batch(id, delta));
    }
    return out;
  }
}
