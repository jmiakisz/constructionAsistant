CREATE TABLE project_alerts (
    id          BIGSERIAL    PRIMARY KEY,
    project_id  BIGINT       NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    document_id BIGINT       REFERENCES documents(id) ON DELETE SET NULL,
    level       VARCHAR(20)  NOT NULL,
    message     TEXT         NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_project_alerts_project ON project_alerts(project_id);
