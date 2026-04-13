-- Migration V4 : ajout des colonnes norad_id1 / norad_id2 dans conjunction_alert
-- et migration de l'index de déduplication vers les NORAD IDs (plus fiables que les noms)

ALTER TABLE conjunction_alert
    ADD COLUMN IF NOT EXISTS norad_id1 INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS norad_id2 INTEGER NOT NULL DEFAULT 0;

-- Suppression de l'ancien index basé sur les noms
DROP INDEX IF EXISTS idx_conjunction_dedup;

-- Nouvel index composite sur NORAD ID + TCA pour la déduplication
CREATE INDEX IF NOT EXISTS idx_conjunction_dedup
    ON conjunction_alert (norad_id1, norad_id2, tca);

