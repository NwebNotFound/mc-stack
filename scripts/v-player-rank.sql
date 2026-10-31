-- Apply manually as craft_admin (MariaDB) AFTER scripts/luckperms-ranks.txt ran.
-- One row per UUID: highest-weight group among active global group.* nodes, fallback default.
-- Color = first two chars of the group's prefix (e.g. '&c'). creepy_ro gets SELECT on the view only.
CREATE OR REPLACE DEFINER='craft_admin'@'%' SQL SECURITY DEFINER VIEW craftcore.v_player_rank AS
WITH grp AS (
  SELECT g.name AS grp,
         COALESCE(MAX(CASE WHEN gp.permission LIKE 'weight.%'
                  THEN CAST(SUBSTRING(gp.permission, 8) AS SIGNED) END), 0) AS weight,
         COALESCE(MAX(CASE WHEN gp.permission LIKE 'prefix.%'
                  THEN SUBSTRING(SUBSTRING_INDEX(gp.permission, '.', -1), 1, 2) END), '&7') AS color
  FROM luckperms.luckperms_groups g
  LEFT JOIN luckperms.luckperms_group_permissions gp
         ON gp.name = g.name AND gp.value = 1 AND gp.server = 'global' AND gp.world = 'global'
  GROUP BY g.name
),
memb AS (
  SELECT p.uuid, p.username,
         COALESCE(SUBSTRING(up.permission, 7), 'default') AS grp
  FROM luckperms.luckperms_players p
  LEFT JOIN luckperms.luckperms_user_permissions up
         ON up.uuid = p.uuid AND up.permission LIKE 'group.%' AND up.value = 1
        AND up.server = 'global' AND up.world = 'global'
        AND (up.expiry = 0 OR up.expiry > UNIX_TIMESTAMP())
),
ranked AS (
  SELECT m.uuid, m.username, m.grp, COALESCE(g.weight, 0) AS weight, COALESCE(g.color, '&7') AS color,
         ROW_NUMBER() OVER (PARTITION BY m.uuid ORDER BY COALESCE(g.weight, 0) DESC, m.grp) AS rn
  FROM memb m LEFT JOIN grp g ON g.grp = m.grp
)
SELECT uuid, username AS name, grp AS rank, color, CAST(weight AS SIGNED) AS weight
FROM ranked WHERE rn = 1;
GRANT SELECT ON craftcore.v_player_rank TO 'creepy_ro'@'%';
