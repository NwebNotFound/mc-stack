package de.mcstack.craftcore;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WithdrawConfirmTest {
  @Test void requiresTwoClicksWithinTenSecondsAndResets() {
    var state=new WithdrawConfirm();
    assertFalse(state.click(1)); assertTrue(state.armed()); assertTrue(state.click(10_000_000_001L));
    assertFalse(state.click(20_000_000_000L)); assertFalse(state.click(30_000_000_001L));
    assertTrue(state.reset()); assertFalse(state.click(30_000_000_002L));
  }
}
