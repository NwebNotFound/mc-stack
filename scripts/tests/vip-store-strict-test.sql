-- Run only in a fresh MariaDB test database after scripts/vip-worlds.sql from vip/1-config-db.
-- The statements below mirror every VipStore SQL constant with literal parameters.
SET SESSION sql_mode='STRICT_ALL_TABLES,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO';
USE craftcore;
INSERT INTO craftcore.known_players (uuid,name) VALUES ('00000000-0000-0000-0000-000000000001','VipStoreTest');

-- INSERT_WORLD, COUNT_WORLDS, LIST_WORLDS, RENAME_WORLD, SET_STATUS, SET_RUNNING, INSERT_RUN, SET_STOPPED, STOP_RUNS, STOP_IDLE_RUNS
INSERT INTO vip_worlds (id,owner,name,type,status,created_at) SELECT '00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','world','normal','stopped',1793500000000 FROM vip_worlds WHERE owner='00000000-0000-0000-0000-000000000001' AND status<>'deleted' HAVING COUNT(*)<1;
SELECT COUNT(*) FROM vip_worlds WHERE owner='00000000-0000-0000-0000-000000000001' AND status<>'deleted';
SELECT id,name,status,slot FROM vip_worlds WHERE owner='00000000-0000-0000-0000-000000000001' AND status<>'deleted' ORDER BY name;
UPDATE vip_worlds SET name='renamed' WHERE id='00000000-0000-0000-0000-000000000002' AND owner='00000000-0000-0000-0000-000000000001' AND status<>'deleted';
UPDATE vip_worlds SET status='starting',slot='vipworld-1' WHERE id='00000000-0000-0000-0000-000000000002';
UPDATE vip_worlds SET status='running',last_started_at=1793500000000 WHERE id='00000000-0000-0000-0000-000000000002' AND status='starting' AND slot='vipworld-1';
INSERT INTO vip_world_runs (world_id,slot,started_at) VALUES ('00000000-0000-0000-0000-000000000002','vipworld-1',1793500000000);
UPDATE vip_worlds SET status='stopped',slot=NULL WHERE id='00000000-0000-0000-0000-000000000002';
UPDATE vip_world_runs SET stopped_at=1793500000000,stop_reason='owner' WHERE world_id='00000000-0000-0000-0000-000000000002' AND stopped_at IS NULL;

INSERT INTO vip_world_runs (world_id,slot,started_at) VALUES ('00000000-0000-0000-0000-000000000002','vipworld-2',1793500000000);
UPDATE vip_world_runs SET stopped_at=1793500000000,stop_reason='idle' WHERE world_id='00000000-0000-0000-0000-000000000002' AND stopped_at IS NULL;

DELIMITER //
CREATE PROCEDURE vip_store_strict_check()
BEGIN
  IF (SELECT COUNT(*) FROM vip_worlds w JOIN vip_world_runs r ON r.world_id=w.id
      WHERE w.id='00000000-0000-0000-0000-000000000002'
        AND w.created_at=1793500000000 AND w.last_started_at=1793500000000
        AND r.started_at=1793500000000 AND r.stopped_at=1793500000000
        AND ((r.slot='vipworld-1' AND r.stop_reason='owner') OR (r.slot='vipworld-2' AND r.stop_reason='idle'))) <> 2 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VipStore epoch values do not match';
  END IF;
END//
DELIMITER ;
CALL vip_store_strict_check();
DROP PROCEDURE vip_store_strict_check;
SELECT 'OK';
