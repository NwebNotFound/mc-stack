package de.mcstack.craftcore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Satchels live in the database so create and additem apply without a deploy. */
final class SatchelStore {
  record Satchel(String name, long priceCents, boolean enabled, int pitySr, int pitySsr) {}
  record Item(int id, String rarity, int weight, byte[] bytes) {}

  private SatchelStore() {}

  static void prepare(Connection c) throws SQLException {
    try (Statement s = c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS satchels (name VARCHAR(32) PRIMARY KEY, price_cents BIGINT NOT NULL, enabled BOOLEAN NOT NULL, pity_sr INT NOT NULL, pity_ssr INT NOT NULL)");
      s.execute("CREATE TABLE IF NOT EXISTS satchel_items (id BIGINT AUTO_INCREMENT PRIMARY KEY, satchel VARCHAR(32) NOT NULL, rarity VARCHAR(16) NOT NULL, weight INT NOT NULL, item_bytes BLOB NOT NULL)");
      s.execute("CREATE TABLE IF NOT EXISTS satchel_pity (player VARCHAR(36) NOT NULL, satchel VARCHAR(32) NOT NULL, since_sr INT NOT NULL, since_ssr INT NOT NULL, PRIMARY KEY(player, satchel))");
    }
  }

  static void create(Connection c, String name, long priceCents, int pitySr, int pitySsr) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("INSERT INTO satchels(name,price_cents,enabled,pity_sr,pity_ssr) VALUES(?,?,TRUE,?,?)")) {
      s.setString(1, name); s.setLong(2, priceCents); s.setInt(3, pitySr); s.setInt(4, pitySsr); s.executeUpdate();
    }
  }

  static void addItem(Connection c, String name, String rarity, int weight, byte[] bytes) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("INSERT INTO satchel_items(satchel,rarity,weight,item_bytes) VALUES(?,?,?,?)")) {
      s.setString(1, name); s.setString(2, rarity); s.setInt(3, weight); s.setBytes(4, bytes); s.executeUpdate();
    }
  }

  static Optional<Satchel> find(Connection c, String name) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("SELECT name,price_cents,enabled,pity_sr,pity_ssr FROM satchels WHERE name=?")) {
      s.setString(1, name);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) return Optional.empty();
        return Optional.of(new Satchel(r.getString(1), r.getLong(2), r.getBoolean(3), r.getInt(4), r.getInt(5)));
      }
    }
  }

  static List<Satchel> enabled(Connection c) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("SELECT name,price_cents,enabled,pity_sr,pity_ssr FROM satchels WHERE enabled=TRUE ORDER BY name");
         ResultSet r = s.executeQuery()) {
      List<Satchel> list = new ArrayList<>();
      while (r.next()) list.add(new Satchel(r.getString(1), r.getLong(2), r.getBoolean(3), r.getInt(4), r.getInt(5)));
      return list;
    }
  }

  static List<Item> items(Connection c, String name) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("SELECT id,rarity,weight,item_bytes FROM satchel_items WHERE satchel=? ORDER BY id")) {
      s.setString(1, name);
      try (ResultSet r = s.executeQuery()) {
        List<Item> list = new ArrayList<>();
        while (r.next()) list.add(new Item(r.getInt(1), r.getString(2), r.getInt(3), r.getBytes(4)));
        return list;
      }
    }
  }

  static SatchelOdds.Pity lockPity(Connection c, String player, String satchel) throws SQLException {
    try (PreparedStatement insert = c.prepareStatement("INSERT INTO satchel_pity(player,satchel,since_sr,since_ssr) SELECT ?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM satchel_pity WHERE player=? AND satchel=?)")) {
      insert.setString(1, player); insert.setString(2, satchel); insert.setInt(3, 0); insert.setInt(4, 0); insert.setString(5, player); insert.setString(6, satchel);
      insert.executeUpdate();
    }
    try (PreparedStatement s = c.prepareStatement("SELECT since_sr,since_ssr FROM satchel_pity WHERE player=? AND satchel=? FOR UPDATE")) {
      s.setString(1, player); s.setString(2, satchel);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new SQLException("pity");
        return new SatchelOdds.Pity(r.getInt(1), r.getInt(2));
      }
    }
  }

  static void savePity(Connection c, String player, String satchel, SatchelOdds.Pity pity) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("UPDATE satchel_pity SET since_sr=?,since_ssr=? WHERE player=? AND satchel=?")) {
      s.setInt(1, pity.sinceSr()); s.setInt(2, pity.sinceSsr()); s.setString(3, player); s.setString(4, satchel); s.executeUpdate();
    }
  }
}
