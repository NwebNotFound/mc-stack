package de.mcstack.craftcore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;
final class Money {
  // Plain decimal only (no exponent): BigDecimal("1E10000000").setScale(2) would compute a huge power of ten on the caller's thread.
  private static final Pattern PLAIN=Pattern.compile("[+-]?(\\d{1,30}([.,]\\d{0,30})?|[.,]\\d{1,30})");
  static long cents(String value) { if(value==null || !PLAIN.matcher(value).matches()) throw new NumberFormatException("Ungueltiger Betrag"); return exact(new BigDecimal(value.replace(',','.'))); }
  static long cents(double value) { if (!Double.isFinite(value)) throw new IllegalArgumentException(); return exact(BigDecimal.valueOf(value)); }
  private static long exact(BigDecimal value) { return value.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact(); }
  static String format(long cents) { return BigDecimal.valueOf(cents, 2).toPlainString() + " CP"; }
}
