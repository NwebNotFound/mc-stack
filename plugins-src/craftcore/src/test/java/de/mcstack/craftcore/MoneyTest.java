package de.mcstack.craftcore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MoneyTest {
 @Test void exactAmounts() { assertEquals(123, Money.cents("1.23")); assertEquals("1.23 CP", Money.format(123)); assertEquals(0, Money.cents("0")); }
 @Test void rejectsPrecisionOverflowAndNonFinite() { assertThrows(ArithmeticException.class, () -> Money.cents("0.001")); assertThrows(ArithmeticException.class, () -> Money.cents("999999999999999999999")); assertThrows(IllegalArgumentException.class, () -> Money.cents(Double.NaN)); }
}
