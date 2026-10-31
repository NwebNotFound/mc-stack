package de.mcstack.craftcore;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

public final class RankDisplays implements Listener {
  private final CraftCore core;
  private final boolean survival;
  private final Map<UUID,RankDisplay.Rank> ranks = new ConcurrentHashMap<>();
  private final Map<UUID,NamedTextColor> nameColors = new ConcurrentHashMap<>();
  private LuckPermsRanks luckPerms;
  RankDisplays(CraftCore core, String server) { this.core=core; this.survival=SpawnRoute.isSurvival(server); }
  void start() {
    Bukkit.getPluginManager().registerEvents(this,core);
    if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) { luckPerms=new LuckPermsRanks(core,this); luckPerms.start(); }
    Bukkit.getOnlinePlayers().forEach(this::refresh);
  }
  boolean command(org.bukkit.command.CommandSender sender,String[] args) {
    if (!sender.hasPermission("craftcore.rank.set")) { sender.sendMessage("Keine Berechtigung."); return true; }
    if (luckPerms==null) { sender.sendMessage("LuckPerms ist nicht aktiv."); return true; }
    return luckPerms.command(sender,args);
  }
  void rank(UUID id, Collection<String> groups) { ranks.put(id,RankDisplay.pick(groups)); Player p=Bukkit.getPlayer(id); if(p!=null) refresh(p); }
  private RankDisplay.Rank rank(Player p) { return ranks.getOrDefault(p.getUniqueId(), RankDisplay.pick(List.of())); }
  private NamedTextColor color(String code) { ChatColor c=ChatColor.getByChar(RankDisplay.parseColor(code).charAt(1)); return NamedTextColor.nearestTo(net.kyori.adventure.text.format.TextColor.color(c.asBungee().getColor().getRGB() & 0xffffff)); }
  private record Appearance(String team, NamedTextColor color, Component prefix) {}
  private Appearance appearance(Player p) {
    RankDisplay.Rank rank=rank(p);
    if (!survival) { NamedTextColor c=color(rank.color()); return new Appearance(RankDisplay.teamName(rank),c,Component.text("["+rank.label()+"] ",c)); }
    try { String[] team=core.tx(c -> { try(var q=c.prepareStatement("SELECT t.id,t.color FROM teams t JOIN members m ON m.team_id=t.id WHERE m.uuid=?")) { q.setString(1,p.getUniqueId().toString()); try(var r=q.executeQuery()) { return r.next()?new String[]{r.getString(1),r.getString(2)}:null; } } });
      if(team!=null) return new Appearance(RankDisplay.teamName(Long.parseLong(team[0])),color(team[1]),Component.empty());
    } catch(Exception ex) { core.getLogger().warning("Teamfarbe: "+ex.getClass().getSimpleName()); }
    return new Appearance("t0",NamedTextColor.WHITE,Component.empty());
  }
  private void apply(Scoreboard board,Player p,Appearance a) {
    Team team=board.getTeam(a.team()); if(team==null) team=board.registerNewTeam(a.team());
    team.prefix(a.prefix()); team.color(a.color()); team.addEntry(p.getName());
  }
  public void refresh(Player p) {
    Appearance a=appearance(p);
    nameColors.put(p.getUniqueId(),a.color());
    Component name=a.prefix().append(Component.text(p.getName(),a.color()));
    if(p.getMetadata("afk").stream().anyMatch(v->v.asBoolean())) name=name.append(Component.text(" [AFK]",NamedTextColor.GRAY));
    p.playerListName(name);
    for(Player viewer:Bukkit.getOnlinePlayers()) apply(viewer.getScoreboard(),p,a);
  }
  void boardCreated(Scoreboard board) { for(Player p:Bukkit.getOnlinePlayers()) apply(board,p,appearance(p)); }
  void refreshAll() { Bukkit.getOnlinePlayers().forEach(this::refresh); }
  @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent e) { if(luckPerms!=null) luckPerms.joined(e.getPlayer().getUniqueId()); Bukkit.getScheduler().runTask(core,()->{ if(e.getPlayer().isOnline()) { boardCreated(e.getPlayer().getScoreboard()); refresh(e.getPlayer()); } }); }
  @EventHandler public void quit(PlayerQuitEvent e) { ranks.remove(e.getPlayer().getUniqueId()); nameColors.remove(e.getPlayer().getUniqueId()); for(Player viewer:Bukkit.getOnlinePlayers()) { Team team=viewer.getScoreboard().getEntryTeam(e.getPlayer().getName()); if(team!=null) team.removeEntry(e.getPlayer().getName()); } }
  @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void chat(AsyncChatEvent e) { RankDisplay.Rank r=rank(e.getPlayer()); Component prefix=survival ? Component.text("[",NamedTextColor.DARK_GRAY).append(Component.text(r.label(),NamedTextColor.GRAY)).append(Component.text("] ",NamedTextColor.DARK_GRAY)) : Component.text("["+r.label()+"] ",color(r.color())); var previous=e.renderer(); e.renderer((source,name,message,viewer)->prefix.append(previous.render(source,survival ? name.color(nameColors.getOrDefault(source.getUniqueId(),NamedTextColor.WHITE)) : name,message,viewer))); }
}
