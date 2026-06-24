CREATE TABLE document_folders (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    parent_id  BIGINT REFERENCES document_folders(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

ALTER TABLE documents ADD COLUMN folder_id BIGINT REFERENCES document_folders(id) ON DELETE SET NULL;
