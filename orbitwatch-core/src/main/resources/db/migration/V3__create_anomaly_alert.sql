-- Migration V3 : table anomaly_alert
-- Stocke les anomalies orbitales détectées par AnomalyDetectionService
-- (Phase 1 : règles métier / Phase 2 : Z-score glissant)

CREATE TABLE IF NOT EXISTS anomaly_alert (
    id             BIGSERIAL PRIMARY KEY,
    norad_id       INTEGER                     NOT NULL,
    satellite_name VARCHAR(120),
    detected_at    TIMESTAMP WITH TIME ZONE    NOT NULL,
    type           VARCHAR(30)                 NOT NULL,
    severity       VARCHAR(10)                 NOT NULL,
    description    TEXT,
    acknowledged   BOOLEAN                     NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_anomaly_norad_type
    ON anomaly_alert (norad_id, type, detected_at DESC);

