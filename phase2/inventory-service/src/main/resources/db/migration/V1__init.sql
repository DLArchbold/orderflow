CREATE TABLE stock (
    sku       TEXT PRIMARY KEY,
    available INT NOT NULL CHECK (available >= 0),
    reserved  INT NOT NULL DEFAULT 0 CHECK (reserved >= 0)
);

-- Idempotent consumer: a message id may be processed at most once.
CREATE TABLE processed_messages (
    message_id   TEXT PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reservations (
    order_id UUID NOT NULL,
    sku      TEXT NOT NULL REFERENCES stock(sku),
    quantity INT  NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (order_id, sku)
);

INSERT INTO stock (sku, available) VALUES ('SKU-1', 1000000), ('SKU-2', 1000000);
