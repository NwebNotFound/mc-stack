package de.mcstack.craftcore;

import java.util.*;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

final class LuckPermsRanks {
  private final CraftCore core; private final RankDisplays displays; private final LuckPerms api;
  LuckPermsRanks(CraftCore core,RankDisplays displays) { this.core=core; this.displays=displays; this.api=LuckPermsProvider.get(); }
  void start() {
    api.getEventBus().subscribe(core,UserDataRecalculateEvent.class,e->update(e.getUser()));
    Bukkit.getOnlinePlayers().forEach(p->{ User user=api.getUserManager().getUser(p.getUniqueId()); if(user!=null) update(user); });
  }
  void joined(UUID id) { User user=api.getUserManager().getUser(id); if(user!=null) update(user); }
  private void update(User user) { UUID id=user.getUniqueId(); List<String> groups=user.getInheritedGroups(QueryOptions.nonContextual()).stream().map(g->g.getName()).toList(); Bukkit.getScheduler().runTask(core,()->displays.rank(id,groups)); }
  boolean command(CommandSender sender,String[] args) {
    if(args.length!=3 || !args[0].equalsIgnoreCase("set")) { sender.sendMessage("Benutzung: /rank set <spieler> <rang>"); return true; }
    String group=args[2].toLowerCase(Locale.ROOT);
    if(RankDisplay.RANKS.stream().noneMatch(r->r.name().equals(group))) { sender.sendMessage("Unbekannter Rang."); return true; }
    String actor=sender.getName(), target=args[1]; boolean console=sender instanceof org.bukkit.command.ConsoleCommandSender;
    api.getUserManager().lookupUniqueId(target).thenCompose(id -> id==null ? java.util.concurrent.CompletableFuture.completedFuture(null) : api.getUserManager().loadUser(id).thenCompose(loaded -> { List<String> current=loaded.getNodes(NodeType.INHERITANCE).stream().map(n->n.getGroupName()).toList(); if(!RankDisplay.rankChangeAllowed(console,group,current)) throw new SecurityException("admin"); return api.getUserManager().modifyUser(id,user->{ user.data().clear(NodeType.INHERITANCE::matches); user.data().add(InheritanceNode.builder(group).build()); }); }).thenApply(v->id)).whenComplete((id,error)->Bukkit.getScheduler().runTask(core,()->{ if(error!=null && (error instanceof SecurityException || error.getCause() instanceof SecurityException)) { sender.sendMessage("Admin-Raenge koennen nur ueber die Konsole vergeben oder geaendert werden."); return; } if(error!=null || id==null) { sender.sendMessage("Rang konnte nicht gesetzt werden oder Spieler unbekannt."); return; } core.getLogger().info(actor+" setzte Rang von "+target+" ("+id+") auf "+group); sender.sendMessage("Rang von "+target+" auf "+group+" gesetzt."); }));
    return true;
  }
}
