-- Apply manually as a MariaDB administrator after CraftCore creates leaderboard_snapshots.
-- Money snapshots are in CP, taken once per Berlin calendar day for known players.
CREATE OR REPLACE SQL SECURITY DEFINER VIEW craftcore.v_economy_daily AS
SELECT day_key AS day,
       CAST(SUM(value) AS DECIMAL(30,4)) AS money_supply_cp,
       CAST(NULL AS DECIMAL(30,4)) AS minted_cp,
       CAST(NULL AS DECIMAL(30,4)) AS burned_cp,
       CAST(NULL AS DECIMAL(30,4)) AS net_cp
FROM craftcore.leaderboard_snapshots
WHERE board = 'money'
  AND day_key BETWEEN CURRENT_DATE - INTERVAL 89 DAY AND CURRENT_DATE
GROUP BY day_key;
GRANT SELECT ON craftcore.v_economy_daily TO 'creepy_ro'@'%';
