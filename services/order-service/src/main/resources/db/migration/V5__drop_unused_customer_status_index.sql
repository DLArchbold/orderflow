-- idx_orders_customer_status (customer_id, status) is no longer chosen for any query the application runs:
-- OrderRepository filters by customer_id and sorts by created_at, which idx_orders_customer_created serves.
-- Dropping it makes every insert and status change a little cheaper (PLAN step 19c, perf.md).
DROP INDEX idx_orders_customer_status;
