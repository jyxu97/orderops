package com.orderops.realtime;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks live WebSocket sessions as a gauge.
 *
 * <p>Active connections are the capacity signal for the real-time layer — the simple broker
 * holds every subscription in this instance's heap, so connection count is what determines
 * whether the API needs scaling out. Exposed via
 * {@code /actuator/metrics/realtime.connections.active}.
 */
@Slf4j
@Component
public class WebSocketConnectionMetrics {

    private final AtomicInteger activeConnections = new AtomicInteger();

    public WebSocketConnectionMetrics(MeterRegistry meterRegistry) {
        meterRegistry.gauge("realtime.connections.active", activeConnections);
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        int active = activeConnections.incrementAndGet();
        log.debug("WebSocket session connected, active={}", active);
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        // Guard against going negative: a disconnect can arrive for a session whose connect
        // event this instance never saw, and a negative gauge is worse than a stale one.
        int active = activeConnections.updateAndGet(current -> Math.max(0, current - 1));
        log.debug("WebSocket session disconnected, active={}", active);
    }

    public int activeConnections() {
        return activeConnections.get();
    }
}
