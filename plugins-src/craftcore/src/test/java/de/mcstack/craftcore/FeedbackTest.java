package de.mcstack.craftcore;

import java.nio.file.*;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackTest {
  @Test void lineIsOnlyTheGermanClickableLink() {
    var line = Feedback.line();
    assertEquals("Feedback & Bugs: hier klicken", PlainTextComponentSerializer.plainText().serialize(line));
    assertTrue(line.children().isEmpty());
    ClickEvent<?> click = line.clickEvent();
    assertEquals(ClickEvent.Action.OPEN_URL, click.action());
    assertEquals("https://nwebnotfound.dev/feedback", ((ClickEvent.Payload.Text) click.payload()).value());
  }
  @Test void commandIsRegisteredForLobbyAndSurvivalPlugin() throws Exception {
    String yml = Files.readString(Path.of("src/main/resources/plugin.yml"));
    assertTrue(yml.contains("\n  feedback:\n"));
    String core = Files.readString(Path.of("src/main/java/de/mcstack/craftcore/CraftCore.java"));
    assertTrue(core.contains("case \"feedback\": p.sendMessage(Feedback.line()); break;"));
  }
}
