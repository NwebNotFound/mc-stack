package de.mcstack.craftcore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/** Satchels live in the database so create and additem apply without a deploy. */
final class SatchelStore {
  record Satchel(String name, long priceCents, boolean enabled, int pitySr, int pitySsr) {}
  record Item(int id, String rarity, int weight, byte[] bytes) {}
  record Claim(String id, String rarity, byte[] bytes, long capCents) {}
  record Buy(boolean ok, String message, List<Claim> claims) {
    static Buy fail(String message) { return new Buy(false, message, List.of()); }
  }

  private SatchelStore() {}

  static void prepare(Connection c) throws SQLException {
    try (Statement s = c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS satchels (name VARCHAR(32) PRIMARY KEY, price_cents BIGINT NOT NULL, enabled BOOLEAN NOT NULL, pity_sr INT NOT NULL, pity_ssr INT NOT NULL)");
      s.execute("CREATE TABLE IF NOT EXISTS satchel_items (id BIGINT AUTO_INCREMENT PRIMARY KEY, satchel VARCHAR(32) NOT NULL, rarity VARCHAR(16) NOT NULL, weight INT NOT NULL, item_bytes BLOB NOT NULL)");
      s.execute("CREATE TABLE IF NOT EXISTS satchel_pity (player VARCHAR(36) NOT NULL, satchel VARCHAR(32) NOT NULL, since_sr INT NOT NULL, since_ssr INT NOT NULL, PRIMARY KEY(player, satchel))");
      s.execute("CREATE TABLE IF NOT EXISTS satchel_claims (id CHAR(36) PRIMARY KEY, player VARCHAR(36) NOT NULL, rarity VARCHAR(16) NOT NULL, item_bytes BLOB NOT NULL, cap_cents BIGINT NOT NULL, status VARCHAR(16) NOT NULL)");
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

  /** Charges CP, rolls, and stores each reward as an open claim. Safe if the player is already gone. */
  static Buy buy(Connection c, String player, String name, int count, Random rng) throws SQLException {
    if (count != 1 && count != 10) return Buy.fail("Ein Zug oder zehn.");
    Optional<Satchel> found = find(c, name);
    if (found.isEmpty() || !found.get().enabled()) return Buy.fail("Satchel ist nicht offen.");
    List<Item> items = items(c, name);
    if (items.isEmpty()) return Buy.fail("Dieser Satchel hat noch keine Items.");
    Satchel satchel = found.get();
    long cost = Math.multiplyExact(satchel.priceCents(), count);
    if (!EconomyStore.change(c, player, -cost)) return Buy.fail("Nicht genug CP.");
    List<SatchelOdds.Entry> entries = new ArrayList<>();
    for (Item item : items) {
      SatchelOdds.Rarity rarity = SatchelOdds.Rarity.parse(item.rarity());
      if (rarity != null && item.weight() > 0) entries.add(new SatchelOdds.Entry(item.id(), rarity, item.weight()));
    }
    if (entries.isEmpty()) {
      EconomyStore.change(c, player, cost);
      return Buy.fail("Dieser Satchel hat noch keine Items.");
    }
    SatchelOdds.Pity pity = lockPity(c, player, name);
    List<SatchelOdds.Outcome> rolls = SatchelOdds.pull(entries, pity, count, satchel.pitySr(), satchel.pitySsr(), rng);
    savePity(c, player, name, rolls.get(rolls.size() - 1).pity());
    List<Claim> claims = new ArrayList<>();
    for (SatchelOdds.Outcome roll : rolls) {
      Item item = items.stream().filter(row -> row.id() == roll.entry().id()).findFirst().orElseThrow();
      String id = UUID.randomUUID().toString();
      insertClaim(c, id, player, item.rarity(), item.bytes(), satchel.priceCents());
      claims.add(new Claim(id, item.rarity(), item.bytes(), satchel.priceCents()));
    }
    return new Buy(true, "", List.copyOf(claims));
  }

  static void insertClaim(Connection c, String id, String player, String rarity, byte[] bytes, long capCents) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("INSERT INTO satchel_claims(id,player,rarity,item_bytes,cap_cents,status) VALUES(?,?,?,?,?,'offen')")) {
      s.setString(1, id); s.setString(2, player); s.setString(3, rarity); s.setBytes(4, bytes); s.setLong(5, capCents); s.executeUpdate();
    }
  }

  static List<Claim> openClaims(Connection c, String player) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("SELECT id,rarity,item_bytes,cap_cents FROM satchel_claims WHERE player=? AND status='offen' ORDER BY id")) {
      s.setString(1, player);
      try (ResultSet r = s.executeQuery()) {
        List<Claim> list = new ArrayList<>();
        while (r.next()) list.add(new Claim(r.getString(1), r.getString(2), r.getBytes(3), r.getLong(4)));
        return list;
      }
    }
  }

  static boolean take(Connection c, String id, String player) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("UPDATE satchel_claims SET status='gegeben' WHERE id=? AND player=? AND status='offen'")) {
      s.setString(1, id); s.setString(2, player);
      return s.executeUpdate() == 1;
    }
  }

  static void savePity(Connection c, String player, String satchel, SatchelOdds.Pity pity) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("UPDATE satchel_pity SET since_sr=?,since_ssr=? WHERE player=? AND satchel=?")) {
      s.setInt(1, pity.sinceSr()); s.setInt(2, pity.sinceSsr()); s.setString(3, player); s.setString(4, satchel); s.executeUpdate();
    }
  }
}
