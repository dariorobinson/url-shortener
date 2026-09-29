-- V1: short_url. Constraints are the final integrity guarantee (see docs/architecture.md).
-- ck_short_url_deleted_consistency (D44): a DELETED row must carry both deleted_at and
-- deleted_by, and any other row must carry neither.
-- short_code and original_url are TEXT (D47): VARCHAR(n) silently truncates over-length input
-- whose excess is only trailing spaces, so their length limits are CHECK constraints instead.
CREATE TABLE short_url (
  id               BIGSERIAL PRIMARY KEY,
  short_code       TEXT          NOT NULL,
  original_url     TEXT          NOT NULL,
  custom_alias     BOOLEAN       NOT NULL DEFAULT FALSE,
  status           VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
  click_count      BIGINT        NOT NULL DEFAULT 0,
  last_accessed_at TIMESTAMPTZ   NULL,
  created_by       VARCHAR(100)  NOT NULL,
  created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
  deleted_at       TIMESTAMPTZ   NULL,
  deleted_by       VARCHAR(100)  NULL,
  version          BIGINT        NOT NULL DEFAULT 0,
  CONSTRAINT uk_short_url_short_code UNIQUE (short_code),
  CONSTRAINT ck_short_url_status CHECK (status IN ('ACTIVE','DEACTIVATED','DELETED')),
  CONSTRAINT ck_short_url_click_count CHECK (click_count >= 0),
  CONSTRAINT ck_short_url_code_format CHECK (short_code ~ '^[A-Za-z0-9]{3,32}$'),
  CONSTRAINT ck_short_url_original_url_length CHECK (char_length(original_url) <= 2048),
  CONSTRAINT ck_short_url_deleted_consistency CHECK (
    (status = 'DELETED' AND deleted_at IS NOT NULL AND deleted_by IS NOT NULL)
    OR (status <> 'DELETED' AND deleted_at IS NULL AND deleted_by IS NULL))
);
