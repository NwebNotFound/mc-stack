-- One-off: playtime snapshots are approximate, not actual last-seen timestamps.
-- Use UTC when reading TIMESTAMP values into presence DATETIME.
SET time_zone = '+00:00';
INSERT INTO player_presence (uuid, server, last_seen, online)
SELECT uuid, server, updated_at, FALSE FROM player_playtime_world
WHERE server IN ('lobby', 'survival')
ON DUPLICATE KEY UPDATE uuid = player_presence.uuid;
