package com.orderops.realtime;

import com.orderops.api.repository.DynamoDbLocalProcess;
import com.orderops.api.service.SqsPublisher;
import com.orderops.shared.event.OrderStatusEvent;
import com.orderops.shared.state.OrderStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * End-to-end WebSocket coverage against a running server and a real STOMP client.
 *
 * <p>Drives {@link OrderEventBroadcaster} directly rather than going through Redis: the Redis
 * hop is covered by {@link OrderEventPublisherTest}, and what needs proving here is the part
 * that unit tests cannot reach — that a real client subscribed to a real destination actually
 * receives the frame, and that a client subscribed elsewhere does not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketOrderTrackingTest {

    private static final DynamoDbLocalProcess DYNAMO;
    static {
        try {
            DYNAMO = DynamoDbLocalProcess.start();
            Runtime.getRuntime().addShutdownHook(new Thread(DYNAMO::close));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("dynamodb.endpoint", DYNAMO::endpoint);
        registry.add("aws.region", () -> "us-west-2");
    }

    @MockBean
    StringRedisTemplate redisTemplate;
    @MockBean
    SqsPublisher sqsPublisher;

    @LocalServerPort
    private int port;

    @Autowired
    private OrderEventBroadcaster broadcaster;
    @Autowired
    private WebSocketConnectionMetrics connectionMetrics;

    private WebSocketStompClient stompClient;
    private final List<StompSession> sessions = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(ops);
        Mockito.when(ops.get(anyString())).thenReturn(null);

        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @AfterEach
    void tearDown() {
        sessions.forEach(session -> {
            if (session.isConnected()) {
                session.disconnect();
            }
        });
        sessions.clear();
        stompClient.stop();
    }

    private StompSession connect() throws Exception {
        StompSession session = stompClient
            .connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
            .get(10, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    /** Subscribes and returns a queue that receives every event delivered to {@code destination}. */
    private BlockingQueue<OrderStatusEvent> subscribe(StompSession session, String destination) {
        BlockingQueue<OrderStatusEvent> received = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return OrderStatusEvent.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((OrderStatusEvent) payload);
            }
        });
        return received;
    }

    private static OrderStatusEvent event(String orderId, String customerId, OrderStatus status) {
        return OrderStatusEvent.statusChanged(
            orderId, customerId, OrderStatus.PAYMENT_PROCESSING, status, "test transition");
    }

    @Test
    void subscriberOfAnOrder_receivesThatOrdersEvents() throws Exception {
        StompSession session = connect();
        BlockingQueue<OrderStatusEvent> received = subscribe(session, "/topic/orders/order-A");
        // The broker registers the subscription asynchronously; a frame sent before it lands
        // would be dropped and make this test flaky.
        Thread.sleep(300);

        broadcaster.broadcast(event("order-A", "customer-1", OrderStatus.PAYMENT_SUCCEEDED));

        OrderStatusEvent delivered = received.poll(10, TimeUnit.SECONDS);
        assertNotNull(delivered, "subscriber should have received the event");
        assertEquals("order-A", delivered.getOrderId());
        assertEquals("PAYMENT_SUCCEEDED", delivered.getStatus());
        assertTrue(delivered.getCommittedAtEpochMilli() > 0);
    }

    @Test
    void subscriberOfADifferentOrder_receivesNothing() throws Exception {
        StompSession session = connect();
        BlockingQueue<OrderStatusEvent> mine = subscribe(session, "/topic/orders/order-MINE");
        BlockingQueue<OrderStatusEvent> theirs = subscribe(session, "/topic/orders/order-THEIRS");
        Thread.sleep(300);

        broadcaster.broadcast(event("order-MINE", "customer-1", OrderStatus.FULFILLED));

        assertNotNull(mine.poll(10, TimeUnit.SECONDS));
        assertNull(theirs.poll(1, TimeUnit.SECONDS),
            "an event must not leak to a subscriber of a different order");
    }

    @Test
    void customerTopic_receivesEventsForThatCustomerOnly() throws Exception {
        StompSession session = connect();
        BlockingQueue<OrderStatusEvent> mine = subscribe(session, "/topic/customers/customer-1/orders");
        BlockingQueue<OrderStatusEvent> other = subscribe(session, "/topic/customers/customer-2/orders");
        Thread.sleep(300);

        broadcaster.broadcast(event("order-B", "customer-1", OrderStatus.FULFILLED));

        assertEquals("order-B", mine.poll(10, TimeUnit.SECONDS).getOrderId());
        assertNull(other.poll(1, TimeUnit.SECONDS),
            "one customer must not see another customer's orders");
    }

    @Test
    void opsTopic_receivesEveryOrdersEvents() throws Exception {
        StompSession session = connect();
        BlockingQueue<OrderStatusEvent> ops = subscribe(session, "/topic/ops/orders");
        Thread.sleep(300);

        broadcaster.broadcast(event("order-C", "customer-1", OrderStatus.FULFILLED));
        broadcaster.broadcast(event("order-D", "customer-2", OrderStatus.FAILED));

        assertEquals("order-C", ops.poll(10, TimeUnit.SECONDS).getOrderId());
        assertEquals("order-D", ops.poll(10, TimeUnit.SECONDS).getOrderId());
    }

    @Test
    void disallowedDestination_isNotSubscribedAndReceivesNothing() throws Exception {
        StompSession session = connect();
        // A wildcard subscription would otherwise expose every order in the system.
        BlockingQueue<OrderStatusEvent> everything = subscribe(session, "/topic/**");
        Thread.sleep(300);

        broadcaster.broadcast(event("order-E", "customer-1", OrderStatus.FULFILLED));

        assertNull(everything.poll(2, TimeUnit.SECONDS),
            "the subscription guard should have dropped the SUBSCRIBE frame");
    }

    @Test
    void connectionGauge_tracksConnectAndDisconnect() throws Exception {
        int before = connectionMetrics.activeConnections();

        StompSession session = connect();
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> connectionMetrics.activeConnections() == before + 1);

        session.disconnect();
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> connectionMetrics.activeConnections() == before);
    }
}
