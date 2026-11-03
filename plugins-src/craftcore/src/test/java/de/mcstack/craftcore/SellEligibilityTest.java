package de.mcstack.craftcore;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Paper's item registry is not available in unit tests, so this locks the flags {@code Market.eligible} checks after damage and enchants are stripped. */
class SellEligibilityTest {
  @Test void unbreakableOrAttributeModifierRejectsTheTool() {
    assertTrue(Market.gearResidueBlocks(true, false, false, false, false, false), "unbreakable");
    assertTrue(Market.gearResidueBlocks(false, false, false, true, false, false), "attribute modifier");
    assertFalse(Market.gearResidueBlocks(false, false, false, false, false, false), "plain tool");
  }

  @Test void missingTableIsOnlyTheSqlStateOrMysqlCode() {
    assertTrue(SurvivalItems.missingTable(sql("42S02", 0, "Table missing")));
    assertTrue(SurvivalItems.missingTable(sql("HY000", 1146, "no text")));
    assertFalse(SurvivalItems.missingTable(sql("42S04", 42104, "Table \"MARKET_CATALOG\" not found")));
    assertFalse(SurvivalItems.missingTable(sql("42S22", 42122, "Column \"ITEM\" not found")));
    assertFalse(SurvivalItems.missingTable(null));
  }

  private static SQLException sql(String state, int code, String message) {
    return new SQLException(message, state, code);
  }
}
