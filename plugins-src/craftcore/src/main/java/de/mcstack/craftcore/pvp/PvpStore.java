package de.mcstack.craftcore.pvp;

import java.sql.*;
import java.util.*;

public final class PvpStore {
    @FunctionalInterface public interface ConnectionSource { Connection open() throws SQLException; }
    public record Assignment(String matchId, UUID a, UUID b, String kitA, String kitB, String status) {}
    private final ConnectionSource source;
    public PvpStore(ConnectionSource source) { this.source = Objects.requireNonNull(source); }

    public Optional<Assignment> pendingFor(UUID player) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement("SELECT match_id,player_a,player_b,kit_a,kit_b,status FROM pvp_assignments WHERE (player_a=? OR player_b=?) AND status IN ('pending','running') ORDER BY created_at DESC LIMIT 1")) {
            s.setString(1,player.toString()); s.setString(2,player.toString());
            try (ResultSet r=s.executeQuery()) { return r.next() ? Optional.of(new Assignment(r.getString(1),UUID.fromString(r.getString(2)),UUID.fromString(r.getString(3)),r.getString(4),r.getString(5),r.getString(6))) : Optional.empty(); }
        }
    }
    public boolean markStatus(String id,String from,String to) throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement("UPDATE pvp_assignments SET status=?,updated_at=? WHERE match_id=? AND status=?")) {
            s.setString(1,to); s.setLong(2,System.currentTimeMillis()); s.setString(3,id); s.setString(4,from); return s.executeUpdate()==1;
        }
    }
    public int pendingCount() throws SQLException {
        try (Connection c=source.open(); PreparedStatement s=c.prepareStatement("SELECT COUNT(*) FROM pvp_assignments WHERE status IN ('pending','running')"); ResultSet r=s.executeQuery()) { r.next(); return r.getInt(1); }
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
