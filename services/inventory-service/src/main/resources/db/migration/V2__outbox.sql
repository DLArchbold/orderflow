-- Transactional outbox: result events are written in the same transaction as the stock change.
CREATE TABLE outbox (
    id           UUID PRIMARY KEY,
    aggregate_id TEXT        NOT NULL,
    type         TEXT        NOT NULL,
    payload      TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
