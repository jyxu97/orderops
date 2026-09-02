package com.orderops.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderops.shared.event.OrderStatusEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes order events onto a Redis channel.
 *
 * <p>Everything goes through Redis, including events raised by the API process itself, rather
 * than being handed straight to the local WebSocket broker. That is what makes the fan-out
 * correct with more than one API task: a locally-broadcast event only reaches the clients
 * connected to the replica that produced it, and behind an ALB that is an arbitrary subset.
 * The API is also a separate process from the fulfillment worker, so Redis is the only path
 * by which a worker's transition can reach a connected browser at all.
 *
 * <p>Publishing never fails the caller. Events are delivery hints layered on top of committed
 * state — if Redis is unavailable, the order is still correct in DynamoDB and the UI recovers
 * on its next refetch. Failing a checkout because a notification could not be sent would trade
 * a cosmetic problem for a real one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    public static final String CHANNEL = "orderops:order-events";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public void publish(OrderStatusEvent event) {
        try {
            redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(event));
            meterRegistry.counter("realtime.events.published").increment();
            log.debug("Published event orderId={} status={}", event.getOrderId(), event.getStatus());
        } catch (Exception e) {
            meterRegistry.counter("realtime.events.publish_failed").increment();
            log.warn("Could not publish order event orderId={} status={}: {}",
                event.getOrderId(), event.getStatus(), e.getMessage());
        }
    }
}
