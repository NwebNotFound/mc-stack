package de.mcstack.craftcore.pvp;

import java.sql.*;
import java.util.*;

public final class PvpStore {
    @FunctionalInterface public interface ConnectionSource { Connection open() throws SQLException; }
    public record Assignment(String matchId, UUID a, UUID b, String kitA, String kitB, String status) {}
    private final ConnectionSource source;
    public PvpStore(ConnectionSource source) { this.source = Objects.requireNonNull(source); }

    public void createAssignment(String matchId, UUID a, UUID b, String kitA, String kitB) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement("INSERT INTO pvp_assignments (match_id,player_a,player_b,kit_a,kit_b,status,created_at,updated_at) VALUES (?,?,?,?,?,'pending',?,?)")) {
            long now=System.currentTimeMillis();
            s.setString(1,matchId); s.setString(2,a.toString()); s.setString(3,b.toString());
            s.setString(4,kitA); s.setString(5,kitB); s.setLong(6,now); s.setLong(7,now); s.executeUpdate();
        }
    }
    /**
     * A pending or running assignment older than this is a leftover (PvP server crashed or stopped mid-match): the
     * lobby gives up on a match after 30 minutes and a match lasts at most three 5 minute rounds.
     */
    public static final long OPEN_MAX_AGE_MS = 35L * 60_000;
    /** The lobby refreshes its queue hold every minute while players queue; an older hold is from a dead lobby. */
    public static final long HOLD_MAX_AGE_MS = 5L * 60_000;
    public Optional<Assignment> pendingFor(UUID player) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement("SELECT match_id,player_a,player_b,kit_a,kit_b,status FROM pvp_assignments WHERE (player_a=? OR player_b=?) AND status IN ('pending','running') AND updated_at>? ORDER BY created_at DESC LIMIT 1")) {
            s.setString(1,player.toString()); s.setString(2,player.toString()); s.setLong(3,System.currentTimeMillis()-OPEN_MAX_AGE_MS);
            try (ResultSet r=s.executeQuery()) { return r.next() ? Optional.of(new Assignment(r.getString(1),UUID.fromString(r.getString(2)),UUID.fromString(r.getString(3)),r.getString(4),r.getString(5),r.getString(6))) : Optional.empty(); }
        }
    }
    public boolean markStatus(String id,String from,String to) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement("UPDATE pvp_assignments SET status=?,updated_at=? WHERE match_id=? AND status=?")) {
            s.setString(1,to); s.setLong(2,System.currentTimeMillis()); s.setString(3,id); s.setString(4,from); return s.executeUpdate()==1;
        }
    }
    public static final String QUEUE_HOLD_ID = "warmup";
    /** Idle stop stays off while a match is pending or running, or at least one player is queued. */
    public static boolean holdsServer(int pendingOrRunning, int queuedPlayers) { return pendingOrRunning > 0 || queuedPlayers > 0; }
    /** Open assignments and the lobby's queue hold; stale rows of a crashed server or lobby no longer count. */
    public int pendingCount() throws SQLException { return pendingCount(System.currentTimeMillis()); }
    public int pendingCount(long now) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement("SELECT COUNT(*) FROM pvp_assignments WHERE (status IN ('pending','running') AND updated_at>?) OR (status='warmup' AND updated_at>?)")) {
            s.setLong(1,now-OPEN_MAX_AGE_MS); s.setLong(2,now-HOLD_MAX_AGE_MS);
            try (ResultSet r=s.executeQuery()) { r.next(); return r.getInt(1); }
        }
    }
    /** PvP server shutdown: every open assignment of these matches becomes failed, so it no longer holds the server. */
    public void failOpen(Collection<String> matchIds) throws SQLException {
        if (matchIds.isEmpty()) return;
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement("UPDATE pvp_assignments SET status='failed',updated_at=? WHERE match_id=? AND status IN ('pending','running')")) {
            long now=System.currentTimeMillis();
            for (String id : matchIds) { s.setLong(1,now); s.setString(2,id); s.addBatch(); }
            s.executeBatch();
        }
    }
    public static String buildRequestId(UUID player) { return ("b" + player.toString().replace("-", "")); }
    public void requestBuild(UUID player) throws SQLException {
        String id=buildRequestId(player); long now=System.currentTimeMillis();
        try (Connection c=source.open()) {
            try (PreparedStatement update=c.prepareStatement("UPDATE pvp_assignments SET status='build', player_a=?, updated_at=? WHERE match_id=?")) {
                update.setString(1, player.toString()); update.setLong(2, now); update.setString(3, id); if (update.executeUpdate()>0) return;
            }
            try (PreparedStatement insert=c.prepareStatement("INSERT INTO pvp_assignments (match_id,player_a,player_b,kit_a,kit_b,status,created_at,updated_at) VALUES (?,?,?,?,?,'build',?,?)")) {
                insert.setString(1, id); insert.setString(2, player.toString()); insert.setString(3, "00000000-0000-0000-0000-000000000000");
                insert.setString(4, "-"); insert.setString(5, "-"); insert.setLong(6, now); insert.setLong(7, now); insert.executeUpdate();
            }
        }
    }
    public boolean consumeBuildRequest(UUID player) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement("UPDATE pvp_assignments SET status='done', updated_at=? WHERE match_id=? AND player_a=? AND status='build'")) {
            s.setLong(1, System.currentTimeMillis()); s.setString(2, buildRequestId(player)); s.setString(3, player.toString()); return s.executeUpdate()==1;
        }
    }
    public void holdForQueue(boolean occupied) throws SQLException {
        try (Connection c=source.open()) {
            if (!occupied) {
                try (PreparedStatement s=c.prepareStatement("DELETE FROM pvp_assignments WHERE match_id=? AND status='warmup'")) { s.setString(1, QUEUE_HOLD_ID); s.executeUpdate(); }
                return;
            }
            long now=System.currentTimeMillis();
            try (PreparedStatement update=c.prepareStatement("UPDATE pvp_assignments SET status='warmup', updated_at=? WHERE match_id=?")) {
                update.setLong(1, now); update.setString(2, QUEUE_HOLD_ID); if (update.executeUpdate()>0) return;
            }
            try (PreparedStatement insert=c.prepareStatement("INSERT INTO pvp_assignments (match_id,player_a,player_b,kit_a,kit_b,status,created_at,updated_at) VALUES (?,?,?,?,?,'warmup',?,?)")) {
                insert.setString(1, QUEUE_HOLD_ID); insert.setString(2, "00000000-0000-0000-0000-000000000000"); insert.setString(3, "00000000-0000-0000-0000-000000000000");
                insert.setString(4, "-"); insert.setString(5, "-"); insert.setLong(6, now); insert.setLong(7, now); insert.executeUpdate();
            }
        }
    }
    public void syncKits(Map<String,String> icons) throws SQLException {
        try (Connection c=source.open()) {
            c.setAutoCommit(false);
            try {
                String placeholders=String.join(",",Collections.nCopies(icons.size(),"?"));
                try (PreparedStatement s=c.prepareStatement(icons.isEmpty()?"DELETE FROM pvp_kits":"DELETE FROM pvp_kits WHERE name NOT IN ("+placeholders+")")) {
                    int i=1; for(String name:icons.keySet()) s.setString(i++,name); s.executeUpdate();
                }
                try (PreparedStatement s=c.prepareStatement("INSERT INTO pvp_kits (name,icon,updated_at) VALUES (?,?,?) ON DUPLICATE KEY UPDATE icon=VALUES(icon),updated_at=VALUES(updated_at)")) {
                    long now=System.currentTimeMillis(); for(var entry:icons.entrySet()) { s.setString(1,entry.getKey()); s.setString(2,entry.getValue()); s.setLong(3,now); s.addBatch(); } s.executeBatch();
                }
                c.commit();
            } catch(SQLException e) { c.rollback(); throw e; }
        }
    }
    public void saveMatch(PvpMatch m,String arena,long endedAt) throws SQLException {
        try (Connection c=source.open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement s=c.prepareStatement("INSERT IGNORE INTO pvp_matches (match_id,arena,kit,winner_uuid,loser_uuid,started_at,ended_at,duration_ms,result) VALUES (?,?,?,?,?,?,?,?,?)")) {
                    s.setString(1,m.matchId()); s.setString(2,arena); s.setString(3,m.kitRound1()); s.setString(4,string(m.matchWinner())); s.setString(5,string(m.matchLoser())); s.setLong(6,m.startedAt()); s.setLong(7,endedAt); s.setLong(8,endedAt-m.startedAt()); s.setString(9,m.result()); s.executeUpdate();
                }
                try (PreparedStatement s=c.prepareStatement("INSERT IGNORE INTO pvp_rounds (match_id,round_no,kit,winner_uuid,duration_ms) VALUES (?,?,?,?,?)")) {
                    for(var round:m.rounds()) { s.setString(1,m.matchId()); s.setInt(2,round.roundNo()); s.setString(3,round.kit()); s.setString(4,string(round.winner())); s.setLong(5,round.durationMs()); s.addBatch(); } s.executeBatch();
                }
                c.commit();
            } catch(SQLException e) { c.rollback(); throw e; }
        }
    }
    private static String string(UUID id) { return id==null?null:id.toString(); }
}
