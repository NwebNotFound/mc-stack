package de.mcstack.craftcore;

import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Sale records are written in the purchase transaction and retained after delivery. */
final class SaleNotices {
  static void start(CraftCore core) throws Exception {
    core.tx(c->{try(Statement s=c.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS sale_notices (id CHAR(36) PRIMARY KEY, seller CHAR(36) NOT NULL, item VARCHAR(64) NOT NULL, amount INT NOT NULL, cp BIGINT NOT NULL, sold_at BIGINT NOT NULL, reported BOOLEAN NOT NULL DEFAULT FALSE, INDEX sale_pending(seller,reported)) ENGINE=InnoDB");}return null;});
    Bukkit.getScheduler().runTaskTimer(core,()->{for(Player p:Bukkit.getOnlinePlayers()) notify(core,p,false);},20L,20L);
  }
  static void notify(CraftCore core,Player player,boolean joined) {
    if(!player.isOnline())return;
    try{core.tx(c->{SaleNoticeStore.deliver(c,player.getUniqueId().toString(),s->player.sendMessage((joined?"Waehrend du weg warst: ":"Dein Verkauf: ")+s.amount()+"x "+s.item().replace('_',' ')+" verkauft, +"+Money.format(s.cents())));return null;});}
    catch(Exception ex){core.getLogger().warning("Verkaufsmeldungen: "+ex);}
  }
  static void afterPurchase(CraftCore core,String purchaseId) {
    try{String seller=core.tx(c->core.one(c,"SELECT seller FROM sale_notices WHERE id=?",purchaseId));if(seller!=null){Player p=Bukkit.getPlayer(UUID.fromString(seller));if(p!=null)notify(core,p,false);}}
    catch(Exception ex){core.getLogger().warning("Verkaufsmeldung: "+ex);}
  }
}
