CREATE TABLE run_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','PAUSED','COMPLETED')),
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    active_duration_ms BIGINT NOT NULL DEFAULT 0 CHECK (active_duration_ms >= 0),
    distance_meters NUMERIC(12,3) NOT NULL DEFAULT 0 CHECK (distance_meters >= 0),
    average_speed_mps NUMERIC(10,4) NOT NULL DEFAULT 0 CHECK (average_speed_mps >= 0),
    average_pace_seconds_per_km NUMERIC(12,3),
    calories_kcal NUMERIC(12,3) NOT NULL DEFAULT 0 CHECK (calories_kcal >= 0),
    weight_kg_at_start NUMERIC(5,2) NOT NULL,
    last_point_sequence INTEGER NOT NULL DEFAULT -1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (device_id,user_id) REFERENCES devices(id,user_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX run_sessions_one_active_user_idx ON run_sessions(user_id) WHERE status IN ('ACTIVE','PAUSED');
CREATE INDEX run_sessions_user_started_idx ON run_sessions(user_id,started_at DESC);

CREATE TABLE run_points (
    session_id UUID NOT NULL REFERENCES run_sessions(id) ON DELETE CASCADE,
    sequence INTEGER NOT NULL CHECK (sequence >= 0),
    recorded_at TIMESTAMPTZ NOT NULL,
    latitude DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    altitude_meters DOUBLE PRECISION,
    accuracy_meters REAL NOT NULL CHECK (accuracy_meters BETWEEN 0 AND 50),
    speed_mps REAL CHECK (speed_mps BETWEEN 0 AND 12),
    bearing_degrees REAL CHECK (bearing_degrees >= 0 AND bearing_degrees < 360),
    PRIMARY KEY(session_id,sequence)
);
CREATE INDEX run_points_session_time_idx ON run_points(session_id,recorded_at);

CREATE TABLE run_pauses (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES run_sessions(id) ON DELETE CASCADE,
    paused_at TIMESTAMPTZ NOT NULL,
    resumed_at TIMESTAMPTZ,
    UNIQUE(session_id,paused_at)
);
