-- ── Migration V5 : activation PgVector + table vector_store ──────────────────
-- Nécessite les droits SUPERUSER ou CREATE EXTENSION (PostgreSQL 15+) sur le user orbitwatch.

CREATE EXTENSION IF NOT EXISTS vector;

-- Table attendue par le starter Spring AI PgVector
-- Dimension 768 : compatible Ollama nomic-embed-text (dev) ET OpenAI text-embedding-3-small (prod)
CREATE TABLE IF NOT EXISTS vector_store (
    id        UUID    DEFAULT gen_random_uuid() PRIMARY KEY,
    content   TEXT,
    metadata  JSONB,
    embedding vector(768)
);

-- Index HNSW pour la recherche de similarité cosinus (O(log n))
CREATE INDEX IF NOT EXISTS idx_vector_store_embedding
    ON vector_store USING hnsw (embedding vector_cosine_ops);

