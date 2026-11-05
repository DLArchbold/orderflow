package com.orderflow.order.stream;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * PLAN step 20a: keeps the open SSE connections per user subject and removes them on completion, timeout and error.
 * Known limit: with two order-service instances only the one that consumed the result has the emitter
 * (a Redis pub/sub fan-out would fix that).
 */
@Component
public class SseHub {
    private static final Logger log = LoggerFactory.getLogger(SseHub.class);

    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter register(String subject) {
        SseEmitter emitter = new SseEmitter(0L);   // no server-side timeout; the heartbeat finds dead clients
        List<SseEmitter> mine = emitters.computeIfAbsent(subject, s -> new CopyOnWriteArrayList<>());
        mine.add(emitter);
        Runnable remove = () -> mine.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    public void send(String subject, Object data) {
        for (SseEmitter emitter : emitters.getOrDefault(subject, List.of())) {
            try {
                emitter.send(SseEmitter.event().data(data, org.springframework.http.MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);   // the client went away
            }
        }
    }

    /** A comment line every 15 s: notices dead clients and stops proxies from closing an idle connection. */
    @Scheduled(fixedRate = 15_000)
    void heartbeat() {
        emitters.values().forEach(list -> list.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException e) {
                log.debug("Dropping a dead SSE connection");
                emitter.completeWithError(e);
            }
        }));
    }
}
