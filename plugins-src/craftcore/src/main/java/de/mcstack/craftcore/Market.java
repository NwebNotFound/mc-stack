package de.mcstack.craftcore;

import java.sql.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

final class Market implements Listener {
  static Market instance;
  private final CraftCore core;
  private final Map<Material, Quote> items = new LinkedHashMap<>();
  private Map<Material,Double> recipeCosts = Map.of();
  record Quote(double base, double min, double max, double pressure) {}
  record Price(long cents, double current, String trend) {}
  static final class Menu implements InventoryHolder {
    final String category, query; final int page; final Map<Integer,Material> slots=new HashMap<>();
    Menu(String category,String query,int page){this.category=category;this.query=query;this.page=page;}
    public Inventory getInventory() { return null; }
  }
  Market(CraftCore core) { this.core=core; instance=this; }
  void start() throws Exception {
    PriceCatalog.build(core).forEach((m,raw)->{if(Bukkit.getItemFactory().getItemMeta(m)==null){core.getLogger().warning("Marktartikel ohne ItemMeta ausgeschlossen: "+m);return;} Quote q=quote(core.getConfig(),m,raw); items.put(m,q);});
    Map<Material,Long> minimumPurchases=new EnumMap<>(Material.class);
    items.forEach((m,q)->{if(!unbuyable(core.getConfig(),m)) minimumPurchases.put(m,cents(core.getConfig(),m,q.base(),true));});
    List<PriceCatalog.CatalogRecipe> recipes=new ArrayList<>();
    Bukkit.recipeIterator().forEachRemaining(recipe->{var converted=PriceCatalog.recipe(recipe);if(converted!=null)recipes.add(converted);});
    recipeCosts=RecipeCost.derive(minimumPurchases,recipes);
    core.tx(c->{core.exec(c,"CREATE TABLE IF NOT EXISTS bank_price_factors (material VARCHAR(64) PRIMARY KEY, sell_factor DOUBLE NOT NULL, buy_factor DOUBLE NOT NULL, updated BIGINT NOT NULL)"); core.exec(c,"CREATE TABLE IF NOT EXISTS discord_links (uuid CHAR(36) PRIMARY KEY, discord_id VARCHAR(20) NULL, INDEX discord_lookup (discord_id))"); core.exec(c,"CREATE TABLE IF NOT EXISTS bank_link_revenue (owner VARCHAR(40) NOT NULL, day_key VARCHAR(10) NOT NULL, cents BIGINT NOT NULL, PRIMARY KEY(owner,day_key))"); core.exec(c,"CREATE TABLE IF NOT EXISTS market_prices (material VARCHAR(64) PRIMARY KEY, price DOUBLE NOT NULL, updated BIGINT NOT NULL, trend VARCHAR(8) NOT NULL)"); core.exec(c,"CREATE TABLE IF NOT EXISTS bank_daily (player CHAR(36) NOT NULL, item VARCHAR(64) NOT NULL, day_key VARCHAR(10) NOT NULL, amount INT NOT NULL, PRIMARY KEY(player,item,day_key))"); core.exec(c,"CREATE TABLE IF NOT EXISTS bank_revenue_daily (player CHAR(36) NOT NULL, day_key VARCHAR(10) NOT NULL, cents BIGINT NOT NULL, PRIMARY KEY(player,day_key))"); core.exec(c,"CREATE TABLE IF NOT EXISTS bank_journal (id CHAR(36) PRIMARY KEY, player CHAR(36) NOT NULL, item VARCHAR(64) NOT NULL, amount INT NOT NULL, cp BIGINT NOT NULL, status VARCHAR(24) NOT NULL, INDEX bank_pending (status,player))"); return null;});
    core.tx(c->{core.exec(c,"CREATE TABLE IF NOT EXISTS bank_buy_daily (owner VARCHAR(40) NOT NULL, item VARCHAR(64) NOT NULL, day_key VARCHAR(10) NOT NULL, amount INT NOT NULL, PRIMARY KEY(owner,item,day_key))"); core.exec(c,"INSERT IGNORE INTO bank_link_revenue(owner,day_key,cents) SELECT CONCAT('u:',player),day_key,cents FROM bank_revenue_daily"); for(Material m:items.keySet()) price(c,m,false,false); return null;});
    Bukkit.getPluginManager().registerEvents(this,core);
    Bukkit.getScheduler().runTaskAsynchronously(core,()->recoverSales(null));
  }
  static Quote quote(org.bukkit.configuration.Configuration cfg, Material m, double raw) {
    double base=Math.max(raw*cfg.getDouble("market.base-factor",0.5),cfg.getDouble("market.valuable-base."+m.name(),0));
    double floor=cfg.getDouble("market.price-floor."+m.name(),0);
    double min=cfg.getDouble("market.min-price."+m.name(),Math.max(base*cfg.getDouble("market.minimum-factor",0.2),floor));
    double max=cfg.getDouble("market.max-price."+m.name(),Math.max(min,base*cfg.getDouble("market.maximum-factor",3.0)));
    if(!Double.isFinite(min)||!Double.isFinite(max)||min<0||max<min)throw new IllegalArgumentException("Ungueltige Preisgrenzen: "+m);
    return new Quote(base,min,max,base*cfg.getDouble("market.pressure-factor",0.01));
  }
  static boolean unbuyable(org.bukkit.configuration.Configuration cfg, Material m) { return m.name().startsWith("NETHERITE_") || m.name().endsWith("SHULKER_BOX") || m.name().endsWith("_SPAWN_EGG") || m == Material.ENCHANTED_BOOK || cfg.getStringList("market.unbuyable").contains(m.name()); }
  static long cents(org.bukkit.configuration.Configuration cfg, Material m, double current, boolean buy) {
    double floor=cfg.getDouble("market.price-floor."+m.name(),0);
    long cents=PriceModel.withFloor(PriceModel.factorQuote(current,cfg.getDouble(buy?"market.buy-factor":"market.sell-factor",buy?1.5:0.25)),floor);
    if(!buy && !unbuyable(cfg,m)) cents=PriceModel.cappedSell(cents,PriceModel.withFloor(PriceModel.factorQuote(current,cfg.getDouble("market.buy-factor",1.5)),floor));
    return cents;
  }
  Price price(Connection c, Material m, boolean trade, boolean buy) throws SQLException {
    Quote q=items.get(m); long now=System.currentTimeMillis();
    core.exec(c,"INSERT IGNORE INTO bank_price_factors VALUES (?,1,1,?)",m.name(),now);
    double sf,bf; long updated;
    try(PreparedStatement st=c.prepareStatement("SELECT sell_factor,buy_factor,updated FROM bank_price_factors WHERE material=? FOR UPDATE")) {
      st.setString(1,m.name()); try(ResultSet rs=st.executeQuery()) {rs.next();sf=rs.getDouble(1);bf=rs.getDouble(2);updated=rs.getLong(3);}
    }
    sf=Math.min(1,Math.max(0.25,BankPrice.recover(sf,now-updated))); bf=Math.max(1,Math.min(2,BankPrice.recover(bf,now-updated)));
    long sell=(long)Math.floor(bankCents(m,q.base(),false)*sf);
    long purchase=Math.max((long)Math.ceil(bankCents(m,q.base(),true)*bf),BankPrice.minimumBuy(sell));
    core.exec(c,"INSERT INTO market_catalog(item,buy_price,sell_price,base_price,updated_at) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE buy_price=VALUES(buy_price),sell_price=VALUES(sell_price),base_price=VALUES(base_price),updated_at=VALUES(updated_at)",m.name(),purchase/100.0,sell/100.0,q.base(),now/1000);
    if(trade) { if(buy) bf=BankPrice.moveBuy(bf); else sf=BankPrice.moveSell(sf); }
    core.exec(c,"UPDATE bank_price_factors SET sell_factor=?,buy_factor=?,updated=? WHERE material=?",sf,bf,now,m.name());
    return new Price(buy?purchase:sell,q.base()*(buy?bf:sf),trade?(buy?"auf":"ab"):"-");
  }

  boolean hasPrice(Material m) { return items.containsKey(m); }
  private long bankCents(Material m,double current,boolean buy) {
    long value=cents(core.getConfig(),m,current,buy);
    if(buy) return Math.max(1,value);
    Double cost=recipeCosts.get(m);
    return cost==null?value:Math.min(value,Math.max(0,(long)Math.floor(cost+1e-9)));
  }
  void command(Player p,String command,String[] args) throws Exception {
    String query=args.length>0 ? String.join(" ",args).toLowerCase(Locale.ROOT).replace(' ','_') : "";
    open(p,"alle",query,0);
  }
  private String dayKey() { return DailyRevenue.dayKey(java.time.Instant.now(),java.time.ZoneId.of(core.getConfig().getString("market.reset-zone","Europe/Berlin")),core.getConfig().getInt("market.reset-hour",5)); }
  private long revenueLimit() { long limit=150000L; if(limit<0) throw new IllegalArgumentException("market.daily-revenue-limit muss mindestens 0 sein"); return limit; }
  private ItemStack button(Material type,String name) { ItemStack item=new ItemStack(type); ItemMeta meta=item.getItemMeta(); meta.setDisplayName(name); item.setItemMeta(meta); return item; }
  private void open(Player p,String category,String query,int requested) throws Exception {
    List<Material> filtered=items.keySet().stream().filter(m->(category.equals("alle") || PriceCatalog.category(m).equals(category)) && m.name().toLowerCase(Locale.ROOT).contains(query)).sorted(Comparator.comparing(Material::name)).toList();
    int pages=Math.max(1,(filtered.size()+44)/45), page=Math.max(0,Math.min(requested,pages-1)); Menu menu=new Menu(category,query,page);
    Inventory inv=Bukkit.createInventory(menu,54,"Markt " + (page+1)+"/"+pages+" " + category);
    long remaining=core.tx(c->DiscordRevenue.remaining(c,p.getUniqueId().toString(),dayKey(),revenueLimit()));
    int first=page*45; Map<Material,Price[]> quotes=core.tx(c->{Map<Material,Price[]> result=new EnumMap<>(Material.class); for(int i=first;i<Math.min(first+45,filtered.size());i++){Material m=filtered.get(i); result.put(m,new Price[]{price(c,m,false,true),price(c,m,false,false)});} return result;}); for(int i=first;i<Math.min(first+45,filtered.size());i++) { Material m=filtered.get(i); int slot=i-first; menu.slots.put(slot,m);
      Price buy=quotes.get(m)[0], sell=quotes.get(m)[1]; ItemStack icon=new ItemStack(m); ItemMeta meta=icon.getItemMeta();
      if(meta==null){menu.slots.remove(slot);core.getLogger().warning("Marktartikel ohne ItemMeta uebersprungen: "+m);continue;} meta.setLore(List.of(isUnbuyable(m)?"Kauf: nur Spielerhandel":"Kauf: "+Money.format(buy.cents()),"Verkauf: "+Money.format(sell.cents()),"Trend: "+buy.trend(),isUnbuyable(m)?"Nicht bei der Bank kaufbar":"Linksklick: 1 kaufen", "Rechtsklick: 1 verkaufen")); icon.setItemMeta(meta); inv.setItem(slot,icon);
    }
    inv.setItem(45,button(Material.ARROW,"Vorherige Seite")); inv.setItem(46,button(Material.GRASS_BLOCK,"Bloecke")); inv.setItem(47,button(Material.IRON_INGOT,"Materialien")); inv.setItem(48,button(Material.IRON_SWORD,"Ausruestung")); inv.setItem(49,button(Material.APPLE,"Nahrung")); inv.setItem(50,button(Material.REDSTONE,"Technik")); inv.setItem(51,button(Material.CHEST,"Alle Kategorien")); inv.setItem(52,button(Material.COMPASS,"Tageslimit uebrig: "+Money.format(remaining)+" | Suche: /market <Suchbegriff>")); inv.setItem(53,button(Material.ARROW,"Naechste Seite")); if(core.npcPreviews.contains(p.getOpenInventory().getTopInventory())) core.npcPreviews.add(inv); p.openInventory(inv);
  }
  @EventHandler public void drag(InventoryDragEvent e) { if(e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true); }
  @EventHandler public void click(InventoryClickEvent e) {
    if(!(e.getView().getTopInventory().getHolder() instanceof Menu menu)) return; e.setCancelled(true);
    if(!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory()!=e.getView().getTopInventory() || !(e.isLeftClick() || e.isRightClick()) || e.isShiftClick()) return;
    int slot=e.getRawSlot(); Material m=menu.slots.get(slot);
    if(m!=null && core.npcPreviews.contains(e.getView().getTopInventory())) { p.sendMessage("NPC-Vorschau: Handel ist hier wegen unsicherer Item-Auslieferung gesperrt."); return; }
    try { if(m!=null) { if(e.isRightClick())sell(p,m); else buy(p,m); open(p,menu.category,menu.query,menu.page); }
      else switch(slot) { case 45 -> open(p,menu.category,menu.query,menu.page-1); case 53 -> open(p,menu.category,menu.query,menu.page+1); case 46 -> open(p,"bloecke",menu.query,0); case 47 -> open(p,"materialien",menu.query,0); case 48 -> open(p,"ausruestung",menu.query,0); case 49 -> open(p,"nahrung",menu.query,0); case 50 -> open(p,"technik",menu.query,0); case 51 -> open(p,"alle",menu.query,0); case 52 -> p.sendMessage("Suche mit /market <Suchbegriff>; /market zeigt wieder alle Artikel."); default -> {} }
    } catch(Exception ex) { core.getLogger().warning("Bank: "+ex); p.sendMessage("Bankaktion fehlgeschlagen. Bitte spaeter erneut versuchen."); }
  }
  private void sell(Player p, Material m) throws Exception {
    if(core.getConfig().getStringList("market.unsellable").contains(m.name())) {p.sendMessage("Dieser Artikel ist nicht an die Bank verkaufbar.");return;}
    PlayerInventory inv=p.getInventory(); int slot=-1;
    for(int i=0;i<36;i++) { ItemStack stack=inv.getItem(i); if(stack!=null && stack.getType()==m && !stack.hasItemMeta()) { slot=i; break; } }
    if(slot<0) { p.sendMessage("Kein geeigneter Artikel im Inventar."); return; }
    ItemStack before=inv.getItem(slot).clone(); ItemStack after=before.clone(); after.setAmount(before.getAmount()-1); inv.setItem(slot,after.getAmount()==0?null:after);
    String id=UUID.randomUUID().toString(); UUID player=p.getUniqueId();
    try {
      boolean accepted=core.tx(c->{String day=dayKey();
        String owner=DiscordRevenue.lock(c,player.toString(),day);
        long cp=price(c,m,false,false).cents(); if(cp<1 || !DiscordRevenue.reserve(c,owner,day,cp,revenueLimit()))return false;
        core.exec(c,"INSERT INTO bank_journal(id,player,item,amount,cp,status) VALUES(?,?,?,?,?,?)",id,player.toString(),m.name(),1,cp,"PENDING"); BankJournal.finishSale(c,id,material->price(c,m,true,false)); WebsiteStats.event(c,m.name(),1,cp,"bank"); return true;});
      if(!accepted){inv.setItem(slot,before);p.sendMessage("Tageslimit erreicht oder Verkaufspreis unter 0,01 CP.");return;}
    } catch(Exception ex) { inv.setItem(slot,before); throw ex; }
    p.sendMessage("Verkauf abgeschlossen.");
  }
  private void finishSale(String id) {
    try { core.tx(c->{
      BankJournal.finishSale(c,id,(material)->{ Material m=Material.matchMaterial(material); if(m==null || !items.containsKey(m)) throw new SQLException("Marktartikel fehlt fuer Journal "+id); price(c,m,true,false); }); return null;
    }); } catch(Exception ex) { core.getLogger().warning("Bankverkauf "+id+": "+ex); }
  }
  private boolean isUnbuyable(Material m) { return unbuyable(core.getConfig(),m); }
  private void buy(Player p,Material m) throws Exception {
    if(isUnbuyable(m)) { p.sendMessage("Dieser Artikel ist nur durch Spielerhandel erhaeltlich."); return; }
    UUID player=p.getUniqueId(); String id=UUID.randomUUID().toString();
    boolean accepted=core.tx(c->{long cp=price(c,m,false,true).cents(); long balance=core.balance(c,player); if(balance<cp)return false;
      String owner=DiscordRevenue.owner(c,player.toString(),true); int limit=core.getConfig().getInt("market.daily-buy-limits."+m.name(),core.getConfig().getInt("market.daily-buy-limit",512));
      if(!BankBuyLimit.reserve(c,owner,m.name(),dayKey(),limit))return false;
      core.exec(c,"UPDATE accounts SET balance=? WHERE uuid=?",balance-cp,player.toString());
      Deliveries.insert(c,id,player.toString(),new ItemStack(m,1),"bank");
      price(c,m,true,true); WebsiteStats.event(c,m.name(),1,cp,"bank"); return true;});
    if(!accepted) {p.sendMessage("Nicht genug CP oder Tages-Kauflimit erreicht."); return;}
    deliver(p,id);
  }
  private void deliver(Player p,String id) {  }
  private void recoverSales(UUID player) {
    try { List<String> ids=core.tx(c->{List<String> found=new ArrayList<>(); String sql="SELECT id FROM bank_journal WHERE status='PENDING'"+(player==null?"":" AND player=?");
      try(PreparedStatement s=c.prepareStatement(sql)) {if(player!=null)s.setString(1,player.toString()); try(ResultSet r=s.executeQuery()){while(r.next())found.add(r.getString(1));}} return found;});
      for(String id:ids) finishSale(id);
    } catch(Exception ex) {core.getLogger().warning("Bank-Wiederaufnahme: "+ex);}
  }
}
