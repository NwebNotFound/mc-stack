package de.mcstack.craftcore;
import java.math.BigDecimal;
import java.math.RoundingMode;
final class Money {
  static long cents(String value) { return new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact(); }
  static long cents(double value) { if (!Double.isFinite(value)) throw new IllegalArgumentException(); return cents(BigDecimal.valueOf(value).toPlainString()); }
  static String format(long cents) { return BigDecimal.valueOf(cents, 2).toPlainString() + " CP"; }
}
