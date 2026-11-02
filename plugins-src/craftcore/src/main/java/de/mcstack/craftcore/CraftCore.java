package de.mcstack.craftcore;

import java.sql.*;
import java.util.*;
import java.lang.reflect.Proxy;
import net.milkbowl.vault.economy.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;

public final class CraftCore extends JavaPlugin implements Listener {
  private String password, server;
  private boolean economyEnabled;
  private de.mcstack.craftcore.vip.VipWorldGate vipWorldGate;
  private de.mcstack.craftcore.vip.LobbyVip lobbyVip;
  private de.mcstack.craftcore.vip.VipWorldMenu vipWorldMenu;
  private Economy economy;
  private Trade trade;
  private LaunchPads launchPads;
  private Portals portals;
  private UserMenu userMenu;
  private TeamInvites teamInvites;
  private Motivation motivation;
  private RankDisplays rankDisplays;
  static final String PLAYER_ONLY_MESSAGE = "Nur für Spieler.";
  static final String TEAM_COLOR_MIGRATION = "ALTER TABLE teams ADD COLUMN IF NOT EXISTS color VARCHAR(4) NOT NULL DEFAULT '&f'";
  void refreshRanks() { if(rankDisplays!=null) rankDisplays.refreshAll(); }
  private Presence presence;
  private PlayerStats playerStats;
  private InventorySync inventorySync;
  private DeathEvents deathEvents;
  final MenuReturn.Registry<UUID> menuReturns=new MenuReturn.Registry<>();
  final Set<org.bukkit.inventory.Inventory> npcPreviews = Collections.newSetFromMap(new WeakHashMap<>());
  private Npcs npcs;
  private CitizensNpcs citizensNpcs;
  private de.mcstack.craftcore.pvp.LobbyPvp lobbyPvp;
  de.mcstack.craftcore.pvp.LobbyPvp lobbyPvp() { return lobbyPvp; }
  de.mcstack.craftcore.vip.LobbyVip lobbyVip() { return lobbyVip; }
  private void startCitizensNpcs() {
    if (citizensNpcs == null && "lobby".equalsIgnoreCase(System.getenv().getOrDefault("CRAFTCORE_SERVER", "")) && Bukkit.getPluginManager().isPluginEnabled("Citizens")) { citizensNpcs = new CitizensNpcs(this, userMenu); citizensNpcs.start(); }
  }
  @EventHandler public void citizensEnabled(org.bukkit.event.server.PluginEnableEvent event) { if (event.getPlugin().getName().equalsIgnoreCase("Citizens")) startCitizensNpcs(); }
  private final Map<UUID, Scoreboard> sidebars = new HashMap<>();
  interface Work<T> { T run(Connection c) throws Exception; }
  Connection connection() throws SQLException { return DriverManager.getConnection("jdbc:mariadb://craftcore-db:3306/craftcore?connectTimeout=3000&socketTimeout=5000", ServerProfile.dbUser(System.getenv()), password); }
  <T> T tx(Work<T> work) throws Exception { return tx(null, work); }
  <T> T tx(String idemKey, Work<T> work) throws Exception {
    try (Connection c = connection()) {
      try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT GET_LOCK('craftcore-write', 5)")) { if (!r.next() || r.getInt(1)!=1) throw new SQLException("DB lock timeout"); }
      try { return SellTransaction.run(c, idemKey, work); }
      finally { try (Statement s=c.createStatement()) { s.execute("SELECT RELEASE_LOCK('craftcore-write')"); } }
    }
  }
  SellTransaction.CommitStatus checkCommitStatus(String idemKey, Connection original, java.time.Duration timeout) { return SellTransaction.checkCommitStatus(idemKey, original, timeout, this::connection); }
  void exec(Connection c,String sql,Object... args) throws SQLException { try (PreparedStatement s=c.prepareStatement(sql)) { for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]); s.executeUpdate(); } }
  String one(Connection c,String sql,Object... args) throws SQLException { try (PreparedStatement s=c.prepareStatement(sql)) { for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]); try(ResultSet r=s.executeQuery()) { return r.next()?r.getString(1):null; } } }
  private void account(Connection c,UUID id) throws SQLException { exec(c,"INSERT IGNORE INTO accounts (uuid,balance) VALUES (?,0)",id.toString()); }
  long balance(Connection c,UUID id) throws SQLException { account(c,id); return Long.parseLong(one(c,"SELECT balance FROM accounts WHERE uuid=? FOR UPDATE",id.toString())); }
  private long balance(UUID id) throws Exception { return tx(c->balance(c,id)); }
  private boolean change(UUID id,long amount) throws Exception { return tx(c->EconomyStore.change(c,id.toString(),amount)); }
  private boolean transfer(UUID from,UUID to,long amount) throws Exception { return tx(c->EconomyStore.transfer(c,from.toString(),to.toString(),amount)); }
  @Override public void onEnable() {
    password=System.getenv("CRAFTCORE_DB_PASSWORD"); server=System.getenv().getOrDefault("CRAFTCORE_SERVER","Server");
    economyEnabled=ServerProfile.economyEnabled(server);
    try {
      Class.forName("org.mariadb.jdbc.Driver");
      Presence newPresence=new Presence(this,server);
      newPresence.start(economyEnabled && !server.startsWith("vipworld-"));
      presence=newPresence;
    } catch(Exception e) { getLogger().warning("Presence start failed: "+e); }
    if (server.startsWith("vipworld-")) {
      try { Class.forName("org.mariadb.jdbc.Driver"); } catch (ClassNotFoundException | LinkageError e) { getLogger().warning("MariaDB-Treiber fehlt; VIP-Zugang nur mit Berechtigung."); }
      vipWorldGate=new de.mcstack.craftcore.vip.VipWorldGate(this, () -> DriverManager.getConnection("jdbc:mariadb://craftcore-db:3306/craftcore?connectTimeout=2000&socketTimeout=2000", ServerProfile.dbUser(System.getenv()), password), System.getenv("CRAFTCORE_VIP_WORLD_ID"));
      vipWorldGate.start(); new de.mcstack.craftcore.vip.VipIdleStop(this).start();
      new de.mcstack.craftcore.vip.VipAddonApplier(this, this::connection).start();
      vipWorldMenu=new de.mcstack.craftcore.vip.VipWorldMenu(this, this::connection); vipWorldMenu.start();
      return;
    }
    if (!economyEnabled) {
      getLogger().info("PvP-Profil: Economy-Module deaktiviert");
      boolean driverLoaded;
      try { Class.forName("org.mariadb.jdbc.Driver"); driverLoaded=true; } catch (ClassNotFoundException | LinkageError e) { driverLoaded=false; }
      String error=ServerProfile.pvpStartupError(password,driverLoaded);
      if(error!=null) { getLogger().severe(error); getServer().getPluginManager().disablePlugin(this); return; }
      new de.mcstack.craftcore.pvp.PvpModule(this, this::connection).start();
      rankDisplays=new RankDisplays(this,server); rankDisplays.start(); return;
    }
    if(password==null || password.isBlank()) { getLogger().severe("CRAFTCORE_DB_PASSWORD fehlt"); getServer().getPluginManager().disablePlugin(this); return; }
    try {
      Class.forName("org.mariadb.jdbc.Driver");
      try(Connection c=connection(); Statement s=c.createStatement()) {
        s.execute("CREATE TABLE IF NOT EXISTS known_players (uuid CHAR(36) PRIMARY KEY, name VARCHAR(16) NOT NULL, UNIQUE KEY known_name (name))");
        s.execute("CREATE TABLE IF NOT EXISTS accounts (uuid CHAR(36) PRIMARY KEY, balance BIGINT NOT NULL DEFAULT 0)");
        s.execute("CREATE TABLE IF NOT EXISTS teams (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(16) NOT NULL UNIQUE)");
        s.execute(TEAM_COLOR_MIGRATION);
        s.execute("CREATE TABLE IF NOT EXISTS members (uuid CHAR(36) PRIMARY KEY, team_id BIGINT NOT NULL, role VARCHAR(8) NOT NULL, FOREIGN KEY(team_id) REFERENCES teams(id) ON DELETE CASCADE)");
        s.execute(SellTransaction.DDL);
        s.execute(SellTransaction.PENDING_DDL);
        s.execute(SellTransaction.PENDING_MIGRATION);
        s.execute(SellTransaction.STATUS_MIGRATION);
        InviteStore.prepareSchema(c);
        SidebarPreference.prepare(c);
      }
      if (InventorySync.tracks(server)) { playerStats=new PlayerStats(this,server.toLowerCase(Locale.ROOT)); playerStats.start(); inventorySync=new InventorySync(this,server); inventorySync.start(); }
      deathEvents=new DeathEvents(this,server); deathEvents.start();
      teamInvites=new TeamInvites(this,server); teamInvites.start();
      saveDefaultConfig(); ResourceSync.sync(this);
      for (OfflinePlayer known : Bukkit.getOfflinePlayers()) if (known.hasPlayedBefore() && known.getName()!=null) tx(c->{exec(c,"INSERT IGNORE INTO known_players(uuid,name) VALUES (?,?)",known.getUniqueId().toString(),known.getName()); return null;});
      new ServerStatus(this).start();
      new Features(this).start();
      launchPads=new LaunchPads(this); launchPads.start();
      portals=new Portals(this); portals.start();
      if ("lobby".equalsIgnoreCase(server)) { lobbyPvp=new de.mcstack.craftcore.pvp.LobbyPvp(this, this::connection); lobbyPvp.start(); lobbyVip=new de.mcstack.craftcore.vip.LobbyVip(this, this::connection); lobbyVip.start(); }
      if (SpawnRoute.isSurvival(server)) { getServer().getPluginManager().registerEvents(new SafeSpawn(this), this); getServer().getPluginManager().registerEvents(new EndLock(this), this); }
      userMenu=new UserMenu(this); userMenu.start();
      new TeamTeleport(this,server).start();
      new Challenges(this,server).start();
      motivation=new Motivation(this); motivation.start();
      rankDisplays=new RankDisplays(this,server); rankDisplays.start();
      InviteRewards.start(this,SpawnRoute.isSurvival(server));
      tx(c->{exec(c,"CREATE TABLE IF NOT EXISTS rcon_purchases (idem_key VARBINARY(64) PRIMARY KEY, kind VARCHAR(8) NOT NULL, buyer CHAR(36) NOT NULL, listing_id CHAR(36) NOT NULL, qty INT NOT NULL, max_price BIGINT NOT NULL, response VARCHAR(80) NOT NULL, created_at BIGINT NOT NULL)");exec(c,"CREATE OR REPLACE SQL SECURITY DEFINER VIEW v_rcon_purchase_result AS SELECT CAST(idem_key AS CHAR(64)) AS idem_key, response FROM rcon_purchases");TestRun.prepare(c);return null;});
      tx(c->{BankBuy.migrate(c);return null;});
      boolean canGrant;
      try (Connection grant=connection()) { canGrant=ReadGrants.canGrant(grant); }
      catch(Exception ex) { canGrant=false; }
      if (!canGrant) getLogger().info("creepy_ro-Grants uebersprungen (kein GRANT-Recht); bitte scripts/creepy-ro-grants.sql als craft_admin ausfuehren");
      if (canGrant) {
        try { try (Connection grant=connection()) { if(!PurchaseViewGrant.apply(grant)) getLogger().warning("GRANT v_rcon_purchase_result fuer creepy_ro nicht moeglich"); if(!PurchaseViewGrant.applyBank(grant)) getLogger().warning("GRANT bank_buy_orders fuer creepy_ro nicht moeglich"); } }
        catch(Exception ex) { getLogger().warning("GRANT v_rcon_purchase_result fuer creepy_ro nicht moeglich: "+ex.getClass().getSimpleName()); }
      }
      Deliveries.start(this);
      SaleNotices.start(this);
      WebsiteStats.start(this);
      tx(c->{PlayerBalance.migrate(c); InventorySync.migrate(c); return null;});
      if (canGrant) for (String view:List.of("v_player_balance","v_player_inventory","v_order_fulfillment","player_deaths","deaths_by")) {
        try { try (Connection grant=connection()) { if(!ReadGrants.apply(grant, view)) getLogger().warning("GRANT "+view+" fuer creepy_ro nicht moeglich"); } }
        catch(Exception ex) { getLogger().warning("GRANT "+view+" fuer creepy_ro nicht moeglich: "+ex.getClass().getSimpleName()); }
      }
      new Market(this).start();
      new Shop(this).start();
      new Auction(this).start();
      Deliveries.migrate(this);
      npcs=new Npcs(this); npcs.start();
      startCitizensNpcs();
      new MineRushLootEditor(this).start();
      trade=new Trade(this); trade.start();
      economy=(Economy)Proxy.newProxyInstance(getClassLoader(),new Class[]{Economy.class},(proxy,method,args)->vault(method.getName(),args));
      getServer().getServicesManager().register(Economy.class,economy,this,ServicePriority.Normal);
      getServer().getPluginManager().registerEvents(this,this);
      getServer().getScheduler().runTaskTimer(this,()->Bukkit.getOnlinePlayers().forEach(this::sidebar),20L,100L);
      getServer().getScheduler().runTaskTimer(this,()->DeliveryNotices.poll(this),100L,100L);
      getLogger().info("CraftCore bereit: gemeinsame MariaDB und Vault-Economy registriert");
    } catch(Exception e) { getLogger().severe("CraftCore Start fehlgeschlagen: "+e); getServer().getPluginManager().disablePlugin(this); }
  }
  @Override public void onDisable() { if(presence!=null)presence.shutdown(); if(vipWorldGate!=null)vipWorldGate.stop(); if(lobbyVip!=null)lobbyVip.shutdown(); if(vipWorldMenu!=null)vipWorldMenu.shutdown(); menuReturns.shutdown(); if(Market.instance!=null)Market.instance.shutdown(); if(deathEvents!=null)deathEvents.shutdown(); if(launchPads!=null)launchPads.shutdown(); if(playerStats!=null)playerStats.shutdown(); if(inventorySync!=null)inventorySync.shutdown(); if(motivation!=null)motivation.shutdown(); if(teamInvites!=null)teamInvites.shutdown(); if(trade!=null)trade.shutdown(); getServer().getServicesManager().unregisterAll(this); }
  private OfflinePlayer resolve(Object value) { if(value instanceof OfflinePlayer p)return p; if(value instanceof String s) return Bukkit.getOfflinePlayer(s); throw new IllegalArgumentException("Spieler fehlt"); }
  private Object vault(String method,Object[] args) throws Exception {
    if(args==null)args=new Object[0];
    switch(method) {
      case "isEnabled": return isEnabled(); case "getName": return "CraftCore"; case "hasBankSupport": return false;
      case "fractionalDigits": return 2; case "format": return Money.format(Money.cents((double)args[0]));
      case "currencyNamePlural": case "currencyNameSingular": return "CP";
      case "getBanks": return Collections.emptyList();
      case "createBank": case "deleteBank": case "bankBalance": case "bankHas": case "bankWithdraw": case "bankDeposit": case "isBankOwner": case "isBankMember": return new EconomyResponse(0,0,EconomyResponse.ResponseType.NOT_IMPLEMENTED,"Bankkonten nicht unterstützt");
      case "hasAccount": return true;
      case "createPlayerAccount": balance(resolve(args[0]).getUniqueId()); return true;
      case "getBalance": return balance(resolve(args[0]).getUniqueId())/100.0;
      case "has": { long n=Money.cents((double)args[args.length-1]); return n>=0 && balance(resolve(args[0]).getUniqueId())>=n; }
      case "withdrawPlayer": case "depositPlayer": {
        UUID id=resolve(args[0]).getUniqueId(); double amount=(double)args[args.length-1];
        try { long n=Money.cents(amount); if(n<0)throw new IllegalArgumentException(); boolean ok=change(id,method.equals("withdrawPlayer")?-n:n); return new EconomyResponse(ok?amount:0,balance(id)/100.0,ok?EconomyResponse.ResponseType.SUCCESS:EconomyResponse.ResponseType.FAILURE,ok?null:"Nicht genug CP"); }
        catch(IllegalArgumentException | ArithmeticException e) { return new EconomyResponse(0,0,EconomyResponse.ResponseType.FAILURE,"Ungültiger Betrag"); }
      }
      case "toString": return "CraftCore Economy"; case "hashCode": return System.identityHashCode(this); case "equals": return args[0]==economy;
      default: throw new UnsupportedOperationException(method);
    }
  }
  void remember(UUID id,String name) throws Exception { tx(c->{exec(c,"DELETE FROM known_players WHERE name=? AND uuid<>?",name,id.toString()); exec(c,"INSERT INTO known_players(uuid,name) VALUES (?,?) ON DUPLICATE KEY UPDATE name=VALUES(name)",id.toString(),name); return null;}); }
  @EventHandler(priority=EventPriority.MONITOR) public void pvpHead(PlayerDeathEvent e) {
    Player victim=e.getEntity(), killer=victim.getKiller();
    if(killer==null || killer.getUniqueId().equals(victim.getUniqueId())) return;
    ItemStack head=new ItemStack(Material.PLAYER_HEAD); SkullMeta meta=(SkullMeta)head.getItemMeta();
    meta.setPlayerProfile(victim.getPlayerProfile()); meta.setDisplayName("Kopf von "+victim.getName());
    meta.setLore(List.of("Getoetet von "+killer.getName(),"Datum: "+ZonedDateTime.now(ZoneId.of("Europe/Berlin")).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm z"))));
    head.setItemMeta(meta); victim.getWorld().dropItemNaturally(victim.getLocation(),head);
  }
  @EventHandler public void join(PlayerJoinEvent e) { try { remember(e.getPlayer().getUniqueId(),e.getPlayer().getName()); } catch(Exception ex) { getLogger().warning("Spielerregistrierung: "+ex); } sidebar(e.getPlayer()); DeliveryNotices.join(this,e.getPlayer()); SaleNotices.notify(this,e.getPlayer(),true); }
  @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
    if(!ServerProfile.commandAllowed(economyEnabled,command.getName())) return List.of();
    if(command.getName().equals("sidebar")) return args.length==1 ? UserMenu.complete(args[0],"an","aus") : List.of();
    if(command.getName().equals("cc")) {
      if(args.length>1 && args[0].equalsIgnoreCase("npc")) return citizensNpcs == null ? List.of() : citizensNpcs.complete(sender, Arrays.copyOfRange(args,1,args.length));
      if(!sender.hasPermission("craftcore.admin")) return args.length==1 && sender.hasPermission("craftcore.admin.npc") ? UserMenu.complete(args[0],"npc") : List.of();
      if(args.length==1) return sender.hasPermission("craftcore.admin.npc") ? UserMenu.complete(args[0],"portal","launch","deaths","npc") : UserMenu.complete(args[0],"portal","launch","deaths");
      if(args.length==2 && args[0].equalsIgnoreCase("deaths")) return UserMenu.complete(args[1],"backfill");
      String[] rest=Arrays.copyOfRange(args,1,args.length);
      if(args[0].equalsIgnoreCase("portal")) return portals==null ? List.of() : portals.onTabComplete(sender,command,alias,rest);
      if(args[0].equalsIgnoreCase("launch")) return launchPads==null ? List.of() : launchPads.onTabComplete(sender,command,alias,rest);
      return List.of();
    }
    if(command.getName().equals("balance")) return List.of();
    if(command.getName().equals("team") && args.length==1) return UserMenu.complete(args[0], "create", "invite", "join", "leave", "kick", "info", "promote", "demote", "color");
    if(command.getName().equals("team") && args.length==2 && args[0].equalsIgnoreCase("color")) return RankDisplay.TEAM_COLORS.keySet().stream().filter(n->n.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
    if(command.getName().equals("team") && args.length==2 && args[0].equalsIgnoreCase("invite")) return teamInvites==null ? List.of() : teamInvites.complete(args[1]);
    if(command.getName().equals("claim") && args.length==1) return UserMenu.complete(args[0], "create", "list");
    if(command.getName().equals("ah") && args.length==1) return UserMenu.complete(args[0], "my", "sell", "search", "zurueckziehen", "cancel");
    if(command.getName().equals("ah") && args.length==2 && sender instanceof Player p && Set.of("zurueckziehen","zur?ckziehen","cancel").contains(args[0].toLowerCase(Locale.ROOT))) try {
      return Auction.instance.list(p,true,"alle","").stream().map(Auction.Listing::id).filter(id->id.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
    } catch(Exception ex){return List.of();}
    if(command.getName().equals("npc") && args.length==1) return UserMenu.complete(args[0], "set");
    if(command.getName().equals("npc") && args.length==2) return UserMenu.complete(args[1], "bank", "ah");
    if(command.getName().equals("trade") && args.length==1) { List<String> names=new ArrayList<>(List.of("accept")); Bukkit.getOnlinePlayers().forEach(p->names.add(p.getName())); return names.stream().filter(n->n.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).toList(); }
    if(command.getName().equals("home") && args.length==1 && sender instanceof Player p) try {return tx(c->{List<String> names=new ArrayList<>();try(PreparedStatement q=c.prepareStatement("SELECT name FROM homes WHERE server=? AND uuid=? ORDER BY name")){q.setString(1,server);q.setString(2,p.getUniqueId().toString());try(ResultSet r=q.executeQuery()){while(r.next())if(r.getString(1).startsWith(args[0].toLowerCase(Locale.ROOT)))names.add(r.getString(1));}}return names;});}catch(Exception ex){return List.of();}
    if(command.getName().equals("shop")) {
      if(args.length==1) try {return tx(c->{List<String> choices=new ArrayList<>(List.of("help","sell","show","remove","meine","entfernen"));try(PreparedStatement s=c.prepareStatement("SELECT DISTINCT seller_name FROM shop_journal WHERE status='OPEN' ORDER BY seller_name");ResultSet r=s.executeQuery()){while(r.next())choices.add(r.getString(1));}return choices.stream().filter(x->x.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).distinct().toList();});}catch(Exception ex){getLogger().warning("Shop-Vervollstaendigung: "+ex);return List.of();}
      if(args.length==2 && sender instanceof Player p && Set.of("entfernen","remove","undo").contains(args[0].toLowerCase(Locale.ROOT))) try {
        List<Shop.Listing> own=Shop.instance.listings(p.getUniqueId().toString(),true);
        List<String> values=new ArrayList<>(); for(int i=0;i<own.size();i++){if(args[0].equalsIgnoreCase("remove")) values.add(Integer.toString(i+1)); else values.add(own.get(i).id());}
        return values.stream().filter(v->v.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
      } catch(Exception ex){return List.of();}
      if(args.length==2 && args[0].equalsIgnoreCase("sell")) return UserMenu.complete(args[1],"1","10","100");
      if(args.length==3 && args[0].equalsIgnoreCase("sell")) return UserMenu.complete(args[2],"1","64");
      return List.of();
    }
    if(command.getName().equals("pay") && args.length==1) try { return tx(c->{ List<String> names=new ArrayList<>(); try(PreparedStatement s=c.prepareStatement("SELECT name FROM known_players ORDER BY name")){try(ResultSet r=s.executeQuery()){while(r.next()){String name=r.getString(1); if(name.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))) names.add(name);}}} return names; }); } catch(Exception ex) { getLogger().warning("Pay-Vervollstaendigung: "+ex); return List.of(); }
    if(command.getName().equals("verkaufen")) return SellModes.complete(args);
    return null;
  }
  @EventHandler public void quit(PlayerQuitEvent e) { sidebars.remove(e.getPlayer().getUniqueId()); DeliveryNotices.quit(e.getPlayer()); }
  private Scoreboard createSidebar(Player p) {
    Scoreboard board=Bukkit.getScoreboardManager().getNewScoreboard();
    Component title=Component.empty();
    int[] colors={0xFFAA00,0xFFB900,0xFFC800,0xFFD700,0xFFE600};
    String letters="CRAFT";
    for(int i=0;i<letters.length();i++) title=title.append(Component.text(letters.substring(i,i+1),TextColor.color(colors[i])));
    Objective objective=board.registerNewObjective("craftcore",Criteria.DUMMY,title.decorate(TextDecoration.BOLD));
    objective.numberFormat(NumberFormat.blank());
    objective.setDisplaySlot(DisplaySlot.SIDEBAR);
    for(int i=0;i<7;i++) {
      String entry=ChatColor.values()[i].toString();
      Team line=board.registerNewTeam("line"+i);
      line.addEntry(entry);
      Score score=objective.getScore(entry);
      score.numberFormat(NumberFormat.blank());
      score.setScore(7-i);
    }
    board.getTeam("line6").prefix(Component.text("mc.nwebnotfound.dev",NamedTextColor.GRAY));
    p.setScoreboard(board);
    if(rankDisplays!=null) rankDisplays.boardCreated(board);
    return board;
  }
  void sellBalance(UUID id,long cp) { Scoreboard board=sidebars.get(id); if(board!=null) setLine(board,2,"CP: ",Money.format(cp),NamedTextColor.GREEN); }
  private void sidebar(Player p) {
    try {
      if (!tx(c->SidebarPreference.enabled(c,p.getUniqueId().toString()))) return;
      String team=tx(c->one(c,"SELECT t.name FROM teams t JOIN members m ON m.team_id=t.id WHERE m.uuid=?",p.getUniqueId().toString()));
      long cp=balance(p.getUniqueId());
      Scoreboard board=sidebars.computeIfAbsent(p.getUniqueId(),id->createSidebar(p));
      setLine(board,1,"Server: ",server,NamedTextColor.AQUA);
      setLine(board,2,"CP: ",Money.format(cp),NamedTextColor.GREEN);
      setLine(board,3,"Online: ",Integer.toString(Bukkit.getOnlinePlayers().size()),NamedTextColor.WHITE);
      setLine(board,4,"Team: ",team==null?"-":team,NamedTextColor.YELLOW);
    } catch(Exception e) { getLogger().warning("Sidebar: "+e.getMessage()); }
  }
  private void setLine(Scoreboard board,int index,String label,String value,NamedTextColor color) {
    Team line=board.getTeam("line"+index);
    Component text=Component.text(label,NamedTextColor.GRAY).append(Component.text(value,color));
    if(!line.prefix().equals(text)) line.prefix(text);
  }
  private Player target(String name) { return Bukkit.getPlayerExact(name); }
  private static ShopBuyDispatch.Caller commandCaller(CommandSender sender) {
    if(sender instanceof RemoteConsoleCommandSender) return ShopBuyDispatch.Caller.RCON;
    if(sender instanceof ConsoleCommandSender) return ShopBuyDispatch.Caller.CONSOLE;
    if(sender instanceof Player) return ShopBuyDispatch.Caller.PLAYER;
    return ShopBuyDispatch.Caller.OTHER;
  }
  static boolean moduleAvailable(Object module, java.util.function.Consumer<String> reply) {
    if(module!=null) return true;
    reply.accept("Dieser Befehl ist auf diesem Server nicht verfuegbar.");
    return false;
  }
  @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
    if(!ServerProfile.commandAllowed(economyEnabled,command.getName())) { sender.sendMessage("Dieser Befehl ist auf dem PvP-Server deaktiviert."); return true; }
    if(command.getName().equals("rank")) return !moduleAvailable(rankDisplays,sender::sendMessage) || rankDisplays.command(sender,args);
    if(command.getName().equals("cc")) {
      if(args.length>0 && args[0].equalsIgnoreCase("npc")) { if (!sender.hasPermission("craftcore.admin.npc")) sender.sendMessage("Keine Berechtigung."); else if (citizensNpcs == null) sender.sendMessage("Citizens-NPCs sind nicht verfuegbar."); else citizensNpcs.command(sender,Arrays.copyOfRange(args,1,args.length)); return true; }
      if(!sender.hasPermission("craftcore.admin")) return true;
      if(args.length>0) {
        String[] rest=Arrays.copyOfRange(args,1,args.length);
        if(args[0].equalsIgnoreCase("deaths")) { if(!moduleAvailable(deathEvents,sender::sendMessage)) return true; if(args.length==2 && args[1].equalsIgnoreCase("backfill")) deathEvents.backfill(sender); else sender.sendMessage("/cc deaths backfill"); return true; }
        if(args[0].equalsIgnoreCase("portal")) return !moduleAvailable(portals,sender::sendMessage) || portals.onCommand(sender,command,label,rest);
        if(args[0].equalsIgnoreCase("launch")) { if(!moduleAvailable(launchPads,sender::sendMessage)) return true; if(launchPads.onCommand(sender,command,label,rest)) return true; }
      }
      sender.sendMessage(Component.text("Admin: ").append(Component.text("[Portale]").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/cc portal list"))).append(Component.text(" [LaunchPads]").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/cc launch list")))); return true;
    }
    if(command.getName().equals("sidebar")) {
      if(!(sender instanceof Player p)){sender.sendMessage("Bitte nutze /sidebar im Spiel.");return true;}
      if(args.length!=1 || !(args[0].equalsIgnoreCase("an") || args[0].equalsIgnoreCase("aus"))) {p.sendMessage(Component.text("Sidebar: ").append(Component.text("[an]").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/sidebar an"))).append(Component.text(" [aus]").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/sidebar aus"))));return true;}
      try {boolean enabled=args[0].equalsIgnoreCase("an");tx(c->{SidebarPreference.set(c,p.getUniqueId().toString(),enabled);return null;});
        if(enabled) sidebar(p); else {Scoreboard board=sidebars.remove(p.getUniqueId()); if(board!=null && p.getScoreboard()==board) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());}
        p.sendMessage("Sidebar "+(enabled?"eingeschaltet":"ausgeschaltet")+". Aendern mit /sidebar "+(enabled?"aus":"an")+".");
      } catch(Exception ex){getLogger().warning("Sidebar-Einstellung: "+ex);p.sendMessage("Einstellung konnte nicht gespeichert werden. Bitte erneut versuchen.");} return true;
    }
    if (command.getName().equals("ah") && args.length>0 && args[0].equalsIgnoreCase("bid")) {
      RealAuctions.Request request=RealAuctions.parse(args);
      if(!SpawnRoute.isSurvival(server) || request==null || (sender instanceof Player p && (!p.getUniqueId().toString().equals(request.bidder()) || !request.source().equals("ingame")))) {sender.sendMessage("ERR INVALID");return true;}
      if(!(sender instanceof Player || sender instanceof org.bukkit.command.ConsoleCommandSender || sender instanceof org.bukkit.command.RemoteConsoleCommandSender)){sender.sendMessage("ERR INVALID");return true;}
      String response;try{response=tx(c->RealAuctions.bid(c,request,System.currentTimeMillis()));}catch(Exception ex){getLogger().warning("Auktionsgebot: "+ex.getClass().getSimpleName());response="ERR INVALID";}sender.sendMessage(response);return true;
    }
    if((command.getName().equals("bank")||command.getName().equals("market")) && args.length==2 && args[0].equalsIgnoreCase("sellcheck")) {
      if(!(sender instanceof org.bukkit.command.ConsoleCommandSender || sender instanceof org.bukkit.command.RemoteConsoleCommandSender) || Market.instance==null) {sender.sendMessage("ERR INVALID");return true;}
      try{sender.sendMessage(Market.instance.sellCheck(args[1]));}catch(Exception ex){getLogger().warning("sellcheck: "+ex.getClass().getSimpleName());sender.sendMessage("ERR INVALID");} return true;
    }
    if(command.getName().equals("cclink")) {
      if(!(sender instanceof org.bukkit.command.ConsoleCommandSender || sender instanceof org.bukkit.command.RemoteConsoleCommandSender) || args.length!=3 || !args[0].equalsIgnoreCase("set") || !args[2].matches("[0-9]{1,20}")) {sender.sendMessage("ERR INVALID");return true;}
      try {String uuid=UUID.fromString(args[1]).toString(); tx(c->{try(PreparedStatement st=c.prepareStatement("INSERT INTO discord_links(uuid,discord_id) VALUES (?,?) ON DUPLICATE KEY UPDATE discord_id=VALUES(discord_id)")){st.setString(1,uuid);st.setString(2,args[2]);st.executeUpdate();}return null;});sender.sendMessage("OK");}
      catch(Exception ex){getLogger().warning("cclink: "+ex.getClass().getSimpleName());sender.sendMessage("ERR INVALID");} return true;
    }
    if(command.getName().equals("testrun")) {
      TestRun.Sender who=sender instanceof RemoteConsoleCommandSender ? TestRun.Sender.RCON : sender instanceof ConsoleCommandSender ? TestRun.Sender.CONSOLE : sender instanceof Player ? TestRun.Sender.PLAYER : TestRun.Sender.OTHER;
      long cents=-1;
      if(args.length>=2 && args[1].matches("[0-9]+")) { try { cents=Long.parseLong(args[1]); } catch(NumberFormatException ex) { cents=-1; } }
      Boolean configured=getConfig().contains("testrun.enabled") ? getConfig().getBoolean("testrun.enabled") : null;
      TestRun.Result result=TestRun.decide(TestRun.enabled(configured), who, args.length>0 ? args[0] : "", cents, args.length>=3 ? args[2] : "");
      String id=result.credit() ? TestRun.cacheId(result) : "";
      TestRun.Dispatch dispatch=TestRun.dispatch(result, result.credit() ? ShopBuyDispatch.lookup(id, System.currentTimeMillis()) : null);
      if(dispatch.start() && ShopBuyDispatch.beginId(id)) {
        getServer().getScheduler().runTaskAsynchronously(this,()->{
          String response=null;
          try { response=tx(c->TestRun.apply(c,result)) ? "OK" : "ERR INVALID"; }
          catch(Exception ex){getLogger().warning("testrun: "+ex.getClass().getSimpleName());}
          finally { ShopBuyDispatch.finishId(id, response); }
        });
      }
      sender.sendMessage(dispatch.line());
      return true;
    }
    if (command.getName().equals("shop") && args.length==3 && args[0].equalsIgnoreCase("refund") && args[1].equalsIgnoreCase("bank")) {
      // storno a bank_buy order whose items are still uncollected: shop refund bank <request_id>
      if(!(sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender) || !SpawnRoute.isSurvival(server) || Market.instance==null) {sender.sendMessage("ERR INVALID");return true;}
      BankBuy.Env env=Market.instance.bankEnv();
      String rid=args[2]; try{String out=tx(c->BankBuy.refund(c,rid,env)); sender.sendMessage(out);}catch(Exception ex){getLogger().warning("bank_buy refund: "+ex);sender.sendMessage("ERR INVALID");}
      return true;
    }
    if (command.getName().equals("shop") && args.length>1 && args[0].equalsIgnoreCase("buy") && args[1].equalsIgnoreCase("bank")) {
      // order type bank_buy from the web queue (relay): shop buy bank <mc_uuid> <item> <amount> <request_id>
      if(!(sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender) || !SpawnRoute.isSurvival(server)) {sender.sendMessage("ERR INVALID");return true;}
      BankBuy.Request request=BankBuy.parse(args); Market market=Market.instance;
      if(request==null || market==null) {sender.sendMessage("ERR INVALID");return true;}
      String id=BankBuy.cacheId(request); String done=ShopBuyDispatch.lookup(id,System.currentTimeMillis());
      if(done!=null) {sender.sendMessage(done);return true;}
      if(ShopBuyDispatch.beginId(id)) {
        BankBuy.Env env=market.bankEnv();
        getServer().getScheduler().runTaskAsynchronously(this,()->{
          String response=null;
          try { response=tx(c->BankBuy.execute(c,request,System.currentTimeMillis(),env)); }
          catch(Exception ex){getLogger().warning("bank_buy: "+ex);}
          finally { ShopBuyDispatch.finishId(id, response); }
        });
      }
      sender.sendMessage("PENDING bank");
      return true;
    }
    if ((command.getName().equals("shop") || command.getName().equals("ah")) && args.length>0 && args[0].equalsIgnoreCase("buy")) {
      ShopBuyDispatch.Caller who=commandCaller(sender);
      ShopBuyDispatch.complete(who, SpawnRoute.isSurvival(server), command.getName(), args, ShopBuyDispatch::stored, request -> {
        if(!ShopBuyDispatch.begin(request)) return;
        getServer().getScheduler().runTaskAsynchronously(this,()->{
          String response=null;
          try { response=tx(c->RconPurchase.buy(c,request,System.currentTimeMillis(),(item,amount)->{
            ItemStack stack=ItemStack.deserializeBytes(Base64.getDecoder().decode(item)); stack.setAmount(amount); return Base64.getEncoder().encodeToString(stack.serializeAsBytes());
          },getConfig().getInt("auction.fee-basis-points",500))); }
          catch(Exception ex){getLogger().warning("RCON Kauf: "+ex);}
          finally { ShopBuyDispatch.finish(request, response); }
        });
      }, sender::sendMessage);
      return true;
    }
    if(!(sender instanceof Player p)) { sender.sendMessage(PLAYER_ONLY_MESSAGE); return true; }
    try {
      if(args.length==0 && Set.of("pay","team","claim","home","trade","vip").contains(command.getName())) { if(moduleAvailable(userMenu,p::sendMessage)) userMenu.open(p,command.getName()); return true; }
      switch(command.getName()) {
        case "vip": if(moduleAvailable(userMenu,p::sendMessage)) userMenu.open(p,command.getName()); break;
        case "abholen": Deliveries.collect(this,p); break;
        case "feedback": p.sendMessage(Feedback.line(Feedback.url(getConfig()))); break;
        case "balance": if(args.length!=0) p.sendMessage("Fuer deinen Kontostand nutze /geld ohne weitere Eingaben."); else p.sendMessage("Kontostand: "+Money.format(balance(p.getUniqueId()))); break;
        case "pay": {
          if(args.length!=2){UserMenu.help(p,"pay"); break;}
          String targetId=tx(c->one(c,"SELECT uuid FROM known_players WHERE name=?",args[0])); if(targetId==null){p.sendMessage("Spieler ist im Netzwerk nicht bekannt."); break;}
          UUID recipient=UUID.fromString(targetId); Player other=Bukkit.getPlayer(recipient);
          long n=Money.cents(args[1]); if(transfer(p.getUniqueId(),recipient,n)){ p.sendMessage("Gesendet an "+args[0]+": "+Money.format(n)); if(other!=null) other.sendMessage(p.getName()+" sendet dir "+Money.format(n)); } else p.sendMessage("Ungültiger Betrag, eigenes Konto oder nicht genug CP."); break;
        }
        case "team": team(p,args); break;
        case "shop": if(moduleAvailable(Shop.instance,p::sendMessage)) Shop.instance.command(p,args); break;
        case "ah": if(moduleAvailable(Auction.instance,p::sendMessage)) Auction.instance.command(p,args); break;
        case "trade": if(moduleAvailable(trade,p::sendMessage)) trade.command(p,args); break;
        case "npc": if(moduleAvailable(npcs,p::sendMessage)) npcs.command(p,args); break;
        case "verkaufen": if(moduleAvailable(Market.instance,p::sendMessage)) Market.instance.sellCommand(p,args); break;
        case "bank": case "market": if(moduleAvailable(Market.instance,p::sendMessage)) Market.instance.command(p,command.getName(),args); break;
        case "claim": case "unclaim": case "spawn": case "home": case "sethome": if(moduleAvailable(Features.instance,p::sendMessage)) Features.instance.command(p,command.getName(),args); break;
      }
    } catch(NumberFormatException | ArithmeticException e) { p.sendMessage("Ungültiger Betrag (maximal zwei Nachkommastellen)."); }
    catch(Exception e) { getLogger().warning("CraftCore Fehler: "+e); p.sendMessage("Aktion fehlgeschlagen. Bitte später erneut versuchen."); }
    return true;
  }
  private void team(Player p,String[] a) throws Exception {
    if(a.length==0){UserMenu.help(p,"team");return;}
    String op=a[0].toLowerCase(Locale.ROOT); UUID id=p.getUniqueId();
    if(op.equals("invite") || op.equals("accept") || op.equals("decline") || op.equals("join")) { if(moduleAvailable(teamInvites,p::sendMessage)) teamInvites.command(p,op,a); return; }
    Player other=(a.length>1 && Set.of("kick","promote","demote").contains(op))?target(a[1]):null;
    if(Set.of("kick","promote","demote").contains(op) && other==null){p.sendMessage("Zielspieler muss auf diesem Server online sein.");return;}
    String result=tx(c->{
      String tid=one(c,"SELECT team_id FROM members WHERE uuid=?",id.toString()); String role=one(c,"SELECT role FROM members WHERE uuid=?",id.toString());
      switch(op) {
        case "create":
          if(a.length!=2 || !a[1].matches("[A-Za-z0-9_]{1,16}"))return "Name: 1–16 Buchstaben, Zahlen oder _.";
          if(tid!=null)return "Du bist bereits in einem Team.";
          if(one(c,"SELECT id FROM teams WHERE name=?",a[1])!=null)return "Teamname bereits vergeben.";
          exec(c,"INSERT INTO teams(name) VALUES (?)",a[1]); String created=one(c,"SELECT id FROM teams WHERE name=?",a[1]); exec(c,"INSERT INTO members VALUES (?,?,?)",id.toString(),created,"OWNER"); return "Team erstellt: "+a[1];
        case "leave":
          if(tid==null)return "Du bist in keinem Team."; if(role.equals("OWNER")){ exec(c,"UPDATE claims SET team_id=NULL WHERE team_id=?",tid); exec(c,"DELETE FROM teams WHERE id=?",tid); return "Team aufgelöst."; } exec(c,"DELETE FROM members WHERE uuid=?",id.toString()); return "Team verlassen.";
        case "kick": case "promote": case "demote":
          if(tid==null || role.equals("MEMBER"))return "Keine Berechtigung.";
          String oid=other.getUniqueId().toString(); String ot=one(c,"SELECT team_id FROM members WHERE uuid=?",oid); String orole=one(c,"SELECT role FROM members WHERE uuid=?",oid);
          if(!tid.equals(ot) || oid.equals(id.toString()) || "OWNER".equals(orole))return "Ungültiges Teammitglied.";
          if(op.equals("kick")){ if(role.equals("ADMIN") && !orole.equals("MEMBER"))return "Admins können nur Mitglieder entfernen."; exec(c,"DELETE FROM members WHERE uuid=?",oid); return "Mitglied entfernt."; }
          if(!role.equals("OWNER"))return "Nur der Owner darf Rollen ändern.";
          exec(c,"UPDATE members SET role=? WHERE uuid=?",op.equals("promote")?"ADMIN":"MEMBER",oid); return "Rolle geändert.";
        case "color":
          if(tid==null || !"OWNER".equals(role)) return "Nur der Owner darf die Teamfarbe ändern.";
          if(a.length!=2 || RankDisplay.teamColor(a[1]).isEmpty()) return "Ungültige Farbe. Verfügbar: "+String.join(", ",RankDisplay.TEAM_COLORS.keySet());
          exec(c,"UPDATE teams SET color=? WHERE id=?",RankDisplay.teamColor(a[1]).orElseThrow(),tid); return "Teamfarbe geändert.";
        case "info":
          if(tid==null)return "Du bist in keinem Team."; String name=one(c,"SELECT name FROM teams WHERE id=?",tid); String count=one(c,"SELECT COUNT(*) FROM members WHERE team_id=?",tid); return "Team: "+name+" | Rolle: "+role+" | Mitglieder: "+count;
        default: {String suggestion=UserMenu.nearest(op,List.of("create","invite","join","leave","kick","info","promote","demote")); return "[Craft] Unbekannter Team-Befehl."+(suggestion==null?"":" Meintest du /team "+suggestion+"?");}
      }
    }); p.sendMessage(result);
    if(Set.of("create","leave","kick","color").contains(op)) refreshRanks();
  }
}
