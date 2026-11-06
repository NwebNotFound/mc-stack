package de.mcstack.craftcore.vip;

import java.sql.*;
import java.time.Clock;
import java.util.*;

public final class VipStore {
    @FunctionalInterface public interface ConnectionSource { Connection open() throws SQLException; }
    public record World(String id, String name, String status, String slot, String type) {}
    public record Entry(String id, UUID owner, String name, String type, String status, String slot) {}
    public record Member(UUID player, String name, String role) {}
    /** One world as the website panel needs it (POST /api/mc-vip-worlds). */
    public record SyncRow(String id, UUID owner, String ownerName, String name, String type, String status,
                          String slot, String visibility, long createdAt, Long lastStartedAt, String settings) {}
    public record SyncMember(String worldId, UUID player, String name, String role, long addedAt) {}
    /** Everything the website panel needs, read in four queries regardless of the number of worlds. */
    public record SyncSnapshot(List<SyncRow> worlds, List<SyncMember> members, Map<String, List<String>> addons,
                               Map<String, Integer> online) {}
    /** Contract limit per sync (docs/vip-api.md, website cleanWorlds). One more row is read to detect overflow. */
    public static final int SYNC_MAX_WORLDS = 500;
    static final String SYNC_WORLDS = "SELECT w.id,w.owner,p.name,w.name,w.type,w.status,w.slot,w.visibility,w.created_at,w.last_started_at,w.settings FROM vip_worlds w LEFT JOIN known_players p ON p.uuid=w.owner WHERE w.status<>'deleted' ORDER BY w.created_at,w.id LIMIT " + (SYNC_MAX_WORLDS + 1);
    static final String COUNT_LIVE = "SELECT COUNT(*) FROM vip_worlds WHERE status<>'deleted'";
    static final String SYNC_MEMBERS = "SELECT m.world_id,m.player,p.name,m.role,m.added_at FROM vip_world_members m JOIN vip_worlds w ON w.id=m.world_id LEFT JOIN known_players p ON p.uuid=m.player WHERE w.status<>'deleted' ORDER BY m.world_id,m.added_at";
    static final String SYNC_ADDONS = "SELECT a.world_id,a.addon FROM vip_world_addons a JOIN vip_worlds w ON w.id=a.world_id WHERE w.status<>'deleted' ORDER BY a.world_id,a.addon";
    static final String SYNC_ONLINE = "SELECT server,COUNT(*) FROM player_presence WHERE online=TRUE GROUP BY server";
    static final String COUNT_WORLDS = "SELECT COUNT(*) FROM vip_worlds WHERE owner=? AND status<>'deleted'";
    /**
     * Count and insert in one statement: two concurrent creates can no longer both see a free slot. The locking read
     * on the owner's rows makes the second insert wait for (or deadlock against) the first instead of passing.
     */
    static final String INSERT_WORLD = "INSERT INTO vip_worlds (id,owner,name,type,status,created_at) SELECT ?,?,?,?,'stopped',? FROM vip_worlds WHERE owner=? AND status<>'deleted' HAVING COUNT(*)<?";
    static final String LIST_WORLDS = "SELECT id,name,status,slot,type FROM vip_worlds WHERE owner=? AND status<>'deleted' ORDER BY name";
    static final String FIND_WORLD = "SELECT id,owner,name,type,status,slot FROM vip_worlds WHERE id=? AND status<>'deleted'";
    static final String RENAME_WORLD = "UPDATE vip_worlds SET name=? WHERE id=? AND owner=? AND status<>'deleted'";
    static final String SET_STATUS = "UPDATE vip_worlds SET status=?,slot=? WHERE id=? AND status<>'deleted'";
    static final String SET_RUNNING = "UPDATE vip_worlds SET status='running',last_started_at=? WHERE id=? AND status='starting' AND slot=?";
    static final String INSERT_RUN = "INSERT INTO vip_world_runs (world_id,slot,started_at) VALUES (?,?,?)";
    static final String MARK_DELETED = "UPDATE vip_worlds SET status='deleted',slot=NULL WHERE id=? AND status IN ('stopped','error')";
    static final String SET_STOPPED = "UPDATE vip_worlds SET status='stopped',slot=NULL WHERE id=? AND status<>'deleted'";
    static final String STOP_RUNS = "UPDATE vip_world_runs SET stopped_at=?,stop_reason='owner' WHERE world_id=? AND stopped_at IS NULL";

    static final String STOP_IDLE_RUNS = "UPDATE vip_world_runs SET stopped_at=?,stop_reason='idle' WHERE world_id=? AND stopped_at IS NULL";

    static final String LIST_MEMBERS = "SELECT m.player,m.role,p.name FROM vip_world_members m LEFT JOIN known_players p ON p.uuid=m.player WHERE m.world_id=? ORDER BY m.added_at";
    static final String COUNT_MEMBERS = "SELECT COUNT(*) FROM vip_world_members WHERE world_id=?";
    static final String INSERT_MEMBER = "INSERT INTO vip_world_members (world_id,player,role,added_at) VALUES (?,?,?,?)";
    static final String DELETE_MEMBER = "DELETE FROM vip_world_members WHERE world_id=? AND player=?";
    static final String SET_MEMBER_ROLE = "UPDATE vip_world_members SET role=? WHERE world_id=? AND player=?";
    static final String LIST_ADDONS = "SELECT addon FROM vip_world_addons WHERE world_id=?";
    static final String DELETE_ADDONS = "DELETE FROM vip_world_addons WHERE world_id=?";
    static final String INSERT_ADDON = "INSERT INTO vip_world_addons (world_id,addon,added_at) VALUES (?,?,?)";
    static final String SET_STARTED = "UPDATE vip_worlds SET status='running',slot=?,last_started_at=? WHERE id=? AND status<>'deleted'";
    static final String FIND_PLAYER = "SELECT uuid FROM known_players WHERE name=?";
    static final String SET_SETTINGS = "UPDATE vip_worlds SET settings=? WHERE id=? AND status<>'deleted'";
    static final String FIND_SETTINGS = "SELECT settings FROM vip_worlds WHERE id=? AND status<>'deleted'";
    static final String SET_VISIBILITY = "UPDATE vip_worlds SET visibility=? WHERE id=? AND status<>'deleted'";
    static final String DELETE_WORLD = "UPDATE vip_worlds SET status='deleted',deleted_at=? WHERE id=? AND status<>'deleted'";
    static final String INSERT_AUDIT = "INSERT INTO vip_world_audit (world_id,action,admin_uuid,admin_name,owner_uuid,owner_name,detail,created_at) VALUES (?,?,?,?,?,?,?,?)";
    /** Worlds the player was invited to (member of), for the hub and /vw join. */
    static final String LIST_SHARED = "SELECT w.id,w.name,w.status,w.slot,w.type FROM vip_worlds w JOIN vip_world_members m ON m.world_id=w.id WHERE m.player=? AND w.status<>'deleted' ORDER BY w.name";
    /** Invites added after a point in time, with the owner's name, for the cross-server invite notice. */
    static final String NEW_INVITES = "SELECT w.name,p.name,m.added_at,w.id FROM vip_world_members m JOIN vip_worlds w ON w.id=m.world_id LEFT JOIN known_players p ON p.uuid=w.owner WHERE m.player=? AND m.added_at>? AND w.status<>'deleted' ORDER BY m.added_at";
    static final String FIND_OWNERS_BY_NAME = "SELECT owner FROM vip_worlds WHERE name=? AND status<>'deleted'";
    static final String FIND_BY_OWNER_NAME = "SELECT id,owner,name,type,status,slot FROM vip_worlds WHERE owner=? AND name=? AND status<>'deleted'";

    private final ConnectionSource source;
    private final Clock clock;
    public VipStore(ConnectionSource source) { this(source, Clock.systemUTC()); }
    public VipStore(ConnectionSource source, Clock clock) { this.source=Objects.requireNonNull(source); this.clock=Objects.requireNonNull(clock); }
    public int count(UUID owner) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(COUNT_WORLDS)) {
            s.setString(1,owner.toString()); try (ResultSet r=s.executeQuery()) { r.next(); return r.getInt(1); }
        }
    }
    public void create(UUID owner, String name, String type) throws SQLException { create(owner, name, type, Integer.MAX_VALUE); }
    /** Creates the world only while the owner has fewer than {@code limit} worlds; false when the limit is reached. */
    public boolean create(UUID owner, String name, String type, int limit) throws SQLException {
        long now=clock.millis();
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(INSERT_WORLD)) {
            s.setString(1,UUID.randomUUID().toString()); s.setString(2,owner.toString()); s.setString(3,name); s.setString(4,type); s.setLong(5,now);
            s.setString(6,owner.toString()); s.setInt(7,limit); return s.executeUpdate()==1;
        }
    }
    public List<World> list(UUID owner) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(LIST_WORLDS)) {
            s.setString(1,owner.toString()); try (ResultSet r=s.executeQuery()) { List<World> worlds=new ArrayList<>(); while(r.next()) worlds.add(new World(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5))); return worlds; }
        }
    }
    public List<World> listShared(UUID player) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(LIST_SHARED)) {
            s.setString(1,player.toString()); try (ResultSet r=s.executeQuery()) { List<World> worlds=new ArrayList<>(); while(r.next()) worlds.add(new World(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5))); return worlds; }
        }
    }
    public record Invite(String world, String owner, long addedAt, String id) {}
    public List<Invite> invitesSince(UUID player, long since) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(NEW_INVITES)) {
            s.setString(1,player.toString()); s.setLong(2,since);
            try (ResultSet r=s.executeQuery()) { List<Invite> out=new ArrayList<>(); while(r.next()) out.add(new Invite(r.getString(1), r.getString(2)==null?"?":r.getString(2), r.getLong(3), r.getString(4))); return out; }
        }
    }
    public Optional<World> find(UUID owner, String name) throws SQLException { return list(owner).stream().filter(w->w.name().equalsIgnoreCase(name)).findFirst(); }
    public Optional<Entry> findById(String id) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(FIND_WORLD)) {
            s.setString(1,id);
            try (ResultSet r=s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                return Optional.of(new Entry(r.getString(1),UUID.fromString(r.getString(2)),r.getString(3),r.getString(4),r.getString(5),r.getString(6)));
            }
        }
    }
    public Optional<Entry> findJoinableById(UUID player, String id) throws SQLException {
        Optional<Entry> entry=findById(id);
        if (entry.isEmpty()) return entry;
        if (entry.get().owner().equals(player) || members(id).stream().anyMatch(m -> m.player().equals(player))) return entry;
        return Optional.empty();
    }

    public int liveWorldCount() throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(COUNT_LIVE); ResultSet r=s.executeQuery()) { r.next(); return r.getInt(1); }
    }
    public SyncSnapshot syncSnapshot() throws SQLException {
        try (Connection c=source.open()) {
            List<SyncRow> worlds=new ArrayList<>();
            try (PreparedStatement s=c.prepareStatement(SYNC_WORLDS); ResultSet r=s.executeQuery()) {
                while(r.next()) {
                    long started=r.getLong(10); Long last=r.wasNull()?null:started;
                    worlds.add(new SyncRow(r.getString(1),UUID.fromString(r.getString(2)),r.getString(3),r.getString(4),r.getString(5),
                        r.getString(6),r.getString(7),r.getString(8),r.getLong(9),last,r.getString(11)));
                }
            }
            List<SyncMember> members=new ArrayList<>();
            try (PreparedStatement s=c.prepareStatement(SYNC_MEMBERS); ResultSet r=s.executeQuery()) {
                while(r.next()) members.add(new SyncMember(r.getString(1),UUID.fromString(r.getString(2)),r.getString(3),r.getString(4),r.getLong(5)));
            }
            Map<String,List<String>> addons=new HashMap<>();
            try (PreparedStatement s=c.prepareStatement(SYNC_ADDONS); ResultSet r=s.executeQuery()) {
                while(r.next()) addons.computeIfAbsent(r.getString(1),k->new ArrayList<>()).add(r.getString(2));
            }
            Map<String,Integer> online=new HashMap<>();
            try (PreparedStatement s=c.prepareStatement(SYNC_ONLINE); ResultSet r=s.executeQuery()) {
                while(r.next()) online.put(r.getString(1),r.getInt(2));
            }
            return new SyncSnapshot(worlds,members,addons,online);
        }
    }
    public List<Member> members(String worldId) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(LIST_MEMBERS)) {
            s.setString(1,worldId);
            try (ResultSet r=s.executeQuery()) {
                List<Member> members=new ArrayList<>();
                while(r.next()) members.add(new Member(UUID.fromString(r.getString(1)),r.getString(3),r.getString(2)));
                return members;
            }
        }
    }
    public int memberCount(String worldId) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(COUNT_MEMBERS)) {
            s.setString(1,worldId); try (ResultSet r=s.executeQuery()) { r.next(); return r.getInt(1); }
        }
    }
    public void addMember(String worldId, UUID player, String role) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(INSERT_MEMBER)) {
            s.setString(1,worldId); s.setString(2,player.toString()); s.setString(3,role); s.setLong(4,clock.millis()); s.executeUpdate();
        }
    }
    public boolean removeMember(String worldId, UUID player) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(DELETE_MEMBER)) {
            s.setString(1,worldId); s.setString(2,player.toString()); return s.executeUpdate()>0;
        }
    }
    public boolean setMemberRole(String worldId, UUID player, String role) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(SET_MEMBER_ROLE)) {
            s.setString(1,role); s.setString(2,worldId); s.setString(3,player.toString()); return s.executeUpdate()>0;
        }
    }
    public List<String> addons(String worldId) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(LIST_ADDONS)) {
            s.setString(1,worldId);
            try (ResultSet r=s.executeQuery()) { List<String> addons=new ArrayList<>(); while(r.next()) addons.add(r.getString(1)); return addons; }
        }
    }
    public void setAddons(String worldId, List<String> addons) throws SQLException {
        long now=clock.millis();
        try (Connection c=source.open()) { c.setAutoCommit(false); try {
            try (PreparedStatement s=c.prepareStatement(DELETE_ADDONS)) { s.setString(1,worldId); s.executeUpdate(); }
            try (PreparedStatement s=c.prepareStatement(INSERT_ADDON)) {
                for (String addon : addons) { s.setString(1,worldId); s.setString(2,addon); s.setLong(3,now); s.addBatch(); }
                s.executeBatch();
            }
            c.commit();
        } catch(SQLException e) { c.rollback(); throw e; } }
    }
    /** Marks a world running after the host confirmed the slot, and opens a run row. */
    public void started(String id, String slot) throws SQLException {
        long now=clock.millis();
        try (Connection c=source.open()) { c.setAutoCommit(false); try {
            try (PreparedStatement s=c.prepareStatement(SET_STARTED)) {
                s.setString(1,slot); s.setLong(2,now); s.setString(3,id);
                if (s.executeUpdate()!=1) throw new SQLException("Welt nicht gefunden");
            }
            try (PreparedStatement s=c.prepareStatement(INSERT_RUN)) { s.setString(1,id); s.setString(2,slot); s.setLong(3,now); s.executeUpdate(); }
            c.commit();
        } catch(SQLException e) { c.rollback(); throw e; } }
    }
    public Optional<UUID> player(String name) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(FIND_PLAYER)) {
            s.setString(1,name);
            try (ResultSet r=s.executeQuery()) { return r.next() ? Optional.of(UUID.fromString(r.getString(1))) : Optional.empty(); }
        }
    }
    public Optional<Entry> findByName(String name) throws SQLException {
        List<UUID> owners = ownersOf(name);
        return owners.size() == 1 ? findByOwnerName(owners.get(0), name) : Optional.empty();
    }
    /** Every owner that has a world with that name; more than one is ambiguous for admins. */
    public List<UUID> ownersOf(String name) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(FIND_OWNERS_BY_NAME)) {
            s.setString(1,name);
            try (ResultSet r=s.executeQuery()) { List<UUID> owners=new ArrayList<>(); while(r.next()) owners.add(UUID.fromString(r.getString(1))); return owners; }
        }
    }
    public Optional<Entry> findByOwnerName(UUID owner, String name) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(FIND_BY_OWNER_NAME)) {
            s.setString(1,owner.toString()); s.setString(2,name);
            try (ResultSet r=s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                return Optional.of(new Entry(r.getString(1),UUID.fromString(r.getString(2)),r.getString(3),r.getString(4),r.getString(5),r.getString(6)));
            }
        }
    }
    public void rename(UUID owner, String id, String name) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(RENAME_WORLD)) { s.setString(1,name); s.setString(2,id); s.setString(3,owner.toString()); s.executeUpdate(); }
    }
    /** A deleted world is never brought back by a later status write (a start racing a delete). Returns false then. */
    public boolean status(String id, String status, String slot) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(SET_STATUS)) { s.setString(1,status); s.setString(2,slot); s.setString(3,id); return s.executeUpdate()==1; }
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
    /** Soft delete of a stopped world; false when it is running, starting or already deleted. */
    public boolean markDeleted(String id) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(MARK_DELETED)) { s.setString(1,id); return s.executeUpdate()==1; }
    }
    public void idleStopped(String id) throws SQLException { stop(id,STOP_IDLE_RUNS); }
    private void stop(String id, String sql) throws SQLException {
        long now=clock.millis();
        try (Connection c=source.open()) { c.setAutoCommit(false); try {
            try (PreparedStatement s=c.prepareStatement(SET_STOPPED)) { s.setString(1,id); s.executeUpdate(); }
            try (PreparedStatement s=c.prepareStatement(sql)) { s.setLong(1,now); s.setString(2,id); s.executeUpdate(); }
            c.commit();
        } catch(SQLException e) { c.rollback(); throw e; } }
    }
    /** Die sechs Regeln als JSON; NULL heisst Vertrags-Defaults. */
    public void setSettings(String id, VipWorldSettings settings) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(SET_SETTINGS)) {
            s.setString(1, settings==null ? null : settings.toJson().toString()); s.setString(2,id); s.executeUpdate();
        }
    }
    public VipWorldSettings settings(String id) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(FIND_SETTINGS)) {
            s.setString(1,id);
            try (ResultSet r=s.executeQuery()) {
                if (!r.next()) return VipWorldSettings.DEFAULTS;
                String json=r.getString(1);
                return json==null ? VipWorldSettings.DEFAULTS : VipWorldSettings.fromJson(com.google.gson.JsonParser.parseString(json));
            }
        }
    }
    /** Vertrag schreibt friends/private; die Spalte kennt historisch public statt friends. */
    public void visibility(String id, String visibility) throws SQLException {
        String stored = "private".equals(visibility) ? "private" : "public";
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(SET_VISIBILITY)) {
            s.setString(1,stored); s.setString(2,id); s.executeUpdate();
        }
    }
    /** Weiches Loeschen: die Zeile bleibt fuer Verlauf und Audit, das Panel blendet sie aus. */
    public void delete(String id) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(DELETE_WORLD)) {
            s.setLong(1,clock.millis()); s.setString(2,id); s.executeUpdate();
        }
    }
    public void audit(String worldId, String action, UUID admin, String adminName, UUID owner, String ownerName, String detail) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement(INSERT_AUDIT)) {
            s.setString(1,worldId); s.setString(2,action); s.setString(3,admin.toString()); s.setString(4,adminName);
            s.setString(5,owner.toString()); s.setString(6,ownerName); s.setString(7,detail); s.setLong(8,clock.millis()); s.executeUpdate();
        }
    }
    public static boolean duplicate(SQLException e) { return e.getErrorCode()==1062 || "23505".equals(e.getSQLState()); }
}
