-- Carry the trace and correlation id of the consumed record across the outbox (PLAN step 21c), same as order-service V6.
ALTER TABLE outbox ADD COLUMN trace_context  TEXT;
ALTER TABLE outbox ADD COLUMN correlation_id TEXT;
