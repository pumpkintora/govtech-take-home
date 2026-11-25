-- PostgreSQL schema for 1-to-1 real-time messaging.
-- Designed to be idempotent and safe to execute under a pg_advisory_lock on startup.

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS sessions (
    -- Store SHA-256 hash of the opaque cookie token rather than plaintext so a read-only
    -- DB snapshot or log leak cannot be replayed directly to hijack active sessions.
    token_hash CHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON sessions (user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_expires_at ON sessions (expires_at);

CREATE TABLE IF NOT EXISTS conversations (
    id UUID PRIMARY KEY,
    -- Canonical participant ordering (user_a_id < user_b_id) combined with the UNIQUE
    -- constraint eliminates the race condition where Alice and Bob simultaneously open
    -- a DM with each other and create two separate 1-to-1 threads.
    user_a_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_b_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_canonical_pair CHECK (user_a_id < user_b_id),
    CONSTRAINT uq_conversation_pair UNIQUE (user_a_id, user_b_id)
);

CREATE INDEX IF NOT EXISTS idx_conversations_user_a ON conversations (user_a_id);
CREATE INDEX IF NOT EXISTS idx_conversations_user_b ON conversations (user_b_id);

CREATE TABLE IF NOT EXISTS messages (
    -- Strictly monotonic server-assigned sequence used for deterministic ordering and
    -- cursor-based gap recovery (?afterSeq=...). Never rely on client wall-clock timestamps
    -- which suffer from mobile/desktop clock skew.
    seq BIGSERIAL PRIMARY KEY,
    id UUID NOT NULL UNIQUE,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    recipient_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- Client-generated idempotency key. If a mobile client loses connectivity right after
    -- the DB commit but before receiving the WS ACK, retrying the send hits this unique
    -- constraint and returns the original message instead of creating a duplicate.
    client_msg_id UUID NOT NULL,
    body TEXT NOT NULL CHECK (char_length(body) BETWEEN 1 AND 4000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delivered_at TIMESTAMPTZ,
    read_at TIMESTAMPTZ,
    CONSTRAINT uq_sender_client_msg UNIQUE (sender_id, client_msg_id)
);

CREATE INDEX IF NOT EXISTS idx_messages_conv_seq ON messages (conversation_id, seq DESC);
CREATE INDEX IF NOT EXISTS idx_messages_recipient_seq ON messages (recipient_id, seq);
CREATE INDEX IF NOT EXISTS idx_messages_sender_seq ON messages (sender_id, seq);
CREATE INDEX IF NOT EXISTS idx_messages_unread ON messages (recipient_id, conversation_id) WHERE read_at IS NULL;

-- Tracks active WebSocket connections across ALL backend nodes so online presence stays
-- accurate when a user holds multiple tabs/devices across different replicas (e.g. phone
-- on node-1 and desktop on node-2). Closing one tab won't falsely mark the user offline.
CREATE TABLE IF NOT EXISTS active_connections (
    connection_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    node_id VARCHAR(64) NOT NULL,
    last_heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_active_connections_user ON active_connections (user_id, last_heartbeat_at);
CREATE INDEX IF NOT EXISTS idx_active_connections_node ON active_connections (node_id);
