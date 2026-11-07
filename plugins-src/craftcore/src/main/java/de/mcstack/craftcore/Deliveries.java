package de.mcstack.craftcore;
import java.sql.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

final class Deliveries {
 static void start(CraftCore core)throws Exception {core.tx(c->{core.exec(c,"CREATE TABLE IF NOT EXISTS pending_deliveries (id CHAR(36) PRIMARY KEY, player_uuid CHAR(36) NOT NULL, item_nbt LONGTEXT NOT NULL, amount INT NOT NULL, source VARCHAR(32) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, status VARCHAR(16) NOT NULL, INDEX delivery_player(player_uuid,status))");core.exec(c,"ALTER TABLE pending_deliveries ADD COLUMN IF NOT EXISTS handout_server VARCHAR(32) NULL");OrderFulfillment.startup(c, message->core.getLogger().warning(message));return null;});Bukkit.getScheduler().runTaskTimerAsynchronously(core,()->{try{core.tx(c->{OrderFulfillment.backfill(c);return null;});}catch(Exception e){core.getLogger().warning("Bestellstatus: "+e.getMessage());}},1200L,1200L);}
 static void insert(Connection c,String id,String player,ItemStack item,String source)throws SQLException {insertRaw(c,id,player,Base64.getEncoder().encodeToString(item.serializeAsBytes()),item.getAmount(),source);}
 /** Insert with item bytes serialised beforehand (on the main thread), so it can run in an async transaction. */
 static void insertRaw(Connection c,String id,String player,String itemData,int amount,String source)throws SQLException {try(PreparedStatement q=c.prepareStatement("INSERT INTO pending_deliveries(id,player_uuid,item_nbt,amount,source,status) VALUES(?,?,?,?,?,'offen')")){q.setString(1,id);q.setString(2,player);q.setString(3,itemData);q.setInt(4,amount);q.setString(5,source);q.executeUpdate();}}
 static void fromJournal(Connection c,String table,String id,String player)throws SQLException {if(!Set.of("shop_journal","ah_journal").contains(table))throw new SQLException("invalid journal");try(PreparedStatement q=c.prepareStatement("INSERT INTO pending_deliveries(id,player_uuid,item_nbt,amount,source,status) SELECT id,?,item,amount,?,'offen' FROM "+table+" WHERE id=?")){q.setString(1,player);q.setString(2,table);q.setString(3,id);if(q.executeUpdate()!=1)throw new SQLException("Lieferung fehlt: "+id);}}
 static boolean claim(Connection c,String id,String player,int amount)throws SQLException {try(PreparedStatement q=c.prepareStatement("UPDATE pending_deliveries SET status='geliefert',amount=? WHERE id=? AND player_uuid=? AND status='offen'")){q.setInt(1,amount);q.setString(2,id);q.setString(3,player);return q.executeUpdate()==1;}}
 static void migrate(CraftCore core)throws Exception {core.tx(c->{for(String table:List.of("shop_journal","ah_journal")){try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT id,recipient FROM "+table+" WHERE status='DELIVERY' AND id NOT IN (SELECT id FROM pending_deliveries) FOR UPDATE")){while(r.next())fromJournal(c,table,r.getString(1),r.getString(2));}}try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT id,player,item,amount FROM bank_journal WHERE status='PENDING_DELIVERY' AND id NOT IN (SELECT id FROM pending_deliveries) FOR UPDATE")){while(r.next()){Material m=Material.matchMaterial(r.getString(3));if(m==null)throw new SQLException("Unbekannter Bankartikel");insert(c,r.getString(1),r.getString(2),new ItemStack(m,r.getInt(4)),"bank_alt");}}return null;});}
 record Row(String id,String data,int amount,String source){}
 static void collect(CraftCore core,Player p){collect(core,p,null);}
 /** As /abholen; with `only` just those delivery ids (a grave hands out its own items, nothing else). */
 static void collect(CraftCore core,Player p,Set<String> only){String player=p.getUniqueId().toString();queue(core).submit(player,()->collectJob(core,p,only));}
 private static volatile PlayerQueue QUEUE;
 /** The per-player hand-out queue (PlayerQueue): jobs on Bukkit's async pool, never on the server thread. */
 static PlayerQueue queue(CraftCore core){PlayerQueue q=QUEUE;if(q==null)synchronized(Deliveries.class){if((q=QUEUE)==null)QUEUE=q=new PlayerQueue(r->Bukkit.getScheduler().runTaskAsynchronously(core,r));}return q;}
 private static void say(CraftCore core,Player p,String msg)throws Exception{PlayerQueue.onMain(core,()->{if(p.isOnline())p.sendMessage(msg);return null;});}
 /** Queued job: DB work here (async), player state only in PlayerQueue.onMain hops. */
 static void collectJob(CraftCore core,Player p,Set<String> only){String player=p.getUniqueId().toString(),server=server();try {
  // first settle hand-outs a crash or DB failure left reserved on this server (Handouts: the player file decides)
  Set<String> marks=PlayerQueue.onMain(core,()->p.isOnline()?marks(core,p):null);if(marks==null)return;
  Set<String> drop=core.tx(c->Handouts.reconcile(c,player,server,marks));
  if(!drop.isEmpty())PlayerQueue.onMain(core,()->{if(p.isOnline())dropMarks(core,p,drop);return null;});
  List<Row> rows=core.tx(c->{List<Row> out=new ArrayList<>();try(PreparedStatement q=c.prepareStatement("SELECT id,item_nbt,amount,source FROM pending_deliveries WHERE player_uuid=? AND status='offen' ORDER BY created_at,id")){q.setString(1,player);try(ResultSet r=q.executeQuery()){while(r.next())out.add(new Row(r.getString(1),r.getString(2),r.getInt(3),r.getString(4)));}}return out;});
  if(only!=null)rows.removeIf(r->!only.contains(r.id()));int delivered=0;
  for(Row row:rows){
   Integer count=PlayerQueue.onMain(core,()->{if(!p.isOnline())return null;ItemStack item=ItemStack.deserializeBytes(Base64.getDecoder().decode(row.data()));int capacity=0;for(ItemStack s:p.getInventory().getStorageContents()){if(s==null||s.getType().isAir())capacity+=item.getMaxStackSize();else if(s.isSimilar(item))capacity+=Math.max(0,s.getMaxStackSize()-s.getAmount());}return Math.min(capacity,row.amount());});
   if(count==null)break;if(count<=0)continue;
   if(!core.tx(c->Handouts.reserve(c,row.id(),player,count,server)))continue;                       // 1. reserve in the DB (async)
   // 2. one main-thread hop: items + marker, saved in one player-file write. Offline meanwhile: nothing given, no
   //    marker, so the next reconcile puts the row back to 'offen'.
   int[] res=PlayerQueue.onMain(core,()->{if(!p.isOnline())return null;ItemStack item=ItemStack.deserializeBytes(Base64.getDecoder().decode(row.data()));item.setAmount(count);Map<Integer,ItemStack> leftovers=p.getInventory().addItem(item);
     int left=DeliveryLeftovers.sum(leftovers.values().stream().map(ItemStack::getAmount).toList());addMark(core,p,Handouts.itemMark(row.id(),count-left));p.saveData();return new int[]{count-left,left};});
   if(res==null)break;
   String mark=Handouts.itemMark(row.id(),res[0]);
   try{core.tx(c->Handouts.finish(c,row.id(),res[0]));PlayerQueue.onMain(core,()->{if(p.isOnline())dropMarks(core,p,List.of(mark));return null;});}   // 3. finalise (async)
   catch(Exception ex){core.getLogger().warning("Abholfach: Abschluss "+row.id()+" folgt beim naechsten Join/abholen: "+ex);}
   if(res[1]>0)say(core,p,DeliveryLeftovers.message(res[1]));
   delivered+=res[0];}
  final int got=delivered;final boolean more=!rows.isEmpty()&&delivered<rows.stream().mapToInt(Row::amount).sum(),none=rows.isEmpty();
  PlayerQueue.onMain(core,()->{if(!p.isOnline())return null;DeliveryNotices.rememberOpen(core,p);if(got>0)p.sendMessage(got+" Items aus dem Abholfach erhalten.");if(more)p.sendMessage("Weitere Lieferungen warten auf Inventarplatz: /abholen");else if(none)p.sendMessage("Keine offenen Lieferungen. /abholen");return null;});
 }catch(Exception ex){core.getLogger().severe("Abholfach: "+ex);try{say(core,p,"Abholfach momentan nicht verfuegbar. Bitte Admin informieren.");}catch(Exception ignored){}}}
 static String server(){return System.getenv().getOrDefault("CRAFTCORE_SERVER","Server");}
 // Hand-out markers in the player's PersistentDataContainer: saved in the same player file as the inventory/XP.
 private static NamespacedKey key(CraftCore core){return new NamespacedKey(core,"handouts");}
 static Set<String> marks(CraftCore core,Player p){return Handouts.parse(p.getPersistentDataContainer().get(key(core),PersistentDataType.STRING));}
 static void addMark(CraftCore core,Player p,String m){Set<String> s=marks(core,p);s.add(m);setMarks(core,p,s);}
 static void dropMarks(CraftCore core,Player p,Collection<String> m){Set<String> s=marks(core,p);if(s.removeAll(m))setMarks(core,p,s);}
 private static void setMarks(CraftCore core,Player p,Set<String> s){if(s.isEmpty())p.getPersistentDataContainer().remove(key(core));else p.getPersistentDataContainer().set(key(core),PersistentDataType.STRING,Handouts.format(s));}
}
