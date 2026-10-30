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
      TestRun.Result result = TestRun.decide(false, sender, FAKE1, 100, "off");
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
    TestRun.Result first = TestRun.decide(true, TestRun.Sender.CONSOLE, FAKE1, 25, "k1");
    TestRun.Result second = TestRun.decide(true, TestRun.Sender.RCON, FAKE2.toUpperCase(), 40, "k2");
    assertTrue(first.credit());
    assertEquals(FAKE1, first.uuid());
    assertEquals(25, first.cents());
    assertEquals("OK", first.reply());
    assertTrue(second.credit());
    assertEquals(FAKE2, second.uuid());
    assertFalse(TestRun.decide(true, TestRun.Sender.CONSOLE, REAL, 25, "k3").credit());
    assertFalse(TestRun.decide(true, TestRun.Sender.CONSOLE, "00000000-0000-0000-0000-0000c0de0003", 25, "k4").credit());
    assertFalse(TestRun.decide(true, TestRun.Sender.CONSOLE, "00000000-0000-0000-0000-c0de00010000", 25, "k5").credit());
    assertFalse(TestRun.decide(true, TestRun.Sender.PLAYER, FAKE1, 25, "k6").credit());
    assertFalse(TestRun.decide(true, TestRun.Sender.OTHER, FAKE1, 25, "k7").credit());
    assertFalse(TestRun.decide(true, TestRun.Sender.RCON, FAKE1, 0, "k8").credit());
    assertFalse(TestRun.decide(true, TestRun.Sender.RCON, "c0de0001", 10, "k9").credit());
    assertEquals("ERR INVALID", TestRun.decide(true, TestRun.Sender.CONSOLE, REAL, 25, "k3").reply());
    assertEquals("ERR INVALID", TestRun.decide(true, TestRun.Sender.PLAYER, FAKE1, 25, "k6").reply());
  }

  @Test void creditRepliesPendingUntilTheStoredResult() {
    TestRun.Result accepted = TestRun.decide(true, TestRun.Sender.RCON, FAKE1, 25, "pend");
    TestRun.Dispatch first = TestRun.dispatch(accepted, null);
    assertEquals("PENDING pend", first.line());
    assertTrue(first.start());
    assertEquals("OK", TestRun.dispatch(accepted, "OK").line());
    assertFalse(TestRun.dispatch(accepted, "OK").start());
    assertEquals("ERR INVALID", TestRun.dispatch(accepted, "ERR INVALID").line());
    TestRun.Dispatch refused = TestRun.dispatch(TestRun.decide(false, TestRun.Sender.CONSOLE, FAKE1, 25, "pend"), null);
    assertEquals("ERR DISABLED", refused.line());
    assertFalse(refused.start());
  }

  @Test void oneCallCannotCreditMoreThanOneMillionCents() {
    assertTrue(TestRun.decide(true, TestRun.Sender.CONSOLE, FAKE1, TestRun.MAX_PER_CALL, "cap").credit());
    TestRun.Result over = TestRun.decide(true, TestRun.Sender.RCON, FAKE1, TestRun.MAX_PER_CALL + 1, "over");
    assertFalse(over.credit());
    assertEquals("ERR INVALID", over.reply());
  }

  @Test void repeatedCreditsStopAtTheTotalAndTheSameKeyDoesNotCreditTwice() throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:h2:mem:testruncap;MODE=MySQL")) {
      c.createStatement().execute("CREATE TABLE accounts(uuid VARCHAR(36) PRIMARY KEY, balance BIGINT NOT NULL)");
      TestRun.prepare(c);
      assertTrue(TestRun.apply(c, TestRun.decide(true, TestRun.Sender.RCON, FAKE1, 600_000, "once")));
      assertTrue(TestRun.apply(c, TestRun.decide(true, TestRun.Sender.RCON, FAKE1, 600_000, "once")));
      assertEquals(600_000, balance(c, FAKE1));
      assertFalse(TestRun.apply(c, TestRun.decide(true, TestRun.Sender.RCON, FAKE1, 500_000, "again")));
      assertEquals(600_000, balance(c, FAKE1));
      assertTrue(TestRun.apply(c, TestRun.decide(true, TestRun.Sender.CONSOLE, FAKE1, 400_000, "fill")));
      assertEquals(TestRun.MAX_TOTAL, balance(c, FAKE1));
      assertFalse(TestRun.apply(c, TestRun.decide(true, TestRun.Sender.CONSOLE, FAKE1, 1, "past")));
      assertEquals(TestRun.MAX_TOTAL, balance(c, FAKE1));
      assertEquals(0, balance(c, REAL));
    }
  }

  @Test void applyCreditsOnlyAnAcceptedFakeAccount() throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:h2:mem:testrun;MODE=MySQL")) {
      c.createStatement().execute("CREATE TABLE accounts(uuid VARCHAR(36) PRIMARY KEY, balance BIGINT NOT NULL)");
      TestRun.prepare(c);
      assertTrue(TestRun.apply(c, TestRun.decide(true, TestRun.Sender.RCON, FAKE1, 40, "a1")));
      assertFalse(TestRun.apply(c, TestRun.decide(false, TestRun.Sender.CONSOLE, FAKE2, 40, "a2")));
      assertFalse(TestRun.apply(c, TestRun.decide(true, TestRun.Sender.CONSOLE, REAL, 40, "a3")));
      assertFalse(TestRun.apply(c, TestRun.decide(true, TestRun.Sender.PLAYER, FAKE1, 40, "a4")));
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
