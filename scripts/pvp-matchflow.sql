-- PvP Schritt 3 (Match-Ablauf). Als craft_admin ausfuehren, idempotent, NACH scripts/pvp-db-user.sql.
-- CraftCore legt keine Tabellen/Grants fuer PvP an (siehe #38).

-- Kit-Liste fuer das Lobby-Menue (pvp schreibt, Lobby liest).
CREATE TABLE IF NOT EXISTS craftcore.pvp_kits (
  name VARCHAR(32) PRIMARY KEY,
  icon VARCHAR(64) NOT NULL DEFAULT 'IRON_SWORD',
  updated_at BIGINT NOT NULL
);
-- Match-Zuweisung Lobby -> pvp. status: pending -> running -> done | failed.
CREATE TABLE IF NOT EXISTS craftcore.pvp_assignments (
  match_id CHAR(36) PRIMARY KEY,
  player_a CHAR(36) NOT NULL,
  player_b CHAR(36) NOT NULL,
  kit_a VARCHAR(32) NOT NULL,
  kit_b VARCHAR(32) NOT NULL,
  status VARCHAR(8) NOT NULL DEFAULT 'pending',
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  KEY (status), KEY (player_a), KEY (player_b)
);

-- favorite_kit = das vom Spieler selbst am haeufigsten GEWAEHLTE Kit (kit_a/kit_b der Zuweisung, nicht das Runden-Kit); Gleichstand: zuletzt gewaehltes.
-- Ergebnis des Bo3-Matches: 'win' oder 'draw' (winner/loser NULL). Draw zaehlt weder als Sieg noch als Niederlage.
ALTER TABLE craftcore.pvp_matches ADD COLUMN IF NOT EXISTS result VARCHAR(8) NOT NULL DEFAULT 'win' AFTER loser_uuid;
UPDATE craftcore.pvp_matches SET result='draw' WHERE winner_uuid IS NULL AND result<>'draw';

CREATE OR REPLACE SQL SECURITY DEFINER VIEW craftcore.v_pvp_matches AS
SELECT m.match_id, m.arena, m.kit, m.result, m.winner_uuid, w.name AS winner_name, m.loser_uuid, l.name AS loser_name,
       m.started_at, m.ended_at, m.duration_ms,
       (SELECT COUNT(*) FROM craftcore.pvp_rounds r WHERE r.match_id = m.match_id) AS rounds
FROM craftcore.pvp_matches m
LEFT JOIN craftcore.known_players w ON w.uuid = m.winner_uuid
LEFT JOIN craftcore.known_players l ON l.uuid = m.loser_uuid;

CREATE OR REPLACE SQL SECURITY DEFINER VIEW craftcore.v_pvp_stats AS
WITH part AS (
  SELECT a.player_a AS uuid, m.match_id, a.kit_a AS own_kit, m.ended_at, m.winner_uuid, m.loser_uuid, m.result
    FROM craftcore.pvp_matches m JOIN craftcore.pvp_assignments a ON a.match_id = m.match_id
  UNION ALL
  SELECT a.player_b, m.match_id, a.kit_b, m.ended_at, m.winner_uuid, m.loser_uuid, m.result
    FROM craftcore.pvp_matches m JOIN craftcore.pvp_assignments a ON a.match_id = m.match_id
),
agg AS (
  SELECT uuid, COALESCE(SUM(winner_uuid = uuid),0) AS wins, COALESCE(SUM(loser_uuid = uuid),0) AS losses, SUM(result = 'draw') AS draws, COUNT(*) AS matches
  FROM part GROUP BY uuid
),
kits AS (
  SELECT uuid, own_kit, ROW_NUMBER() OVER (PARTITION BY uuid ORDER BY COUNT(*) DESC, MAX(ended_at) DESC, own_kit) AS rn
  FROM part GROUP BY uuid, own_kit
)
SELECT g.uuid, k.name, g.wins, g.losses, g.draws, g.matches,
       CASE WHEN g.matches < 5 OR g.wins + g.losses = 0 THEN NULL ELSE ROUND(100 * g.wins / (g.wins + g.losses), 1) END AS winrate_pct,
       f.own_kit AS favorite_kit
FROM agg g
LEFT JOIN craftcore.known_players k ON k.uuid = g.uuid
LEFT JOIN kits f ON f.uuid = g.uuid AND f.rn = 1;

GRANT SELECT, INSERT, UPDATE, DELETE ON craftcore.pvp_kits TO 'craft_pvp'@'%';
GRANT SELECT, UPDATE ON craftcore.pvp_assignments TO 'craft_pvp'@'%';
GRANT SELECT ON craftcore.v_pvp_stats TO 'creepy_ro'@'%';
GRANT SELECT ON craftcore.v_pvp_matches TO 'creepy_ro'@'%';