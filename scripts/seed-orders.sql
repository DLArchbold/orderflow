-- PLAN step 19a: load about 3 million orders (1 to 3 lines each) into a COPY of the orders database.
--
-- Run (order-service must be stopped, CREATE DATABASE ... TEMPLATE needs no open connections to "orders"):
--   docker compose -f phase2/infra/docker-compose.full.yml exec -T postgres psql -U orderflow -d postgres -c "CREATE DATABASE orders_perf TEMPLATE orders;"
--   docker compose -f phase2/infra/docker-compose.full.yml exec -T postgres psql -U orderflow -d orders_perf [-v rows=300000] < scripts/seed-orders.sql
--
-- Data shape: customer_id from 20,000 values plus 'alice' and 'bob' (about 1% of the orders each, so they have
-- long histories), status about 90% CONFIRMED / 5% REJECTED / 3% CANCELLED / 2% PENDING, created_at spread over
-- the last two years.

-- number of orders; override with: psql -v rows=300000 ... (default: 3 million)
\if :{?rows}
\else
  \set rows 3000000
\endif

SET synchronous_commit = off;

INSERT INTO orders (id, customer_id, status, created_at, updated_at)
SELECT gen_random_uuid(),
       CASE WHEN r.a < 0.01 THEN 'alice'
            WHEN r.a < 0.02 THEN 'bob'
            ELSE 'cust-' || floor(random() * 20000)::int END,
       CASE WHEN r.b < 0.90 THEN 'CONFIRMED'
            WHEN r.b < 0.95 THEN 'REJECTED'
            WHEN r.b < 0.98 THEN 'CANCELLED'
            ELSE 'PENDING' END,
       now() - random() * interval '730 days',
       now()
FROM generate_series(1, :rows) g
CROSS JOIN LATERAL (SELECT random() AS a, random() AS b, g AS keep) r;

INSERT INTO order_lines (order_id, line_no, sku, quantity)
SELECT o.id, n, 'SKU-' || (1 + floor(random() * 2))::int, 1 + floor(random() * 5)::int
FROM orders o
CROSS JOIN LATERAL generate_series(0, abs(hashtext(o.id::text)::bigint) % 3) n
WHERE NOT EXISTS (SELECT 1 FROM order_lines l WHERE l.order_id = o.id);   -- skip orders that already have lines

-- the planner chooses plans from statistics: a stale table gives plans that do not match production
ANALYZE orders;
ANALYZE order_lines;

SELECT count(*) AS orders FROM orders;
SELECT count(*) AS order_lines FROM order_lines;
SELECT status, count(*), round(100.0 * count(*) / sum(count(*)) OVER (), 1) AS pct FROM orders GROUP BY status ORDER BY 2 DESC;
SELECT pg_size_pretty(pg_total_relation_size('orders')) AS orders_total, pg_size_pretty(pg_total_relation_size('order_lines')) AS order_lines_total;
