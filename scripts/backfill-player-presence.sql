-- Existing presence is preserved; imported players have an unknown last-seen time.
-- Allow unknown timestamps in tables created by earlier versions.
ALTER TABLE player_presence MODIFY last_seen DATETIME(6) NULL;
INSERT INTO player_presence (uuid, server, last_seen, online)
SELECT uuid, server, NULL, FALSE
FROM (
  SELECT uuid, server, MAX(updated_at) AS updated_at
  FROM player_playtime_world
  WHERE server IN ('lobby', 'survival')
  GROUP BY uuid, server
) AS playtime
ON DUPLICATE KEY UPDATE uuid = player_presence.uuid;
