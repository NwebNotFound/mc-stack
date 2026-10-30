-- Website read grants. CraftCore creates the views on startup.
-- Apply with an account that has GRANT OPTION, same shape as scripts/v-player-stats.sql.
GRANT SELECT ON craftcore.v_player_inventory TO 'creepy_ro'@'%';
GRANT SELECT ON craftcore.v_player_balance TO 'creepy_ro'@'%';
GRANT SELECT ON craftcore.v_order_fulfillment TO 'creepy_ro'@'%';
