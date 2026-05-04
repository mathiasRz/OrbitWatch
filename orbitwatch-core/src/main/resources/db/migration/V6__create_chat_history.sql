-- ── Migration V6 : table chat_history (mémoire conversationnelle agent IA) ──────────────────
-- Stocke les échanges de chaque session de chat pour la mémoire persistante Spring AI.

CREATE TABLE chat_history (
    id         BIGSERIAL    PRIMARY KEY,
    session_id VARCHAR(36)  NOT NULL,
    role       VARCHAR(20)  NOT NULL,    -- 'USER' | 'ASSISTANT' | 'SYSTEM'
    content    TEXT         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Index composite sur (session_id, id) : filtre par session + tri par ordre
-- d'insertion (id auto-increment) — plus fiable que created_at en cas de
-- résolution temporelle insuffisante.
CREATE INDEX idx_chat_history_session ON chat_history (session_id, id);

