package de.mcstack.craftcore.vip;

import java.sql.*;
import java.time.Clock;
import java.util.*;

public final class VipStore {
    @FunctionalInterface public interface ConnectionSource { Connection open() throws SQLException; }
    public record World(String id, String name, String status, String slot, String type) {}
    static final String COUNT_WORLDS = "SELECT COUNT(*) FROM vip_worlds WHERE owner=? AND status<>'deleted'";
    static final String INSERT_WORLD = "INSERT INTO vip_worlds (id,owner,name,type,status,created_at) VALUES (?,?,?,?,'stopped',?)";
    static final String LIST_WORLDS = "SELECT id,name,status,slot,type FROM vip_worlds WHERE owner=? AND status<>'deleted' ORDER BY name";
    static final String RENAME_WORLD = "UPDATE vip_worlds SET name=? WHERE id=? AND owner=? AND status<>'deleted'";
    static final String SET_STATUS = "UPDATE vip_worlds SET status=?,slot=? WHERE id=?";
    static final String SET_RUNNING = "UPDATE vip_worlds SET status='running',last_started_at=? WHERE id=? AND status='starting' AND slot=?";
    static final String INSERT_RUN = "INSERT INTO vip_world_runs (world_id,slot,started_at) VALUES (?,?,?)";
    static final String SET_STOPPED = "UPDATE vip_worlds SET status='stopped',slot=NULL WHERE id=?";
    static final String STOP_RUNS = "UPDATE vip_world_runs SET stopped_at=?,stop_reason='owner' WHERE world_id=? AND stopped_at IS NULL";

    static final String STOP_IDLE_RUNS = "UPDATE vip_world_runs SET stopped_at=?,stop_reason='idle' WHERE world_id=? AND stopped_at IS NULL";

    private final ConnectionSource source;
    private final Clock clock;
    public VipStore(ConnectionSource source) { this(source, Clock.systemUTC()); }
    public VipStore(ConnectionSource source, Clock clock) { this.source=Objects.requireNonNull(source); this.clock=Objects.requireNonNull(clock); }
    public int count(UUID owner) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(COUNT_WORLDS)) {
            s.setString(1,owner.toString()); try (ResultSet r=s.executeQuery()) { r.next(); return r.getInt(1); }
        }
    }
    public void create(UUID owner, String name, String type) throws SQLException {
        long now=clock.millis();
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(INSERT_WORLD)) {
            s.setString(1,UUID.randomUUID().toString()); s.setString(2,owner.toString()); s.setString(3,name); s.setString(4,type); s.setLong(5,now); s.executeUpdate();
        }
    }
    public List<World> list(UUID owner) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(LIST_WORLDS)) {
            s.setString(1,owner.toString()); try (ResultSet r=s.executeQuery()) { List<World> worlds=new ArrayList<>(); while(r.next()) worlds.add(new World(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5))); return worlds; }
        }
    }
    public Optional<World> find(UUID owner, String name) throws SQLException { return list(owner).stream().filter(w->w.name().equalsIgnoreCase(name)).findFirst(); }
    public void rename(UUID owner, String id, String name) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(RENAME_WORLD)) { s.setString(1,name); s.setString(2,id); s.setString(3,owner.toString()); s.executeUpdate(); }
    }
    public void status(String id, String status, String slot) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(SET_STATUS)) { s.setString(1,status); s.setString(2,slot); s.setString(3,id); s.executeUpdate(); }
    }
    public void running(String id, String slot) throws SQLException {
        long now=clock.millis();
        try (Connection c=source.open()) { c.setAutoCommit(false); try {
            try (PreparedStatement s=c.prepareStatement(SET_RUNNING)) { s.setLong(1,now); s.setString(2,id); s.setString(3,slot); if(s.executeUpdate()!=1) throw new SQLException("Weltstatus geaendert"); }
            try (PreparedStatement s=c.prepareStatement(INSERT_RUN)) { s.setString(1,id); s.setString(2,slot); s.setLong(3,now); s.executeUpdate(); }
            c.commit();
        } catch(SQLException e) { c.rollback(); throw e; } }
    }
    public void stopped(String id) throws SQLException { stop(id,STOP_RUNS); }
    public void idleStopped(String id) throws SQLException { stop(id,STOP_IDLE_RUNS); }
    private void stop(String id, String sql) throws SQLException {
        long now=clock.millis();
        try (Connection c=source.open()) { c.setAutoCommit(false); try {
            try (PreparedStatement s=c.prepareStatement(SET_STOPPED)) { s.setString(1,id); s.executeUpdate(); }
            try (PreparedStatement s=c.prepareStatement(sql)) { s.setLong(1,now); s.setString(2,id); s.executeUpdate(); }
            c.commit();
        } catch(SQLException e) { c.rollback(); throw e; } }
    }
    public static boolean duplicate(SQLException e) { return e.getErrorCode()==1062 || "23505".equals(e.getSQLState()); }
}
