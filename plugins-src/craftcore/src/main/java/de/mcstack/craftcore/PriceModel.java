package de.mcstack.craftcore;

final class PriceModel {
  static long cappedSell(long sell,long buy) { return Math.min(sell, (buy / 100)*35 + (buy % 100)*35/100); }
  static long withFloor(long cents, double floor) { return Math.max(cents, (long)Math.ceil(Math.max(0, floor)*100)); }
  static long factorQuote(double current,double factor) { return Math.max(0,(long)Math.floor(current*100*Math.max(0,factor))); }
  static double diminishing(int count,int tier1,double factor1,int tier2,double factor2) { return count>=tier2?factor2:count>=tier1?factor1:1.0; }
  static long quote(double current,double spread,boolean buy) {
    long sell=Math.max(1,(long)Math.floor(current*100*(1-spread)));
    return buy ? Math.max(sell+1,(long)Math.ceil(current*100*(1+spread))) : sell;
  }
  static double recover(double price, double base, double min, double max, double rate, long elapsedMillis) {
    double value = base + (price - base) * Math.exp(-Math.max(0, rate) * Math.max(0, elapsedMillis) / 3600000.0);
    return Math.max(min, Math.min(max, value));
  }
  static double move(double price, double pressure, boolean buy, double min, double max) {
    return Math.max(min, Math.min(max, price + (buy ? pressure : -pressure)));
  }
}
