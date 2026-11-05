package de.mcstack.status;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import java.sql.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class StatusPlugin {
  private final ProxyServer proxy;
  private final Logger logger;
  private volatile String mode="wartung";
  @Inject public StatusPlugin(ProxyServer proxy, Logger logger) { this.proxy=proxy; this.logger=logger; }
  @Subscribe public void initialize(ProxyInitializeEvent event) {
    proxy.getScheduler().buildTask(this,this::refresh).repeat(5,TimeUnit.SECONDS).schedule();
    proxy.getScheduler().buildTask(this,this::switches).repeat(500,TimeUnit.MILLISECONDS).schedule();
  }
  private Connection db() throws SQLException {
    return DriverManager.getConnection("jdbc:mariadb://craftcore-db:3306/craftcore?connectTimeout=3000&socketTimeout=5000","craftcore",System.getenv("CRAFTCORE_DB_PASSWORD"));
  }
  private final Map<UUID, Long> sessions = new ConcurrentHashMap<>();
  @Subscribe public void login(LoginEvent event) { sessions.put(event.getPlayer().getUniqueId(), System.currentTimeMillis()); }
  @Subscribe public void disconnect(DisconnectEvent event) { sessions.remove(event.getPlayer().getUniqueId()); }
  private volatile boolean switchWarned;
  /** Fallback for backend switches whose plugin message never reached the proxy (see SwitchDecision). */
  private synchronized void switches() {
    long now=System.currentTimeMillis();
    try(Connection c=db()) {
      c.setAutoCommit(false);
      try(PreparedStatement x=c.prepareStatement("UPDATE proxy_switch_requests SET done_at=?,result='expired' WHERE done_at IS NULL AND created_at<=?")) { x.setLong(1,now); x.setLong(2,now-SwitchDecision.MAX_AGE_MS); x.executeUpdate(); }
      try(PreparedStatement q=c.prepareStatement("SELECT id,player,server,created_at,source FROM proxy_switch_requests WHERE done_at IS NULL AND created_at<=? ORDER BY id LIMIT 50 FOR UPDATE")) {
        q.setLong(1,now-SwitchDecision.GRACE_MS);
        try(ResultSet r=q.executeQuery(); PreparedStatement done=c.prepareStatement("UPDATE proxy_switch_requests SET done_at=?,result=? WHERE id=? AND done_at IS NULL")) {
          while(r.next()) {
            long id=r.getLong(1); String target=r.getString(3);
            var player=proxy.getPlayer(java.util.UUID.fromString(r.getString(2)));
            var server=proxy.getServer(target);
            String current=player.flatMap(p->p.getCurrentServer()).map(s->s.getServerInfo().getName()).orElse(null);
            SwitchDecision.Action action=SwitchDecision.decide(r.getLong(4),now,player.isPresent(),current,target,server.isPresent());
            if (!SwitchDecision.belongsToSession(r.getLong(4), sessions.get(UUID.fromString(r.getString(2))), r.getString(5), current)) action=SwitchDecision.Action.EXPIRED;
            if(action==SwitchDecision.Action.WAIT) continue;
            if(action==SwitchDecision.Action.CONNECT) {
              player.get().createConnectionRequest(server.get()).fireAndForget();
              logger.info("Wechsel per Queue: {} -> {}",player.get().getUsername(),target);
            }
            done.setLong(1,now); done.setString(2,action.name().toLowerCase()); done.setLong(3,id); done.executeUpdate();
          }
        }
      }
      c.commit();
      switchWarned=false;
    } catch(Exception e) { if(!switchWarned) { logger.warn("Wechsel-Queue nicht lesbar: {}",e.getMessage()); switchWarned=true; } }
  }
  private void refresh() {
    try {
      Class.forName("org.mariadb.jdbc.Driver");
      try(Connection c=db();
          Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT mode FROM server_status WHERE id=1")) {
        if(!r.next()) throw new SQLException("Status fehlt");
        String value=r.getString(1); if(!List.of("dev","online","wartung").contains(value)) throw new SQLException("Status ungueltig"); mode=value;
      }
    } catch(Exception e) { logger.warn("Servermodus nicht lesbar: {}",e.getMessage()); }
  }
  @Subscribe public void ping(ProxyPingEvent event) {
    event.setPing(event.getPing().asBuilder().description(Component.text("CRAFT | Servermodus: "+mode,NamedTextColor.GOLD)).build());
  }
}
