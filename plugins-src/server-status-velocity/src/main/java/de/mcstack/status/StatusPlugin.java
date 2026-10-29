package de.mcstack.status;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
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
  }
  private void refresh() {
    try {
      Class.forName("org.mariadb.jdbc.Driver");
      try(Connection c=DriverManager.getConnection("jdbc:mariadb://craftcore-db:3306/craftcore?connectTimeout=3000&socketTimeout=5000","craftcore",System.getenv("CRAFTCORE_DB_PASSWORD"));
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
