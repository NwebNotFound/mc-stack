-- VIP-Welten Rework: Addons pro Welt und die Schreibrechte des VIP-Servers.
-- Als craft_admin ausfuehren, idempotent. Vorher ein Backup der Datenbank machen.

CREATE TABLE IF NOT EXISTS craftcore.vip_world_addons (
  world_id CHAR(36) NOT NULL,
  addon VARCHAR(32) NOT NULL,
  added_at BIGINT NOT NULL,
  PRIMARY KEY (world_id, addon),
  FOREIGN KEY (world_id) REFERENCES craftcore.vip_worlds(id) ON DELETE CASCADE
);

-- Der In-World-Server pflegt Einladungen und Addons selbst.
GRANT INSERT, DELETE ON craftcore.vip_world_members TO 'craft_vip'@'%';
GRANT SELECT, INSERT, DELETE ON craftcore.vip_world_addons TO 'craft_vip'@'%';
FLUSH PRIVILEGES;
