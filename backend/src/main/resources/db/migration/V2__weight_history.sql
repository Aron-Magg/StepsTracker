CREATE TABLE user_weight_history (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    weight_kg NUMERIC(5,2) NOT NULL CHECK (weight_kg BETWEEN 20 AND 400),
    effective_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, effective_at)
);

CREATE INDEX user_weight_history_lookup_idx
    ON user_weight_history(user_id, effective_at DESC);

INSERT INTO user_weight_history(user_id, weight_kg, effective_at)
SELECT p.user_id, p.weight_kg, u.created_at
FROM user_profiles p
JOIN users u ON u.id = p.user_id;

