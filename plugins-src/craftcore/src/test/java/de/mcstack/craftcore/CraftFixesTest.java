package de.mcstack.craftcore;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.sql.*;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class CraftFixesTest {
  @Test void rawIronCycleInheritsSmeltedPriceInsteadOfFallback() {
    YamlConfiguration cfg=new YamlConfiguration(); cfg.set("base.IRON_INGOT",8.0); cfg.set("base.GOLD_INGOT",12.0); cfg.set("fallback.default",10.0); cfg.set("fallback.bloecke",3.0);
    List<PriceCatalog.CatalogRecipe> r=List.of(
      new PriceCatalog.CatalogRecipe(Material.RAW_IRON_BLOCK,1,Collections.nCopies(9,List.of(Material.RAW_IRON))),
      new PriceCatalog.CatalogRecipe(Material.RAW_IRON,9,List.of(List.of(Material.RAW_IRON_BLOCK))),
      new PriceCatalog.CatalogRecipe(Material.IRON_INGOT,1,List.of(List.of(Material.RAW_IRON)),true),
      new PriceCatalog.CatalogRecipe(Material.IRON_INGOT,1,List.of(List.of(Material.IRON_ORE)),true));
    Set<Material> el=EnumSet.of(Material.IRON_INGOT,Material.RAW_IRON,Material.RAW_IRON_BLOCK,Material.IRON_ORE,Material.GOLD_INGOT);
    var p=PriceCatalog.build(cfg,r,el::contains,m->m==Material.RAW_IRON_BLOCK||m==Material.IRON_ORE?"bloecke":"materialien");
    assertEquals(8.0,p.get(Material.RAW_IRON),1e-9);
    assertEquals(79.2,p.get(Material.RAW_IRON_BLOCK),1e-6);
    // Rezeptkostengrenze: Raw Iron ueber Block nicht mehr billiger als Raw Iron selbst
    Map<Material,Long> buy=new EnumMap<>(Material.class); p.forEach((m,v)->buy.put(m,Math.round(v*0.5*1.5*100)));
    var cost=RecipeCost.derive(buy,r);
    assertTrue(cost.get(Material.RAW_IRON)>=buy.get(Material.RAW_IRON)-1, "raw iron cost "+cost.get(Material.RAW_IRON));
    assertTrue(cost.get(Material.IRON_INGOT)>=0.2*buy.get(Material.IRON_INGOT));
  }
  @Test void recipeCapFactorIsConfigurableAndDefaultsToFullBase() {
    YamlConfiguration cfg=new YamlConfiguration(); assertEquals(1.0,Market.recipeCapFactor(cfg));
    cfg.set("market.recipe-cap-factor",0.5); assertEquals(0.5,Market.recipeCapFactor(cfg));
    cfg.set("market.recipe-cap-factor",-1); assertEquals(1.0,Market.recipeCapFactor(cfg));
    cfg=new YamlConfiguration(); cfg.set("market.sell-factor",0.375); cfg.set("market.buy-factor",1.5);
    long sell=Market.cents(cfg,Material.STONE,5.0,false), b=Market.cents(cfg,Material.STONE,5.0,true);
    assertEquals(187,sell); assertEquals(750,b); assertTrue(sell>0.2*b);
  }
  @Test void sellLocksDoNotAffectBankPurchasesOrSales() throws Exception {
    try(Connection c=DriverManager.getConnection("jdbc:h2:mem:sell_all;MODE=MySQL"); Statement s=c.createStatement()) {
      s.execute("CREATE TABLE market_locks(item VARCHAR(64) PRIMARY KEY,lock_buy BOOLEAN,lock_sell BOOLEAN)");
      s.execute("INSERT INTO market_locks VALUES('DIRT',TRUE,TRUE)");
      var cfg=new YamlConfiguration(); cfg.set("market.unsellable",List.of("DIRT"));
      assertNull(Market.sellBlocked(c,cfg,Material.DIRT));
      assertTrue(Market.sellEnabled(false,"LOCKED"));
      assertFalse(Market.buyEnabled(false,true,Market.locked(c,Material.DIRT,true)));
      assertFalse(Market.buyEnabled(false,false,false));
    }
  }
  @Test void minimumSaleIsCappedForRecipeChains() {
    var recipes=List.of(new PriceCatalog.CatalogRecipe(Material.GOLD_NUGGET,9,List.of(List.of(Material.GOLD_INGOT))));
    var costs=RecipeCost.derive(Map.of(Material.GOLD_INGOT,9L),recipes);
    assertEquals(1, Market.safeSell(20,costs.get(Material.GOLD_NUGGET)));
    assertEquals(1, Market.safeSell(0,null));
    assertEquals(1, Market.safeSell(0,0.5));
    assertEquals(5, Market.safeSell(20,5.9));
  }
  @Test void parsesVanillaStatsJson() {
    String json="{\"stats\":{\"minecraft:killed\":{\"minecraft:sheep\":1},\"minecraft:mined\":{\"minecraft:stone\":12,\"minecraft:dirt\":3},\"minecraft:custom\":{\"minecraft:walk_one_cm\":2247,\"minecraft:play_time\":2009,\"minecraft:fly_one_cm\":7523,\"minecraft:deaths\":2,\"minecraft:player_kills\":1}},\"DataVersion\":5023}";
    UUID u=UUID.randomUUID(); Object[] v=PlayerStats.parseStats(u,json,true);
    assertEquals(100L,v[0]); var snap=(PlayerStats.Snapshot)v[1]; assertEquals(15,snap.blocks()); assertEquals(1,snap.kills()); assertEquals(2,snap.deaths());
    @SuppressWarnings("unchecked") List<PlayerStats.Detail> d=(List<PlayerStats.Detail>)v[2];
    assertTrue(d.contains(new PlayerStats.Detail("blocks_mined","STONE",12)));
    assertTrue(d.contains(new PlayerStats.Detail("distance_cm","WALK_ONE_CM",2247)));
    assertTrue(d.contains(new PlayerStats.Detail("kills_entity","SHEEP",1)));
    Object[] lobby=PlayerStats.parseStats(u,json,false); assertEquals(100L,lobby[0]); assertNull(lobby[1]);
  }
  @Test void mariaDbLocksAndBackfillFeedViews() throws Exception {
    String password=System.getenv("CRAFTCORE_DB_PASSWORD"); assumeTrue(password!=null && "true".equals(System.getenv("CRAFTCORE_MARIADB_TEST")) && "true".equals(System.getenv("CRAFTCORE_FIX_DB_TEST")));
    try(Connection c=DriverManager.getConnection("jdbc:mariadb://craftcore-db:3306/craftcore","craftcore",password); Statement s=c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS known_players (uuid CHAR(36) PRIMARY KEY, name VARCHAR(16) NOT NULL)");
      s.execute("CREATE TABLE IF NOT EXISTS accounts (uuid CHAR(36) PRIMARY KEY, balance BIGINT NOT NULL DEFAULT 0)");
      PlayerStats.migrate(c);
      s.execute("CREATE OR REPLACE VIEW v_player_stats AS SELECT p.uuid,p.name mc_name,CAST(a.balance/100.0 AS DECIMAL(21,2)) money,st.playtime_s,st.kills,st.deaths,st.blocks_mined,st.updated_at FROM known_players p LEFT JOIN accounts a ON a.uuid=p.uuid LEFT JOIN player_stats st ON st.uuid=p.uuid");
      WebsiteStats.migrate(c);
      ResultSet cols=s.executeQuery("SHOW COLUMNS FROM v_market WHERE Field IN ('buy_enabled','sell_enabled')"); int n=0; while(cols.next())n++; assertEquals(2,n);
      String u=UUID.randomUUID().toString(); s.execute("INSERT INTO known_players VALUES('"+u+"','fixtest"+u.substring(0,6)+"')");
      String json="{\"stats\":{\"minecraft:mined\":{\"minecraft:stone\":12},\"minecraft:custom\":{\"minecraft:play_time\":2000,\"minecraft:walk_one_cm\":500}}}";
      Object[] v=PlayerStats.parseStats(UUID.fromString(u),json,true);
      PlayerStats.upsertPlaytime(c,UUID.fromString(u),"survival",(Long)v[0]); PlayerStats.upsert(c,(PlayerStats.Snapshot)v[1]);
      @SuppressWarnings("unchecked") List<PlayerStats.Detail> det=(List<PlayerStats.Detail>)v[2]; PlayerStats.replaceDetails(c,UUID.fromString(u),det);
      ResultSet r=s.executeQuery("SELECT value FROM v_leaderboards WHERE uuid='"+u+"' AND board='playtime' AND period='all'"); assertTrue(r.next()); assertEquals(100,r.getLong(1));
      r=s.executeQuery("SELECT value FROM v_leaderboards WHERE uuid='"+u+"' AND board='blocks_mined' AND period='all'"); assertTrue(r.next()); assertEquals(12,r.getLong(1));
      r=s.executeQuery("SELECT JSON_EXTRACT(details,'$.blocks_mined.STONE') FROM v_player_stats_ext WHERE uuid='"+u+"'"); assertTrue(r.next()); assertEquals(12,r.getLong(1));
      s.execute("INSERT INTO market_locks(item,lock_buy,lock_sell) VALUES('GRAVEL',0,1) ON DUPLICATE KEY UPDATE lock_sell=1");
      YamlConfiguration cfg=new YamlConfiguration(); cfg.set("market.unsellable",List.of("DIRT"));
      assertNull(Market.sellBlocked(c,cfg,Material.GRAVEL));
      assertNull(Market.sellBlocked(c,cfg,Material.DIRT));
      assertNull(Market.sellBlocked(c,cfg,Material.SAND));
      r=s.executeQuery("SELECT sell_enabled FROM v_market WHERE item='GRAVEL'"); if(r.next()) assertEquals(1,r.getInt(1));
    }
  }
}
