package de.mcstack.craftcore;

import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;

final class ServerStatus implements Listener, CommandExecutor, TabCompleter {
  private final CraftCore plugin;
  private volatile String mode = "wartung";
  ServerStatus(CraftCore plugin) { this.plugin=plugin; }
  private String read() throws Exception {
    String value=plugin.tx(c->plugin.one(c,"SELECT mode FROM server_status WHERE id=1"));
    if(!List.of("dev","online","wartung").contains(value)) throw new IllegalStateException("Ungueltiger Servermodus");
    return value;
  }
  void start() throws Exception {
    mode=read();
    plugin.getServer().getPluginManager().registerEvents(this,plugin);
    var command=plugin.getCommand("servermode"); command.setExecutor(this); command.setTabCompleter(this);
    Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,()->{
      try { String current=read(); mode=current; Bukkit.getScheduler().runTask(plugin,()->Bukkit.getOnlinePlayers().forEach(this::header)); }
      catch(Exception ex) { plugin.getLogger().warning("Servermodus nicht lesbar: "+ex.getMessage()); }
    },20L,100L);
  }
  private void header(Player player) { player.sendPlayerListHeader(Component.text("CRAFT | Modus: "+mode,NamedTextColor.GOLD)); }
  @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
  public void login(PlayerLoginEvent event) {
    try {
      String current=read(); mode=current;
      Player p=event.getPlayer();
      if(current.equals("wartung") && !p.isOp() && !p.hasPermission("group.admin"))
        event.disallow(PlayerLoginEvent.Result.KICK_OTHER,Component.text("Der Server wird gerade gewartet. Bitte versuche es spaeter erneut!",NamedTextColor.YELLOW));
    } catch(Exception ex) { event.disallow(PlayerLoginEvent.Result.KICK_OTHER,Component.text("Der Serverstatus ist gerade nicht verfuegbar. Bitte versuche es spaeter erneut!",NamedTextColor.YELLOW)); }
  }
  @EventHandler public void join(PlayerJoinEvent event) {
    Player p=event.getPlayer(); header(p);
    p.sendMessage(Component.text("Willkommen! Servermodus: "+mode,NamedTextColor.GOLD));
  }
  public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
    if(!(sender instanceof Player p) || !p.isOp()) { sender.sendMessage("Nur OPs koennen den Servermodus aendern."); return true; }
    if(args.length!=1 || !List.of("dev","online","wartung").contains(args[0].toLowerCase(Locale.ROOT))) { sender.sendMessage("Benutzung: /servermode <dev|online|wartung>"); return true; }
    String requested=args[0].toLowerCase(Locale.ROOT);
    try { plugin.tx(c->{plugin.exec(c,"UPDATE server_status SET mode=? WHERE id=1",requested);return null;}); mode=requested; Bukkit.getOnlinePlayers().forEach(this::header); sender.sendMessage("Servermodus gesetzt: "+requested); }
    catch(Exception ex) { sender.sendMessage("Servermodus konnte nicht gespeichert werden."); plugin.getLogger().warning("Servermodus: "+ex.getMessage()); }
    return true;
  }
  public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) { return sender instanceof Player p && p.isOp() && args.length==1 ? List.of("dev","online","wartung").stream().filter(v->v.startsWith(args[0].toLowerCase(Locale.ROOT))).toList() : List.of(); }
}
