package de.mcstack.craftcore;

import java.sql.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;

final class TeamInvites implements Listener {
  private final CraftCore core;
  private final String server;
  /** Main thread only: invites already announced per player. */
  private final Map<UUID,Set<String>> shown=new HashMap<>();
  /** Set while a poll waits for the database, so a slow database never stacks up polls. */
  private final java.util.concurrent.atomic.AtomicBoolean polling=new java.util.concurrent.atomic.AtomicBoolean();
  private record Online(UUID id,String name) {}
  private record Invite(UUID player,String id,String team) {}
  static final String PRESENCE="INSERT INTO online_players(uuid,name,server,seen_at) VALUES (?,?,?,UNIX_TIMESTAMP()) ON DUPLICATE KEY UPDATE name=VALUES(name),server=VALUES(server),seen_at=VALUES(seen_at)";
  TeamInvites(CraftCore core,String server) { this.core=core; this.server=server; }
  void start() throws Exception {
    core.tx(c->{
      try(Statement s=c.createStatement()) {
        s.execute("CREATE TABLE IF NOT EXISTS team_invites (id CHAR(36) PRIMARY KEY, uuid CHAR(36) NOT NULL, team_id BIGINT NOT NULL, UNIQUE KEY invite_target (uuid,team_id), FOREIGN KEY(team_id) REFERENCES teams(id) ON DELETE CASCADE)");
        s.execute("CREATE TABLE IF NOT EXISTS online_players (uuid CHAR(36) PRIMARY KEY, name VARCHAR(16) NOT NULL, server VARCHAR(64) NOT NULL, seen_at BIGINT NOT NULL, KEY online_seen (seen_at))");
      }
      return null;
    });
    Bukkit.getPluginManager().registerEvents(this,core);
    Bukkit.getScheduler().runTaskTimer(core,this::poll,1L,40L);
  }
  private void remove(Player p) throws Exception { core.tx(c->{core.exec(c,"DELETE FROM online_players WHERE uuid=? AND server=?",p.getUniqueId().toString(),server);return null;}); }
  // Presence and invite reads run off the server thread on one plain connection: a slow or unreachable database must
  // not stall the tick, and none of this needs the global craftcore-write lock (#153).
  @EventHandler public void join(PlayerJoinEvent e) { Player p=e.getPlayer(); refreshAsync(List.of(new Online(p.getUniqueId(),p.getName())),"Team-Einladungen bei Join",null); }
  @EventHandler public void quit(PlayerQuitEvent e) {
    UUID id=e.getPlayer().getUniqueId(); shown.remove(id);
    Bukkit.getScheduler().runTaskAsynchronously(core,()->{
      try(Connection c=core.connection()) { core.exec(c,"DELETE FROM online_players WHERE uuid=? AND server=?",id.toString(),server); }
      catch(Exception ex){core.getLogger().warning("Online-Status: "+ex.getMessage());}
    });
  }
  void shutdown() { for(Player p:Bukkit.getOnlinePlayers()) try {remove(p);} catch(Exception ex){core.getLogger().warning("Online-Status: "+ex.getMessage());} }
  private void poll() {
    if(!polling.compareAndSet(false,true)) return;
    List<Online> online=new ArrayList<>(); for(Player p:Bukkit.getOnlinePlayers()) online.add(new Online(p.getUniqueId(),p.getName()));
    if(online.isEmpty()) { polling.set(false); return; }
    refreshAsync(online,"Team-Einladungen",polling);
  }
  /** Upserts the presence of these players and loads their invites in one round trip, then announces on the main thread. */
  private void refreshAsync(List<Online> online,String what,java.util.concurrent.atomic.AtomicBoolean busy) {
    try {
      Bukkit.getScheduler().runTaskAsynchronously(core,()->{
        List<Invite> invites=null;
        try { invites=refresh(online); }
        catch(Exception ex){core.getLogger().warning(what+": "+ex.getMessage());}
        finally { if(busy!=null) busy.set(false); }
        List<Invite> found=invites;
        if(found!=null) Bukkit.getScheduler().runTask(core,()->show(online,found));
      });
    } catch(RuntimeException ex) { if(busy!=null) busy.set(false); throw ex; }
  }
  private List<Invite> refresh(List<Online> online) throws SQLException {
    try(Connection c=core.connection()) {
      try(PreparedStatement q=c.prepareStatement(PRESENCE)) {
        for(Online o:online) { q.setString(1,o.id().toString()); q.setString(2,o.name()); q.setString(3,server); q.addBatch(); }
        q.executeBatch();
      }
      List<Invite> out=new ArrayList<>();
      String marks=String.join(",",Collections.nCopies(online.size(),"?"));
      try(PreparedStatement q=c.prepareStatement("SELECT i.uuid,i.id,t.name FROM team_invites i JOIN teams t ON t.id=i.team_id WHERE i.uuid IN ("+marks+")")) {
        int i=1; for(Online o:online) q.setString(i++,o.id().toString());
        try(ResultSet r=q.executeQuery()) { while(r.next()) out.add(new Invite(UUID.fromString(r.getString(1)),r.getString(2),r.getString(3))); }
      }
      return out;
    }
  }
  private void show(List<Online> polled,List<Invite> invites) {
    Map<UUID,List<Invite>> byPlayer=new HashMap<>(); for(Invite i:invites) byPlayer.computeIfAbsent(i.player(),k->new ArrayList<>()).add(i);
    for(Online o:polled) { Player p=Bukkit.getPlayer(o.id()); if(p!=null) notifyInvites(p,byPlayer.getOrDefault(o.id(),List.of())); }
  }
  private void notifyInvites(Player p,List<Invite> invites) {
    Set<String> seen=shown.computeIfAbsent(p.getUniqueId(),x->new HashSet<>()); Set<String> pending=new HashSet<>();
    for(Invite row:invites) { pending.add(row.id()); if(seen.add(row.id())) p.sendMessage(Component.text("[Craft] Einladung zum Team "+row.team()+" ",NamedTextColor.GOLD).append(Component.text("[Annehmen]",NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/team accept "+row.id()))).append(Component.text(" ")).append(Component.text("[Ablehnen]",NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/team decline "+row.id())))); }
    seen.retainAll(pending);
  }
  List<String> complete(String prefix) {
    try { return core.tx(c->{List<String> names=new ArrayList<>();try(PreparedStatement q=c.prepareStatement("SELECT name FROM online_players WHERE seen_at >= UNIX_TIMESTAMP()-15 ORDER BY name");ResultSet r=q.executeQuery()){while(r.next()){String n=r.getString(1);if(n.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) names.add(n);}}return names;}); }
    catch(Exception ex){core.getLogger().warning("Team-Vervollstaendigung: "+ex.getMessage());return List.of();}
  }
  void command(Player p,String op,String[] args) throws Exception {
    if(args.length!=2){p.sendMessage("[Craft] Benutzung: /team "+op+" <"+(op.equals("invite")?"Spieler":op.equals("join")?"Team":"Einladung")+">");return;}
    String result=core.tx(c->TeamInviteStore.apply(c,p.getUniqueId().toString(),op,args[1])); p.sendMessage("[Craft] "+result); if(op.equals("join") || op.equals("accept")) core.refreshRanks();
  }
}
