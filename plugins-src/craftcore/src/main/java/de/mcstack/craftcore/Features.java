package de.mcstack.craftcore;

import java.sql.*;
import java.util.*;
import java.io.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.util.io.*;

final class Features implements Listener {
  static Features instance;
  final CraftCore plugin;
  final String server;
  final Map<UUID, Integer> teleports = new HashMap<>();
  final Set<String> graveLocations = new HashSet<>();
  String graveKey(String world,int x,int y,int z){return world+":"+x+":"+y+":"+z;}
  Features(CraftCore p) { plugin=p; server=System.getenv().getOrDefault("CRAFTCORE_SERVER","Server"); instance=this; }
  void start() throws Exception {
    plugin.tx(c->{try(Statement s=c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS spawn_transfers (uuid CHAR(36) PRIMARY KEY, expires BIGINT NOT NULL)");
      s.execute("CREATE TABLE IF NOT EXISTS claims (server VARCHAR(32) NOT NULL, world VARCHAR(64) NOT NULL, x INT NOT NULL, z INT NOT NULL, owner CHAR(36) NOT NULL, team_id BIGINT NULL, PRIMARY KEY(server,world,x,z))");
      s.execute("CREATE TABLE IF NOT EXISTS homes (server VARCHAR(32) NOT NULL, uuid CHAR(36) NOT NULL, name VARCHAR(16) NOT NULL, world VARCHAR(64) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, PRIMARY KEY(server,uuid,name))");
      s.execute("CREATE TABLE IF NOT EXISTS graves (id BIGINT AUTO_INCREMENT PRIMARY KEY, server VARCHAR(32) NOT NULL, world VARCHAR(64) NOT NULL, x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, owner CHAR(36) NOT NULL, team_id BIGINT NULL, items LONGTEXT NOT NULL, xp INT NOT NULL, expires BIGINT NOT NULL, UNIQUE KEY grave_location(server,world,x,y,z))");
    } return null;});
    plugin.tx(c->{HomeSlots.start(c);return null;});
    plugin.tx(c->{try(Statement s=c.createStatement()) {
      s.execute("ALTER TABLE graves ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'OPEN', ADD COLUMN IF NOT EXISTS delivered BOOLEAN NOT NULL DEFAULT FALSE, ADD COLUMN IF NOT EXISTS claiming_at BIGINT NULL");
      try (ResultSet r=c.getMetaData().getIndexInfo(c.getCatalog(),null,"graves",false,false)) {
        boolean unique=false; while(r.next()) if("grave_location".equalsIgnoreCase(r.getString("INDEX_NAME")) && !r.getBoolean("NON_UNIQUE")) unique=true;
        if(unique) try(Statement alter=c.createStatement()){alter.execute("ALTER TABLE graves DROP INDEX grave_location, ADD INDEX grave_location(server,world,x,y,z)");}
      }
      plugin.exec(c,"UPDATE graves SET status='OPEN', claiming_at=NULL WHERE server=? AND status='CLAIMING' AND delivered=FALSE AND claiming_at<?",server,System.currentTimeMillis()-60000L);
    } return null;});
    plugin.tx(c->{try(PreparedStatement s=c.prepareStatement("SELECT world,x,y,z FROM graves WHERE server=? AND status IN ('OPEN','CLAIMING')")){s.setString(1,server);try(ResultSet r=s.executeQuery()){while(r.next())graveLocations.add(graveKey(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4)));}}return null;});
    ProxyChannel.register(plugin);
    Bukkit.getPluginManager().registerEvents(this,plugin);
    // Existing graves are retained until their contents can be transferred safely.
  }
  String world(Location l){return l.getWorld().getName();}
  String team(Connection c, UUID u) throws SQLException {return plugin.one(c,"SELECT team_id FROM members WHERE uuid=?",u.toString());}
  boolean allowed(Connection c, UUID u, String owner, String team) throws SQLException {return Access.allowed(u.toString(), owner, team, team(c,u));}
  boolean can(Player p, Location l) {
    try {return plugin.tx(c->{try(PreparedStatement s=c.prepareStatement("SELECT owner,team_id FROM claims WHERE server=? AND world=? AND x=? AND z=?")) {s.setString(1,server);s.setString(2,world(l));s.setInt(3,l.getBlockX()>>4);s.setInt(4,l.getBlockZ()>>4);try(ResultSet r=s.executeQuery()){return !r.next() || allowed(c,p.getUniqueId(),r.getString(1),r.getString(2));}}});}
    catch(Exception e){plugin.getLogger().warning("Claim-Prüfung fehlgeschlagen: "+e.getMessage());return false;}
  }
  boolean claimed(Location l) {
    try{return plugin.tx(c->plugin.one(c,"SELECT owner FROM claims WHERE server=? AND world=? AND x=? AND z=?",server,world(l),l.getBlockX()>>4,l.getBlockZ()>>4)!=null);}catch(Exception e){return true;}
  }
  void command(Player p,String cmd,String[] a) throws Exception {
    Location l=p.getLocation(); UUID id=p.getUniqueId();
    if(cmd.equals("claim")) {
      if(a.length>0 && a[0].equalsIgnoreCase("list")) { List<String> list=plugin.tx(c->{List<String> out=new ArrayList<>();try(PreparedStatement s=c.prepareStatement("SELECT world,x,z FROM claims WHERE server=? AND (owner=? OR team_id=(SELECT team_id FROM members WHERE uuid=?)) ORDER BY world,x,z")){s.setString(1,server);s.setString(2,id.toString());s.setString(3,id.toString());try(ResultSet r=s.executeQuery()){while(r.next())out.add(r.getString(1)+" "+r.getInt(2)+","+r.getInt(3));}}return out;});p.sendMessage("Claims: "+(list.isEmpty()?"keine":String.join("; ",list)));return; }
      if(a.length!=1 || !a[0].equalsIgnoreCase("create")){p.sendMessage("[Craft] Unbekannter Claim-Befehl. Nutze /claim create oder /claim list.");return;}
      long cost=Money.cents(plugin.getConfig().getDouble("claim-cost",100)); if(cost<0)throw new IllegalArgumentException("negative claim cost");
      String result=plugin.tx(c->{if(plugin.one(c,"SELECT owner FROM claims WHERE server=? AND world=? AND x=? AND z=?",server,world(l),l.getBlockX()>>4,l.getBlockZ()>>4)!=null)return "Chunk ist bereits beansprucht.";long b=plugin.balance(c,id);if(b<cost)return "Nicht genug CP.";plugin.exec(c,"UPDATE accounts SET balance=? WHERE uuid=?",b-cost,id.toString());plugin.exec(c,"INSERT INTO claims VALUES (?,?,?,?,?,?)",server,world(l),l.getBlockX()>>4,l.getBlockZ()>>4,id.toString(),team(c,id));return "Chunk beansprucht für "+Money.format(cost);});p.sendMessage(result); if(result.startsWith("Chunk beansprucht")) border(p,l);return;
    }
    if(cmd.equals("unclaim")) {String msg=plugin.tx(c->{try(PreparedStatement s=c.prepareStatement("SELECT owner,team_id FROM claims WHERE server=? AND world=? AND x=? AND z=?")){s.setString(1,server);s.setString(2,world(l));s.setInt(3,l.getBlockX()>>4);s.setInt(4,l.getBlockZ()>>4);try(ResultSet r=s.executeQuery()){if(!r.next())return "Chunk ist nicht beansprucht.";if(!allowed(c,id,r.getString(1),r.getString(2)))return "Keine Berechtigung.";}}plugin.exec(c,"DELETE FROM claims WHERE server=? AND world=? AND x=? AND z=?",server,world(l),l.getBlockX()>>4,l.getBlockZ()>>4);return "Claim freigegeben.";});p.sendMessage(msg);return;}
    if((cmd.equals("home") || cmd.equals("sethome")) && !SpawnRoute.isSurvival(server)){p.sendMessage("Homes sind nur auf Survival verf?gbar.");return;}
    // /spawn is handled by LobbyRoute on lobby, survival, VIP worlds and PvP.
    if(cmd.equals("sethome")) {String name=a.length==0?"home":a[0].toLowerCase(Locale.ROOT);if(!name.matches("[a-z0-9_]{1,16}")){p.sendMessage("Ungültiger Name.");return;}String msg=plugin.tx(c->{int slots=HomeSlots.lock(c,server,id.toString());String existing=plugin.one(c,"SELECT name FROM homes WHERE server=? AND uuid=? AND name=?",server,id.toString(),name);int count=Integer.parseInt(plugin.one(c,"SELECT COUNT(*) FROM homes WHERE server=? AND uuid=?",server,id.toString()));if(existing==null && count>=slots)return "Home-Limit erreicht. Weitere Slots im /home-Menue kaufen.";plugin.exec(c,"REPLACE INTO homes VALUES (?,?,?,?,?,?,?,?,?)",server,id.toString(),name,world(l),l.getX(),l.getY(),l.getZ(),l.getYaw(),l.getPitch());return "Home gespeichert: "+name;});p.sendMessage(msg);return;}
    Location dest;
    {String name=a.length==0?"home":a[0].toLowerCase(Locale.ROOT);dest=plugin.tx(c->{try(PreparedStatement s=c.prepareStatement("SELECT world,x,y,z,yaw,pitch FROM homes WHERE server=? AND uuid=? AND name=?")){s.setString(1,server);s.setString(2,id.toString());s.setString(3,name);try(ResultSet r=s.executeQuery()){if(!r.next())return null;World w=Bukkit.getWorld(r.getString(1));return w==null?null:new Location(w,r.getDouble(2),r.getDouble(3),r.getDouble(4),r.getFloat(5),r.getFloat(6));}}});}
    if(dest==null){p.sendMessage("Home nicht gefunden.");return;}startTeleport(p,()->p.teleport(dest));
  }
  void border(Player p,Location l){int x=(l.getBlockX()>>4)<<4,z=(l.getBlockZ()>>4)<<4;for(int i=0;i<=16;i++){p.spawnParticle(Particle.HAPPY_VILLAGER,x+i,l.getY()+1,z,1,0,0,0,0);p.spawnParticle(Particle.HAPPY_VILLAGER,x+i,l.getY()+1,z+16,1,0,0,0,0);p.spawnParticle(Particle.HAPPY_VILLAGER,x,l.getY()+1,z+i,1,0,0,0,0);p.spawnParticle(Particle.HAPPY_VILLAGER,x+16,l.getY()+1,z+i,1,0,0,0,0);}}
  void cancel(Player p){Integer task=teleports.remove(p.getUniqueId());if(task!=null){Bukkit.getScheduler().cancelTask(task);p.sendMessage("Teleport abgebrochen.");}}
  void startTeleport(Player p,Runnable action){cancel(p);if(plugin.lobbyRoute()!=null)plugin.lobbyRoute().cancel(p);int[] left={Math.max(1,plugin.getConfig().getInt("teleport-seconds",10))};p.sendMessage("Teleport in "+left[0]+" Sekunden. Stillstehen!");int task=Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin,()->{if(!p.isOnline()){cancel(p);return;}if(--left[0]<=0){Integer done=teleports.remove(p.getUniqueId());if(done!=null)Bukkit.getScheduler().cancelTask(done);action.run();return;}p.sendMessage("Teleport in "+left[0]+" Sekunden.");},20,20);teleports.put(p.getUniqueId(),task);}
  void cancelSilent(Player p){Integer task=teleports.remove(p.getUniqueId());if(task!=null)Bukkit.getScheduler().cancelTask(task);}
  @EventHandler public void arrival(PlayerJoinEvent e){
    if(!SpawnRoute.isLobby(server))return;
    Player p=e.getPlayer();
    try {
      boolean pending=plugin.tx(c->{String expiry=plugin.one(c,"SELECT expires FROM spawn_transfers WHERE uuid=?",p.getUniqueId().toString());plugin.exec(c,"DELETE FROM spawn_transfers WHERE uuid=?",p.getUniqueId().toString());return expiry!=null && Long.parseLong(expiry)>System.currentTimeMillis();});
      if(pending)Bukkit.getScheduler().runTask(plugin,()->{if(p.isOnline())p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());});
    } catch(Exception ex){plugin.getLogger().warning("Lobby-Spawn konnte nicht gepr?ft werden: "+ex.getMessage());p.sendMessage("Lobby-Spawn konnte nicht gesetzt werden. Bitte /spawn erneut nutzen.");}
  }
  @EventHandler(ignoreCancelled=true) public void move(PlayerMoveEvent e){
    Location from=e.getFrom(), to=e.getTo();
    if(to!=null && (!Objects.equals(from.getWorld(),to.getWorld()) || WarmupMove.blockChanged(from.getBlockX(),from.getBlockY(),from.getBlockZ(),to.getBlockX(),to.getBlockY(),to.getBlockZ())))cancel(e.getPlayer());
  }
  @EventHandler(ignoreCancelled=true) public void damage(EntityDamageEvent e){if(e.getEntity() instanceof Player p)cancel(p);}
  @EventHandler public void quit(PlayerQuitEvent e){cancel(e.getPlayer());}
  @EventHandler(ignoreCancelled=true) public void place(BlockPlaceEvent e){if(!can(e.getPlayer(),e.getBlock().getLocation()))e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){if(grave(e.getBlock()) || grave(e.getBlock().getRelative(0,1,0)) || !can(e.getPlayer(),e.getBlock().getLocation()))e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void interact(PlayerInteractEvent e){Block b=e.getClickedBlock();if(b==null)return;if(grave(b)){e.setCancelled(true);if(e.getAction()==Action.RIGHT_CLICK_BLOCK && e.getHand()==org.bukkit.inventory.EquipmentSlot.HAND)open(e.getPlayer(),b);return;}if(!can(e.getPlayer(),b.getLocation()))e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void bucketEmpty(PlayerBucketEmptyEvent e){if(!can(e.getPlayer(),e.getBlockClicked().getRelative(e.getBlockFace()).getLocation()))e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void bucketFill(PlayerBucketFillEvent e){if(!can(e.getPlayer(),e.getBlockClicked().getLocation()))e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void pistonExtend(BlockPistonExtendEvent e){for(Block b:e.getBlocks())if(claimed(b.getLocation()) || claimed(b.getRelative(e.getDirection()).getLocation()) || grave(b)){e.setCancelled(true);return;}}
  @EventHandler(ignoreCancelled=true) public void pistonRetract(BlockPistonRetractEvent e){for(Block b:e.getBlocks())if(claimed(b.getLocation()) || claimed(b.getRelative(e.getDirection()).getLocation()) || grave(b)){e.setCancelled(true);return;}}
  @EventHandler(ignoreCancelled=true) public void hopper(InventoryMoveItemEvent e){InventoryHolder source=e.getSource().getHolder(), dest=e.getDestination().getHolder();if(source instanceof org.bukkit.block.BlockState b && (claimed(b.getLocation()) || grave(b.getBlock())))e.setCancelled(true);if(dest instanceof org.bukkit.block.BlockState b && (claimed(b.getLocation()) || grave(b.getBlock())))e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void explode(EntityExplodeEvent e){e.blockList().removeIf(b->claimed(b.getLocation())||grave(b)||grave(b.getRelative(0,1,0)));}
  @EventHandler(ignoreCancelled=true) public void explodeBlock(BlockExplodeEvent e){e.blockList().removeIf(b->claimed(b.getLocation())||grave(b)||grave(b.getRelative(0,1,0)));}
  @EventHandler(ignoreCancelled=true) public void burn(BlockBurnEvent e){if(claimed(e.getBlock().getLocation())||grave(e.getBlock()))e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void ignite(BlockIgniteEvent e){if(claimed(e.getBlock().getLocation()))e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void spread(BlockSpreadEvent e){if(e.getNewState().getType()==Material.FIRE && claimed(e.getBlock().getLocation()))e.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void gravePhysics(BlockPhysicsEvent e){if(grave(e.getBlock()))e.setCancelled(true);}
  boolean grave(Block b){return graveLocations.contains(graveKey(b.getWorld().getName(),b.getX(),b.getY(),b.getZ()));}
  String encode(List<ItemStack> items)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(BukkitObjectOutputStream o=new BukkitObjectOutputStream(bytes)){o.writeObject(new ArrayList<>(items));}return Base64.getEncoder().encodeToString(bytes.toByteArray());}
  @SuppressWarnings("unchecked") List<ItemStack> decode(String s)throws Exception{try(BukkitObjectInputStream in=new BukkitObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(s)))){return (List<ItemStack>)in.readObject();}}
  @EventHandler(priority=EventPriority.HIGHEST) public void death(PlayerDeathEvent e){if(e.getKeepInventory())return;Player p=e.getEntity();Block b=p.getLocation().getBlock();if(!b.getType().isAir())b=b.getRelative(0,1,0);if(!b.getType().isAir())return;final Block target=b;try{String items=encode(e.getDrops());long expires=System.currentTimeMillis()+Math.max(1,plugin.getConfig().getLong("grave-despawn-minutes",1440))*60000L;plugin.tx(c->{if(plugin.one(c,"SELECT id FROM graves WHERE server=? AND world=? AND x=? AND y=? AND z=? AND status IN ('OPEN','CLAIMING')",server,target.getWorld().getName(),target.getX(),target.getY(),target.getZ())!=null)throw new SQLException("Grabposition belegt");plugin.exec(c,"INSERT INTO graves(server,world,x,y,z,owner,team_id,items,xp,expires) VALUES (?,?,?,?,?,?,?,?,?,?)",server,target.getWorld().getName(),target.getX(),target.getY(),target.getZ(),p.getUniqueId().toString(),team(c,p.getUniqueId()),items,Experience.half(p.getLevel(),p.getExp(),p.getExpToLevel()),expires);return null;});e.getDrops().clear();e.setDroppedExp(0);graveLocations.add(graveKey(target.getWorld().getName(),target.getX(),target.getY(),target.getZ()));target.setType(Material.PLAYER_HEAD); org.bukkit.block.Skull skull=(org.bukkit.block.Skull)target.getState(); skull.setPlayerProfile(p.getPlayerProfile()); org.bukkit.block.data.Rotatable rotation=(org.bukkit.block.data.Rotatable)skull.getBlockData(); float yaw=p.getLocation().getYaw(); org.bukkit.block.BlockFace[] faces={org.bukkit.block.BlockFace.SOUTH,org.bukkit.block.BlockFace.SOUTH_SOUTH_WEST,org.bukkit.block.BlockFace.SOUTH_WEST,org.bukkit.block.BlockFace.WEST_SOUTH_WEST,org.bukkit.block.BlockFace.WEST,org.bukkit.block.BlockFace.WEST_NORTH_WEST,org.bukkit.block.BlockFace.NORTH_WEST,org.bukkit.block.BlockFace.NORTH_NORTH_WEST,org.bukkit.block.BlockFace.NORTH,org.bukkit.block.BlockFace.NORTH_NORTH_EAST,org.bukkit.block.BlockFace.NORTH_EAST,org.bukkit.block.BlockFace.EAST_NORTH_EAST,org.bukkit.block.BlockFace.EAST,org.bukkit.block.BlockFace.EAST_SOUTH_EAST,org.bukkit.block.BlockFace.SOUTH_EAST,org.bukkit.block.BlockFace.SOUTH_SOUTH_EAST}; rotation.setRotation(faces[Math.floorMod(Math.round(yaw/22.5f),16)]); skull.setBlockData(rotation); skull.update(true,false);p.sendMessage("Grab bei "+target.getX()+", "+target.getY()+", "+target.getZ());}catch(Exception ex){plugin.getLogger().warning("Grab nicht erstellt, normale Drops bleiben: "+ex.getMessage());}}
  private final Map<UUID, Long> graveClicks = new HashMap<>();
  void open(Player p, Block b) {
    UUID playerId=p.getUniqueId(); long now=System.currentTimeMillis();
    Long previous=graveClicks.put(playerId,now);
    if(previous!=null && now-previous<1000) return;
    String world=b.getWorld().getName(); int x=b.getX(),y=b.getY(),z=b.getZ();
    Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{
      try {
        GraveClaim claim=plugin.tx(c->{
          try(PreparedStatement s=c.prepareStatement("SELECT id,owner,team_id,items,xp FROM graves WHERE server=? AND world=? AND x=? AND y=? AND z=? AND status='OPEN' ORDER BY id LIMIT 1")) {
            s.setString(1,server);s.setString(2,world);s.setInt(3,x);s.setInt(4,y);s.setInt(5,z);
            try(ResultSet r=s.executeQuery()) {
              if(!r.next()) return null;
              if(!allowed(c,playerId,r.getString(2),r.getString(3))) return new GraveClaim(-1,null,0);
              long id=r.getLong(1); String items=r.getString(4); int xp=r.getInt(5);
              if(!GraveStore.lock(c,id,System.currentTimeMillis())) return null;
              return new GraveClaim(id,items,xp);
            }
          }
        });
        Bukkit.getScheduler().runTask(plugin,()->{
          if(claim==null){if(p.isOnline())p.sendMessage("Grab ist bereits in Benutzung oder leer.");return;}
          if(claim.id()<0){if(p.isOnline())p.sendMessage("Dieses Grab gehoert dir nicht.");return;}
          if(!p.isOnline()){release(claim.id());return;}
          List<ItemStack> items;
          try {items=decode(claim.items());} catch(Exception ex){plugin.getLogger().warning("Grabdaten unlesbar: "+ex);release(claim.id());p.sendMessage("Grab konnte nicht gelesen werden.");return;}
          final List<ItemStack> loot=items;
          // Close the grave out (CLAIMED, delivered=TRUE) first and hand out items only after that commit:
          // a failed close-out leaves CLAIMING/delivered=FALSE with nothing given, so reopening it later cannot loot twice.
          Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{
            try {plugin.tx(c->{GraveStore.finish(c,claim.id());return null;});}
            catch(Exception ex){plugin.getLogger().warning("Grab "+claim.id()+" DB-Abschluss fehlgeschlagen, nichts ausgegeben: "+ex);release(claim.id());Bukkit.getScheduler().runTask(plugin,()->{if(p.isOnline())p.sendMessage("Grab konnte nicht geoeffnet werden. Bitte erneut versuchen.");});return;}
            Bukkit.getScheduler().runTask(plugin,()->{
              graveLocations.remove(graveKey(world,x,y,z));if(b.getType()==Material.PLAYER_HEAD || b.getType()==Material.BARREL)b.setType(Material.AIR);
              if(p.isOnline()){for(ItemStack item:loot) if(item!=null) for(ItemStack excess:p.getInventory().addItem(item).values()) p.getWorld().dropItemNaturally(p.getLocation(),excess);p.giveExp(claim.xp());p.saveData();p.sendMessage("Grab geleert.");}
              else for(ItemStack item:loot) if(item!=null) try{plugin.tx(c->{Deliveries.insert(c,UUID.randomUUID().toString(),playerId.toString(),item,"grave");return null;});}catch(Exception ex){plugin.getLogger().severe("Grab "+claim.id()+": Abholfach fehlgeschlagen, Item am Grab abgelegt: "+ex);b.getWorld().dropItemNaturally(b.getLocation(),item);}
            });
          });
        });
      } catch(Exception ex){plugin.getLogger().warning("Grab oeffnen: "+ex);Bukkit.getScheduler().runTask(plugin,()->{if(p.isOnline())p.sendMessage("Grab konnte nicht geoeffnet werden.");});}
    });
  }
  record GraveClaim(long id,String items,int xp) {}
  void release(long id){Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{try{plugin.tx(c->{plugin.exec(c,"UPDATE graves SET status='OPEN', claiming_at=NULL WHERE id=? AND status='CLAIMING' AND delivered=FALSE",id);return null;});}catch(Exception ex){plugin.getLogger().warning("Grab-Freigabe: "+ex);}});}
  @EventHandler(ignoreCancelled=true) public void entityInteract(PlayerInteractEntityEvent e) {
    if (!can(e.getPlayer(), e.getRightClicked().getLocation())) e.setCancelled(true);
  }
  @EventHandler(ignoreCancelled=true) public void entityDamage(EntityDamageByEntityEvent e) {
    org.bukkit.entity.Entity actor = e.getDamager();
    if (actor instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof org.bukkit.entity.Entity shooter) actor = shooter;
    if (actor instanceof Player p && !(e.getEntity() instanceof Player) && !can(p, e.getEntity().getLocation())) e.setCancelled(true);
  }

}
