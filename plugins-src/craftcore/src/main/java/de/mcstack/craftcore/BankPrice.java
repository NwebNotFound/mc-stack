package de.mcstack.craftcore;

final class BankPrice {
  static double recover(double factor,long elapsedMillis) { return 1+(factor-1)*Math.exp(-0.10*Math.max(0,elapsedMillis)/3600000.0); }
  static double moveSell(double factor) { return Math.max(0.25,factor*Math.exp(-0.01)); }
  static double moveBuy(double factor) { return Math.min(2.0,1+(factor-1)+0.01); }
  static long minimumBuy(long sell) { return Math.max(sell+1,(long)Math.ceil(sell*1.10)); }
  private BankPrice() {}
}
