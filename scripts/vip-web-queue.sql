-- Web-Queue fuer die VIP-Welten (Vertrag docs/vip-api.md, Website-PR #67).
-- Als craft_admin ausfuehren, idempotent. Vorher ein Backup machen.

-- Die sechs einfachen Weltregeln, die das Panel setzt. NULL = Vertrags-Defaults.
ALTER TABLE craftcore.vip_worlds
  ADD COLUMN IF NOT EXISTS settings JSON NULL;

-- Entscheidungen zu Queue-Auftraegen: (discord_id, request_id) ist der Schluessel.
-- Ein erneuter Poll derselben Kennung liefert die gespeicherte Antwort und fuehrt nichts aus.
CREATE TABLE IF NOT EXISTS craftcore.vip_command_acks (
  discord_id VARCHAR(20) NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  status ENUM('done','denied','failed','expired') NOT NULL,
  reason VARCHAR(32) NOT NULL DEFAULT '',
  created_at BIGINT NOT NULL,
  PRIMARY KEY (discord_id, request_id),
  KEY created (created_at)
);

-- Admin-Eingriffe auf fremden Welten (Vertrag: gleiche Transaktion wie die Aenderung).
CREATE TABLE IF NOT EXISTS craftcore.vip_world_audit (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  world_id CHAR(36) NOT NULL,
  action VARCHAR(24) NOT NULL,
  admin_uuid CHAR(36) NOT NULL,
  admin_name VARCHAR(16) NULL,
  owner_uuid CHAR(36) NOT NULL,
  owner_name VARCHAR(16) NULL,
  detail VARCHAR(64) NULL,
  created_at BIGINT NOT NULL,
  KEY world (world_id, created_at)
);

GRANT INSERT, UPDATE, SELECT ON craftcore.vip_command_acks TO 'craft_vip'@'%';
GRANT INSERT ON craftcore.vip_world_audit TO 'craft_vip'@'%';
GRANT SELECT (id, owner, name, type, status, slot, visibility, settings) ON craftcore.vip_worlds TO 'craft_vip'@'%';
GRANT UPDATE (settings) ON craftcore.vip_worlds TO 'craft_vip'@'%';
