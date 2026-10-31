-- Wegwerf-Test fuer scripts/pvp-matchflow.sql (NUR in einem frischen MariaDB-Testcontainer ausfuehren, nie gegen Produktion).
-- Ablauf: setup -> pvp-db-user.sql (Platzhalter-Passwort) -> pvp-matchflow.sql -> dieses Skript; erwartet Ausgabe "OK".
-- A: 4 Siege, 1 Niederlage, 2 Draws (7 Matches) -> winrate 80.0 ; B: 1 Sieg, 4 Niederlagen, 2 Draws -> 20.0 ; C: 1 Draw -> NULL
INSERT INTO craftcore.known_players (uuid,name) VALUES ('a','A'),('b','B'),('c','C');
INSERT INTO craftcore.pvp_assignments (match_id,player_a,player_b,kit_a,kit_b,status,created_at,updated_at) VALUES
 ('m1','a','b','k','k','done',0,0),('m2','a','b','k','k','done',0,0),('m3','a','b','k','k','done',0,0),('m4','a','b','k','k','done',0,0),
 ('m5','a','b','k','k','done',0,0),('m6','a','b','k','k','done',0,0),('m7','a','b','k','k','done',0,0),('m8','a','c','k','k','done',0,0);
INSERT INTO craftcore.pvp_matches (match_id,arena,kit,winner_uuid,loser_uuid,result,started_at,ended_at,duration_ms) VALUES
 ('m1','x','k','a','b','win',0,1,1),('m2','x','k','a','b','win',0,1,1),('m3','x','k','a','b','win',0,1,1),('m4','x','k','a','b','win',0,1,1),
 ('m5','x','k','b','a','win',0,1,1),('m6','x','k',NULL,NULL,'draw',0,1,1),('m7','x','k',NULL,NULL,'draw',0,1,1);
SELECT IF(
  (SELECT CONCAT_WS(',',wins,losses,draws,matches,winrate_pct) FROM craftcore.v_pvp_stats WHERE uuid='a')='4,1,2,7,80.0' AND
  (SELECT CONCAT_WS(',',wins,losses,draws,matches,winrate_pct) FROM craftcore.v_pvp_stats WHERE uuid='b')='1,4,2,7,20.0' AND
  (SELECT COUNT(*) FROM craftcore.v_pvp_matches WHERE result='draw')=2,
  'OK','FAIL') AS pvp_stats_test;