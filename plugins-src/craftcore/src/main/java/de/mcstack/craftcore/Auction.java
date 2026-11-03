package de.mcstack.craftcore;
import de.mcstack.craftcore.vip.VipStarGuard;
import de.mcstack.craftcore.vip.VipStarRules;
import java.sql.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

final class Auction implements Listener {
  final CraftCore core; static Auction instance;
  record Listing(String id,String seller,String name,long price,String item) {}
  static final class Menu implements InventoryHolder {final List<Listing> rows;final boolean own;final String category,query;final int page;Menu(List<Listing> r,boolean o,String c,String q,int p){rows=r;own=o;category=c;query=q;page=p;}public Inventory getInventory(){return null;}}
  static final class Confirm implements InventoryHolder {final Listing row;Confirm(Listing r){row=r;}public Inventory getInventory(){return null;}}
  Auction(CraftCore c){core=c;instance=this;}
  void start()throws Exception {
    core.tx(c->{core.exec(c,"CREATE TABLE IF NOT EXISTS purchase_ids (id CHAR(36) PRIMARY KEY)");return null;});
    core.tx(c->{core.exec(c,"CREATE TABLE IF NOT EXISTS ah_journal (id CHAR(36) PRIMARY KEY,seller CHAR(36) NOT NULL,seller_name VARCHAR(16) NOT NULL,item LONGTEXT NOT NULL,material VARCHAR(64) NOT NULL,amount INT NOT NULL,price BIGINT NOT NULL,created_at BIGINT NOT NULL,expires_at BIGINT NOT NULL,status VARCHAR(16) NOT NULL,recipient CHAR(36) NULL,INDEX ah_status(status,expires_at),INDEX ah_recipient(recipient,status))");core.exec(c,"CREATE TABLE IF NOT EXISTS ah_fee_journal (purchase_id CHAR(36) PRIMARY KEY, listing_id CHAR(36) NOT NULL, buyer CHAR(36) NOT NULL, seller CHAR(36) NOT NULL, gross BIGINT NOT NULL, fee BIGINT NOT NULL, created_at BIGINT NOT NULL)");core.exec(c,"UPDATE ah_journal SET status='OPEN' WHERE status='PENDING'");return null;});
    core.tx(c->{RealAuctions.schema(c);return null;});
    Bukkit.getPluginManager().registerEvents(this,core); expire();Bukkit.getScheduler().runTaskTimer(core,this::expire,1200,1200);
  }
  void expireReal(){try{long now=System.currentTimeMillis();List<Long> ids=core.tx(c->{List<Long> out=new ArrayList<>();try(PreparedStatement s=c.prepareStatement("SELECT id FROM auctions WHERE status='OPEN' AND ends_at<=?")){s.setLong(1,now);try(ResultSet r=s.executeQuery()){while(r.next())out.add(r.getLong(1));}}return out;});for(long id:ids)core.tx(c->{RealAuctions.settle(c,id,now);return null;});}catch(Exception ex){core.getLogger().warning("Gebotsauktion Abschluss: "+ex.getClass().getSimpleName());}}
  void expire(){expireReal();try{core.tx(c->{try(PreparedStatement q=c.prepareStatement("SELECT id,seller FROM ah_journal WHERE status='OPEN' AND expires_at<=? FOR UPDATE")){q.setLong(1,System.currentTimeMillis());try(ResultSet r=q.executeQuery()){while(r.next()){Deliveries.fromJournal(c,"ah_journal",r.getString(1),r.getString(2));core.exec(c,"UPDATE ah_journal SET status='DELIVERY',recipient=seller WHERE id=? AND status='OPEN'",r.getString(1));}}}return null;});}catch(Exception ex){core.getLogger().warning("Auktionsablauf: "+ex);}}
  void command(Player p,String[] a)throws Exception {
    if(a.length==0){open(p,false,"alle","",0);return;}
    if(a.length==2 && Set.of("zurueckziehen","zur?ckziehen","cancel").contains(a[0].toLowerCase(Locale.ROOT))) {
      try { UUID.fromString(a[1]); } catch(IllegalArgumentException ex) { p.sendMessage("Ungueltige Angebots-ID."); return; }
      boolean ok=core.tx(c->AuctionStore.cancel(c,a[1],p.getUniqueId().toString()));
      p.sendMessage(ok?"Angebot zurueckgezogen. Item im Abholfach: /abholen":"Angebot nicht verfuegbar oder bereits teilweise verkauft."); return;
    }
    if(a.length==1&&a[0].equalsIgnoreCase("my")){open(p,true,"alle","",0);return;}
    if(a.length==3&&a[0].equalsIgnoreCase("auction")){createAuction(p,a[1],a[2]);return;}
    if(a.length==2&&a[0].equalsIgnoreCase("sell")){sell(p,a[1]);return;}
    if(a.length>=2&&a[0].equalsIgnoreCase("search")){open(p,false,"alle",String.join(" ",Arrays.copyOfRange(a,1,a.length)).toLowerCase(Locale.ROOT).replace(' ','_'),0);return;}
    p.sendMessage("/ah | /ah sell <Preis> | /ah my | /ah search <Suchbegriff>");
  }
  void createAuction(Player p,String input,String duration)throws Exception {
    long price,hours;try{price=Money.cents(input);if(!duration.matches("[0-9]+h"))throw new IllegalArgumentException();hours=Long.parseLong(duration.substring(0,duration.length()-1));}catch(RuntimeException ex){p.sendMessage("ERR INVALID");return;}
    long length;try{length=Math.multiplyExact(hours,AuctionRules.HOUR);}catch(ArithmeticException ex){p.sendMessage("ERR INVALID");return;}
    ItemStack hand=p.getInventory().getItemInMainHand();if(VipStarGuard.tagged(hand)){p.sendMessage(VipStarRules.UNSELLABLE_MESSAGE);return;}if(price<=0||!AuctionRules.validDuration(length)||hand.getType().isAir()||!Market.instance.hasPrice(hand.getType())){p.sendMessage("ERR INVALID");return;}
    ItemStack item=hand.clone();long now=System.currentTimeMillis();long minimum=core.tx(c->Math.multiplyExact(Market.instance.price(c,item.getType(),false,false).cents(),item.getAmount()));if(price<minimum){p.sendMessage("ERR BELOW_BANK_PRICE "+minimum);return;}
    String data=Base64.getEncoder().encodeToString(item.serializeAsBytes());
    // PENDING is quarantined if either persistence step is interrupted. Never automatically
    // return its item: the player's saved inventory may still contain the same stack.
    long id=core.tx(c->{try(PreparedStatement s=c.prepareStatement("INSERT INTO auctions(seller,item,material,amount,min_price,ends_at,status,created_at) VALUES(?,?,?,?,?,?,'PENDING',?)",Statement.RETURN_GENERATED_KEYS)){s.setString(1,p.getUniqueId().toString());s.setString(2,data);s.setString(3,item.getType().name());s.setInt(4,item.getAmount());s.setLong(5,price);s.setLong(6,Math.addExact(now,length));s.setLong(7,now);s.executeUpdate();try(ResultSet r=s.getGeneratedKeys()){if(!r.next())throw new SQLException("auction id missing");return r.getLong(1);}}});
    p.getInventory().setItemInMainHand(null);p.saveData();
    core.tx(c->{core.exec(c,"UPDATE auctions SET status='OPEN' WHERE id=? AND status='PENDING'",id);return null;});p.sendMessage("Auktion "+id+" eingestellt. Mindestpreis: "+Money.format(price));
  }
  void sell(Player p,String input)throws Exception {
    long price=Money.cents(input);if(price<=0){p.sendMessage("Preis muss positiv sein.");return;}ItemStack hand=p.getInventory().getItemInMainHand();if(VipStarGuard.tagged(hand)){p.sendMessage(VipStarRules.UNSELLABLE_MESSAGE);return;}if(hand.getType().isAir()){p.sendMessage("Halte den Stapel in der Hand.");return;}
    ItemStack original=hand.clone();String item=Base64.getEncoder().encodeToString(original.serializeAsBytes()),id=UUID.randomUUID().toString();long now=System.currentTimeMillis(),end=Math.addExact(now,Math.multiplyExact(core.getConfig().getLong("auction.duration-hours",48),3600000L));p.getInventory().setItemInMainHand(null);
    try{core.tx(c->{core.exec(c,"INSERT INTO ah_journal(id,seller,seller_name,item,material,amount,price,created_at,expires_at,status) VALUES(?,?,?,?,?,?,?,?,?,'PENDING')",id,p.getUniqueId().toString(),p.getName(),item,original.getType().name(),original.getAmount(),price,now,end);return null;});}catch(Exception ex){p.getInventory().setItemInMainHand(original);throw ex;}
    core.tx(c->{core.exec(c,"UPDATE ah_journal SET status='OPEN' WHERE id=? AND status='PENDING'",id);return null;});p.sendMessage("Angebot eingestellt: "+Money.format(price));
  }
  List<Listing> list(Player p,boolean own,String category,String query)throws Exception{return core.tx(c->{List<Listing> rows=new ArrayList<>();String sql="SELECT id,seller,seller_name,price,item,material FROM ah_journal WHERE status='OPEN' AND expires_at>?"+(own?" AND seller=?":"")+" ORDER BY created_at,id";try(PreparedStatement s=c.prepareStatement(sql)){s.setLong(1,System.currentTimeMillis());if(own)s.setString(2,p.getUniqueId().toString());try(ResultSet r=s.executeQuery()){while(r.next()){Material m=Material.matchMaterial(r.getString(6));if(m!=null&&(category.equals("alle")||PriceCatalog.category(m).equals(category))&&m.name().toLowerCase(Locale.ROOT).contains(query))rows.add(new Listing(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getString(5)));}}}return rows;});}
  ItemStack button(Material m,String n){ItemStack i=new ItemStack(m);ItemMeta meta=i.getItemMeta();meta.setDisplayName(n);i.setItemMeta(meta);return i;}
  void open(Player p,boolean own,String category,String query,int requested)throws Exception {List<Listing> all=list(p,own,category,query);int pages=Math.max(1,(all.size()+44)/45),page=Math.max(0,Math.min(requested,pages-1));List<Listing> rows=new ArrayList<>(all.subList(page*45,Math.min(all.size(),page*45+45)));Inventory inv=Bukkit.createInventory(new Menu(rows,own,category,query,page),54,(own?"Meine Auktionen ":"Auktionshaus ")+(page+1)+"/"+pages);
    for(int i=0;i<rows.size();i++){Listing r=rows.get(i);ItemStack icon=ItemStack.deserializeBytes(Base64.getDecoder().decode(r.item()));ItemMeta meta=icon.getItemMeta();List<String> lore=meta.hasLore()?new ArrayList<>(meta.getLore()):new ArrayList<>();lore.add("Verkaeufer: "+r.name());lore.add("Preis: "+Money.format(r.price()));lore.add(own?"Linksklick: entfernen":"Linksklick: kaufen");meta.setLore(lore);icon.setItemMeta(meta);inv.setItem(i,icon);}inv.setItem(45,button(Material.ARROW,"Vorherige Seite"));inv.setItem(46,button(Material.GRASS_BLOCK,"Bloecke"));inv.setItem(47,button(Material.IRON_INGOT,"Materialien"));inv.setItem(48,button(Material.IRON_SWORD,"Ausruestung"));inv.setItem(49,button(Material.APPLE,"Nahrung"));inv.setItem(50,button(Material.REDSTONE,"Technik"));inv.setItem(51,button(Material.CHEST,"Alle"));inv.setItem(52,button(Material.COMPASS,"Suche: /ah search <Begriff>"));inv.setItem(53,button(Material.ARROW,"Naechste Seite"));if(core.npcPreviews.contains(p.getOpenInventory().getTopInventory()))core.npcPreviews.add(inv);p.openInventory(inv);
  }
  @EventHandler public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof Menu||e.getView().getTopInventory().getHolder() instanceof Confirm||e.getView().getTopInventory().getHolder() instanceof PurchaseMenu)e.setCancelled(true);}
  @EventHandler public void click(InventoryClickEvent e){Object h=e.getView().getTopInventory().getHolder();if(h instanceof PurchaseMenu pm && pm.table.equals("ah_journal")){e.setCancelled(true);if(e.getWhoClicked() instanceof Player p && e.getClickedInventory()==e.getView().getTopInventory() && e.isLeftClick() && !e.isShiftClick())try{pm.click(core,p,e.getRawSlot());}catch(Exception ex){core.getLogger().warning("Auktionskauf: "+ex);p.sendMessage("Kauf fehlgeschlagen.");}return;}if(!(h instanceof Menu)&&!(h instanceof Confirm))return;e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p)||e.getClickedInventory()!=e.getView().getTopInventory()||!e.isLeftClick()||e.isShiftClick())return;try{
    if(e.getRawSlot()<45 && core.npcPreviews.contains(e.getView().getTopInventory())) {p.sendMessage("NPC-Vorschau: Handel ist hier wegen unsicherer Item-Auslieferung gesperrt.");return;}
    if(h instanceof Confirm cf){if(e.getRawSlot()==15){p.closeInventory();return;}if(e.getRawSlot()!=11)return;int fee=core.getConfig().getInt("auction.fee-basis-points",500);if(fee<0||fee>10000)throw new IllegalArgumentException("Ungueltige Auktionsgebuehr");String result=core.tx(c->AuctionStore.buy(c,cf.row.id(),p.getUniqueId().toString(),System.currentTimeMillis(),fee,cf.row.price(),cf.row.item()));p.closeInventory();if(result==null){p.sendMessage("Kauf abgeschlossen.");deliver(p,cf.row.id());}else p.sendMessage(result);return;}
    Menu m=(Menu)h;int slot=e.getRawSlot();if(slot<45&&slot>=0&&slot<m.rows.size()){Listing r=m.rows.get(slot);if(m.own){boolean ok=core.tx(c->AuctionStore.cancel(c,r.id(),p.getUniqueId().toString()));p.closeInventory();if(ok)p.sendMessage("Angebot zurueckgezogen. Item im Abholfach: /abholen");else p.sendMessage("Angebot nicht verfuegbar oder bereits teilweise verkauft.");}else{new PurchaseMenu("ah_journal",r.id(),r.price(),r.item()).show(p);}return;}
    switch(slot){case 45->open(p,m.own,m.category,m.query,m.page-1);case 53->open(p,m.own,m.category,m.query,m.page+1);case 46->open(p,m.own,"bloecke",m.query,0);case 47->open(p,m.own,"materialien",m.query,0);case 48->open(p,m.own,"ausruestung",m.query,0);case 49->open(p,m.own,"nahrung",m.query,0);case 50->open(p,m.own,"technik",m.query,0);case 51->open(p,m.own,"alle",m.query,0);case 52->p.sendMessage("Suche: /ah search <Begriff>");default->{}}
    }catch(Exception ex){core.getLogger().warning("Auktion: "+ex);p.sendMessage("Auktionsaktion fehlgeschlagen.");}}
  void deliver(Player p,String id){}
}
