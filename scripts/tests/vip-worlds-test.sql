-- Wegwerf-Test fuer scripts/vip-worlds.sql (NUR in einem frischen MariaDB-Testcontainer ausfuehren, nie gegen Produktion).
-- Ablauf: setup (craftcore, known_players und User creepy_ro) -> vip-worlds.sql -> dieses Skript; erwartet Ausgabe "OK".
INSERT INTO craftcore.known_players (uuid,name) VALUES ('a','A'),('b','B');
INSERT INTO craftcore.vip_worlds (id,owner,name,type,status,created_at) VALUES
 ('w1','a','Insel','normal','stopped',1),('w2','b','Insel','flat','queued',2),('w3','a','Alt','void','deleted',3);
INSERT INTO craftcore.vip_world_members (world_id,player,role,added_at) VALUES ('w1','b','builder',1);
INSERT INTO craftcore.vip_world_runs (world_id,slot,started_at) VALUES ('w1','vipworld-1',1);
INSERT INTO craftcore.vip_world_queue (world_id,player,priority,queued_at,status) VALUES
 ('w1','a',10,1,'waiting'),('w1','a',20,3,'waiting'),('w2','b',20,2,'waiting'),('w3','a',30,0,'cancelled');
INSERT IGNORE INTO craftcore.vip_worlds (id,owner,name,type,created_at) VALUES ('w4','a','Insel','normal',4);
SET @duplicate_inserted = ROW_COUNT();
SELECT IF(
  @duplicate_inserted=0 AND
  (SELECT COUNT(*) FROM craftcore.vip_worlds WHERE name='Insel')=2 AND
  (SELECT COUNT(*) FROM craftcore.v_vip_worlds)=2 AND
  (SELECT COUNT(*) FROM craftcore.v_vip_worlds WHERE id='w3')=0 AND
  (SELECT owner_name FROM craftcore.v_vip_worlds WHERE id='w1')='A' AND
  (SELECT world_id FROM craftcore.vip_world_queue WHERE status='waiting' ORDER BY priority DESC, queued_at ASC LIMIT 1)='w2',
  'OK','FAIL') AS vip_worlds_test;
