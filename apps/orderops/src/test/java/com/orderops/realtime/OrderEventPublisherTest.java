package com.orderops.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderops.shared.event.OrderStatusEvent;
import com.orderops.shared.state.OrderStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;

class OrderEventPublisherTest {

    private StringRedisTemplate redis;
    private SimpleMeterRegistry meterRegistry;
    private OrderEventPublisher publisher;

    @BeforeEach
    void setUp() {
        redis = Mockito.mock(StringRedisTemplate.class);
        meterRegistry = new SimpleMeterRegistry();
        publisher = new OrderEventPublisher(redis, new ObjectMapper(), meterRegistry);
    }

    private static OrderStatusEvent event() {
        return OrderStatusEvent.statusChanged(
            "o-1", "c-1", OrderStatus.CREATED, OrderStatus.INVENTORY_RESERVED, "Order created");
    }

    @Test
    void publish_sendsSerializedEventOnTheOrderEventsChannel() {
        publisher.publish(event());

        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(redis).convertAndSend(channel.capture(), payload.capture());

        assertEquals(OrderEventPublisher.CHANNEL, channel.getValue());
        assertTrue(payload.getValue().toString().contains("\"orderId\":\"o-1\""));
        assertEquals(1.0, meterRegistry.counter("realtime.events.published").count());
    }

    @Test
    void publish_redisUnavailable_doesNotPropagate() {
        Mockito.doThrow(new RedisConnectionFailureException("redis is down"))
            .when(redis).convertAndSend(Mockito.anyString(), Mockito.any());

        // The order is already committed in DynamoDB. Failing the caller because a notification
        // could not be sent would turn a cosmetic problem into a failed checkout.
        assertDoesNotThrow(() -> publisher.publish(event()));

        assertEquals(1.0, meterRegistry.counter("realtime.events.publish_failed").count());
        assertEquals(0.0, meterRegistry.counter("realtime.events.published").count());
    }
}
