package de.mcstack.status;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.event.connection.DisconnectEvent;
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
  @FunctionalInterface interface ConnectionSource { Connection open() throws SQLException; }
  private final ConnectionSource database;
  @Inject public StatusPlugin(ProxyServer proxy, Logger logger) {
    this(proxy, logger, () -> DriverManager.getConnection("jdbc:mariadb://craftcore-db:3306/craftcore?connectTimeout=3000&socketTimeout=5000", "craftcore", System.getenv("CRAFTCORE_DB_PASSWORD")));
  }
  StatusPlugin(ProxyServer proxy, Logger logger, ConnectionSource database) { this.proxy=proxy; this.logger=logger; this.database=database; }
  @Subscribe public void initialize(ProxyInitializeEvent event) {
    proxy.getScheduler().buildTask(this,this::refresh).repeat(5,TimeUnit.SECONDS).schedule();
    proxy.getChannelRegistrar().register(SWITCH_CHANNEL);
  }
  static final MinecraftChannelIdentifier SWITCH_CHANNEL = MinecraftChannelIdentifier.from("craftcore:switch");
  private final SwitchRouter switches = new SwitchRouter();
  @Subscribe public void disconnect(DisconnectEvent event) { switches.forget(event.getPlayer().getUniqueId(), event.getPlayer()); }

  @Subscribe public void switchMessage(PluginMessageEvent event) {
    if (!event.getIdentifier().equals(SWITCH_CHANNEL)) return;
    // Never forward this channel to clients or other backends; client-origin messages cannot switch anyone.
    event.setResult(PluginMessageEvent.ForwardResult.handled());
    if (!(event.getSource() instanceof ServerConnection backend)) return;
    var player = backend.getPlayer();
    if (event.getTarget() != player) return;
    try {
      SwitchRequest request = SwitchRequest.decode(event.getData());
      var destination = proxy.getServer(request.target());
      switches.dispatch(player.getUniqueId(), player, backend, request.revision(),
          () -> player.isActive() && player.getCurrentServer().orElse(null) == backend,
          () -> {
            if (destination.isEmpty()) { logger.warn("Unbekanntes Proxy-Ziel: {}", request.target()); return; }
            player.createConnectionRequest(destination.get()).connect().thenAccept(result -> {
              if (!result.isSuccessful()) logger.warn("Proxy-Wechsel nach {} fehlgeschlagen: {}", request.target(), result.getStatus());
            });
          });
    } catch (java.io.IOException | IllegalArgumentException e) {
      logger.warn("Ungueltige CraftCore-Wechselnachricht");
    }
  }
  void refresh() {
    try {
      Class.forName("org.mariadb.jdbc.Driver");
      try(Connection c=database.open();
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
