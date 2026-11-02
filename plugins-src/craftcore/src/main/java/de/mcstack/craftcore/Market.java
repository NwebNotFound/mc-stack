package de.mcstack.craftcore;

import java.sql.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.enchantments.Enchantment;

final class Market implements Listener {
  static Market instance;
  private final CraftCore core;
  private final Map<Material, Quote> items = new LinkedHashMap<>();
  private Map<Material,Double> recipeCosts = Map.of();
  private Map<Material,Long> minimumPurchases = Map.of();
  record Quote(double base, double min, double max, double pressure) {}
  record Price(long cents, double current, String trend) {}
  static final class Menu implements InventoryHolder {
    final String category, query; final int page; final Map<Integer,Material> slots=new HashMap<>();
    Menu(String category,String query,int page){this.category=category;this.query=query;this.page=page;}
    public Inventory getInventory() { return null; }
  }
  static final class SellMenu implements InventoryHolder {
    final UUID owner; Inventory inventory; final ItemStack[] snapshots=new ItemStack[36]; final boolean[] sellable=new boolean[36]; long total; int count;
    SellMenu(UUID owner){this.owner=owner;}
    public Inventory getInventory(){return inventory;}
  }
  private final Map<UUID,SellMenu> sellMenus=new HashMap<>();
  private final SellStackFlow sellFlow;
  Market(CraftCore core) { this.core=core; this.sellFlow=new SellStackFlow(r->Bukkit.getScheduler().runTaskAsynchronously(core,r),r->Bukkit.getScheduler().runTask(core,r)); instance=this; }
  private Set<Material> priced = EnumSet.noneOf(Material.class);
  boolean tradable(Material m){ return PriceCatalog.eligible(m) && (!core.getConfig().getBoolean("market.lock-fallback",true) || priced.contains(m)); }
  /** Every catalog item can be sold; purchase restrictions are independent. */
  static String sellBlocked(Connection c, org.bukkit.configuration.Configuration cfg, Material m) { return null; }
  String sellCheck(String raw) {
    Material m=raw==null?null:Material.matchMaterial(raw);
    return m==null || !items.containsKey(m) ? "ERR UNKNOWN" : "OK SELLABLE "+m.name();
  }
  static boolean buyEnabled(boolean unbuyable,boolean tradable,boolean lockedBuy){ return !unbuyable && tradable && !lockedBuy; }
  static boolean sellEnabled(boolean tradable,String sellBlocked){ return true; }
  static boolean locked(Connection c, Material m, boolean buy) throws SQLException {
    try(PreparedStatement s=c.prepareStatement("SELECT "+(buy?"lock_buy":"lock_sell")+" FROM market_locks WHERE item=?")){s.setString(1,m.name());try(ResultSet r=s.executeQuery()){return r.next()&&r.getInt(1)==1;}}
  }
  void start() throws Exception {
    DynamicPrice.configure(core.getConfig());
    PriceCatalog.build(core).forEach((m,raw)->{if(Bukkit.getItemFactory().getItemMeta(m)==null){core.getLogger().warning("Marktartikel ohne ItemMeta ausgeschlossen: "+m);return;} Quote q=quote(core.getConfig(),m,raw); items.put(m,q);});
    Map<Material,Long> minimumPurchases=new EnumMap<>(Material.class);
    items.forEach((m,q)->{if(!unbuyable(core.getConfig(),m)) minimumPurchases.put(m,DynamicPrice.apply(Math.max(1,cents(core.getConfig(),m,q.base(),true)),DynamicPrice.floor(),true));});
    List<PriceCatalog.CatalogRecipe> recipes=new ArrayList<>();
    Bukkit.recipeIterator().forEachRemaining(recipe->{var converted=PriceCatalog.recipe(recipe);if(converted!=null)recipes.add(converted);});
    // Ensure even the cheapest purchasable recipe chain costs at least one cent
    // per output before applying the universal one-cent sale floor.
    recipeCosts=RecipeCost.derive(minimumPurchases,recipes);
    double cheapest=recipeCosts.values().stream().mapToDouble(Double::doubleValue).filter(v->v>0).min().orElse(1);
    double purchaseScale=Math.max(1,1/cheapest);
    if(purchaseScale>1) {
      minimumPurchases.replaceAll((m,v)->(long)Math.ceil(v*purchaseScale));
      recipeCosts=RecipeCost.derive(minimumPurchases,recipes);
    }
    this.minimumPurchases=minimumPurchases;
    core.tx(c->{MarketEffective.refresh(c,core.getConfig(),MarketEffective.bases(core),recipes);return null;});
    { var f=new java.io.File(core.getDataFolder(),"prices-base.yml"); var pricesCfg=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f); priced=PriceCatalog.known(pricesCfg,recipes); java.time.Instant eff=PriceMeta.effectiveFromOrPlaceholder(pricesCfg,core.getLogger()); PriceMeta.storeSafely(core,eff); core.getLogger().info("Preis-Stichtag effective_from="+(eff.equals(PriceMeta.PLACEHOLDER)?"nicht gesetzt (Platzhalter)":eff.toString())+", buyback_caps="+BuybackCaps.enabled(core.getConfig())); core.getLogger().info("Markt: "+priced.size()+" Items mit eigenem Preis, "+items.keySet().stream().filter(x->!priced.contains(x)).count()+" auf Fallback (gesperrt="+core.getConfig().getBoolean("market.lock-fallback",true)+")"); }
    for(Material dm:List.of(Material.IRON_INGOT,Material.RAW_IRON,Material.IRON_BLOCK,Material.GOLD_INGOT)) if(items.containsKey(dm)) core.getLogger().info("Rezeptkosten "+dm+": "+recipeCosts.get(dm)+" ct via "+RecipeCost.path(dm));
    core.tx(c->{core.exec(c,"CREATE TABLE IF NOT EXISTS bank_price_factors (material VARCHAR(64) PRIMARY KEY, sell_factor DOUBLE NOT NULL, buy_factor DOUBLE NOT NULL, updated BIGINT NOT NULL)"); core.exec(c,"CREATE TABLE IF NOT EXISTS discord_links (uuid CHAR(36) PRIMARY KEY, discord_id VARCHAR(20) NULL, INDEX discord_lookup (discord_id))"); core.exec(c,"CREATE TABLE IF NOT EXISTS bank_link_revenue (owner VARCHAR(40) NOT NULL, day_key VARCHAR(10) NOT NULL, cents BIGINT NOT NULL, PRIMARY KEY(owner,day_key))"); core.exec(c,"CREATE TABLE IF NOT EXISTS market_prices (material VARCHAR(64) PRIMARY KEY, price DOUBLE NOT NULL, updated BIGINT NOT NULL, trend VARCHAR(8) NOT NULL)"); core.exec(c,"CREATE TABLE IF NOT EXISTS bank_daily (player CHAR(36) NOT NULL, item VARCHAR(64) NOT NULL, day_key VARCHAR(10) NOT NULL, amount INT NOT NULL, PRIMARY KEY(player,item,day_key))"); core.exec(c,"CREATE TABLE IF NOT EXISTS bank_revenue_daily (player CHAR(36) NOT NULL, day_key VARCHAR(10) NOT NULL, cents BIGINT NOT NULL, PRIMARY KEY(player,day_key))"); core.exec(c,"CREATE TABLE IF NOT EXISTS bank_journal (id CHAR(36) PRIMARY KEY, player CHAR(36) NOT NULL, item VARCHAR(64) NOT NULL, amount INT NOT NULL, cp BIGINT NOT NULL, status VARCHAR(24) NOT NULL, INDEX bank_pending (status,player))"); return null;});
    core.tx(c->{DynamicPrice.migrate(c);BankBuy.migrate(c);BuybackCaps.ensureSchema(c);return null;});
    core.tx(c->{core.exec(c,"CREATE TABLE IF NOT EXISTS bank_buy_daily (owner VARCHAR(40) NOT NULL, item VARCHAR(64) NOT NULL, day_key VARCHAR(10) NOT NULL, amount INT NOT NULL, PRIMARY KEY(owner,item,day_key))"); core.exec(c,"INSERT IGNORE INTO bank_link_revenue(owner,day_key,cents) SELECT CONCAT('u:',player),day_key,cents FROM bank_revenue_daily"); for(Material m:items.keySet()) price(c,m,false,false); return null;});
    Bukkit.getPluginManager().registerEvents(this,core);
    Bukkit.getScheduler().runTaskAsynchronously(core,()->recoverSales(null));
    if(SpawnRoute.isSurvival(System.getenv().getOrDefault("CRAFTCORE_SERVER","Server")))
      Bukkit.getScheduler().runTaskTimerAsynchronously(core,()->{ try { core.tx(c->{ for(Material m:items.keySet()) price(c,m,false,false); WebsiteStats.snapshot(c,java.time.Instant.now()); return null; }); } catch(Exception e) { core.getLogger().warning("Markt-Snapshot: "+e.getMessage()); } },1L,72000L);
    else Bukkit.getScheduler().runTaskTimerAsynchronously(core,()->{ try { core.tx(c->{ for(Material m:items.keySet()) price(c,m,false,false); return null; }); } catch(Exception e) { core.getLogger().warning("Marktkurse: "+e.getMessage()); } },72000L,72000L);
    int dropped=core.tx(c->SurvivalItems.purge(c,name->!SurvivalItems.obtainable(name, core.getConfig().getStringList("market.unobtainable"))));
    if(dropped>0) core.getLogger().info("Unobtainable Katalogzeilen entfernt: "+dropped);
  }
  // Anteil des Basiskurses, zu dem Rezeptzutaten fuer die Ankaufsgrenze bewertet werden.
  // Frueher fest min(Mindestkurs, 25 % Basis) => Ankauf effektiv hart auf 20 % des Kaufpreises gedeckelt.
  static double recipeCapFactor(org.bukkit.configuration.Configuration cfg) { double f=cfg.getDouble("market.recipe-cap-factor",1.0); return Double.isFinite(f)&&f>0?f:1.0; }
  static Quote quote(org.bukkit.configuration.Configuration cfg, Material m, double raw) {
    double base=Math.max(raw*cfg.getDouble("market.base-factor",0.5),cfg.getDouble("market.valuable-base."+m.name(),0));
    double floor=cfg.getDouble("market.price-floor."+m.name(),0);
    double min=cfg.getDouble("market.min-price."+m.name(),Math.max(base*cfg.getDouble("market.minimum-factor",0.2),floor));
    double max=cfg.getDouble("market.max-price."+m.name(),Math.max(min,base*cfg.getDouble("market.maximum-factor",3.0)));
    if(!Double.isFinite(min)||!Double.isFinite(max)||min<0||max<min)throw new IllegalArgumentException("Ungueltige Preisgrenzen: "+m);
    return new Quote(base,min,max,base*cfg.getDouble("market.pressure-factor",0.01));
  }
  static boolean unbuyable(org.bukkit.configuration.Configuration cfg, Material m) { return !SurvivalItems.obtainable(cfg, m) || m.name().startsWith("NETHERITE_") || m.name().endsWith("SHULKER_BOX") || m.name().endsWith("_SPAWN_EGG") || m == Material.ENCHANTED_BOOK || cfg.getStringList("market.unbuyable").contains(m.name()); }
  static long cents(org.bukkit.configuration.Configuration cfg, Material m, double current, boolean buy) {
    double floor=cfg.getDouble("market.price-floor."+m.name(),0);
    long cents=PriceModel.withFloor(PriceModel.factorQuote(current,cfg.getDouble(buy?"market.buy-factor":"market.sell-factor",buy?1.5:0.25)),floor);
    // Ankauf-Grenze gilt fuer alle Items, auch nicht kaufbare (Netherite, Shulker, Kauf-Sperre).
    if(!buy) cents=PriceModel.cappedSell(cents,PriceModel.withFloor(PriceModel.factorQuote(current,cfg.getDouble("market.buy-factor",1.5)),floor),cfg.getDouble("market.max-sell-ratio",0.35));
    return cents;
  }
  record SellConfig(long baseBuy, long baseSell, long maxSell, int perDay, double recipeCost,
                    long minimumPurchase, double base, java.time.ZoneId zone, int resetHour, long revenueLimit, SellPricing.Config pricing) {}
  SellConfig sellConfig(Material m) {
    var cfg=core.getConfig(); Quote q=items.get(m);
    return sellConfig(cfg,m,bankCents(m,q.base(),true),bankCents(m,q.base(),false),recipeCosts.getOrDefault(m,Double.POSITIVE_INFINITY),minimumPurchases.getOrDefault(m,1L),q.base(),revenueLimit());
  }
  static SellConfig sellConfig(org.bukkit.configuration.Configuration cfg,Material m,long baseBuy,long baseSell,double recipeCost,long minimumPurchase,double base,long limit) {
    return new SellConfig(baseBuy,baseSell,
      BuybackCaps.capCents(cfg,m,Long.MAX_VALUE),BuybackCaps.perDay(cfg,m),
      recipeCost,minimumPurchase,base,
      java.time.ZoneId.of(cfg.getString("market.reset-zone","Europe/Berlin")),cfg.getInt("market.reset-hour",5),limit,pricingConfig(cfg));
  }
  static SellPricing.Config pricingConfig(org.bukkit.configuration.Configuration cfg) {
    Map<String,Long> overrides=new HashMap<>();
    ConfigurationSection section=cfg.getConfigurationSection("market.sell.enchant.overrides");
    if(section!=null) for(String key:section.getKeys(false)) overrides.put(key.toLowerCase(Locale.ROOT),Money.cents(section.getDouble(key)));
    String tipped="market.sell.tipped-arrow-price";
    return new SellPricing.Config(Money.cents(cfg.getDouble("market.sell.enchant.bonus-per-level",1.0)),overrides,
      Money.cents(cfg.getDouble("market.sell.enchant.max-bonus",5.0)),
      cfg.getDouble("market.sell.durability.min-factor",0.0),cfg.isSet(tipped)?Money.cents(cfg.getDouble(tipped)):null);
  }
  static List<SellPricing.Enchant> sellEnchants(ItemStack item) {
    ItemMeta meta=item.getItemMeta();
    if(meta==null) return List.of();
    Map<Enchantment,Integer> enchants=new HashMap<>(meta.getEnchants());
    if(meta instanceof EnchantmentStorageMeta stored) stored.getStoredEnchants().forEach((e,level)->enchants.merge(e,level,Integer::sum));
    return enchants.entrySet().stream().map(e->new SellPricing.Enchant(e.getKey().getKey().toString(),e.getValue(),e.getKey().isCursed())).toList();
  }
  static SellPricing.Breakdown sellBreakdown(Material material, long price, List<SellPricing.Enchant> enchants, int damage, SellPricing.Config config) {
    return SellPricing.calculate(SellPricing.base(price,material==Material.TIPPED_ARROW,config),enchants,damage,material.getMaxDurability(),config);
  }
  Price price(Connection c, Material m, boolean trade, boolean buy) throws SQLException {
    return price(c,m,trade,buy,sellConfig(m));
  }
  Price price(Connection c, Material m, boolean trade, boolean buy, SellConfig config) throws SQLException {
    long now=System.currentTimeMillis();
    double f=DynamicPrice.current(c,m.name(),now);
    if(trade) f=DynamicPrice.trade(c,m.name(),buy?-1:1,m.getMaxStackSize(),now);
    long sell=safeSell(Math.min(config.maxSell(),Math.min(
      DynamicPrice.apply(config.baseSell(),f,false),
      DynamicPrice.apply(config.baseBuy(),DynamicPrice.floor(),true))),config.recipeCost());
    long purchase=Math.max(config.minimumPurchase(),Math.max(DynamicPrice.apply(config.baseBuy(),f,true),BankPrice.minimumBuy(sell)));
    WebsiteStats.catalog(c,m.name(),purchase,sell,config.base(),now);
    return new Price(buy?purchase:sell,config.base()*f,trade?(buy?"auf":"ab"):"-");
  }

  static long safeSell(long cents, Double recipeCost) {
    long capped=recipeCost==null ? cents : Math.min(cents,Math.max(0,(long)Math.floor(recipeCost+1e-9)));
    return Math.max(1,capped);
  }
  boolean hasPrice(Material m) { return items.containsKey(m); }
  private long bankCents(Material m,double current,boolean buy) {
    long value=cents(core.getConfig(),m,current,buy);
    if(buy) return Math.max(1,value);
    Double cost=recipeCosts.get(m);
    return cost==null?value:Math.min(value,Math.max(0,(long)Math.floor(cost+1e-9)));
  }
  void command(Player p,String command,String[] args) throws Exception {
    if(args.length==1 && args[0].equalsIgnoreCase("sell")){openSell(p);return;}
    String query=args.length>0 ? String.join(" ",args).toLowerCase(Locale.ROOT).replace(' ','_') : "";
    open(p,"alle",query,0);
  }
  private String dayKey() { return DailyRevenue.dayKey(java.time.Instant.now(),java.time.ZoneId.of(core.getConfig().getString("market.reset-zone","Europe/Berlin")),core.getConfig().getInt("market.reset-hour",5)); }
  private long revenueLimit() { long limit=150000L; if(limit<0) throw new IllegalArgumentException("market.daily-revenue-limit muss mindestens 0 sein"); return limit; }
  private ItemStack button(Material type,String name) { ItemStack item=new ItemStack(type); ItemMeta meta=item.getItemMeta(); meta.setDisplayName(name); item.setItemMeta(meta); return item; }
  private void open(Player p,String category,String query,int requested) throws Exception {
    List<Material> filtered=items.keySet().stream().filter(m->SurvivalItems.obtainable(core.getConfig(), m) && (category.equals("alle") || PriceCatalog.category(m).equals(category)) && m.name().toLowerCase(Locale.ROOT).contains(query)).sorted(Comparator.comparing(Material::name)).toList();
    int pages=Math.max(1,(filtered.size()+44)/45), page=Math.max(0,Math.min(requested,pages-1)); Menu menu=new Menu(category,query,page);
    Inventory inv=Bukkit.createInventory(menu,54,"Markt " + (page+1)+"/"+pages+" " + category);
    long remaining=core.tx(c->DiscordRevenue.remaining(c,p.getUniqueId().toString(),dayKey(),revenueLimit()));
    int first=page*45; Map<Material,Price[]> quotes=core.tx(c->{Map<Material,Price[]> result=new EnumMap<>(Material.class); for(int i=first;i<Math.min(first+45,filtered.size());i++){Material m=filtered.get(i); result.put(m,new Price[]{price(c,m,false,true),price(c,m,false,false)});} return result;}); for(int i=first;i<Math.min(first+45,filtered.size());i++) { Material m=filtered.get(i); int slot=i-first; menu.slots.put(slot,m);
      Price buy=quotes.get(m)[0], sell=quotes.get(m)[1]; ItemStack icon=new ItemStack(m); ItemMeta meta=icon.getItemMeta();
      if(meta==null){menu.slots.remove(slot);core.getLogger().warning("Marktartikel ohne ItemMeta uebersprungen: "+m);continue;} meta.setLore(List.of(isUnbuyable(m)?"Kauf: nur Spielerhandel":"Kauf: "+Money.format(buy.cents()),"Verkauf: "+Money.format(sell.cents()),"Trend: "+buy.trend(),isUnbuyable(m)?"Nicht bei der Bank kaufbar":"Linksklick: 1 kaufen", "Rechtsklick: 1 verkaufen")); icon.setItemMeta(meta); inv.setItem(slot,icon);
    }
    inv.setItem(45,button(Material.ARROW,"Vorherige Seite")); inv.setItem(46,button(Material.GRASS_BLOCK,"Bloecke")); inv.setItem(47,button(Material.IRON_INGOT,"Materialien")); inv.setItem(48,button(Material.IRON_SWORD,"Ausruestung")); inv.setItem(49,button(Material.APPLE,"Nahrung")); inv.setItem(50,button(Material.REDSTONE,"Technik")); inv.setItem(51,button(Material.CHEST,"Alle Kategorien")); inv.setItem(52,button(Material.GOLD_INGOT,"Verkaufen | Tageslimit uebrig: "+Money.format(remaining))); inv.setItem(53,button(Material.ARROW,"Naechste Seite")); if(core.npcPreviews.contains(p.getOpenInventory().getTopInventory())) core.npcPreviews.add(inv); p.openInventory(inv);
  }
  @EventHandler public void drag(InventoryDragEvent e) { if(e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true); }
  @EventHandler public void click(InventoryClickEvent e) {
    if(!(e.getView().getTopInventory().getHolder() instanceof Menu menu)) return; e.setCancelled(true);
    if(!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory()!=e.getView().getTopInventory() || !(e.isLeftClick() || e.isRightClick()) || e.isShiftClick()) return;
    int slot=e.getRawSlot(); Material m=menu.slots.get(slot);
    if(m!=null && core.npcPreviews.contains(e.getView().getTopInventory())) { p.sendMessage("NPC-Vorschau: Handel ist hier wegen unsicherer Item-Auslieferung gesperrt."); return; }
    try { if(m!=null) { if(e.isRightClick())sell(p,m); else buy(p,m); open(p,menu.category,menu.query,menu.page); }
      else switch(slot) { case 45 -> open(p,menu.category,menu.query,menu.page-1); case 53 -> open(p,menu.category,menu.query,menu.page+1); case 46 -> open(p,"bloecke",menu.query,0); case 47 -> open(p,"materialien",menu.query,0); case 48 -> open(p,"ausruestung",menu.query,0); case 49 -> open(p,"nahrung",menu.query,0); case 50 -> open(p,"technik",menu.query,0); case 51 -> open(p,"alle",menu.query,0); case 52 -> { if(core.npcPreviews.contains(e.getView().getTopInventory())) p.sendMessage("NPC-Vorschau: Handel gesperrt."); else openSell(p); } default -> {} }
    } catch(Exception ex) { core.getLogger().warning("Bank: "+ex); p.sendMessage("Bankaktion fehlgeschlagen. Bitte spaeter erneut versuchen."); }
  }
  static long durabilityPrice(long cents, int damage, int maxDurability) {
    if(maxDurability<=0) return cents;
    int remaining=Math.max(0,Math.min(maxDurability,maxDurability-Math.max(0,damage)));
    return cents / maxDurability * remaining + cents % maxDurability * remaining / maxDurability;
  }
  private void sell(Player p, Material m) throws Exception {
    if(!SurvivalItems.obtainable(core.getConfig(), m)) { p.sendMessage("Dieses Item gibt es im Survival nicht."); return; }
    PlayerInventory inv=p.getInventory(); int slot=-1;
    for(int i=0;i<36;i++) { ItemStack stack=inv.getItem(i); if(stack!=null && stack.getType()==m && eligible(stack)) { slot=i; break; } }
    if(slot<0) { p.sendMessage("Kein geeigneter Artikel im Inventar."); return; }
    ItemStack before=inv.getItem(slot).clone(); ItemStack after=before.clone(); after.setAmount(before.getAmount()-1); inv.setItem(slot,after.getAmount()==0?null:after);
    int damage=before.getItemMeta() instanceof Damageable d ? d.getDamage() : 0;
    try {
      if(!sellUnit(p.getUniqueId(),m,damage,sellEnchants(before),pricingConfig(core.getConfig()))){inv.setItem(slot,before);p.sendMessage("Tageslimit erreicht.");return;}
    } catch(Exception ex) { inv.setItem(slot,before); throw ex; }
    p.sendMessage("Verkauf abgeschlossen.");
  }
  private boolean sellUnit(UUID player, Material m, int damage, List<SellPricing.Enchant> enchants, SellPricing.Config config) throws Exception {
    String id=UUID.randomUUID().toString();
    return core.tx(c->{String day=dayKey(); String owner=DiscordRevenue.lock(c,player.toString(),day);
      long cp=sellBreakdown(m,price(c,m,false,false).cents(),enchants,damage,config).total();
      if(cp<=0 || affordable(DiscordRevenue.remainingOwner(c,owner,day,revenueLimit()),cp,1)==0) return false;
      if(!BuybackCaps.reserve(c,core.getConfig(),owner,m,day)) return false;
      if(cp>0 && !DiscordRevenue.reserve(c,owner,day,cp,revenueLimit())) throw new SQLException("Tageslimit konnte nicht reserviert werden");
      core.exec(c,"INSERT INTO bank_journal(id,player,item,amount,cp,status) VALUES(?,?,?,?,?,?)",id,player.toString(),m.name(),1,cp,"PENDING");
      BankJournal.finishSale(c,id,material->price(c,m,true,false)); WebsiteStats.event(c,m.name(),1,cp,"bank"); return true;});
  }
  static boolean eligible(ItemStack item) {
    if(item==null || item.getType().isAir()) return false;
    if(!item.hasItemMeta()) return true;
    Material material=item.getType();
    ItemMeta meta=item.getItemMeta().clone();
    if((meta.hasEnchants() || meta instanceof EnchantmentStorageMeta stored && stored.hasStoredEnchants())
        && material.getMaxDurability()<=0 && material!=Material.ENCHANTED_BOOK) return false;
    for(Enchantment enchant:new ArrayList<>(meta.getEnchants().keySet())) meta.removeEnchant(enchant);
    if(meta instanceof EnchantmentStorageMeta stored) for(Enchantment enchant:new ArrayList<>(stored.getStoredEnchants().keySet())) stored.removeStoredEnchant(enchant);
    if(meta instanceof Damageable damageable && material.getMaxDurability()>0) damageable.setDamage(0);
    ItemMeta empty=Bukkit.getItemFactory().getItemMeta(material);
    if(material==Material.TIPPED_ARROW && meta instanceof PotionMeta potion && empty instanceof PotionMeta defaultPotion)
      potion.setBasePotionType(defaultPotion.getBasePotionType());
    return Bukkit.getItemFactory().equals(meta,empty);
  }
  static long total(long unit,int amount) { return Math.multiplyExact(unit,amount); }
  static int affordable(long remaining,long unit,int amount) { return unit<=0?0:(int)Math.min(amount,Math.max(0,remaining)/unit); }
  static <T> void drain(int size, java.util.function.IntFunction<T> get, java.util.function.IntConsumer clear, java.util.function.Consumer<T> deliver) {
    for(int slot=0;slot<size;slot++){T item=get.apply(slot);clear.accept(slot);if(item!=null)deliver.accept(item);}
  }
  static final String SELL_MENU_OFF = "Das Verkaufsmenue ist gerade deaktiviert.";
 static boolean marketSellEnabled(org.bukkit.configuration.Configuration cfg) { return cfg.getBoolean("market.sell.enabled", false); }
 static boolean sellMenuEnabled(org.bukkit.configuration.Configuration cfg) { return cfg.getBoolean("sell-menu.enabled", false); }
 void openSell(Player p) throws Exception {
    if(!marketSellEnabled(core.getConfig()) || !sellMenuEnabled(core.getConfig())) { p.sendMessage(SELL_MENU_OFF); return; }
    SellMenu menu=new SellMenu(p.getUniqueId()); menu.inventory=Bukkit.createInventory(menu,54,"Bank: Verkaufen");
    sellMenus.put(menu.owner,menu); refreshSell(menu); p.openInventory(menu.inventory);
  }
  static final class SellConfirmation implements InventoryHolder {
    final SellMenu menu; SellConfirmation(SellMenu menu){this.menu=menu;}
    public Inventory getInventory(){return null;}
  }
  private void refreshSell(SellMenu menu) {
    Player p=Bukkit.getPlayer(menu.owner); if(p==null)return;
    menu.total=0; menu.count=0;
    for(int i=36;i<54;i++)menu.inventory.setItem(i,button(Material.GRAY_STAINED_GLASS_PANE," "));
    menu.inventory.setItem(53,button(Material.BARRIER,"Schliessen"));
    // One DB round trip per distinct material (price) and material/value (limit) instead of per slot.
    Map<Material,Long> priceCache=new EnumMap<>(Material.class); Map<String,Boolean> allowedCache=new HashMap<>();
    for(int i=0;i<36;i++) {
      ItemStack real=p.getInventory().getItem(i); menu.snapshots[i]=real==null?null:real.clone(); menu.sellable[i]=false;
      if(real==null||real.getType().isAir()){menu.inventory.setItem(i,null);continue;}
      ItemStack icon=real.clone(); ItemMeta meta=icon.getItemMeta(); List<String> lore=meta.hasLore()?new ArrayList<>(meta.getLore()):new ArrayList<>();
      try {
        String reason=null; SellPricing.Breakdown breakdown=null;
        if(!marketSellEnabled(core.getConfig())||!sellMenuEnabled(core.getConfig())) reason="Bankverkauf deaktiviert";
        else if(!eligible(real)||!hasPrice(real.getType())||core.getConfig().getStringList("market.unsellable").contains(real.getType().name())) reason="Nicht handelbar";
        else {
          int damage=real.getItemMeta() instanceof Damageable d?d.getDamage():0;
          SellConfig config=sellConfig(real.getType());
          Long cents=priceCache.get(real.getType());
          if(cents==null){cents=core.tx(c->price(c,real.getType(),false,false,config).cents());priceCache.put(real.getType(),cents);}
          breakdown=sellBreakdown(real.getType(),cents,sellEnchants(real),damage,config.pricing());
          long unitTotal=breakdown.total();
          if(unitTotal==0)reason="Wert 0 CP (komplett beschaedigt)";
          else {
            String key=real.getType().name()+"|"+unitTotal; Boolean allowed=allowedCache.get(key);
            if(allowed==null){allowed=previewAllowed(p,real.getType(),unitTotal,config);allowedCache.put(key,allowed);}
            if(!allowed)reason="Limit erreicht";
          }
        }
        if(reason!=null) lore.add(org.bukkit.ChatColor.GRAY+reason);
        else {
          long unit=breakdown.total(); menu.sellable[i]=true; menu.total=Math.addExact(menu.total,total(unit,real.getAmount())); menu.count+=real.getAmount();
          lore.add("Preis/Stueck: "+Money.format(unit)); lore.add("Preis/Stack ("+real.getAmount()+"): "+Money.format(total(unit,real.getAmount())));
          lore.add("Basis: "+Money.format(breakdown.base())); lore.add("Abzug Haltbarkeit: "+Money.format(breakdown.durabilityReduction()));
          lore.add("Roher Bonus: "+Money.format(breakdown.enchantBonus())); lore.add("Bonus-Deckel: "+Money.format(configCap())+"; nach Deckel: "+Money.format(breakdown.enchantBonusCapped()));
          lore.add("Bonus nach Haltbarkeit: "+Money.format(breakdown.scaledBonus()));
          if(breakdown.baseLimited())lore.add("Bonus: max 100% der Basis");
          lore.add("Gesamt/Stueck: "+Money.format(unit)); lore.add("Klick: 1 verkaufen"); lore.add("Shift-Klick: ganzen Stack verkaufen");
        }
      } catch(Exception ex){lore.add(org.bukkit.ChatColor.GRAY+"Preis momentan nicht verfuegbar");core.getLogger().warning("Verkaufspreis-Vorschau: "+ex);}
      meta.setLore(lore);icon.setItemMeta(meta);menu.inventory.setItem(i,icon);
    }
    ItemStack all=button(Material.LIME_WOOL,"Alles Verkaufbare verkaufen"); ItemMeta m=all.getItemMeta();m.setLore(List.of("Gesamt: "+Money.format(menu.total),"Items: "+menu.count,"Preis und Limits werden beim Verkauf geprueft."));all.setItemMeta(m);menu.inventory.setItem(49,all);
  }
  private long configCap(){return pricingConfig(core.getConfig()).enchantCap();}
  private boolean previewAllowed(Player p,Material material,long unit,SellConfig cfg)throws Exception {
    return core.tx(c->{String day=DailyRevenue.dayKey(java.time.Instant.now(),cfg.zone(),cfg.resetHour()); String owner=DiscordRevenue.owner(c,p.getUniqueId().toString(),false);
      if(DiscordRevenue.remainingOwner(c,owner,day,cfg.revenueLimit())<unit || cfg.perDay()<1)return false;
      if(cfg.perDay()==Integer.MAX_VALUE)return true;
      try(PreparedStatement q=c.prepareStatement("SELECT amount FROM bank_sell_cap_daily WHERE owner=? AND item=? AND day_key=?")){q.setString(1,owner);q.setString(2,material.name());q.setString(3,day);try(ResultSet r=q.executeQuery()){return !r.next()||r.getInt(1)<cfg.perDay();}}
    });
  }
  interface PreviewQuote { Price get(Material material) throws SQLException; }
  static Map<Material,Price> previewPrices(List<Material> materials, PreviewQuote quote) throws SQLException {
    Map<Material,Price> prices=new EnumMap<>(Material.class); for(Material material:materials)if(!prices.containsKey(material))prices.put(material,quote.get(material));return prices;
  }
  static String sellPreview(String item,long unit,int amount) { return item+": "+Money.format(unit)+" / Stueck, "+Money.format(total(unit,amount))+" / Stack"; }
  @EventHandler public void sellDrag(InventoryDragEvent e) { if(e.getView().getTopInventory().getHolder() instanceof SellMenu || e.getView().getTopInventory().getHolder() instanceof SellConfirmation)e.setCancelled(true); }
  @EventHandler public void sellClick(InventoryClickEvent e) {
    InventoryHolder holder=e.getView().getTopInventory().getHolder();
    if(!(holder instanceof SellMenu) && !(holder instanceof SellConfirmation))return;
    e.setCancelled(true); if(!(e.getWhoClicked() instanceof Player p))return;
    int slot=e.getRawSlot();
    if(holder instanceof SellConfirmation confirm){
      if(!e.isLeftClick() || e.isShiftClick())return;
      if(slot==11){p.openInventory(confirm.menu.inventory);for(int i=0;i<36;i++)if(confirm.menu.sellable[i])sellSlot(p,confirm.menu,i,true);}
      else if(slot==15)p.openInventory(confirm.menu.inventory); return;
    }
    SellMenu menu=(SellMenu)holder;
    if(slot>=0 && slot<36 && (e.isLeftClick()||e.isRightClick()))sellSlot(p,menu,slot,e.isShiftClick());
    else if(slot==49)showSellConfirmation(p,menu); else if(slot==53)p.closeInventory();
  }
  private void showSellConfirmation(Player p,SellMenu menu){
    refreshSell(menu); Inventory inv=Bukkit.createInventory(new SellConfirmation(menu),27,"Verkauf bestaetigen");
    for(int i=0;i<27;i++)inv.setItem(i,button(Material.GRAY_STAINED_GLASS_PANE," "));
    ItemStack info=button(Material.PAPER,"Gesamt: "+Money.format(menu.total));ItemMeta meta=info.getItemMeta();meta.setLore(List.of("Items: "+menu.count));info.setItemMeta(meta);inv.setItem(13,info);
    inv.setItem(11,button(Material.LIME_WOOL,"Bestaetigen"));inv.setItem(15,button(Material.RED_WOOL,"Abbrechen"));p.openInventory(inv);
  }
  private void sellSlot(Player p,SellMenu menu,int slot,boolean whole){
    if(!marketSellEnabled(core.getConfig())||!sellMenuEnabled(core.getConfig())||!menu.sellable[slot])return;
    ItemStack expected=menu.snapshots[slot], real=p.getInventory().getItem(slot);
    if(expected==null||real==null||!real.isSimilar(expected)||real.getAmount()!=expected.getAmount()){refreshSell(menu);return;}
    if(!eligible(real)||!hasPrice(real.getType())||core.getConfig().getStringList("market.unsellable").contains(real.getType().name())){refreshSell(menu);return;}
    int amount=whole?real.getAmount():1; ItemStack sold=real.clone();sold.setAmount(amount);
    String lock=p.getUniqueId()+":"+slot, key=UUID.randomUUID().toString(), nbt=Base64.getEncoder().encodeToString(sold.serializeAsBytes());
    int damage=sold.getItemMeta() instanceof Damageable d?d.getDamage():0; SellConfig config=sellConfig(sold.getType()); List<SellPricing.Enchant> enchants=sellEnchants(sold);
    sellFlow.start(lock,()->{ItemStack rest=real.clone();rest.setAmount(real.getAmount()-amount);p.getInventory().setItem(slot,rest.getAmount()==0?null:rest);},
      ()->sellStack(p.getUniqueId(),key,nbt,sold,damage,enchants,config), result->{finishSell(p,p.getUniqueId(),key,sold,result);if(sellMenus.get(menu.owner)==menu)refreshSell(menu);});
    refreshSell(menu);
  }
  private record SellResult(SellTransaction.CommitStatus status, boolean limited, long cp, long balance) {}
  private SellResult sellStack(UUID uuid,String key,String nbt,ItemStack stack,int damage,List<SellPricing.Enchant> enchants,SellConfig config) {
     Material material=stack.getType(); int amount=stack.getAmount();
    long[] cp={0}, balance={0}; Connection original=null;
    SellTransaction.CommitStatus status=SellTransaction.CommitStatus.NOT_COMMITTED;
    boolean limited=false;
    try {
      // Persist the recovery record before the sale can reach COMMIT. If this
      // fails, no sale transaction is started and the stack can be refunded.
      try(Connection pending=core.connection(); PreparedStatement q=pending.prepareStatement("INSERT INTO sell_pending(idem_key,player_uuid,item_nbt,amount,cp) VALUES(?,?,?,?,0)")) {
        q.setString(1,key); q.setString(2,uuid.toString()); q.setString(3,nbt); q.setInt(4,amount); q.executeUpdate();
      }
      original=core.connection();
      try (Connection c=original) {
        try (Statement q=c.createStatement(); ResultSet r=q.executeQuery("SELECT GET_LOCK('craftcore-write', 5)")) {if(!r.next()||r.getInt(1)!=1)throw new SQLException("DB lock timeout");}
        try {
          SellTransaction.run(c,key,tx->{
            String day=DailyRevenue.dayKey(java.time.Instant.now(),config.zone(),config.resetHour()), owner=DiscordRevenue.lock(tx,uuid.toString(),day);
            long unit=sellBreakdown(material,price(tx,material,false,false,config).cents(),enchants,damage,config.pricing()).total();
            if(unit<=0) throw new SellLimit();
            cp[0]=total(unit,amount);
            if(affordable(DiscordRevenue.remainingOwner(tx,owner,day,config.revenueLimit()),unit,amount)<amount) throw new SellLimit();
            for(int i=0;i<amount;i++) if(!BuybackCaps.reserve(tx,config.perDay(),owner,material,day)) throw new SellLimit();
            if(cp[0]>0 && !DiscordRevenue.reserve(tx,owner,day,cp[0],config.revenueLimit())) throw new SellLimit();
            core.exec(tx,"INSERT INTO bank_journal(id,player,item,amount,cp,status) VALUES(?,?,?,?,?,?)",key,uuid.toString(),material.name(),amount,cp[0],"PENDING");
            BankJournal.finishSale(tx,key,m->price(tx,material,true,false,config));
            WebsiteStats.event(tx,material.name(),amount,cp[0],"bank");
            core.exec(tx,"UPDATE sell_pending SET cp=?, resolution='COMMITTED' WHERE idem_key=?",cp[0],key);
            balance[0]=core.balance(tx,uuid); return null;
          });
        } finally {try(Statement q=c.createStatement()){q.execute("SELECT RELEASE_LOCK('craftcore-write')");}}
      }
      status=SellTransaction.CommitStatus.COMMITTED;
    } catch(Exception ex) {
      limited=ex instanceof SellLimit;
      if(original!=null) status=core.checkCommitStatus(key,original,java.time.Duration.ofSeconds(2));
      if(status==SellTransaction.CommitStatus.UNKNOWN) {
        core.getLogger().warning("Ungeklaerter Bankverkauf " + key + " fuer " + uuid + "; sell_pending pruefen");
      } else if(status==SellTransaction.CommitStatus.NOT_COMMITTED) {
        try(Connection c=core.connection(); PreparedStatement q=c.prepareStatement("UPDATE sell_pending SET resolution='NOT_COMMITTED' WHERE idem_key=?")) {
          q.setString(1,key); q.executeUpdate();
        } catch(SQLException pending) {core.getLogger().warning("Pending-Status nicht aktualisiert fuer " + key);}
      }
    }
    return new SellResult(status,limited,cp[0],balance[0]);
  }
  private void finishSell(Player player,UUID uuid,String key,ItemStack stack,SellResult result) {
    SellStackFlow.resolve(result.status(),
      ()->{core.sellBalance(uuid,result.balance()); player.sendMessage("Verkauft: "+stack.getAmount()+" Gegenstaende fuer "+Money.format(result.cp())+". Neuer Kontostand: "+Money.format(result.balance())); player.playSound(player.getLocation(),Sound.ENTITY_EXPERIENCE_ORB_PICKUP,1f,1f);},
      ()->{if(player.isOnline() && Bukkit.getPlayer(uuid)==player) { giveBack(player,List.of(stack)); player.sendMessage(result.limited()?"Tageslimit erreicht; Stack zurueckgegeben.":"Verkauf fehlgeschlagen; Stack zurueckgegeben."); }
        else { String nbt=Base64.getEncoder().encodeToString(stack.serializeAsBytes()); Bukkit.getScheduler().runTaskAsynchronously(core,()->{ try { core.tx(c->{SellTransaction.refundOwed(c,key,uuid.toString(),nbt,stack.getAmount());return null;}); queueRefunds(uuid); } catch(Exception ex) {core.getLogger().severe("Rueckerstattung nicht gespeichert fuer "+key+": "+ex); } }); } },
      ()->player.sendMessage("Verkauf ungeklaert. Bitte Admin informieren: "+key));
  }
  @EventHandler public void refundJoin(PlayerJoinEvent e) { UUID uuid=e.getPlayer().getUniqueId(); Bukkit.getScheduler().runTaskAsynchronously(core,()->queueRefunds(uuid)); }
  private void queueRefunds(UUID uuid) {
    try { int count=core.tx(c->SellTransaction.queueRefunds(c,uuid.toString()));
      if(count>0) Bukkit.getScheduler().runTask(core,()->{Player online=Bukkit.getPlayer(uuid); if(online!=null && online.isOnline()) Deliveries.collect(core,online);});
    } catch(Exception ex) {core.getLogger().warning("Rueckerstattung fuer "+uuid+": "+ex);}
  }
  private static final class SellLimit extends Exception {}
  private void giveBack(Player p,List<ItemStack> items) {
    MenuReturn.giveBack(items,item->new ArrayList<>(p.getInventory().addItem(item).values()),item->{
      try { core.tx(c->{Deliveries.insert(c,UUID.randomUUID().toString(),p.getUniqueId().toString(),item,"menu_return");return null;}); }
      catch(Exception ex) { core.getLogger().severe("Menue-Rueckgabe nicht gespeichert: "+ex);p.getWorld().dropItemNaturally(p.getLocation(),item); }
    });
  }
  @EventHandler public void sellClose(InventoryCloseEvent e){
    if(!core.isEnabled())return;
    if(e.getInventory().getHolder() instanceof SellMenu menu)Bukkit.getScheduler().runTask(core,()->{Player p=Bukkit.getPlayer(menu.owner);if(p==null || !(p.getOpenInventory().getTopInventory().getHolder() instanceof SellConfirmation))sellMenus.remove(menu.owner,menu);});
    else if(e.getInventory().getHolder() instanceof SellConfirmation confirmation)Bukkit.getScheduler().runTask(core,()->{Player p=Bukkit.getPlayer(confirmation.menu.owner);if(p==null || p.getOpenInventory().getTopInventory().getHolder()!=confirmation.menu)sellMenus.remove(confirmation.menu.owner,confirmation.menu);});
  }
  @EventHandler public void sellQuit(PlayerQuitEvent e){sellMenus.remove(e.getPlayer().getUniqueId());}
  void shutdown(){for(SellMenu menu:new ArrayList<>(sellMenus.values())){Player p=Bukkit.getPlayer(menu.owner);if(p!=null)p.closeInventory();}sellMenus.clear();}
  private void finishSale(String id) {
    try { core.tx(c->{
      BankJournal.finishSale(c,id,(material)->{ Material m=Material.matchMaterial(material); if(m==null || !items.containsKey(m)) throw new SQLException("Marktartikel fehlt fuer Journal "+id); price(c,m,true,false); }); return null;
    }); } catch(Exception ex) { core.getLogger().warning("Bankverkauf "+id+": "+ex); }
  }
  private boolean isUnbuyable(Material m) { return unbuyable(core.getConfig(),m); }
  /** Bank purchase checks for order type bank_buy: same as buy(Player,Material). */
  BankBuy.Env bankEnv() { return new BankBuy.Env() {
    private Material mat(String item) { Material m=Material.matchMaterial(item); return m!=null && items.containsKey(m) && SurvivalItems.obtainable(core.getConfig(), m) ? m : null; }
    public String blocked(Connection c,String item) throws Exception { Material m=mat(item); if(m==null) return "UNKNOWN"; return buyEnabled(isUnbuyable(m),tradable(m),locked(c,m,true)) ? null : "LOCKED"; }
    public int maxStack(String item) { Material m=mat(item); return m==null ? 0 : m.getMaxStackSize(); }
    public long unitPrice(Connection c,String item) throws Exception { return price(c,mat(item),false,true).cents(); }
    public void moved(Connection c,String item) throws Exception { price(c,mat(item),true,true); }
    public boolean reserve(Connection c,String uuid,String item) throws Exception {
      String owner=DiscordRevenue.owner(c,uuid,true); int limit=core.getConfig().getInt("market.daily-buy-limits."+item,core.getConfig().getInt("market.daily-buy-limit",512));
      return BankBuyLimit.reserve(c,owner,item,dayKey(),limit); }
    public long balance(Connection c,String uuid) throws Exception { return core.balance(c,UUID.fromString(uuid)); }
    public void debit(Connection c,String uuid,long cents) throws Exception { core.exec(c,"UPDATE accounts SET balance=balance-? WHERE uuid=?",cents,uuid); }
    public void deliver(Connection c,String id,String uuid,String item,int amount) throws Exception { Deliveries.insert(c,id,uuid,new ItemStack(mat(item),amount),"bank"); }
    public void stats(Connection c,String item,int amount,long cents) throws Exception { WebsiteStats.event(c,item,amount,cents,"bank"); }
  }; }
  private void buy(Player p,Material m) throws Exception {
    if(!SurvivalItems.obtainable(core.getConfig(), m)) { p.sendMessage("Dieses Item gibt es im Survival nicht."); return; }
    if(!buyEnabled(isUnbuyable(m),tradable(m),core.tx(c->locked(c,m,true)))) { p.sendMessage("Dieser Artikel ist nur durch Spielerhandel erhaeltlich."); return; }
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
