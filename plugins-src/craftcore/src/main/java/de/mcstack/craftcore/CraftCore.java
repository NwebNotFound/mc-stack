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
  private Economy economy;
  private Trade trade;
  private UserMenu userMenu;
  private TeamInvites teamInvites;
  private Motivation motivation;
  private PlayerStats playerStats;
  final Set<org.bukkit.inventory.Inventory> npcPreviews = Collections.newSetFromMap(new WeakHashMap<>());
  private Npcs npcs;
  private final Map<UUID, Scoreboard> sidebars = new HashMap<>();
  interface Work<T> { T run(Connection c) throws Exception; }
  private Connection connection() throws SQLException { return DriverManager.getConnection("jdbc:mariadb://craftcore-db:3306/craftcore?connectTimeout=3000&socketTimeout=5000", "craftcore", password); }
  <T> T tx(Work<T> work) throws Exception {
    try (Connection c = connection()) {
      try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT GET_LOCK('craftcore-write', 5)")) { if (!r.next() || r.getInt(1)!=1) throw new SQLException("DB lock timeout"); }
      try { c.setAutoCommit(false); T result=work.run(c); c.commit(); return result; }
      catch (Exception e) { c.rollback(); throw e; }
      finally { try (Statement s=c.createStatement()) { s.execute("SELECT RELEASE_LOCK('craftcore-write')"); } }
    }
  }
  void exec(Connection c,String sql,Object... args) throws SQLException { try (PreparedStatement s=c.prepareStatement(sql)) { for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]); s.executeUpdate(); } }
  String one(Connection c,String sql,Object... args) throws SQLException { try (PreparedStatement s=c.prepareStatement(sql)) { for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]); try(ResultSet r=s.executeQuery()) { return r.next()?r.getString(1):null; } } }
  private void account(Connection c,UUID id) throws SQLException { exec(c,"INSERT IGNORE INTO accounts (uuid,balance) VALUES (?,0)",id.toString()); }
  long balance(Connection c,UUID id) throws SQLException { account(c,id); return Long.parseLong(one(c,"SELECT balance FROM accounts WHERE uuid=? FOR UPDATE",id.toString())); }
  private long balance(UUID id) throws Exception { return tx(c->balance(c,id)); }
  private boolean change(UUID id,long amount) throws Exception { return tx(c->EconomyStore.change(c,id.toString(),amount)); }
  private boolean transfer(UUID from,UUID to,long amount) throws Exception { return tx(c->EconomyStore.transfer(c,from.toString(),to.toString(),amount)); }
  @Override public void onEnable() {
    password=System.getenv("CRAFTCORE_DB_PASSWORD"); server=System.getenv().getOrDefault("CRAFTCORE_SERVER","Server");
    if(password==null || password.isBlank()) { getLogger().severe("CRAFTCORE_DB_PASSWORD fehlt"); getServer().getPluginManager().disablePlugin(this); return; }
    try {
      Class.forName("org.mariadb.jdbc.Driver");
      try(Connection c=connection(); Statement s=c.createStatement()) {
        s.execute("CREATE TABLE IF NOT EXISTS known_players (uuid CHAR(36) PRIMARY KEY, name VARCHAR(16) NOT NULL, UNIQUE KEY known_name (name))");
        s.execute("CREATE TABLE IF NOT EXISTS accounts (uuid CHAR(36) PRIMARY KEY, balance BIGINT NOT NULL DEFAULT 0)");
        s.execute("CREATE TABLE IF NOT EXISTS teams (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(16) NOT NULL UNIQUE)");
        s.execute("CREATE TABLE IF NOT EXISTS members (uuid CHAR(36) PRIMARY KEY, team_id BIGINT NOT NULL, role VARCHAR(8) NOT NULL, FOREIGN KEY(team_id) REFERENCES teams(id) ON DELETE CASCADE)");
        InviteStore.prepareSchema(c);
      }
      if (SpawnRoute.isSurvival(server) || server.equalsIgnoreCase("lobby")) { playerStats=new PlayerStats(this,server.toLowerCase(Locale.ROOT)); playerStats.start(); }
      teamInvites=new TeamInvites(this,server); teamInvites.start();
      saveDefaultConfig();
      for (OfflinePlayer known : Bukkit.getOfflinePlayers()) if (known.hasPlayedBefore() && known.getName()!=null) tx(c->{exec(c,"INSERT IGNORE INTO known_players(uuid,name) VALUES (?,?)",known.getUniqueId().toString(),known.getName()); return null;});
      new ServerStatus(this).start();
      new Features(this).start();
      if (SpawnRoute.isSurvival(server)) { getServer().getPluginManager().registerEvents(new SafeSpawn(this), this); getServer().getPluginManager().registerEvents(new EndLock(this), this); }
      userMenu=new UserMenu(this); userMenu.start();
      new TeamTeleport(this,server).start();
      new Challenges(this,server).start();
      motivation=new Motivation(this); motivation.start();
      InviteRewards.start(this,SpawnRoute.isSurvival(server));
      tx(c->{exec(c,"CREATE TABLE IF NOT EXISTS rcon_purchases (idem_key VARBINARY(64) PRIMARY KEY, kind VARCHAR(8) NOT NULL, buyer CHAR(36) NOT NULL, listing_id CHAR(36) NOT NULL, qty INT NOT NULL, max_price BIGINT NOT NULL, response VARCHAR(80) NOT NULL, created_at BIGINT NOT NULL)");exec(c,"CREATE OR REPLACE SQL SECURITY DEFINER VIEW v_rcon_purchase_result AS SELECT CAST(idem_key AS CHAR(64)) AS idem_key, response FROM rcon_purchases");TestRun.prepare(c);return null;});
      Deliveries.start(this);
      SaleNotices.start(this);
      WebsiteStats.start(this);
      new Market(this).start();
      new Shop(this).start();
      new Auction(this).start();
      Deliveries.migrate(this);
      npcs=new Npcs(this); npcs.start();
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
  @Override public void onDisable() { if(playerStats!=null)playerStats.shutdown(); if(motivation!=null)motivation.shutdown(); if(teamInvites!=null)teamInvites.shutdown(); if(trade!=null)trade.shutdown(); getServer().getServicesManager().unregisterAll(this); }
  private OfflinePlayer resolve(Object value) { if(value instanceof OfflinePlayer p)return p; if(value instanceof String s) return Bukkit.getOfflinePlayer(s); throw new IllegalArgumentException("Spieler fehlt"); }
  private Object vault(String method,Object[] args) throws Exception {
    if(args==null)args=new Object[0];
    switch(method) {
      case "isEnabled": return isEnabled(); case "getName": return "CraftCore"; case "hasBankSupport": return false;
      case "fractionalDigits": return 2; case "format": return Money.format(Money.cents((double)args[0]));
      case "currencyNamePlural": case "currencyNameSingular": return "CP";
      case "getBanks": return Collections.emptyList();
      case "createBank": case "deleteBank": case "bankBalance": case "bankHas": case "bankWithdraw": case "bankDeposit": case "isBankOwner": case "isBankMember": return new EconomyResponse(0,0,EconomyResponse.ResponseType.NOT_IMPLEMENTED,"Bankkonten nicht unterstÃƒÂ¼tzt");
      case "hasAccount": return true;
      case "createPlayerAccount": balance(resolve(args[0]).getUniqueId()); return true;
      case "getBalance": return balance(resolve(args[0]).getUniqueId())/100.0;
      case "has": { long n=Money.cents((double)args[args.length-1]); return n>=0 && balance(resolve(args[0]).getUniqueId())>=n; }
      case "withdrawPlayer": case "depositPlayer": {
        UUID id=resolve(args[0]).getUniqueId(); double amount=(double)args[args.length-1];
        try { long n=Money.cents(amount); if(n<0)throw new IllegalArgumentException(); boolean ok=change(id,method.equals("withdrawPlayer")?-n:n); return new EconomyResponse(ok?amount:0,balance(id)/100.0,ok?EconomyResponse.ResponseType.SUCCESS:EconomyResponse.ResponseType.FAILURE,ok?null:"Nicht genug CP"); }
        catch(IllegalArgumentException | ArithmeticException e) { return new EconomyResponse(0,0,EconomyResponse.ResponseType.FAILURE,"UngÃƒÂ¼ltiger Betrag"); }
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
    if(command.getName().equals("team") && args.length==1) return UserMenu.complete(args[0], "create", "invite", "join", "leave", "kick", "info", "promote", "demote");
    if(command.getName().equals("team") && args.length==2 && args[0].equalsIgnoreCase("invite")) return teamInvites.complete(args[1]);
    if(command.getName().equals("claim") && args.length==1) return UserMenu.complete(args[0], "create", "list");
    if(command.getName().equals("ah") && args.length==1) return UserMenu.complete(args[0], "my", "sell", "search");
    if(command.getName().equals("npc") && args.length==1) return UserMenu.complete(args[0], "set");
    if(command.getName().equals("npc") && args.length==2) return UserMenu.complete(args[1], "bank", "ah");
    if(command.getName().equals("trade") && args.length==1) { List<String> names=new ArrayList<>(List.of("accept")); Bukkit.getOnlinePlayers().forEach(p->names.add(p.getName())); return names.stream().filter(n->n.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).toList(); }
    if(command.getName().equals("home") && args.length==1 && sender instanceof Player p) try {return tx(c->{List<String> names=new ArrayList<>();try(PreparedStatement q=c.prepareStatement("SELECT name FROM homes WHERE server=? AND uuid=? ORDER BY name")){q.setString(1,server);q.setString(2,p.getUniqueId().toString());try(ResultSet r=q.executeQuery()){while(r.next())if(r.getString(1).startsWith(args[0].toLowerCase(Locale.ROOT)))names.add(r.getString(1));}}return names;});}catch(Exception ex){return List.of();}
    if(command.getName().equals("shop")) {
      if(args.length==1) try {return tx(c->{List<String> choices=new ArrayList<>(List.of("help","sell","show","remove"));try(PreparedStatement s=c.prepareStatement("SELECT DISTINCT seller_name FROM shop_journal WHERE status='OPEN' ORDER BY seller_name");ResultSet r=s.executeQuery()){while(r.next())choices.add(r.getString(1));}return choices.stream().filter(x->x.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).distinct().toList();});}catch(Exception ex){getLogger().warning("Shop-Vervollstaendigung: "+ex);return List.of();}
      return List.of();
    }
    if(command.getName().equals("pay") && args.length==1) try { return tx(c->{ List<String> names=new ArrayList<>(); try(PreparedStatement s=c.prepareStatement("SELECT name FROM known_players ORDER BY name")){try(ResultSet r=s.executeQuery()){while(r.next()){String name=r.getString(1); if(name.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))) names.add(name);}}} return names; }); } catch(Exception ex) { getLogger().warning("Pay-Vervollstaendigung: "+ex); return List.of(); }
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
    return board;
  }
  private void sidebar(Player p) {
    try {
      String team=tx(c->one(c,"SELECT t.name FROM teams t JOIN members m ON m.team_id=t.id WHERE m.uuid=?",p.getUniqueId().toString()));
      long cp=balance(p.getUniqueId());
      Scoreboard board=sidebars.computeIfAbsent(p.getUniqueId(),id->createSidebar(p));
      setLine(board,1,"Server: ",server,NamedTextColor.AQUA);
      setLine(board,2,"CP: ",String.format(Locale.GERMANY,"%,d CP",cp/100),NamedTextColor.GREEN);
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
  @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
    if (command.getName().equals("ah") && args.length>0 && args[0].equalsIgnoreCase("bid")) {
      RealAuctions.Request request=RealAuctions.parse(args);
      if(!SpawnRoute.isSurvival(server) || request==null || (sender instanceof Player p && (!p.getUniqueId().toString().equals(request.bidder()) || !request.source().equals("ingame")))) {sender.sendMessage("ERR INVALID");return true;}
      if(!(sender instanceof Player || sender instanceof org.bukkit.command.ConsoleCommandSender || sender instanceof org.bukkit.command.RemoteConsoleCommandSender)){sender.sendMessage("ERR INVALID");return true;}
      String response;try{response=tx(c->RealAuctions.bid(c,request,System.currentTimeMillis()));}catch(Exception ex){getLogger().warning("Auktionsgebot: "+ex.getClass().getSimpleName());response="ERR INVALID";}sender.sendMessage(response);return true;
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
    if(!(sender instanceof Player p)) { sender.sendMessage("Nur fÃƒÂ¼r Spieler."); return true; }
    try {
      if(args.length==0 && Set.of("pay","team","claim","home","trade","vip").contains(command.getName())) { userMenu.open(p,command.getName()); return true; }
      switch(command.getName()) {
        case "vip": userMenu.open(p,command.getName()); break;
        case "abholen": Deliveries.collect(this,p); break;
        case "balance": p.sendMessage("Kontostand: "+Money.format(balance(p.getUniqueId()))); break;
        case "pay": {
          if(args.length!=2){UserMenu.help(p,"pay"); break;}
          String targetId=tx(c->one(c,"SELECT uuid FROM known_players WHERE name=?",args[0])); if(targetId==null){p.sendMessage("Spieler ist im Netzwerk nicht bekannt."); break;}
          UUID recipient=UUID.fromString(targetId); Player other=Bukkit.getPlayer(recipient);
          long n=Money.cents(args[1]); if(transfer(p.getUniqueId(),recipient,n)){ p.sendMessage("Gesendet an "+args[0]+": "+Money.format(n)); if(other!=null) other.sendMessage(p.getName()+" sendet dir "+Money.format(n)); } else p.sendMessage("UngÃƒÂ¼ltiger Betrag, eigenes Konto oder nicht genug CP."); break;
        }
        case "team": team(p,args); break;
        case "shop": Shop.instance.command(p,args); break;
        case "ah": Auction.instance.command(p,args); break;
        case "trade": trade.command(p,args); break;
        case "npc": npcs.command(p,args); break;
        case "bank": case "market": Market.instance.command(p,command.getName(),args); break;
        case "claim": case "unclaim": case "spawn": case "home": case "sethome": Features.instance.command(p,command.getName(),args); break;
      }
    } catch(NumberFormatException | ArithmeticException e) { p.sendMessage("UngÃƒÂ¼ltiger Betrag (maximal zwei Nachkommastellen)."); }
    catch(Exception e) { getLogger().warning("CraftCore Fehler: "+e); p.sendMessage("Aktion fehlgeschlagen. Bitte spÃƒÂ¤ter erneut versuchen."); }
    return true;
  }
  private void team(Player p,String[] a) throws Exception {
    if(a.length==0){UserMenu.help(p,"team");return;}
    String op=a[0].toLowerCase(Locale.ROOT); UUID id=p.getUniqueId();
    if(op.equals("invite") || op.equals("accept") || op.equals("decline") || op.equals("join")) { teamInvites.command(p,op,a); return; }
    Player other=(a.length>1 && Set.of("kick","promote","demote").contains(op))?target(a[1]):null;
    if(Set.of("kick","promote","demote").contains(op) && other==null){p.sendMessage("Zielspieler muss auf diesem Server online sein.");return;}
    String result=tx(c->{
      String tid=one(c,"SELECT team_id FROM members WHERE uuid=?",id.toString()); String role=one(c,"SELECT role FROM members WHERE uuid=?",id.toString());
      switch(op) {
        case "create":
          if(a.length!=2 || !a[1].matches("[A-Za-z0-9_]{1,16}"))return "Name: 1Ã¢â‚¬â€œ16 Buchstaben, Zahlen oder _.";
          if(tid!=null)return "Du bist bereits in einem Team.";
          if(one(c,"SELECT id FROM teams WHERE name=?",a[1])!=null)return "Teamname bereits vergeben.";
          exec(c,"INSERT INTO teams(name) VALUES (?)",a[1]); String created=one(c,"SELECT id FROM teams WHERE name=?",a[1]); exec(c,"INSERT INTO members VALUES (?,?,?)",id.toString(),created,"OWNER"); return "Team erstellt: "+a[1];
        case "leave":
          if(tid==null)return "Du bist in keinem Team."; if(role.equals("OWNER")){ exec(c,"UPDATE claims SET team_id=NULL WHERE team_id=?",tid); exec(c,"DELETE FROM teams WHERE id=?",tid); return "Team aufgelÃƒÂ¶st."; } exec(c,"DELETE FROM members WHERE uuid=?",id.toString()); return "Team verlassen.";
        case "kick": case "promote": case "demote":
          if(tid==null || role.equals("MEMBER"))return "Keine Berechtigung.";
          String oid=other.getUniqueId().toString(); String ot=one(c,"SELECT team_id FROM members WHERE uuid=?",oid); String orole=one(c,"SELECT role FROM members WHERE uuid=?",oid);
          if(!tid.equals(ot) || oid.equals(id.toString()) || "OWNER".equals(orole))return "UngÃƒÂ¼ltiges Teammitglied.";
          if(op.equals("kick")){ if(role.equals("ADMIN") && !orole.equals("MEMBER"))return "Admins kÃƒÂ¶nnen nur Mitglieder entfernen."; exec(c,"DELETE FROM members WHERE uuid=?",oid); return "Mitglied entfernt."; }
          if(!role.equals("OWNER"))return "Nur der Owner darf Rollen ÃƒÂ¤ndern.";
          exec(c,"UPDATE members SET role=? WHERE uuid=?",op.equals("promote")?"ADMIN":"MEMBER",oid); return "Rolle geÃƒÂ¤ndert.";
        case "info":
          if(tid==null)return "Du bist in keinem Team."; String name=one(c,"SELECT name FROM teams WHERE id=?",tid); String count=one(c,"SELECT COUNT(*) FROM members WHERE team_id=?",tid); return "Team: "+name+" | Rolle: "+role+" | Mitglieder: "+count;
        default: {String suggestion=UserMenu.nearest(op,List.of("create","invite","join","leave","kick","info","promote","demote")); return "[Craft] Unbekannter Team-Befehl."+(suggestion==null?"":" Meintest du /team "+suggestion+"?");}
      }
    }); p.sendMessage(result);
  }
}
