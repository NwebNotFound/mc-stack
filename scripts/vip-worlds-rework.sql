-- VIP-Welten Rework: Addons pro Welt und die Schreibrechte des VIP-Servers.
-- Als craft_admin ausfuehren, idempotent. Vorher ein Backup der Datenbank machen.

CREATE TABLE IF NOT EXISTS craftcore.vip_world_addons (
  world_id CHAR(36) NOT NULL,
  addon VARCHAR(32) NOT NULL,
  added_at BIGINT NOT NULL,
  PRIMARY KEY (world_id, addon),
  FOREIGN KEY (world_id) REFERENCES craftcore.vip_worlds(id) ON DELETE CASCADE
);

-- "Vertrauen umschalten" schreibt role='trusted'. MODIFY ist idempotent und erweitert
-- auch eine Tabelle, die noch das alte ENUM('member','builder') hat.
ALTER TABLE craftcore.vip_world_members
  MODIFY role ENUM('member','builder','trusted') NOT NULL;

-- Der In-World-Server pflegt Einladungen, Rollen, Addons, Status und Namen selbst.
-- Nur die Spalten, die der In-World-Code wirklich schreibt (Spaltenrechte, kein Tabellenrecht).
GRANT INSERT, DELETE ON craftcore.vip_world_members TO 'craft_vip'@'%';
GRANT UPDATE (role) ON craftcore.vip_world_members TO 'craft_vip'@'%';
GRANT SELECT, INSERT, DELETE ON craftcore.vip_world_addons TO 'craft_vip'@'%';
GRANT UPDATE (name, status, slot, last_started_at) ON craftcore.vip_worlds TO 'craft_vip'@'%';
