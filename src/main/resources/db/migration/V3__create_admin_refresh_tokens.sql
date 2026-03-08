CREATE TABLE IF NOT EXISTS rami_art_studio.admin_refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_user_id UUID NOT NULL REFERENCES rami_art_studio.admin_users(id) ON DELETE CASCADE,
    refresh_token VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_admin_refresh_tokens_admin_user_id
    ON rami_art_studio.admin_refresh_tokens(admin_user_id);

CREATE INDEX IF NOT EXISTS idx_admin_refresh_tokens_expires_at
    ON rami_art_studio.admin_refresh_tokens(expires_at);
