-- ============================================================
-- OrbitWatch — initialisation complète du schéma PostgreSQL
-- À exécuter UNE SEULE FOIS après avoir créé la base via create_db.sql
--
-- Usage :
--   psql -U orbitwatch -d orbitwatch -f orbitwatch-core/src/main/resources/db/init/init_schema.sql
-- ============================================================

-- ── V1+V4 : conjunction_alert ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS conjunction_alert (
    id           BIGSERIAL    PRIMARY KEY,
    name_sat1    VARCHAR(255) NOT NULL,
    name_sat2    VARCHAR(255) NOT NULL,
    norad_id1    INTEGER      NOT NULL DEFAULT 0,
    norad_id2    INTEGER      NOT NULL DEFAULT 0,
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

-- Index de déduplication basé sur les NORAD IDs (plus fiables que les noms)
CREATE INDEX IF NOT EXISTS idx_conjunction_dedup
    ON conjunction_alert (norad_id1, norad_id2, tca);

-- ── V2 : orbital_history ─────────────────────────────────────────────────────
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

CREATE INDEX IF NOT EXISTS idx_orbital_history_norad_time
    ON orbital_history (norad_id, fetched_at DESC);

-- ── V3 : anomaly_alert ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS anomaly_alert (
    id             BIGSERIAL PRIMARY KEY,
    norad_id       INTEGER                  NOT NULL,
    satellite_name VARCHAR(120),
    detected_at    TIMESTAMPTZ              NOT NULL,
    type           VARCHAR(30)              NOT NULL,
    severity       VARCHAR(10)              NOT NULL,
    description    TEXT,
    acknowledged   BOOLEAN                  NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_anomaly_norad_type
    ON anomaly_alert (norad_id, type, detected_at DESC);

-- ── V5 : vector_store (Spring AI PgVector — RAG) ──────────────────────────────
-- Nécessite l'extension pgvector (PostgreSQL 15+ avec droits CREATE EXTENSION)
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS vector_store (
    id        UUID    DEFAULT gen_random_uuid() PRIMARY KEY,
    content   TEXT,
    metadata  JSONB,
    embedding vector(768)   -- dimension 768 : nomic-embed-text (Ollama) / text-embedding-3-small (OpenAI)
);

-- Index HNSW pour la recherche de similarité cosinus (O(log n))
CREATE INDEX IF NOT EXISTS idx_vector_store_embedding
    ON vector_store USING hnsw (embedding vector_cosine_ops);

