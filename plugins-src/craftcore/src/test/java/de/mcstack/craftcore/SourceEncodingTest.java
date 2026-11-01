package de.mcstack.craftcore;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SourceEncodingTest {
  @Test void javaSourcesContainNoMojibakeMarker() throws Exception {
    try (var paths = Files.walk(Path.of("src/main/java"))) {
      for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
        assertFalse(Files.readString(path, StandardCharsets.UTF_8).contains("\u00c3"),
            () -> "Mojibake marker in " + path);
      }
    }
  }
}
