-- Migration V2 : création de la table orbital_history
-- Utilisée en prod (PostgreSQL) — en dev/test H2 la table est créée par ddl-auto=create-drop

CREATE TABLE IF NOT EXISTS orbital_history (
    id                  BIGSERIAL        PRIMARY KEY,
    norad_id            INTEGER          NOT NULL,
    satellite_name      VARCHAR(120),
    fetched_at          TIMESTAMPTZ      NOT NULL,
    semi_major_axis_km  DOUBLE PRECISION,
    eccentricity        DOUBLE PRECISION,
    inclination_deg     DOUBLE PRECISION,
    raan_deg            DOUBLE PRECISION,
    arg_of_perigee_deg  DOUBLE PRECISION,
    mean_motion_rev_day DOUBLE PRECISION,
    altitude_perigee_km DOUBLE PRECISION,
    altitude_apogee_km  DOUBLE PRECISION
);

-- Index composite pour les requêtes par satellite + plage temporelle
-- Couvre : findByNoradIdOrderByFetchedAtDesc, findByNoradIdAndFetchedAtBetween, countByNoradId
CREATE INDEX IF NOT EXISTS idx_orbital_history_norad_time
    ON orbital_history (norad_id, fetched_at DESC);

