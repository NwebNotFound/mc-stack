package de.mcstack.craftcore.vip;

import java.sql.*;
import java.util.Optional;

/** Stores the decision for {@code (discord_id, request_id)} so a second poll never runs it again. */
public final class VipAcks implements VipWebQueue.Acks {
    private static final String FIND = "SELECT status,reason FROM vip_command_acks WHERE discord_id=? AND request_id=?";
    private static final String SAVE = "INSERT INTO vip_command_acks (discord_id,request_id,status,reason,created_at) "
        + "VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE status=VALUES(status), reason=VALUES(reason)";

    private final VipStore.ConnectionSource source;
    private final java.time.Clock clock;

    public VipAcks(VipStore.ConnectionSource source) { this(source, java.time.Clock.systemUTC()); }
    public VipAcks(VipStore.ConnectionSource source, java.time.Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    @Override
    public Optional<VipWebQueue.Outcome> look(String discordId, String requestId) throws SQLException {
        try (Connection c = source.open(); PreparedStatement s = c.prepareStatement(FIND)) {
            s.setString(1, discordId);
            s.setString(2, requestId);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(new VipWebQueue.Outcome(r.getString(1), r.getString(2))) : Optional.empty();
            }
        }
    }

    @Override
    public void save(String discordId, String requestId, String status, String reason) throws SQLException {
        try (Connection c = source.open(); PreparedStatement s = c.prepareStatement(SAVE)) {
            s.setString(1, discordId);
            s.setString(2, requestId);
            s.setString(3, status);
            s.setString(4, reason == null ? "" : reason);
            s.setLong(5, clock.millis());
            s.executeUpdate();
        }
    }
}
