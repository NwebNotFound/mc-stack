USE craftcore;
-- Preis-Stichtag fuer den Ingest (PR craft/prices-base). NICHT automatisch ausfuehren.
-- CraftCore legt price_meta und v_price_meta beim Start selbst an (PriceMeta.ensureSchema) und schreibt
-- effective_from aus prices-base.yml (oberste Ebene, ISO-8601) als UTC hinein. Platzhalter 1970-01-01T00:00:00Z = leere View.
-- Dieses Skript ist fuer den Deploy gedacht (als root/Admin, da GRANT):
CREATE TABLE IF NOT EXISTS price_meta (id TINYINT PRIMARY KEY, effective_from DATETIME NOT NULL, effective_from_epoch BIGINT NOT NULL);
CREATE OR REPLACE VIEW v_price_meta AS SELECT effective_from FROM price_meta WHERE id=1;
GRANT SELECT ON v_price_meta TO 'creepy_ro'@'%';
-- Ingest (bot/, Dex): SELECT effective_from FROM v_price_meta -> als price_reset_at (ISO-8601, UTC) auf oberster Ebene mitsenden.
