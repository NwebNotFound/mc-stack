-- VIP-Welt-Server (vipworld-N): eigener DB-User, Passwort aus .env (CRAFTCORE_VIP_DB_PASSWORD).
-- Ausfuehren: mariadb -u root -p --init-command="SET @pw='<aus .env>'" < scripts/vip-db-user.sql
SET @sql = CONCAT('CREATE USER IF NOT EXISTS ''craft_vip''@''%'' IDENTIFIED BY ', QUOTE(@pw));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
GRANT SELECT ON craftcore.vip_worlds TO 'craft_vip'@'%';
GRANT SELECT ON craftcore.vip_world_members TO 'craft_vip'@'%';
GRANT SELECT, INSERT, UPDATE ON craftcore.vip_world_runs TO 'craft_vip'@'%';
GRANT SELECT ON craftcore.known_players TO 'craft_vip'@'%';

-- Run scripts/v-player-stats.sql first to create the shared presence table.
GRANT SELECT, INSERT, UPDATE ON craftcore.player_presence TO 'craft_vip'@'%';
