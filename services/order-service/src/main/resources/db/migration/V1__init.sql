CREATE TABLE orders (
    id          UUID PRIMARY KEY,
    customer_id TEXT        NOT NULL,
    status      TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE order_lines (
    order_id UUID    NOT NULL REFERENCES orders(id),
    line_no  INT     NOT NULL,
    sku      TEXT    NOT NULL,
    quantity INT     NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (order_id, line_no)
);
CREATE INDEX idx_orders_customer_status ON orders (customer_id, status);
