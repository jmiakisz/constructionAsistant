-- Knowledge entries: temporal support
ALTER TABLE knowledge_entries ADD COLUMN entry_type VARCHAR(20) NOT NULL DEFAULT 'PERMANENT';
ALTER TABLE knowledge_entries ADD COLUMN valid_until TIMESTAMP;

-- Users: communication profile
ALTER TABLE users ADD COLUMN communication_style TEXT;
ALTER TABLE users ADD COLUMN formality_level VARCHAR(20) DEFAULT 'NEUTRAL';

-- Raw style observations (one per assistant response)
CREATE TABLE user_style_observations (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    observation TEXT   NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_user_style_obs_user ON user_style_observations(user_id, created_at DESC);

-- Messages: knowledge category
ALTER TABLE messages ADD COLUMN knowledge_category VARCHAR(50);
