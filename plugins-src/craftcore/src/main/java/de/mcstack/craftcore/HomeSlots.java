package de.mcstack.craftcore;

import java.sql.*;

final class HomeSlots {
  static final long[] DEFAULT_PRICES = {2000, 5000, 12000, 25000};
  static void start(Connection c) throws SQLException {
    try (Statement s=c.createStatement()) { s.execute("CREATE TABLE IF NOT EXISTS home_slots (server VARCHAR(32) NOT NULL, uuid CHAR(36) NOT NULL, slots INT NOT NULL, PRIMARY KEY(server,uuid))"); }
  }
  static int lock(Connection c, String server, String id) throws SQLException {
    try (PreparedStatement s=c.prepareStatement("INSERT IGNORE INTO home_slots(server,uuid,slots) SELECT ?,?,GREATEST(1,COUNT(*)) FROM homes WHERE server=? AND uuid=?")) {
      s.setString(1,server); s.setString(2,id); s.setString(3,server); s.setString(4,id); s.executeUpdate();
    }
    try (PreparedStatement s=c.prepareStatement("SELECT slots FROM home_slots WHERE server=? AND uuid=? FOR UPDATE")) {
      s.setString(1,server); s.setString(2,id); try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("Home-Slots fehlen");return r.getInt(1);}
    }
  }
  static String buy(Connection c,String server,String id,int expected,long price) throws SQLException {
    int slots=lock(c,server,id);
    if(slots!=expected || slots>=5)return "Angebot hat sich geändert. Bitte Menü erneut öffnen.";
    if(price<=0)throw new IllegalArgumentException("Home-Preis muss positiv sein");
    if(!EconomyStore.change(c,id,-price))return "Nicht genug CP.";
    try(PreparedStatement s=c.prepareStatement("UPDATE home_slots SET slots=slots+1 WHERE server=? AND uuid=?")){s.setString(1,server);s.setString(2,id);if(s.executeUpdate()!=1)throw new SQLException("Home-Slot nicht gespeichert");}
    return "Home-Slot gekauft: "+(slots+1)+" Slots für "+Money.format(price);
  }
  static long price(CraftCore core,int current){if(current<1||current>=5)throw new IllegalArgumentException("Home-Slot");return Money.cents(core.getConfig().getDouble("home-slot-prices."+(current+1),DEFAULT_PRICES[current-1]));}
}
