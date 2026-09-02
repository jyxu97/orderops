package com.orderops.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderops.shared.event.OrderStatusEvent;
import com.orderops.shared.state.OrderStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.junit.jupiter.api.Assertions.*;

class OrderEventBroadcasterTest {

    private SimpMessagingTemplate messagingTemplate;
    private SimpleMeterRegistry meterRegistry;
    private ObjectMapper objectMapper;
    private OrderEventBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        messagingTemplate = Mockito.mock(SimpMessagingTemplate.class);
        meterRegistry = new SimpleMeterRegistry();
        objectMapper = new ObjectMapper();
        broadcaster = new OrderEventBroadcaster(messagingTemplate, objectMapper, meterRegistry);
    }

    @Test
    void onMessage_deserializesAndSendsToEveryDestination() throws Exception {
        OrderStatusEvent event = OrderStatusEvent.statusChanged(
            "o-1", "c-1", OrderStatus.PAYMENT_PROCESSING, OrderStatus.PAYMENT_SUCCEEDED, "Payment authorized");

        broadcaster.onMessage(objectMapper.writeValueAsString(event));

        ArgumentCaptor<String> destinations = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(messagingTemplate, Mockito.times(3))
            .convertAndSend(destinations.capture(), payloads.capture());

        assertEquals(
            java.util.List.of("/topic/orders/o-1", "/topic/customers/c-1/orders", "/topic/ops/orders"),
            destinations.getAllValues());

        OrderStatusEvent delivered = (OrderStatusEvent) payloads.getAllValues().get(0);
        assertEquals("PAYMENT_SUCCEEDED", delivered.getStatus());
        assertEquals("PAYMENT_PROCESSING", delivered.getPreviousStatus());
        assertEquals("Payment authorized", delivered.getReason());
        assertEquals(event.getCommittedAtEpochMilli(), delivered.getCommittedAtEpochMilli(),
            "the commit timestamp must survive the round trip, or latency cannot be measured");
    }

    @Test
    void onMessage_malformedPayload_isDiscardedWithoutBroadcasting() {
        // The Redis listener is long-lived: one bad payload must not stop later events.
        broadcaster.onMessage("{not valid json");

        Mockito.verifyNoInteractions(messagingTemplate);
        assertEquals(1.0, meterRegistry.counter("realtime.events.malformed").count());
        assertEquals(0.0, meterRegistry.counter("realtime.events.broadcast").count());
    }

    @Test
    void onMessage_survivesAndKeepsWorkingAfterABadPayload() throws Exception {
        broadcaster.onMessage("garbage");
        broadcaster.onMessage(objectMapper.writeValueAsString(OrderStatusEvent.statusChanged(
            "o-2", "c-2", OrderStatus.CREATED, OrderStatus.INVENTORY_RESERVED, "Order created")));

        assertEquals(1.0, meterRegistry.counter("realtime.events.broadcast").count());
    }

    @Test
    void broadcast_countsOncePerEventNotOncePerDestination() {
        broadcaster.broadcast(OrderStatusEvent.statusChanged(
            "o-3", "c-3", OrderStatus.CREATED, OrderStatus.INVENTORY_RESERVED, "Order created"));

        assertEquals(1.0, meterRegistry.counter("realtime.events.broadcast").count());
    }
}
