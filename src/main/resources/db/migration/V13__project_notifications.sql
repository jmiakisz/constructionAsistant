CREATE TABLE project_notifications (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    conversation_id BIGINT REFERENCES conversations(id) ON DELETE SET NULL,
    sender_user_id BIGINT NOT NULL REFERENCES users(id),
    question TEXT NOT NULL,
    admin_response TEXT,
    answered_by_user_id BIGINT REFERENCES users(id),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    answered_at TIMESTAMP
);
CREATE INDEX ON project_notifications(project_id, status);
