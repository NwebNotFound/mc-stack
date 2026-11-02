-- VIP-Welt-Server (vipworld-N): eigener DB-User, Passwort aus .env (CRAFTCORE_VIP_DB_PASSWORD).
-- tools/vip-db-user.ps1 ersetzt @VIP_DB_PASSWORD@ im Speicher und sendet SQL ueber stdin.
CREATE USER IF NOT EXISTS 'craft_vip'@'%' IDENTIFIED BY '@VIP_DB_PASSWORD@';
ALTER USER 'craft_vip'@'%' IDENTIFIED BY '@VIP_DB_PASSWORD@';
GRANT SELECT ON craftcore.vip_worlds TO 'craft_vip'@'%';
GRANT SELECT ON craftcore.vip_world_members TO 'craft_vip'@'%';
GRANT INSERT, DELETE ON craftcore.vip_world_members TO 'craft_vip'@'%';
GRANT SELECT, INSERT, DELETE ON craftcore.vip_world_addons TO 'craft_vip'@'%';
GRANT SELECT, INSERT, UPDATE ON craftcore.vip_world_runs TO 'craft_vip'@'%';
GRANT SELECT ON craftcore.known_players TO 'craft_vip'@'%';

-- Run scripts/v-player-stats.sql first to create the shared presence table.
GRANT SELECT, INSERT, UPDATE ON craftcore.player_presence TO 'craft_vip'@'%';
