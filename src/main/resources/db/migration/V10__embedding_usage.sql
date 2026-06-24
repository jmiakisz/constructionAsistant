CREATE TABLE embedding_usage (
    id           BIGSERIAL PRIMARY KEY,
    source       VARCHAR(50) NOT NULL,  -- CHAT_QUERY | DOCUMENT_CHUNK | KNOWLEDGE_ENTRY
    text_length  INT NOT NULL,
    project_id   BIGINT REFERENCES projects(id) ON DELETE SET NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_embedding_usage_created_at ON embedding_usage(created_at);
CREATE INDEX idx_embedding_usage_source ON embedding_usage(source);
