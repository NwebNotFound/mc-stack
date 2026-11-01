-- PvP-DB-User craft_pvp. Als craft_admin ausfuehren (idempotent), am besten ueber tools/pvp-db-user.ps1,
-- das @PVP_DB_PASSWORD@ im Speicher durch CRAFTCORE_PVP_DB_PASSWORD aus .env ersetzt (Passwort nie im Repo/Log).
-- Rechte: NUR pvp_*-Tabellen (DML) + SELECT auf v_player_rank. Kein Zugriff auf Economy-/Inventar-Tabellen.
CREATE TABLE IF NOT EXISTS craftcore.pvp_matches (
  match_id CHAR(36) PRIMARY KEY,
  arena VARCHAR(64) NOT NULL,
  kit VARCHAR(64) NOT NULL,
  winner_uuid CHAR(36) NULL,
  loser_uuid CHAR(36) NULL,
  started_at BIGINT NOT NULL,
  ended_at BIGINT NOT NULL,
  duration_ms BIGINT NOT NULL,
  KEY (winner_uuid), KEY (loser_uuid), KEY (ended_at)
);
CREATE TABLE IF NOT EXISTS craftcore.pvp_rounds (
  match_id CHAR(36) NOT NULL,
  round_no TINYINT NOT NULL,
  kit VARCHAR(64) NOT NULL,
  winner_uuid CHAR(36) NULL,
  duration_ms BIGINT NOT NULL,
  PRIMARY KEY (match_id, round_no),
  FOREIGN KEY (match_id) REFERENCES craftcore.pvp_matches(match_id) ON DELETE CASCADE
);
CREATE USER IF NOT EXISTS 'craft_pvp'@'%' IDENTIFIED BY '@PVP_DB_PASSWORD@';
ALTER USER 'craft_pvp'@'%' IDENTIFIED BY '@PVP_DB_PASSWORD@';
GRANT SELECT, INSERT, UPDATE, DELETE ON craftcore.pvp_matches TO 'craft_pvp'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON craftcore.pvp_rounds TO 'craft_pvp'@'%';
GRANT SELECT ON craftcore.v_player_rank TO 'craft_pvp'@'%';
-- Run scripts/v-player-stats.sql first to create the shared presence table.
GRANT SELECT, INSERT, UPDATE ON craftcore.player_presence TO 'craft_pvp'@'%';
