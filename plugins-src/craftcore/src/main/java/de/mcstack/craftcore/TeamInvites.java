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
  private final Map<UUID,Set<String>> shown=new HashMap<>();
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
  private void presence(Player p) throws Exception {
    core.tx(c->{core.exec(c,"INSERT INTO online_players(uuid,name,server,seen_at) VALUES (?,?,?,UNIX_TIMESTAMP()) ON DUPLICATE KEY UPDATE name=VALUES(name),server=VALUES(server),seen_at=VALUES(seen_at)",p.getUniqueId().toString(),p.getName(),server);return null;});
  }
  private void remove(Player p) throws Exception { core.tx(c->{core.exec(c,"DELETE FROM online_players WHERE uuid=? AND server=?",p.getUniqueId().toString(),server);return null;}); }
  @EventHandler public void join(PlayerJoinEvent e) { try { presence(e.getPlayer()); notifyInvites(e.getPlayer()); } catch(Exception ex){core.getLogger().warning("Team-Einladungen bei Join: "+ex.getMessage());} }
  @EventHandler public void quit(PlayerQuitEvent e) { shown.remove(e.getPlayer().getUniqueId()); try {remove(e.getPlayer());} catch(Exception ex){core.getLogger().warning("Online-Status: "+ex.getMessage());} }
  void shutdown() { for(Player p:Bukkit.getOnlinePlayers()) try {remove(p);} catch(Exception ex){core.getLogger().warning("Online-Status: "+ex.getMessage());} }
  private void poll() { for(Player p:Bukkit.getOnlinePlayers()) try { presence(p); notifyInvites(p); } catch(Exception ex){core.getLogger().warning("Team-Einladungen: "+ex.getMessage());} }
  private void notifyInvites(Player p) throws Exception {
    List<String[]> invites=core.tx(c->{List<String[]> rows=new ArrayList<>(); try(PreparedStatement q=c.prepareStatement("SELECT i.id,t.name FROM team_invites i JOIN teams t ON t.id=i.team_id WHERE i.uuid=?")){q.setString(1,p.getUniqueId().toString());try(ResultSet r=q.executeQuery()){while(r.next())rows.add(new String[]{r.getString(1),r.getString(2)});}}return rows;});
    Set<String> seen=shown.computeIfAbsent(p.getUniqueId(),x->new HashSet<>()); Set<String> pending=new HashSet<>();
    for(String[] row:invites) { pending.add(row[0]); if(seen.add(row[0])) p.sendMessage(Component.text("[Craft] Einladung zum Team "+row[1]+" ",NamedTextColor.GOLD).append(Component.text("[Annehmen]",NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/team accept "+row[0]))).append(Component.text(" ")).append(Component.text("[Ablehnen]",NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/team decline "+row[0])))); }
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
