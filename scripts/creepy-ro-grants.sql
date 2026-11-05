-- Manual setup/recovery as an administrator with GRANT OPTION, after CraftCore
-- has created the objects. Re-runnable: only these eight SELECT grants are added.
-- Require the existing account even when the server permits implicit GRANT users.
SET @creepy_ro_saved_sql_mode = @@SESSION.sql_mode;
SET SESSION sql_mode = CONCAT_WS(',', NULLIF(@@SESSION.sql_mode, ''), 'NO_AUTO_CREATE_USER');
GRANT SELECT ON craftcore.v_rcon_purchase_result TO 'creepy_ro'@'%';
GRANT SELECT ON craftcore.v_parkour_courses TO 'creepy_ro'@'%';
GRANT SELECT ON craftcore.bank_buy_orders TO 'creepy_ro'@'%';
GRANT SELECT ON craftcore.v_player_balance TO 'creepy_ro'@'%';
GRANT SELECT ON craftcore.v_player_inventory TO 'creepy_ro'@'%';
GRANT SELECT ON craftcore.v_order_fulfillment TO 'creepy_ro'@'%';
GRANT SELECT ON craftcore.player_deaths TO 'creepy_ro'@'%';
GRANT SELECT ON craftcore.deaths_by TO 'creepy_ro'@'%';
SET SESSION sql_mode = @creepy_ro_saved_sql_mode;
