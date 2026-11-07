-- "My newest orders": equality on customer_id, then created_at in descending order, so Postgres reads the first
-- 50 index entries in order instead of fetching and sorting all of a customer's orders (PLAN step 19, perf.md).
-- A plain CREATE INDEX locks writes while it builds. On a live table use CREATE INDEX CONCURRENTLY, which cannot
-- run inside a transaction, so Flyway would need executeInTransaction=false for that script.
CREATE INDEX idx_orders_customer_created ON orders (customer_id, created_at DESC);
