package de.mcstack.craftcore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestRunTest {
  static final String FAKE1 = "00000000-0000-0000-0000-0000c0de0001";
  static final String FAKE2 = "00000000-0000-0000-0000-0000c0de0002";
  static final String REAL = "00000000-0000-0000-0000-000000000099";

  @Test void disabledHookRefusesConsoleAndRcon() {
    for (TestRun.Sender sender : new TestRun.Sender[] {TestRun.Sender.CONSOLE, TestRun.Sender.RCON}) {
      TestRun.Result result = TestRun.decide(false, sender, FAKE1, 100);
      assertFalse(result.credit());
      assertEquals("ERR DISABLED", result.reply());
    }
  }

  @Test void missingOrFalseConfigStaysOff() {
    assertFalse(TestRun.enabled(null));
    assertFalse(TestRun.enabled(Boolean.FALSE));
    assertTrue(TestRun.enabled(Boolean.TRUE));
  }

  @Test void defaultConfigKeepsTestrunDisabled() throws Exception {
    String yaml = Files.readString(Path.of("src/main/resources/config.yml"));
    int section = yaml.indexOf("\ntestrun:\n");
    assertTrue(section > 0);
    String block = yaml.substring(section, yaml.indexOf("\n", yaml.indexOf("enabled:", section)));
    assertTrue(block.contains("enabled: false"));
    String commands = Files.readString(Path.of("src/main/resources/plugin.yml"));
    assertTrue(commands.contains("\n  testrun:\n"));
  }

  @Test void enabledCreditsOnlyTheFakeAccountsFromConsoleOrRcon() {
    TestRun.Result first = TestRun.decide(true, TestRun.Sender.CONSOLE, FAKE1, 25);
    TestRun.Result second = TestRun.decide(true, TestRun.Sender.RCON, FAKE2.toUpperCase(), 40);
    assertTrue(first.credit());
    assertEquals(FAKE1, first.uuid());
    assertEquals(25, first.cents());
    assertEquals("OK", first.reply());
    assertTrue(second.credit());
    assertEquals(FAKE2, second.uuid());
    assertFalse(TestRun.decide(true, TestRun.Sender.CONSOLE, REAL, 25).credit());
    assertFalse(TestRun.decide(true, TestRun.Sender.CONSOLE, "00000000-0000-0000-0000-0000c0de0003", 25).credit());
    assertFalse(TestRun.decide(true, TestRun.Sender.CONSOLE, "00000000-0000-0000-0000-c0de00010000", 25).credit());
    assertFalse(TestRun.decide(true, TestRun.Sender.PLAYER, FAKE1, 25).credit());
    assertFalse(TestRun.decide(true, TestRun.Sender.OTHER, FAKE1, 25).credit());
    assertFalse(TestRun.decide(true, TestRun.Sender.RCON, FAKE1, 0).credit());
    assertFalse(TestRun.decide(true, TestRun.Sender.RCON, "c0de0001", 10).credit());
    assertEquals("ERR INVALID", TestRun.decide(true, TestRun.Sender.CONSOLE, REAL, 25).reply());
    assertEquals("ERR INVALID", TestRun.decide(true, TestRun.Sender.PLAYER, FAKE1, 25).reply());
  }

  @Test void applyCreditsOnlyAnAcceptedFakeAccount() throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:h2:mem:testrun;MODE=MySQL")) {
      c.createStatement().execute("CREATE TABLE accounts(uuid VARCHAR(36) PRIMARY KEY, balance BIGINT NOT NULL)");
      assertTrue(TestRun.apply(c, TestRun.decide(true, TestRun.Sender.RCON, FAKE1, 40)));
      assertFalse(TestRun.apply(c, TestRun.decide(false, TestRun.Sender.CONSOLE, FAKE2, 40)));
      assertFalse(TestRun.apply(c, TestRun.decide(true, TestRun.Sender.CONSOLE, REAL, 40)));
      assertFalse(TestRun.apply(c, TestRun.decide(true, TestRun.Sender.PLAYER, FAKE1, 40)));
      assertEquals(40, balance(c, FAKE1));
      assertEquals(0, balance(c, FAKE2));
      assertEquals(0, balance(c, REAL));
    }
  }

  static long balance(Connection c, String uuid) throws Exception {
    try (PreparedStatement s = c.prepareStatement("SELECT balance FROM accounts WHERE uuid=?")) {
      s.setString(1, uuid);
      try (ResultSet r = s.executeQuery()) { return r.next() ? r.getLong(1) : 0; }
    }
  }
}
