package de.mcstack.craftcore.vip;

import java.sql.*;
import java.util.*;

public final class VipStore {
    @FunctionalInterface public interface ConnectionSource { Connection open() throws SQLException; }
    public record World(String id, String name, String status, String slot) {}
    private final ConnectionSource source;
    public VipStore(ConnectionSource source) { this.source=source; }
    public int count(UUID owner) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement("SELECT COUNT(*) FROM vip_worlds WHERE owner=? AND status<>'deleted'")) {
            s.setString(1,owner.toString()); try (ResultSet r=s.executeQuery()) { r.next(); return r.getInt(1); }
        }
    }
    public void create(UUID owner, String name, String type) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement("INSERT INTO vip_worlds (id,owner,name,type,status) VALUES (?,?,?,?,'stopped')")) {
            s.setString(1,UUID.randomUUID().toString()); s.setString(2,owner.toString()); s.setString(3,name); s.setString(4,type); s.executeUpdate();
        }
    }
    public List<World> list(UUID owner) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement("SELECT id,name,status,slot FROM vip_worlds WHERE owner=? AND status<>'deleted' ORDER BY name")) {
            s.setString(1,owner.toString()); try (ResultSet r=s.executeQuery()) { List<World> worlds=new ArrayList<>(); while(r.next()) worlds.add(new World(r.getString(1),r.getString(2),r.getString(3),r.getString(4))); return worlds; }
        }
    }
    public Optional<World> find(UUID owner, String name) throws SQLException { return list(owner).stream().filter(w->w.name().equalsIgnoreCase(name)).findFirst(); }
    public void rename(UUID owner, String id, String name) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement("UPDATE vip_worlds SET name=? WHERE id=? AND owner=? AND status<>'deleted'")) { s.setString(1,name); s.setString(2,id); s.setString(3,owner.toString()); s.executeUpdate(); }
    }
    public void status(String id, String status, String slot) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement("UPDATE vip_worlds SET status=?,slot=? WHERE id=?")) { s.setString(1,status); s.setString(2,slot); s.setString(3,id); s.executeUpdate(); }
    }
    public void running(String id, String slot) throws SQLException {
        try (Connection c=source.open()) { c.setAutoCommit(false); try {
            try (PreparedStatement s=c.prepareStatement("UPDATE vip_worlds SET status='running',last_started_at=CURRENT_TIMESTAMP WHERE id=? AND status='starting' AND slot=?")) { s.setString(1,id); s.setString(2,slot); if(s.executeUpdate()!=1) throw new SQLException("Weltstatus geaendert"); }
            try (PreparedStatement s=c.prepareStatement("INSERT INTO vip_world_runs (world_id,slot,started_at) VALUES (?,?,CURRENT_TIMESTAMP)")) { s.setString(1,id); s.setString(2,slot); s.executeUpdate(); }
            c.commit();
        } catch(SQLException e) { c.rollback(); throw e; } }
    }
    public void stopped(String id) throws SQLException {
        try (Connection c=source.open()) { c.setAutoCommit(false); try {
            try (PreparedStatement s=c.prepareStatement("UPDATE vip_worlds SET status='stopped',slot=NULL WHERE id=?")) { s.setString(1,id); s.executeUpdate(); }
            try (PreparedStatement s=c.prepareStatement("UPDATE vip_world_runs SET stopped_at=CURRENT_TIMESTAMP,stop_reason='owner' WHERE world_id=? AND stopped_at IS NULL")) { s.setString(1,id); s.executeUpdate(); }
            c.commit();
        } catch(SQLException e) { c.rollback(); throw e; } }
    }
    public static boolean duplicate(SQLException e) { return e.getErrorCode()==1062 || "23505".equals(e.getSQLState()); }
}
