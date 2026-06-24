CREATE TABLE project_briefings (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    role_name VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    generated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_project_briefing UNIQUE (project_id, role_name)
);
