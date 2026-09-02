package com.orderops.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderops.shared.event.OrderStatusEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Receives order events from Redis and pushes them to STOMP subscribers on this instance.
 *
 * <p>The counterpart to {@link OrderEventPublisher}: every API instance subscribes to the same
 * Redis channel, so an event published anywhere reaches every connected client regardless of
 * which task produced it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /** Entry point for the Redis listener; {@code payload} is a serialized event. */
    public void onMessage(String payload) {
        OrderStatusEvent event;
        try {
            event = objectMapper.readValue(payload, OrderStatusEvent.class);
        } catch (Exception e) {
            // A malformed payload must not kill the listener — the next event should still land.
            meterRegistry.counter("realtime.events.malformed").increment();
            log.warn("Discarding unparseable order event: {}", e.getMessage());
            return;
        }
        broadcast(event);
    }

    public void broadcast(OrderStatusEvent event) {
        for (String destination : RealtimeTopics.destinationsFor(event)) {
            messagingTemplate.convertAndSend(destination, event);
        }
        meterRegistry.counter("realtime.events.broadcast").increment();
        log.debug("Broadcast event orderId={} status={}", event.getOrderId(), event.getStatus());
    }
}
