-- pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Users
CREATE TABLE users (
    id         BIGSERIAL PRIMARY KEY,
    email      VARCHAR(255) NOT NULL UNIQUE,
    password   VARCHAR(255) NOT NULL,
    name       VARCHAR(255) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Projects
CREATE TABLE projects (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by  BIGINT       REFERENCES users(id) ON DELETE SET NULL
);

-- Project members with roles
CREATE TABLE project_members (
    project_id BIGINT      NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users(id)    ON DELETE CASCADE,
    role       VARCHAR(50) NOT NULL,
    PRIMARY KEY (project_id, user_id)
);

-- Documents
CREATE TABLE documents (
    id                BIGSERIAL    PRIMARY KEY,
    project_id        BIGINT       NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name              VARCHAR(255) NOT NULL,
    file_path         VARCHAR(500),
    visible_for_roles TEXT[]       NOT NULL DEFAULT '{}',
    status            VARCHAR(50)  NOT NULL DEFAULT 'PROCESSING',
    uploaded_by       BIGINT       REFERENCES users(id) ON DELETE SET NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    extracted_data    JSONB
);

-- Document chunks with vector embeddings
CREATE TABLE document_chunks (
    id          BIGSERIAL PRIMARY KEY,
    document_id BIGINT    NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    content     TEXT      NOT NULL,
    embedding   vector(384),
    page_number INTEGER,
    chunk_index INTEGER   NOT NULL
);

CREATE INDEX idx_document_chunks_embedding ON document_chunks USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- Conversations (per user per project)
CREATE TABLE conversations (
    id         BIGSERIAL PRIMARY KEY,
    project_id BIGINT    NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id    BIGINT    NOT NULL REFERENCES users(id)    ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Messages
CREATE TABLE messages (
    id                      BIGSERIAL    PRIMARY KEY,
    conversation_id         BIGINT       NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role                    VARCHAR(20)  NOT NULL,
    content                 TEXT         NOT NULL,
    tokens_used             INTEGER,
    processed_for_knowledge BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_messages_unprocessed ON messages(processed_for_knowledge) WHERE processed_for_knowledge = FALSE;

-- Project memory (per project per role, skumulowana wiedza)
CREATE TABLE project_memory (
    id         BIGSERIAL    PRIMARY KEY,
    project_id BIGINT       NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    role       VARCHAR(50)  NOT NULL,
    content    TEXT,
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (project_id, role)
);

-- Knowledge entries (wiedza firmowa cross-project)
CREATE TABLE knowledge_entries (
    id                BIGSERIAL    PRIMARY KEY,
    project_id        BIGINT       REFERENCES projects(id) ON DELETE SET NULL,
    content           VARCHAR(500) NOT NULL,
    embedding         vector(384),
    source_role       VARCHAR(50),
    category          VARCHAR(50),
    confidence        INTEGER      NOT NULL DEFAULT 1,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    last_confirmed_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_knowledge_entries_embedding ON knowledge_entries USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
