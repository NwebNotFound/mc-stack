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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;

final class Shop implements Listener {
  static Shop instance;
  final CraftCore core;
  record Listing(String id,String seller,String name,long price,String item) {}
  static final class Menu implements InventoryHolder {
    final List<Listing> rows; final String filter; final int page; final boolean cancel;
    Menu(List<Listing> rows,String filter,int page,boolean cancel){this.rows=rows;this.filter=filter;this.page=page;this.cancel=cancel;}
    public Inventory getInventory(){return null;}
  }
  static final class Shops implements InventoryHolder {
    final List<Listing> sellers; final int page; final int pages;
    Shops(List<Listing> sellers,int page,int pages){this.sellers=sellers;this.page=page;this.pages=pages;}
    public Inventory getInventory(){return null;}
  }
  void overview(Player p,int page)throws Exception {
    Map<String,Listing> sellers=new LinkedHashMap<>();
    for(Listing row:listings(null,false))sellers.putIfAbsent(row.seller(),row);
    List<Listing> all=new ArrayList<>(sellers.values());
    all.sort(Comparator.comparing(Listing::name,String.CASE_INSENSITIVE_ORDER));
    int pages=Math.max(1,(all.size()+44)/45);page=Math.max(0,Math.min(page,pages-1));
    List<Listing> rows=new ArrayList<>(all.subList(page*45,Math.min(all.size(),page*45+45)));
    Inventory inv=Bukkit.createInventory(new Shops(rows,page,pages),54,"Spielershops");
    for(int i=0;i<rows.size();i++){
      Listing row=rows.get(i);ItemStack head=new ItemStack(Material.PLAYER_HEAD);SkullMeta meta=(SkullMeta)head.getItemMeta();
      meta.setOwningPlayer(Bukkit.getOfflinePlayer(UUID.fromString(row.seller())));meta.setDisplayName(row.name());
      meta.setLore(List.of("Linksklick: Shop oeffnen"));head.setItemMeta(meta);inv.setItem(i,head);
    }
    if(page>0)inv.setItem(45,button(Material.ARROW,"Vorherige Seite"));
    inv.setItem(49,button(Material.EMERALD,"Kaufauftraege"));
    inv.setItem(48,button(Material.CHEST,"Meine Angebote"));
    if(page+1<pages)inv.setItem(53,button(Material.ARROW,"Naechste Seite"));p.openInventory(inv);
  }
  static final class Edit implements InventoryHolder {
    Listing row; final int page; long pendingPrice; final WithdrawConfirm withdraw=new WithdrawConfirm(); Edit(Listing row,int page){this.row=row;this.page=page;this.pendingPrice=row.price();}
    public Inventory getInventory(){return null;}
  }
  static long editDelta(int slot){return switch(slot){case 9 -> -1000;case 10 -> -100;case 11 -> -10;case 13 -> 10;case 14 -> 100;case 15 -> 1000;default -> 0;};}
  ItemStack editButton(Material type,String name,String lore){ItemStack item=button(type,name);ItemMeta meta=item.getItemMeta();meta.setLore(List.of(lore));item.setItemMeta(meta);return item;}
  void edit(Player p,Listing row,int page){Edit edit=new Edit(row,page);Inventory inv=Bukkit.createInventory(edit,27,"Angebot bearbeiten");renderEdit(inv,edit);p.openInventory(inv);
    // The listing icon is a display copy of the DB-owned item; there are no owned input slots.
    core.menuReturns.registerHeld(p.getUniqueId(),edit,new int[0],inv::getItem,slot->inv.setItem(slot,null),
      item->new ArrayList<>(p.getInventory().addItem(item).values()),item->{try{core.tx(c->{Deliveries.insert(c,UUID.randomUUID().toString(),p.getUniqueId().toString(),item,"menu_return");return null;});}catch(Exception ex){core.getLogger().severe("Menue-Rueckgabe nicht gespeichert: "+ex);p.getWorld().dropItemNaturally(p.getLocation(),item);}},
      ()->edit.withdraw.reset(),p::closeInventory);}
  void renderEdit(Inventory inv,Edit edit){
    for(int i=0;i<27;i++)inv.setItem(i,editButton(Material.GRAY_STAINED_GLASS_PANE," "," "));
    ItemStack offered=ItemStack.deserializeBytes(Base64.getDecoder().decode(edit.row.item()));ItemMeta meta=offered.getItemMeta();List<String> lore=meta.hasLore()?new ArrayList<>(meta.getLore()):new ArrayList<>();lore.add("Menge: "+offered.getAmount());lore.add("Gesamtpreis: "+Money.format(edit.pendingPrice));meta.setLore(lore);offered.setItemMeta(meta);inv.setItem(4,offered);
    int[] slots={9,10,11,13,14,15};String[] names={"Preis -10 CP","Preis -1 CP","Preis -0,10 CP","Preis +0,10 CP","Preis +1 CP","Preis +10 CP"};for(int i=0;i<slots.length;i++)inv.setItem(slots[i],editButton(i<3?Material.RED_DYE:Material.LIME_DYE,names[i],"Aenderung wird erst beim Speichern uebernommen."));
    inv.setItem(12,editButton(Material.PAPER,"Gesamtpreis: "+Money.format(edit.pendingPrice),"Vorgemerkter Preis fuer das gesamte Angebot."));
    inv.setItem(16,editButton(Material.CHEST,"Menge +: aus Haupthand nachfuellen","Passende Items werden sofort zum Angebot hinzugefuegt."));
    inv.setItem(19,editButton(Material.LIME_WOOL,"Speichern","Vorgemerkten Preis speichern."));inv.setItem(21,editButton(Material.RED_WOOL,"Abbrechen","Vorgemerkten Preis verwerfen."));
    inv.setItem(23,editButton(Material.HOPPER,edit.withdraw.armed()?"Wirklich zurueckziehen? Nochmal klicken":"Angebot zurueckziehen","Items gehen ins Abholfach; innerhalb von 10 Sekunden erneut klicken."));inv.setItem(25,editButton(Material.ARROW,"Zurueck","Zur Angebotsliste ohne Preis zu speichern."));
  }
  @EventHandler public void editClose(InventoryCloseEvent e){if(e.getInventory().getHolder() instanceof Edit edit)core.menuReturns.finish(e.getPlayer().getUniqueId(),edit);}
  @EventHandler public void editQuit(PlayerQuitEvent e){core.menuReturns.finish(e.getPlayer().getUniqueId());}
  static final class Confirmation implements InventoryHolder {
    final Listing row; Confirmation(Listing row){this.row=row;}
    public Inventory getInventory(){return null;}
  }
  void confirm(Player p,Listing row){
    Inventory inv=Bukkit.createInventory(new Confirmation(row),27,"Kauf bestaetigen");
    ItemStack item=ItemStack.deserializeBytes(Base64.getDecoder().decode(row.item()));
    ItemMeta meta=item.getItemMeta();List<String> lore=meta.hasLore()?new ArrayList<>(meta.getLore()):new ArrayList<>();lore.add("Menge: "+item.getAmount());lore.add("Gesamtpreis: "+Money.format(row.price()));meta.setLore(lore);item.setItemMeta(meta);inv.setItem(13,item);
    inv.setItem(11,button(Material.LIME_WOOL,"Kauf bestaetigen"));inv.setItem(15,button(Material.RED_WOOL,"Abbrechen"));p.openInventory(inv);
  }
  Shop(CraftCore core){this.core=core;instance=this;}
  void start() throws Exception {
    core.tx(c->{core.exec(c,"CREATE TABLE IF NOT EXISTS purchase_ids (id CHAR(36) PRIMARY KEY)");return null;});
    core.tx(c->{core.exec(c,"CREATE TABLE IF NOT EXISTS shop_journal (id CHAR(36) PRIMARY KEY, seller CHAR(36) NOT NULL, seller_name VARCHAR(16) NOT NULL, item LONGTEXT NOT NULL, price BIGINT NOT NULL, status VARCHAR(16) NOT NULL, recipient CHAR(36) NULL, INDEX shop_status (status,seller), INDEX shop_recipient (recipient,status))");return null;});
    core.tx(c->{core.exec(c,"CREATE TABLE IF NOT EXISTS shop_notifications (id CHAR(36) PRIMARY KEY, seller CHAR(36) NOT NULL, listing_id CHAR(36) NOT NULL, buyer CHAR(36) NOT NULL, price BIGINT NOT NULL, delivered BOOLEAN NOT NULL DEFAULT FALSE, INDEX shop_notice (seller,delivered))");return null;});
    core.tx(c->{core.exec(c,"CREATE TABLE IF NOT EXISTS shop_events (id CHAR(36) PRIMARY KEY, listing_id CHAR(36) NOT NULL, event_type VARCHAR(24) NOT NULL, seller CHAR(36) NOT NULL, seller_name VARCHAR(16) NOT NULL, price BIGINT NOT NULL, actor CHAR(36) NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, INDEX shop_events_listing (listing_id,created_at))");return null;});
    core.tx(c->{core.exec(c,"ALTER TABLE shop_journal ADD COLUMN IF NOT EXISTS material VARCHAR(64) NULL, ADD COLUMN IF NOT EXISTS amount INT NULL");
      try(PreparedStatement q=c.prepareStatement("SELECT id,item FROM shop_journal WHERE material IS NULL OR amount IS NULL");ResultSet r=q.executeQuery()){
        while(r.next()){ItemStack stack=ItemStack.deserializeBytes(Base64.getDecoder().decode(r.getString(2)));core.exec(c,"UPDATE shop_journal SET material=?,amount=? WHERE id=?",stack.getType().name(),stack.getAmount(),r.getString(1));}}
      return null;});
    core.tx(c->{core.exec(c,"ALTER TABLE shop_events ADD COLUMN IF NOT EXISTS seq BIGINT NOT NULL AUTO_INCREMENT UNIQUE");return null;});
    core.tx(c->{core.exec(c,"CREATE OR REPLACE VIEW v_shop_listings AS SELECT id,seller,seller_name,material,amount,price AS price_cents,id AS listing_id,material AS item_material,NULL AS item_display_name,seller AS seller_uuid,GREATEST  (1,CEILING(price / NULLIF(amount,0))) AS unit_price_cents,amount AS remaining_quantity FROM shop_journal WHERE status='OPEN'");
      core.exec(c,"CREATE OR REPLACE VIEW v_shop_events AS SELECT e.seq AS event_id,CASE e.event_type WHEN 'gelistet' THEN 'CREATED' WHEN 'verkauft' THEN 'SOLD' WHEN 'zurueckgezogen' THEN 'CANCELLED' ELSE e.event_type END AS type,e.seller_name,p.name AS buyer_name,e.price / 100.0 AS price,e.listing_id FROM shop_events e LEFT JOIN known_players p ON p.uuid=e.actor");return null;});
    new BuyOrders(core).start();
    Bukkit.getPluginManager().registerEvents(this,core);
    Bukkit.getScheduler().runTaskTimer(core,()->{for(Player p:Bukkit.getOnlinePlayers())notifySales(p);},100,100);
    // PENDING listings are quarantined: the seller's saved inventory may still hold the stack, so they are never published automatically.
    List<String> pending=core.tx(c->{List<String> out=new ArrayList<>();try(PreparedStatement q=c.prepareStatement("SELECT id,seller,amount,material FROM shop_journal WHERE status='PENDING'");ResultSet r=q.executeQuery()){while(r.next())out.add(r.getString(1)+" seller="+r.getString(2)+" "+r.getInt(3)+"x"+r.getString(4));}return out;});
    if(!pending.isEmpty())core.getLogger().warning("Shop: "+pending.size()+" PENDING-Angebote in Quarantaene (nicht veroeffentlicht, Admin-Pruefung noetig): "+pending);
  }
  void command(Player p,String[] a)throws Exception {
    if(a.length==0){overview(p,0);return;}
    if(a.length==1 && a[0].equalsIgnoreCase("help")){p.sendMessage("/shop - Angebote | /shop <Spieler> - Shop | /shop show - eigene Angebote");p.sendMessage("/shop sell <Preis> [Menge] - einstellen | /shop remove <Nr> - entfernen");return;}
    if(a.length==1 && (a[0].equalsIgnoreCase("meine")||a[0].equalsIgnoreCase("cancel")||a[0].equalsIgnoreCase("show"))){open(p,p.getUniqueId().toString(),0,true);return;}
    if((a.length==2||a.length==3) && a[0].equalsIgnoreCase("sell")){sell(p,a[1],a.length==3?a[2]:null);return;}
    if(a.length==2 && a[0].equalsIgnoreCase("remove")){int nr=Integer.parseInt(a[1]);List<Listing> own=listings(p.getUniqueId().toString(),true);if(nr<1||nr>own.size()){p.sendMessage("Diese Angebotsnummer gibt es nicht. Siehe /shop show.");return;}remove(p,own.get(nr-1).id());return;}
    if(a.length==2 && a[0].equalsIgnoreCase("entfernen")) { try { UUID.fromString(a[1]); } catch(IllegalArgumentException ex) { p.sendMessage("Bitte eine Angebots-ID angeben. Siehe /markt meine oder Tab-Vervollstaendigung."); return; } remove(p,a[1]); return; }
    if(a.length==2 && a[0].equalsIgnoreCase("undo")){try{UUID.fromString(a[1]);}catch(IllegalArgumentException ex){p.sendMessage("Ungueltiges Angebot.");return;}remove(p,a[1]);return;}
    if(a.length==1 && !Set.of("sell","remove","undo","entfernen","meine").contains(a[0].toLowerCase(Locale.ROOT))){if(!a[0].matches("[A-Za-z0-9_]{1,16}")){p.sendMessage("Ungueltiger Spielername.");return;}open(p,a[0],0,false);return;}
    p.sendMessage(Component.text("Bitte waehle ").append(Component.text("[Angebote]").clickEvent(ClickEvent.runCommand("/markt meine"))).append(Component.text(" oder ")).append(Component.text("[Hilfe]").clickEvent(ClickEvent.runCommand("/shop help"))));
  }
  void remove(Player p,String id)throws Exception {
    boolean ok=core.tx(c->ShopStore.cancel(c,id,p.getUniqueId().toString()));
    if(ok){p.sendMessage("Angebot entfernt. Hole dein Item mit /abholen ab.");deliver(p,id);}else p.sendMessage("Das eigene Angebot ist nicht mehr verfuegbar.");
  }
  void sell(Player p,String amount,String requested)throws Exception {
    long price=Money.cents(amount); if(price<=0){p.sendMessage("Der Preis muss positiv sein.");return;}
    ItemStack hand=p.getInventory().getItemInMainHand(); String refusal=VipStarRules.refusal(VipStarGuard.tagged(hand)); if(refusal!=null){p.sendMessage(refusal);return;} if(hand.getType().isAir() || hand.getAmount()<=0){p.sendMessage("Halte das anzubietende Item in der Hand.");return;}
    int count=requested==null?hand.getAmount():Integer.parseInt(requested);if(count<1||count>hand.getAmount()){p.sendMessage("Die Menge muss zwischen 1 und der Anzahl in deiner Hand liegen.");return;}
    ItemStack copy=hand.clone();copy.setAmount(count); String item=Base64.getEncoder().encodeToString(copy.serializeAsBytes()); String id=UUID.randomUUID().toString();
    ItemStack original=hand.clone(); if(count==hand.getAmount())p.getInventory().setItemInMainHand(null);else{ItemStack remaining=hand.clone();remaining.setAmount(hand.getAmount()-count);p.getInventory().setItemInMainHand(remaining);}
    try {core.tx(c->{core.exec(c,"INSERT INTO shop_journal(id,seller,seller_name,item,price,status,material,amount) VALUES(?,?,?,?,?,'PENDING',?,?)",id,p.getUniqueId().toString(),p.getName(),item,price,copy.getType().name(),copy.getAmount());return null;});}
    catch(Exception ex){p.getInventory().setItemInMainHand(original);throw ex;}
    p.saveData();
    core.tx(c->{ShopStore.publish(c,id);return null;});p.sendMessage(Component.text("Angebot erstellt: "+Money.format(price)+" ").append(Component.text("[Rueckgaengig]").clickEvent(ClickEvent.runCommand("/shop undo "+id))));
  }
  List<Listing> listings(String filter,boolean cancel)throws Exception{return core.tx(c->{List<Listing> out=new ArrayList<>();String sql="SELECT id,seller,seller_name,price,item FROM shop_journal WHERE status='OPEN'"+(filter==null?"":cancel?" AND seller=?":" AND LOWER(seller_name)=LOWER(?)")+" ORDER BY id";try(PreparedStatement s=c.prepareStatement(sql)){if(filter!=null)s.setString(1,filter);try(ResultSet r=s.executeQuery()){while(r.next())out.add(new Listing(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getString(5)));}}return out;});}
  void open(Player p,String filter,int page,boolean cancel)throws Exception {
    List<Listing> all=listings(filter,cancel); int pages=Math.max(1,(all.size()+44)/45);page=Math.max(0,Math.min(page,pages-1));List<Listing> rows=new ArrayList<>(all.subList(page*45,Math.min(all.size(),page*45+45)));Menu menu=new Menu(rows,filter,page,cancel);Inventory inv=Bukkit.createInventory(menu,54,cancel?"Eigene Angebote":"Spielershops");
    for(int i=0;i<rows.size();i++){Listing row=rows.get(i);ItemStack icon=ItemStack.deserializeBytes(Base64.getDecoder().decode(row.item()));ItemMeta meta=icon.getItemMeta();List<String> lore=meta.hasLore()?new ArrayList<>(meta.getLore()):new ArrayList<>();lore.add("Verkaeufer: "+row.name());lore.add("Preis: "+Money.format(row.price()));lore.add(cancel?"Angebot Nr. "+(page*45+i+1)+" - Linksklick zum Bearbeiten":"Linksklick zur Kaufbestaetigung");if(cancel)lore.add("ID: "+row.id());meta.setLore(lore);icon.setItemMeta(meta);inv.setItem(i,icon);}
    if(page>0)inv.setItem(45,button(Material.ARROW,"Vorherige Seite"));if(page+1<pages)inv.setItem(53,button(Material.ARROW,"Naechste Seite"));p.openInventory(inv);
  }
  ItemStack button(Material m,String name){ItemStack item=new ItemStack(m);ItemMeta meta=item.getItemMeta();meta.setDisplayName(name);item.setItemMeta(meta);return item;}
  @EventHandler public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof Menu || e.getView().getTopInventory().getHolder() instanceof Shops || e.getView().getTopInventory().getHolder() instanceof Confirmation || e.getView().getTopInventory().getHolder() instanceof Edit || e.getView().getTopInventory().getHolder() instanceof PurchaseMenu)e.setCancelled(true);}
  @EventHandler public void click(InventoryClickEvent e){
    if(e.getView().getTopInventory().getHolder() instanceof PurchaseMenu pm && pm.table.equals("shop_journal")){e.setCancelled(true);if(e.getWhoClicked() instanceof Player p && e.getClickedInventory()==e.getView().getTopInventory() && e.isLeftClick() && !e.isShiftClick())try{pm.click(core,p,e.getRawSlot());}catch(Exception ex){core.getLogger().warning("Shopkauf: "+ex);p.sendMessage("Kauf fehlgeschlagen.");}return;}
    if(e.getView().getTopInventory().getHolder() instanceof Edit edit){
      e.setCancelled(true);
      if(e.getRawSlot()!=23 || e.getClickedInventory()!=e.getView().getTopInventory() || !e.isLeftClick() || e.isShiftClick()) {
        if(edit.withdraw.reset())renderEdit(e.getView().getTopInventory(),edit);
      }
      if(!(e.getWhoClicked() instanceof Player p)||e.getClickedInventory()!=e.getView().getTopInventory()||!e.isLeftClick()||e.isShiftClick())return;
      try{int slot=e.getRawSlot();Listing row=edit.row;String seller=p.getUniqueId().toString();
        if(slot==21||slot==25){open(p,seller,edit.page,true);return;}
        if(slot==23){if(!edit.withdraw.click(System.nanoTime())){renderEdit(e.getView().getTopInventory(),edit);return;}remove(p,row.id());open(p,seller,edit.page,true);return;}
        long delta=editDelta(slot);if(delta!=0){long price=Math.addExact(edit.pendingPrice,delta);if(price<1){p.sendMessage("Preis muss positiv sein.");return;}edit.pendingPrice=price;renderEdit(e.getView().getTopInventory(),edit);return;}
        if(slot==19){boolean ok=core.tx(c->{try(PreparedStatement q=c.prepareStatement("UPDATE shop_journal SET price=? WHERE id=? AND seller=? AND status='OPEN' AND price=? AND BINARY item=BINARY ?")){q.setLong(1,edit.pendingPrice);q.setString(2,row.id());q.setString(3,seller);q.setLong(4,row.price());q.setString(5,row.item());return q.executeUpdate()==1;}});p.sendMessage(ok?"Preis gespeichert.":"Angebot wurde inzwischen geaendert.");open(p,seller,edit.page,true);return;}
        if(slot==16){ItemStack existing=ItemStack.deserializeBytes(Base64.getDecoder().decode(row.item()));ItemStack hand=p.getInventory().getItemInMainHand();
          String refusal=VipStarRules.refusal(VipStarGuard.tagged(hand));if(refusal!=null){p.sendMessage(refusal);return;}
          if(hand.getType().isAir()||!existing.isSimilar(hand)){p.sendMessage("Halte passende Items in der Haupthand.");return;}
          int count=Math.min(hand.getAmount(),existing.getMaxStackSize()-existing.getAmount());if(count<=0){p.sendMessage("Der Angebotsstapel ist voll.");return;}
          ItemStack original=hand.clone(),updated=existing.clone();updated.setAmount(existing.getAmount()+count);String encoded=Base64.getEncoder().encodeToString(updated.serializeAsBytes());
          if(count==hand.getAmount())p.getInventory().setItemInMainHand(null);else {ItemStack rest=hand.clone();rest.setAmount(hand.getAmount()-count);p.getInventory().setItemInMainHand(rest);}
          p.saveData(); // persist the removal before the listing grows, so a crash cannot leave the items in both places
          boolean ok;try{ok=core.tx(c->{try(PreparedStatement q=c.prepareStatement("UPDATE shop_journal SET item=?,amount=? WHERE id=? AND seller=? AND status='OPEN' AND price=? AND BINARY item=BINARY ?")){q.setString(1,encoded);q.setInt(2,updated.getAmount());q.setString(3,row.id());q.setString(4,seller);q.setLong(5,row.price());q.setString(6,row.item());return q.executeUpdate()==1;}});}catch(Exception ex){p.getInventory().setItemInMainHand(original);throw ex;}
          if(!ok){p.getInventory().setItemInMainHand(original);p.saveData();p.sendMessage("Angebot wurde inzwischen geaendert.");open(p,seller,edit.page,true);return;}p.sendMessage(count+" Items nachgefuellt; Gesamtpreis bleibt gleich.");edit.row=new Listing(row.id(),seller,row.name(),row.price(),encoded);renderEdit(e.getView().getTopInventory(),edit);
        }
      }catch(Exception ex){core.getLogger().warning("Shopbearbeitung: "+ex);p.sendMessage("Bearbeitung fehlgeschlagen.");}return;
    }
    if(e.getView().getTopInventory().getHolder() instanceof Shops shops){
      e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p)||e.getClickedInventory()!=e.getView().getTopInventory()||!e.isLeftClick()||e.isShiftClick())return;
      int slot=e.getRawSlot();try{if(slot==48)open(p,p.getUniqueId().toString(),0,true);else if(slot==49)BuyOrders.instance.open(p,0);else if(slot==45 && shops.page>0)overview(p,shops.page-1);else if(slot==53 && shops.page+1<shops.pages)overview(p,shops.page+1);else if(slot>=0 && slot<shops.sellers.size())open(p,shops.sellers.get(slot).name(),0,false);}catch(Exception ex){core.getLogger().warning("Shopuebersicht: "+ex);p.sendMessage("Shop konnte nicht geoeffnet werden.");}return;
    }
    if(e.getView().getTopInventory().getHolder() instanceof Confirmation confirmation){e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p)||e.getClickedInventory()!=e.getView().getTopInventory()||!e.isLeftClick()||e.isShiftClick())return;if(e.getRawSlot()==15){p.closeInventory();return;}if(e.getRawSlot()!=11)return;try{String result=core.tx(c->{try(PreparedStatement q=c.prepareStatement("SELECT price,item FROM shop_journal WHERE id=? AND status='OPEN' FOR UPDATE")){q.setString(1,confirmation.row.id());try(ResultSet r=q.executeQuery()){if(!r.next()||r.getLong(1)!=confirmation.row.price()||!r.getString(2).equals(confirmation.row.item()))return "Angebot wurde geaendert. Bitte erneut oeffnen.";}}return ShopStore.buy(c,confirmation.row.id(),p.getUniqueId().toString());});p.closeInventory();if(result==null){p.sendMessage("Kauf abgeschlossen.");deliver(p,confirmation.row.id());}else p.sendMessage(result);}catch(Exception ex){core.getLogger().warning("Shopkauf: "+ex);p.sendMessage("Kauf fehlgeschlagen. Bitte erneut versuchen.");}return;}
    if(!(e.getView().getTopInventory().getHolder() instanceof Menu menu))return;e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p)||e.getClickedInventory()!=e.getView().getTopInventory()||!e.isLeftClick()||e.isShiftClick())return;int slot=e.getRawSlot();try{if(slot==45 && menu.page>0){open(p,menu.filter,menu.page-1,menu.cancel);return;}if(slot==53 && e.getCurrentItem()!=null){open(p,menu.filter,menu.page+1,menu.cancel);return;}if(slot<0||slot>=menu.rows.size())return;Listing row=menu.rows.get(slot);if(menu.cancel){edit(p,row,menu.page);}else{new PurchaseMenu("shop_journal",row.id(),row.price(),row.item()).show(p);}}catch(Exception ex){core.getLogger().warning("Shop: "+ex);p.sendMessage("Shopaktion fehlgeschlagen. Bitte erneut versuchen.");}}
  void deliver(Player p,String id){}
  void notifySales(Player p){
    try {
      List<Long> prices=core.tx(c->{List<Long> result=new ArrayList<>();List<String> ids=new ArrayList<>();
        try(PreparedStatement s=c.prepareStatement("SELECT id,price FROM shop_notifications WHERE seller=? AND delivered=FALSE FOR UPDATE")) {s.setString(1,p.getUniqueId().toString());try(ResultSet r=s.executeQuery()){while(r.next()){ids.add(r.getString(1));result.add(r.getLong(2));}}}
        try(PreparedStatement s=c.prepareStatement("UPDATE shop_notifications SET delivered=TRUE WHERE id=? AND delivered=FALSE")){for(String id:ids){s.setString(1,id);s.executeUpdate();}}return result;});
      for(long price:prices)p.sendMessage("Dein Shopangebot wurde fuer "+Money.format(price)+" verkauft.");
    } catch(Exception ex){core.getLogger().warning("Shopbenachrichtigungen: "+ex);}
  }
  @EventHandler public void join(PlayerJoinEvent e){notifySales(e.getPlayer());}
}
