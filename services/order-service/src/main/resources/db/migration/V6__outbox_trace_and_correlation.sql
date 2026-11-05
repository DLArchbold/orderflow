-- Carry the request's trace and correlation id across the outbox: the publisher runs later on a scheduler thread
-- with no current span, so without these two columns the trace would stop at the database (PLAN step 21c).
ALTER TABLE outbox ADD COLUMN trace_context  TEXT;   -- W3C traceparent of the request that wrote the row
ALTER TABLE outbox ADD COLUMN correlation_id TEXT;
