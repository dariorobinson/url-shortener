-- V2: click_event, one row per counted redirect (FR-5).
-- D8: the click timestamp only. There is no column for an IP address, user agent or referrer.
-- D45: the application always supplies clicked_at from its injected Clock, truncated to microseconds.
-- The DEFAULT now() serves raw SQL inserts only, as for short_url's timestamps in V1.
-- fk_click_event_short_url: every click belongs to an existing short_url row. Links are only ever
-- soft-deleted (D1), so the default NO ACTION rule never has to cascade and a hard delete of a
-- clicked link is refused.
-- ix_click_event_short_url_id_clicked_at serves the per-link, time-ranged daily stats query (FR-5,
-- D10) and the referencing-side lookups of the foreign key.
-- No CHECK on the encoded length of short_url.original_url is added here (D85).
CREATE TABLE click_event (
  id           BIGSERIAL     PRIMARY KEY,
  short_url_id BIGINT        NOT NULL,
  clicked_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
  CONSTRAINT fk_click_event_short_url FOREIGN KEY (short_url_id) REFERENCES short_url (id)
);

CREATE INDEX ix_click_event_short_url_id_clicked_at ON click_event (short_url_id, clicked_at);
