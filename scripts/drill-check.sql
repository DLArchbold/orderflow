-- PLAN step 26a: the numbers behind "lost" and "duplicated". One query set per database.
-- Run it with scripts/drill_check.py (compares the two databases and prints PASS/FAIL), or by hand:
--   docker compose -f phase2/infra/docker-compose.full.yml exec -T postgres psql -U orderflow -d orders    < (the "orders" block)
-- Definitions are in phase2/docs/resilience.md. Block markers (-- db:, -- name:) are read by drill_check.py.

-- db: orders
-- name: orders_total
SELECT count(*) FROM orders;

-- name: orders_by_status
SELECT status, count(*) FROM orders GROUP BY status ORDER BY status;

-- name: orders_still_pending
SELECT count(*) FROM orders WHERE status = 'PENDING';

-- name: orders_outbox_unpublished
SELECT count(*) FROM outbox WHERE published_at IS NULL;

-- name: orders_with_more_than_one_created_event
SELECT count(*) FROM (SELECT aggregate_id FROM outbox WHERE type = 'OrderCreated' GROUP BY aggregate_id HAVING count(*) > 1) d;

-- name: confirmed_quantity_by_sku
SELECT l.sku, coalesce(sum(l.quantity), 0) AS qty
FROM order_lines l JOIN orders o ON o.id = l.order_id
WHERE o.status = 'CONFIRMED' GROUP BY l.sku ORDER BY l.sku;

-- db: inventory
-- name: processed_messages
SELECT count(*) FROM processed_messages;

-- name: inventory_outbox_unpublished
SELECT count(*) FROM outbox WHERE published_at IS NULL;

-- name: orders_with_more_than_one_result
SELECT count(*) FROM (SELECT aggregate_id FROM outbox GROUP BY aggregate_id HAVING count(*) > 1) d;

-- name: stock_available_plus_reserved
SELECT sku, available + reserved AS total FROM stock ORDER BY sku;

-- name: reserved_quantity_by_sku
SELECT sku, reserved AS qty FROM stock ORDER BY sku;

-- name: stock_negative
SELECT count(*) FROM stock WHERE available < 0 OR reserved < 0;
