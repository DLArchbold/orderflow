-- Phase 2: copy to services/order-service/src/main/resources/db/migration/ when you reach the messaging steps.
CREATE TABLE idempotency_keys (
    key        TEXT PRIMARY KEY,
    order_id   UUID        NOT NULL REFERENCES orders(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox (
    id           UUID PRIMARY KEY,
    aggregate_id TEXT        NOT NULL,
    type         TEXT        NOT NULL,
    payload      TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
