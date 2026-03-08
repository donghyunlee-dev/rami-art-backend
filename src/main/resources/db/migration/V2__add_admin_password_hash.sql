ALTER TABLE rami_art_studio.admin_users
    ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);
