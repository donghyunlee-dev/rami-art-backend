CREATE TABLE IF NOT EXISTS public.admin_auth_rate_limit (
    bucket_hash CHAR(64) PRIMARY KEY,
    attempts INTEGER NOT NULL CHECK (attempts > 0),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_admin_auth_rate_limit_hash
        CHECK (bucket_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX IF NOT EXISTS ix_admin_auth_rate_limit_expiry
    ON public.admin_auth_rate_limit (expires_at);

ALTER TABLE public.admin_auth_rate_limit ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.admin_auth_rate_limit FROM anon, authenticated;
