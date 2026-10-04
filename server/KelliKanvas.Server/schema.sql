-- Dedicated database/role on the existing PostgreSQL instance. No other app tables are altered.
CREATE SCHEMA IF NOT EXISTS kanvas;
CREATE TABLE IF NOT EXISTS kanvas.users (
    id text PRIMARY KEY, email text NOT NULL, name text NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS kanvas.account_state (
    user_id text PRIMARY KEY REFERENCES kanvas.users(id) ON DELETE CASCADE,
    revision bigint NOT NULL DEFAULT 0, settings jsonb NOT NULL DEFAULT '{}'
);
CREATE TABLE IF NOT EXISTS kanvas.devices (
    id uuid PRIMARY KEY, user_id text NOT NULL REFERENCES kanvas.users(id) ON DELETE CASCADE,
    name text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), revoked_at timestamptz
);
CREATE TABLE IF NOT EXISTS kanvas.sessions (
    token_hash text PRIMARY KEY, user_id text NOT NULL REFERENCES kanvas.users(id) ON DELETE CASCADE,
    kind text NOT NULL CHECK (kind IN ('browser','device')), device_id uuid REFERENCES kanvas.devices(id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_user ON kanvas.sessions(user_id);
CREATE TABLE IF NOT EXISTS kanvas.pairings (
    id uuid PRIMARY KEY, secret_hash text NOT NULL, user_code text NOT NULL UNIQUE, name text NOT NULL,
    expires_at timestamptz NOT NULL, user_id text REFERENCES kanvas.users(id) ON DELETE CASCADE,
    device_id uuid REFERENCES kanvas.devices(id) ON DELETE CASCADE, delivered boolean NOT NULL DEFAULT false
);
CREATE TABLE IF NOT EXISTS kanvas.connections (
    user_id text NOT NULL REFERENCES kanvas.users(id) ON DELETE CASCADE, id text NOT NULL,
    provider text NOT NULL, name text NOT NULL, encrypted_payload bytea NOT NULL,
    PRIMARY KEY (user_id,id)
);
ALTER TABLE kanvas.devices ADD COLUMN IF NOT EXISTS last_seen_at timestamptz;
CREATE TABLE IF NOT EXISTS kanvas.browse_requests (
    id uuid PRIMARY KEY, user_id text NOT NULL, device_id uuid NOT NULL REFERENCES kanvas.devices(id) ON DELETE CASCADE,
    connection_id text NOT NULL, encrypted_request bytea NOT NULL, encrypted_result bytea,
    status text NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','working','complete')),
    expires_at timestamptz NOT NULL, claimed_at timestamptz,
    FOREIGN KEY(user_id,connection_id) REFERENCES kanvas.connections(user_id,id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS browse_device_queue ON kanvas.browse_requests(device_id,status,expires_at);
