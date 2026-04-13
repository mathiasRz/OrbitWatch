-- Migration V1 : création de la table conjunction_alert

CREATE TABLE IF NOT EXISTS conjunction_alert (
    id           BIGSERIAL    PRIMARY KEY,
    name_sat1    VARCHAR(255) NOT NULL,
    name_sat2    VARCHAR(255) NOT NULL,
    tca          TIMESTAMPTZ  NOT NULL,
    distance_km  DOUBLE PRECISION NOT NULL,
    lat1         DOUBLE PRECISION,
    lon1         DOUBLE PRECISION,
    alt1         DOUBLE PRECISION,
    lat2         DOUBLE PRECISION,
    lon2         DOUBLE PRECISION,
    alt2         DOUBLE PRECISION,
    detected_at  TIMESTAMPTZ  NOT NULL,
    acknowledged BOOLEAN      NOT NULL DEFAULT FALSE
);

-- Index composite pour la déduplication (requête existsByNameSat1AndNameSat2AndTcaBetween)
CREATE INDEX IF NOT EXISTS idx_conjunction_dedup
    ON conjunction_alert (name_sat1, name_sat2, tca);

