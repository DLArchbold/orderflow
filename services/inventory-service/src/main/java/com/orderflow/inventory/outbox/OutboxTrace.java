package com.orderflow.inventory.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Copy of order-service's OutboxTrace (ADR 0002): saves the current trace as a W3C traceparent when the outbox row
 * is written and continues it when the row is published later on a scheduler thread.
 */
@Component
public class OutboxTrace {
    private final Tracer tracer;
    private final Propagator propagator;

    public OutboxTrace(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** The traceparent of the current span, or null when there is none. */
    public String capture() {
        Span current = tracer.currentSpan();
        if (current == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(current.context(), carrier, Map::put);
        return carrier.get("traceparent");
    }

    /** Starts a span that is a child of the saved trace (or a new trace when nothing was saved). */
    public Span startChild(String traceparent, String name) {
        if (traceparent == null) {
            return tracer.nextSpan().name(name).start();
        }
        Map<String, String> carrier = Map.of("traceparent", traceparent);
        return propagator.extract(carrier, Map<String, String>::get).name(name).start();
    }

    public Tracer.SpanInScope inScope(Span span) {
        return tracer.withSpan(span);
    }
}
