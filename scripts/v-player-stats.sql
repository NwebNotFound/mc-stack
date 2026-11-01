-- Apply as a MariaDB administrator after CraftCore has migrated player_stats and teams.
CREATE TABLE IF NOT EXISTS craftcore.player_presence (
  uuid CHAR(36) NOT NULL,
  server VARCHAR(64) NOT NULL,
  last_seen DATETIME(6) NULL,
  online BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (uuid, server)
);
-- Preserve TINYINT(1) metadata for consumers; MariaDB cannot CAST expressions to TINYINT.
CREATE TABLE IF NOT EXISTS craftcore.presence_boolean (value BOOLEAN NOT NULL PRIMARY KEY);
INSERT IGNORE INTO craftcore.presence_boolean (value) VALUES (FALSE), (TRUE);
CREATE OR REPLACE SQL SECURITY DEFINER VIEW craftcore.v_player_stats AS
SELECT p.uuid, p.name AS mc_name,
       CAST(a.balance / 100.0 AS DECIMAL(21,2)) AS money,
       s.playtime_s, s.kills, s.deaths, s.blocks_mined, s.updated_at,
       t.name AS team, t.color AS team_color_code,
       t.color_hex AS team_color_hex, t.color_hex AS team_color,
       DATE_FORMAT(presence.last_seen,'%Y-%m-%dT%H:%i:%sZ') AS last_seen,
       boolean_value.value AS online
FROM craftcore.known_players p
LEFT JOIN craftcore.accounts a ON a.uuid = p.uuid
LEFT JOIN craftcore.player_stats s ON s.uuid = p.uuid
LEFT JOIN craftcore.members m ON m.uuid = p.uuid
LEFT JOIN (SELECT id, name, color,
                  CASE LOWER(REPLACE(color, '§', '&'))
                    WHEN '&0' THEN '#000000'
                    WHEN '&1' THEN '#0000AA'
                    WHEN '&2' THEN '#00AA00'
                    WHEN '&3' THEN '#00AAAA'
                    WHEN '&4' THEN '#AA0000'
                    WHEN '&5' THEN '#AA00AA'
                    WHEN '&6' THEN '#FFAA00'
                    WHEN '&7' THEN '#AAAAAA'
                    WHEN '&8' THEN '#555555'
                    WHEN '&9' THEN '#5555FF'
                    WHEN '&a' THEN '#55FF55'
                    WHEN '&b' THEN '#55FFFF'
                    WHEN '&c' THEN '#FF5555'
                    WHEN '&d' THEN '#FF55FF'
                    WHEN '&e' THEN '#FFFF55'
                    WHEN '&f' THEN '#FFFFFF'
                    ELSE NULL
                  END AS color_hex
           FROM craftcore.teams) t ON t.id = m.team_id
LEFT JOIN (SELECT uuid, MAX(last_seen) AS last_seen, MAX(online) AS online
           FROM craftcore.player_presence GROUP BY uuid) presence ON presence.uuid = p.uuid
JOIN craftcore.presence_boolean boolean_value ON boolean_value.value = (COALESCE(presence.online, 0) <> 0);
GRANT SELECT ON craftcore.v_player_stats TO 'creepy_ro'@'%';

-- Idempotent shop/ah buy replies. creepy_ro must not read rcon_purchases itself.
CREATE OR REPLACE SQL SECURITY DEFINER VIEW craftcore.v_rcon_purchase_result AS
SELECT CAST(idem_key AS CHAR(64)) AS idem_key, response
FROM craftcore.rcon_purchases;
GRANT SELECT ON craftcore.v_rcon_purchase_result TO 'creepy_ro'@'%';
