-- Apply as a MariaDB administrator after CraftCore has migrated player_stats.
CREATE OR REPLACE SQL SECURITY DEFINER VIEW craftcore.v_player_stats AS
SELECT p.uuid, p.name AS mc_name,
       CAST(a.balance / 100.0 AS DECIMAL(21,2)) AS money,
       s.playtime_s, s.kills, s.deaths, s.blocks_mined, s.updated_at
FROM craftcore.known_players p
LEFT JOIN craftcore.accounts a ON a.uuid = p.uuid
LEFT JOIN craftcore.player_stats s ON s.uuid = p.uuid;
GRANT SELECT ON craftcore.v_player_stats TO 'creepy_ro'@'%';
