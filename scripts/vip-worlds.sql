-- VIP-Welten: als craft_admin ausfuehren, idempotent.
-- Die Lobby nutzt den Standard-User craftcore (compose.yaml / ServerProfile); dieser hat bereits volle Rechte auf craftcore.*.

CREATE TABLE IF NOT EXISTS craftcore.vip_worlds (
  id CHAR(36) PRIMARY KEY,
  owner CHAR(36) NOT NULL,
  name VARCHAR(24) NOT NULL,
  type VARCHAR(16) NOT NULL,
  seed VARCHAR(32) NULL,
  status ENUM('stopped','queued','starting','running','stopping','error','deleted') NOT NULL DEFAULT 'stopped',
  visibility ENUM('private','public') NOT NULL DEFAULT 'private',
  slot VARCHAR(16) NULL,
  size_bytes BIGINT NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL,
  last_started_at BIGINT NULL,
  deleted_at BIGINT NULL,
  UNIQUE KEY (owner, name), KEY (status)
);
CREATE TABLE IF NOT EXISTS craftcore.vip_world_members (
  world_id CHAR(36) NOT NULL,
  player CHAR(36) NOT NULL,
  role ENUM('member','builder','trusted') NOT NULL,
  added_at BIGINT NOT NULL,
  PRIMARY KEY (world_id, player),
  FOREIGN KEY (world_id) REFERENCES craftcore.vip_worlds(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS craftcore.vip_world_runs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  world_id CHAR(36) NOT NULL,
  slot VARCHAR(16) NOT NULL,
  started_at BIGINT NOT NULL,
  stopped_at BIGINT NULL,
  stop_reason VARCHAR(32) NULL,
  FOREIGN KEY (world_id) REFERENCES craftcore.vip_worlds(id)
);
CREATE TABLE IF NOT EXISTS craftcore.vip_world_queue (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  world_id CHAR(36) NOT NULL,
  player CHAR(36) NOT NULL,
  priority INT NOT NULL,
  queued_at BIGINT NOT NULL,
  status ENUM('waiting','started','cancelled') NOT NULL,
  KEY (status, priority, queued_at)
);
CREATE TABLE IF NOT EXISTS craftcore.vip_world_addons (
  world_id CHAR(36) NOT NULL,
  addon VARCHAR(32) NOT NULL,
  added_at BIGINT NOT NULL,
  PRIMARY KEY (world_id, addon),
  FOREIGN KEY (world_id) REFERENCES craftcore.vip_worlds(id) ON DELETE CASCADE
);

CREATE OR REPLACE SQL SECURITY DEFINER VIEW craftcore.v_vip_worlds AS
SELECT w.id, w.owner, p.name AS owner_name, w.name, w.status, w.visibility, w.slot, w.last_started_at
FROM craftcore.vip_worlds w
LEFT JOIN craftcore.known_players p ON p.uuid = w.owner
WHERE w.status <> 'deleted';

GRANT SELECT ON craftcore.v_vip_worlds TO 'creepy_ro'@'%';
