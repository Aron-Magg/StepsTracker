CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    disabled_at TIMESTAMPTZ
);

CREATE TABLE user_profiles (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    weight_kg NUMERIC(5,2) NOT NULL CHECK (weight_kg BETWEEN 20 AND 400),
    height_cm NUMERIC(5,2) NOT NULL CHECK (height_cm BETWEEN 80 AND 250),
    birth_date DATE NOT NULL,
    sex VARCHAR(16) NOT NULL CHECK (sex IN ('FEMALE', 'MALE', 'OTHER')),
    timezone VARCHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE devices (
    id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform VARCHAR(32) NOT NULL,
    model VARCHAR(128) NOT NULL,
    last_sync_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, user_id)
);

CREATE TABLE step_intervals (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id UUID NOT NULL,
    source VARCHAR(32) NOT NULL CHECK (source IN ('HEALTH_CONNECT', 'STEP_COUNTER')),
    interval_start TIMESTAMPTZ NOT NULL,
    interval_end TIMESTAMPTZ NOT NULL,
    steps INTEGER NOT NULL CHECK (steps BETWEEN 0 AND 100000),
    distance_m_estimated NUMERIC(12,3) NOT NULL CHECK (distance_m_estimated >= 0),
    calories_kcal_estimated NUMERIC(12,3) NOT NULL CHECK (calories_kcal_estimated >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (device_id, user_id) REFERENCES devices(id, user_id) ON DELETE CASCADE,
    CONSTRAINT interval_15_minutes CHECK (interval_end = interval_start + INTERVAL '15 minutes'),
    CONSTRAINT interval_aligned CHECK (date_part('minute', interval_start)::int % 15 = 0 AND date_part('second', interval_start) = 0),
    UNIQUE (user_id, device_id, source, interval_start)
);
CREATE INDEX step_intervals_user_time_idx ON step_intervals(user_id, interval_start);

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens(user_id);

