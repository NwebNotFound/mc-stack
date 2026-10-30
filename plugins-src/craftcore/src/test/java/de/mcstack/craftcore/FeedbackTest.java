package de.mcstack.craftcore;

import java.nio.file.*;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackTest {
  static String clickUrl(net.kyori.adventure.text.Component c) {
    ClickEvent<?> click = c.clickEvent();
    assertEquals(ClickEvent.Action.OPEN_URL, click.action());
    return ((ClickEvent.Payload.Text) click.payload()).value();
  }
  @Test void lineIsOnlyTheGermanClickableLink() {
    var line = Feedback.line();
    assertEquals("Feedback geben: hier klicken", PlainTextComponentSerializer.plainText().serialize(line));
    assertTrue(line.children().isEmpty());
    assertEquals("https://nwebnotfound.dev/feedback", clickUrl(line));
  }
  @Test void urlComesFromConfigWithDefault() throws Exception {
    var jar = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/config.yml").toFile());
    assertEquals("https://nwebnotfound.dev/feedback", jar.getString("feedback-url"));
    assertEquals("https://nwebnotfound.dev/feedback", Feedback.url(jar));
    var cfg = new YamlConfiguration();
    assertEquals(Feedback.URL, Feedback.url(cfg));
    assertEquals(Feedback.URL, Feedback.url(null));
    cfg.set("feedback-url", "javascript:alert(1)");
    assertEquals(Feedback.URL, Feedback.url(cfg));
    cfg.set("feedback-url", "https://example.org/form");
    assertEquals("https://example.org/form", clickUrl(Feedback.line(Feedback.url(cfg))));
  }
  @Test void commandIsRegisteredWithoutPermission() throws Exception {
    var yml = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/plugin.yml").toFile());
    assertTrue(yml.isConfigurationSection("commands.feedback"));
    assertNull(yml.getString("commands.feedback.permission"));
    String core = Files.readString(Path.of("src/main/java/de/mcstack/craftcore/CraftCore.java"));
    assertTrue(core.contains("case \"feedback\": p.sendMessage(Feedback.line(Feedback.url(getConfig()))); break;"));
  }
}