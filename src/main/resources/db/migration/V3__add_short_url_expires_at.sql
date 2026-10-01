-- V3: optional per-link expiration (D106, D107, D112). NULL means the link never expires.
-- Expiry is computed at request time (now >= expires_at, D111); there is no EXPIRED status and no job.
-- ck_short_url_expires_after_created holds the only timeless rule; "strictly in the future" and the
-- 10-year horizon depend on the clock and on configuration, so they are enforced by the application (D107).
ALTER TABLE short_url ADD COLUMN expires_at TIMESTAMPTZ NULL;
ALTER TABLE short_url ADD CONSTRAINT ck_short_url_expires_after_created
  CHECK (expires_at IS NULL OR expires_at > created_at);
