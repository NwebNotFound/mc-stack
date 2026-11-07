package de.mcstack.craftcore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MoneyTest {
 @Test void exactAmounts() { assertEquals(123, Money.cents("1.23")); assertEquals("1.23 CP", Money.format(123)); assertEquals(0, Money.cents("0")); }
 @Test void rejectsPrecisionOverflowAndNonFinite() { assertThrows(ArithmeticException.class, () -> Money.cents("0.001")); assertThrows(ArithmeticException.class, () -> Money.cents("999999999999999999999")); assertThrows(IllegalArgumentException.class, () -> Money.cents(Double.NaN)); }
 @Test void acceptsPlayerFormats() { assertEquals(1200, Money.cents("12")); assertEquals(1250, Money.cents("12.5")); assertEquals(1250, Money.cents("12,50")); assertEquals(50, Money.cents(".5")); assertEquals(1200, Money.cents("12.")); assertEquals(150, Money.cents("1.500")); assertEquals(-500, Money.cents("-5")); assertEquals(1250, Money.cents(12.5)); assertEquals(10000, Money.cents(100.0)); }
 @Test void vaultDoublesRoundToCents() { assertEquals(30, Money.cents(0.1+0.2, java.math.RoundingMode.HALF_EVEN)); assertEquals(31, Money.cents(0.30000000000000004, java.math.RoundingMode.UP)); assertEquals(30, Money.cents(0.30000000000000004, java.math.RoundingMode.DOWN)); assertEquals(-1, Money.cents(-0.001, java.math.RoundingMode.UP)); assertEquals(1250, Money.cents(12.5, java.math.RoundingMode.DOWN)); assertThrows(IllegalArgumentException.class, () -> Money.cents(Double.POSITIVE_INFINITY, java.math.RoundingMode.DOWN)); }
 @Test void vaultRejectsNegativeBeforeRounding() {
  // -0.001 rounded down is 0: it must not become a "successful" deposit (Gatekeeper #170)
  assertThrows(IllegalArgumentException.class, () -> Money.vaultCents(-0.001, false));
  assertThrows(IllegalArgumentException.class, () -> Money.vaultCents(-0.001, true));
  assertThrows(IllegalArgumentException.class, () -> Money.vaultCents(-5, false));
  assertThrows(IllegalArgumentException.class, () -> Money.vaultCents(Double.NaN, false));
  assertEquals(0, Money.vaultCents(0.009, false)); assertEquals(1, Money.vaultCents(0.001, true)); assertEquals(101, Money.vaultCents(1.005, true)); assertEquals(0, Money.vaultCents(0, false));
 }
 @Test void rejectsExponentAndJunkQuickly() {
  long start=System.nanoTime();
  for(String bad:new String[]{"1E10000000","1e5","1E-10000000","12.5.0","12,5,0","","-","abc"," 12","12 ","0x10","Infinity","NaN","1"+"0".repeat(40)}) assertThrows(NumberFormatException.class, () -> Money.cents(bad), bad);
  assertThrows(NumberFormatException.class, () -> Money.cents((String)null));
  assertTrue(System.nanoTime()-start<1_000_000_000L);
 }
}
