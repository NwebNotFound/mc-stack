package de.mcstack.craftcore;

import java.sql.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

final class BuyOrders implements Listener {
 static BuyOrders instance;
 final CraftCore core;
 record Order(String id,String buyer,String name,String item,int remaining,long price) {}
 record Input(String item,Integer amount) {}
 final Map<UUID,Input> input=new java.util.concurrent.ConcurrentHashMap<>();
 static final class ListMenu implements InventoryHolder {
  final List<Order> rows; final int page,pages;
  ListMenu(List<Order> rows,int page,int pages){this.rows=rows;this.page=page;this.pages=pages;}
  public Inventory getInventory(){return null;}
 }
 static final class SellMenu implements InventoryHolder {
  final Order order;
  SellMenu(Order order){this.order=order;}
  public Inventory getInventory(){return null;}
 }
 BuyOrders(CraftCore core){this.core=core;instance=this;}
 void start() throws Exception {
  core.tx(c->{core.exec(c,"CREATE TABLE IF NOT EXISTS buy_orders (id CHAR(36) PRIMARY KEY, buyer CHAR(36) NOT NULL, buyer_name VARCHAR(16) NOT NULL, item LONGTEXT NOT NULL, remaining INT NOT NULL, unit_price BIGINT NOT NULL, status VARCHAR(16) NOT NULL, INDEX buy_orders_status(status))");core.exec(c,"CREATE TABLE IF NOT EXISTS buy_order_sales (id CHAR(36) PRIMARY KEY, order_id CHAR(36) NOT NULL, seller CHAR(36) NOT NULL, amount INT NOT NULL, INDEX buy_order_sales_order(order_id))");return null;});
  Bukkit.getPluginManager().registerEvents(this,core);
 }
 static String encode(ItemStack item){ItemStack one=item.clone();one.setAmount(1);return Base64.getEncoder().encodeToString(one.serializeAsBytes());}
 static ItemStack decode(String item){return ItemStack.deserializeBytes(Base64.getDecoder().decode(item));}
 ItemStack button(Material type,String name){ItemStack i=new ItemStack(type);ItemMeta m=i.getItemMeta();m.setDisplayName(name);i.setItemMeta(m);return i;}
 void open(Player p,int page)throws Exception {
  List<Order> all=core.tx(c->{List<Order> out=new ArrayList<>();try(PreparedStatement q=c.prepareStatement("SELECT id,buyer,buyer_name,item,remaining,unit_price FROM buy_orders WHERE status='OPEN' ORDER BY id");ResultSet r=q.executeQuery()){while(r.next())out.add(new Order(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getInt(5),r.getLong(6)));}return out;});
  int pages=Math.max(1,(all.size()+44)/45);page=Math.max(0,Math.min(page,pages-1));List<Order> rows=new ArrayList<>(all.subList(page*45,Math.min(all.size(),page*45+45)));
  Inventory inv=Bukkit.createInventory(new ListMenu(rows,page,pages),54,"Kaufauftraege");
  for(int i=0;i<rows.size();i++){Order o=rows.get(i);ItemStack icon=decode(o.item());ItemMeta m=icon.getItemMeta();List<String> lore=m.hasLore()?new ArrayList<>(m.getLore()):new ArrayList<>();lore.add("Kaeufer: "+o.name());lore.add("Stueckpreis: "+Money.format(o.price()));lore.add("Restmenge: "+o.remaining());lore.add(o.buyer().equals(p.getUniqueId().toString())?"Klicken: Stornieren":"Klicken: Verkaufen");m.setLore(lore);icon.setItemMeta(m);inv.setItem(i,icon);}
  if(page>0)inv.setItem(45,button(Material.ARROW,"Vorherige Seite"));inv.setItem(49,button(Material.EMERALD,"Auftrag erstellen"));if(page+1<pages)inv.setItem(53,button(Material.ARROW,"Naechste Seite"));p.openInventory(inv);
 }
 void sellMenu(Player p,Order order){Inventory inv=Bukkit.createInventory(new SellMenu(order),27,"Menge verkaufen");int[] amounts={1,8,16,32,64};for(int i=0;i<amounts.length;i++)inv.setItem(10+i,button(Material.PAPER,"Verkaufen: "+amounts[i]));inv.setItem(16,button(Material.CHEST,"Alles Moegliche verkaufen"));inv.setItem(22,button(Material.ARROW,"Zurueck"));p.openInventory(inv);}
 static int available(ItemStack[] items,ItemStack wanted){int n=0;for(ItemStack item:items)if(item!=null && item.isSimilar(wanted))n+=item.getAmount();return n;}
 static boolean remove(PlayerInventory inventory,ItemStack wanted,int amount){ItemStack[] items=inventory.getStorageContents();if(available(items,wanted)<amount)return false;int left=amount;for(int i=0;i<items.length&&left>0;i++){ItemStack item=items[i];if(item==null||!item.isSimilar(wanted))continue;int take=Math.min(left,item.getAmount());left-=take;if(take==item.getAmount())items[i]=null;else{items[i]=item.clone();items[i].setAmount(item.getAmount()-take);}}inventory.setStorageContents(items);return true;}
 void sell(Player p,Order o,int amount)throws Exception {
  ItemStack wanted=decode(o.item());PlayerInventory inv=p.getInventory();ItemStack[] snapshot=Arrays.stream(inv.getStorageContents()).map(x->x==null?null:x.clone()).toArray(ItemStack[]::new);
  if(amount<1||available(snapshot,wanted)<amount){p.sendMessage("Du hast nicht genug passende Items.");return;}
  boolean[] removed={false};boolean ok;
  try{ok=core.tx(c->{boolean sold=BuyOrderTransaction.sell(c,UUID.randomUUID().toString(),o.id(),p.getUniqueId().toString(),amount,o.item(),()->{removed[0]=true;return remove(inv,wanted,amount);}); if(sold) WebsiteStats.event(c,wanted.getType().name(),amount,UnitPrice.total(o.price(),amount),"order"); return sold;});}
  catch(Exception ex){if(removed[0])inv.setStorageContents(snapshot);throw ex;}
  if(ok){p.saveData();p.sendMessage(amount+" Items verkauft; "+Money.format(UnitPrice.total(o.price(),amount))+" gutgeschrieben. Lieferung liegt im Abholfach.");}else p.sendMessage("Auftrag nicht mehr verfuegbar oder Restmenge zu klein.");
 }
 @EventHandler public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof ListMenu || e.getView().getTopInventory().getHolder() instanceof SellMenu)e.setCancelled(true);}
 @EventHandler public void click(InventoryClickEvent e){InventoryHolder h=e.getView().getTopInventory().getHolder();if(!(h instanceof ListMenu)&&!(h instanceof SellMenu))return;e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p)||e.getClickedInventory()!=e.getView().getTopInventory()||!e.isLeftClick()||e.isShiftClick())return;int slot=e.getRawSlot();try{
  if(h instanceof ListMenu menu){if(slot==45&&menu.page>0){open(p,menu.page-1);return;}if(slot==53&&menu.page+1<menu.pages){open(p,menu.page+1);return;}if(slot==49){ItemStack hand=p.getInventory().getItemInMainHand();if(hand.getType().isAir()){p.sendMessage("Halte das gewuenschte Item in der Haupthand.");return;}input.put(p.getUniqueId(),new Input(encode(hand),null));p.closeInventory();p.sendMessage("Gib die gewuenschte Menge im Chat ein (oder abbrechen).");return;}if(slot<0||slot>=menu.rows.size())return;Order o=menu.rows.get(slot);if(o.buyer().equals(p.getUniqueId().toString())){boolean ok=core.tx(c->BuyOrderStore.cancel(c,o.id(),o.buyer()));p.sendMessage(ok?"Auftrag storniert; restliche CP zurueckgebucht.":"Auftrag ist nicht mehr offen.");open(p,menu.page);}else sellMenu(p,o);}
  else if(h instanceof SellMenu menu){if(slot==22){open(p,0);return;}int amount=slot>=10&&slot<=14?new int[]{1,8,16,32,64}[slot-10]:slot==16?Math.min(menu.order.remaining(),available(p.getInventory().getStorageContents(),decode(menu.order.item()))):0;if(amount>0){sell(p,menu.order,amount);open(p,0);}else if(slot==16)p.sendMessage("Du hast keine passenden Items.");}
 }catch(Exception ex){core.getLogger().warning("Kaufauftrag: "+ex);p.sendMessage("Kaufauftrag fehlgeschlagen.");}}
 @EventHandler public void chat(AsyncPlayerChatEvent e){UUID id=e.getPlayer().getUniqueId();synchronized(input){if(!input.containsKey(id))return;}e.setCancelled(true);String message=e.getMessage().trim();Bukkit.getScheduler().runTask(core,()->handleInput(e.getPlayer(),message));}
 void handleInput(Player p,String message){Input in=input.get(p.getUniqueId());if(in==null)return;if(message.equalsIgnoreCase("abbrechen")){input.remove(p.getUniqueId());p.sendMessage("Erstellung abgebrochen.");return;}try{if(in.amount()==null){int amount=Integer.parseInt(message);if(amount<1)throw new IllegalArgumentException();input.put(p.getUniqueId(),new Input(in.item(),amount));p.sendMessage("Gib den Stueckpreis in CP ein (z.B. 1.50).");return;}long price=Money.cents(message.replace(',','.'));UnitPrice.total(price,in.amount());boolean ok=core.tx(c->BuyOrderStore.create(c,UUID.randomUUID().toString(),p.getUniqueId().toString(),p.getName(),in.item(),in.amount(),price));input.remove(p.getUniqueId());p.sendMessage(ok?"Kaufauftrag erstellt und CP reserviert.":"Nicht genug CP fuer diesen Auftrag.");open(p,0);}catch(IllegalArgumentException|ArithmeticException ex){p.sendMessage("Ungueltige Eingabe. Bitte erneut eingeben oder abbrechen.");}catch(Exception ex){input.remove(p.getUniqueId());core.getLogger().warning("Kaufauftrag erstellen: "+ex);p.sendMessage("Auftrag konnte nicht erstellt werden.");}}
 @EventHandler public void quit(PlayerQuitEvent e){input.remove(e.getPlayer().getUniqueId());}
}
