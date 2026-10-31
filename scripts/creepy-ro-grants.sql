-- als craft_admin ausfuehren, idempotent
GRANT SELECT ON craftcore.v_rcon_purchase_result TO 'creepy_ro'@'%';
GRANT SELECT ON craftcore.bank_buy_orders TO 'creepy_ro'@'%';
GRANT SELECT ON craftcore.v_player_balance TO 'creepy_ro'@'%';
GRANT SELECT ON craftcore.v_player_inventory TO 'creepy_ro'@'%';
GRANT SELECT ON craftcore.v_order_fulfillment TO 'creepy_ro'@'%';
GRANT SELECT ON craftcore.player_deaths TO 'creepy_ro'@'%';
GRANT SELECT ON craftcore.deaths_by TO 'creepy_ro'@'%';
