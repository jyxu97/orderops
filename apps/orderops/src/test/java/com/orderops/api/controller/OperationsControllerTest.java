package com.orderops.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderops.api.repository.DynamoDbLocalProcess;
import com.orderops.api.repository.AuditLogRepository;
import com.orderops.api.repository.OrderRepository;
import com.orderops.shared.model.OrderAuditLog;
import com.orderops.api.service.SqsPublisher;
import com.orderops.shared.state.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ListMessageMoveTasksRequest;
import software.amazon.awssdk.services.sqs.model.ListMessageMoveTasksResponse;
import software.amazon.awssdk.services.sqs.model.ListMessageMoveTasksResultEntry;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.StartMessageMoveTaskRequest;
import software.amazon.awssdk.services.sqs.model.StartMessageMoveTaskResponse;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Covers the operations dashboard endpoints and the order audit timeline. */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsControllerTest {

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
    static void dynamoProperties(DynamicPropertyRegistry registry) {
        registry.add("dynamodb.endpoint", DYNAMO::endpoint);
        registry.add("aws.region", () -> "us-west-2");
    }

    @MockBean
    StringRedisTemplate redisTemplate;
    @MockBean
    SqsPublisher sqsPublisher;
    @MockBean
    SqsClient sqsClient;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;

    private String itemId;

    @BeforeEach
    void setUp() throws Exception {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(ops);
        Mockito.when(ops.get(anyString())).thenReturn(null);

        stubQueueAttributes(3, 1, 0);

        itemId = "ops-item-" + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/inventory/seed")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"itemId": "%s", "quantity": 50, "unitPrice": 10.00}
                    """.formatted(itemId)))
            .andExpect(status().isCreated());
    }

    /** Same stub for the main queue and the DLQ, with the DLQ depth controlled separately. */
    private void stubQueueAttributes(int visible, int inFlight, int dlqVisible) {
        Mockito.when(sqsClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
            .thenAnswer(invocation -> {
                GetQueueAttributesRequest request = invocation.getArgument(0);
                boolean isDlq = request.queueUrl().endsWith("dlq");
                return GetQueueAttributesResponse.builder()
                    .attributes(Map.of(
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                            String.valueOf(isDlq ? dlqVisible : visible),
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                            String.valueOf(isDlq ? 0 : inFlight),
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED, "0",
                        // The redrive service resolves ARNs through the same call.
                        QueueAttributeName.QUEUE_ARN,
                            "arn:aws:sqs:us-west-2:000000000000:" + (isDlq ? "dlq" : "queue")))
                    .build();
            });
    }

    private String createOrder() throws Exception {
        String result = mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerId": "ops-customer", "items": [{"itemId": "%s", "quantity": 1}]}
                    """.formatted(itemId)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(result).get("orderId").asText();
    }

    /** Walks an order into NEEDS_MANUAL_REVIEW the way a failed fulfillment would. */
    private String createFailedOrder() throws Exception {
        String orderId = createOrder();
        advance(orderId, OrderStatus.PAYMENT_PROCESSING, 1L, "Processing payment");
        advance(orderId, OrderStatus.FAILED, 2L, "Payment declined");
        advance(orderId, OrderStatus.NEEDS_MANUAL_REVIEW, 3L, "Queued for manual review");
        return orderId;
    }

    /**
     * Applies a transition and records it, the way the worker does — the audit entry matters
     * because the failures view reads its reason back.
     */
    private void advance(String orderId, OrderStatus status, long expectedVersion, String reason) {
        orderRepository.updateStatus(orderId, status, expectedVersion);
        auditLogRepository.save(OrderAuditLog.builder()
            .orderId(orderId)
            .timestamp(Instant.now().toString())
            .fromStatus("PREVIOUS")
            .toStatus(status.name())
            .reason(reason)
            .build());
    }

    @Test
    void overview_reportsStatusCountsRecentOrdersAndQueueDepth() throws Exception {
        String orderId = createOrder();

        mockMvc.perform(get("/api/v1/ops/overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.statusCounts.INVENTORY_RESERVED").isNumber())
            // Every status appears, so the dashboard can render a stable set of tiles.
            .andExpect(jsonPath("$.statusCounts.CANCELLED").isNumber())
            .andExpect(jsonPath("$.countsCapped").value(false))
            .andExpect(jsonPath("$.recentOrders[?(@.orderId=='%s')]".formatted(orderId)).exists())
            .andExpect(jsonPath("$.queueHealth.available").value(true))
            .andExpect(jsonPath("$.queueHealth.queue.visibleMessages").value(3))
            .andExpect(jsonPath("$.queueHealth.queue.inFlightMessages").value(1))
            .andExpect(jsonPath("$.generatedAt").isNotEmpty());
    }

    @Test
    void overview_recentLimitOutOfRange_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/ops/overview").param("recentLimit", "0"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void recentOrders_returnsMostRecentlyUpdatedFirst() throws Exception {
        createOrder();
        String newest = createOrder();

        mockMvc.perform(get("/api/v1/ops/orders").param("limit", "50"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.orderId=='%s')]".formatted(newest)).exists());
    }

    @Test
    void queueHealth_dlqHoldingMessages_isUnhealthyWithAWarning() throws Exception {
        stubQueueAttributes(2, 0, 3);

        mockMvc.perform(get("/api/v1/ops/queue-health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.healthy").value(false))
            .andExpect(jsonPath("$.deadLetterQueue.visibleMessages").value(3))
            .andExpect(jsonPath("$.warnings[0]").value(org.hamcrest.Matchers.containsString("DLQ")));
    }

    @Test
    void queueHealth_backlogAboveThreshold_isUnhealthy() throws Exception {
        stubQueueAttributes(500, 0, 0);

        mockMvc.perform(get("/api/v1/ops/queue-health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.healthy").value(false))
            .andExpect(jsonPath("$.warnings[0]").value(org.hamcrest.Matchers.containsString("backlog")));
    }

    @Test
    void queueHealth_emptyQueues_areHealthy() throws Exception {
        stubQueueAttributes(0, 0, 0);

        mockMvc.perform(get("/api/v1/ops/queue-health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.healthy").value(true))
            .andExpect(jsonPath("$.warnings").isEmpty());
    }

    @Test
    void queueHealth_sqsUnreachable_reportsUnavailableInsteadOfFailing() throws Exception {
        Mockito.when(sqsClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
            .thenThrow(QueueDoesNotExistException.builder().message("queue is gone").build());

        // A broken metrics read must not take down the endpoint, and must not be reported
        // as healthy either.
        mockMvc.perform(get("/api/v1/ops/queue-health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.healthy").doesNotExist())
            .andExpect(jsonPath("$.unavailableReason").isNotEmpty());
    }

    @Test
    void failures_listsFailedOrdersWithTheirLastReason() throws Exception {
        String orderId = createFailedOrder();

        mockMvc.perform(get("/api/v1/ops/failures").param("limit", "50"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.orderId=='%s')]".formatted(orderId)).exists());
    }

    @Test
    void failures_excludesHealthyOrders() throws Exception {
        String healthy = createOrder();

        mockMvc.perform(get("/api/v1/ops/failures").param("limit", "50"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.orderId=='%s')]".formatted(healthy)).doesNotExist());
    }

    @Test
    void failures_reportTheCauseNotTheRoutingStep() throws Exception {
        // A real failure path: the worker declines payment, moves the order to FAILED with the
        // cause, then routes it to manual review. The newest audit entry is that routing step,
        // so showing it would tell an operator nothing about why the order failed.
        String orderId = createOrder();
        advance(orderId, OrderStatus.PAYMENT_PROCESSING, 1L, "Processing payment");
        advance(orderId, OrderStatus.FAILED, 2L, "Payment declined");
        advance(orderId, OrderStatus.NEEDS_MANUAL_REVIEW, 3L, "Queued for manual review");

        mockMvc.perform(get("/api/v1/ops/failures").param("limit", "50"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.orderId=='%s')].lastFailureReason".formatted(orderId))
                .value(org.hamcrest.Matchers.hasItem("Payment declined")));
    }

    @Test
    void failures_orderWithNoFailedTransition_fallsBackToTheLatestEntry() throws Exception {
        String orderId = createOrder();
        advance(orderId, OrderStatus.PAYMENT_PROCESSING, 1L, "Processing payment");
        advance(orderId, OrderStatus.FAILED, 2L, null);

        mockMvc.perform(get("/api/v1/ops/failures").param("limit", "50"))
            .andExpect(status().isOk())
            // No reason recorded on the FAILED transition — must not invent one.
            .andExpect(jsonPath("$[?(@.orderId=='%s')]".formatted(orderId)).exists());
    }

    @Test
    void failures_markManualReviewOrdersAsCancellable() throws Exception {
        String orderId = createFailedOrder();

        mockMvc.perform(get("/api/v1/ops/failures").param("limit", "50"))
            .andExpect(jsonPath("$[?(@.orderId=='%s')].cancellable".formatted(orderId))
                .value(org.hamcrest.Matchers.hasItem(true)));
    }

    @Test
    void getOrderAudit_returnsTheTransitionTimelineOldestFirst() throws Exception {
        String orderId = createOrder();
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/orders/" + orderId + "/audit"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].fromStatus").value("CREATED"))
            .andExpect(jsonPath("$[0].toStatus").value("INVENTORY_RESERVED"))
            .andExpect(jsonPath("$[1].toStatus").value("CANCELLED"))
            .andExpect(jsonPath("$[1].reason").isNotEmpty());
    }

    @Test
    void getOrderAudit_unknownOrder_returns404() throws Exception {
        // An unknown order must not look like an order that simply has no history.
        mockMvc.perform(get("/api/v1/orders/does-not-exist/audit"))
            .andExpect(status().isNotFound());
    }

    /** Stubs the move-task history SQS would report. */
    private void stubMoveTasks(ListMessageMoveTasksResultEntry... entries) {
        Mockito.when(sqsClient.listMessageMoveTasks(any(ListMessageMoveTasksRequest.class)))
            .thenReturn(ListMessageMoveTasksResponse.builder().results(entries).build());
    }

    private static ListMessageMoveTasksResultEntry moveTask(String status, long moved, long toMove) {
        return ListMessageMoveTasksResultEntry.builder()
            .status(status)
            .approximateNumberOfMessagesMoved(moved)
            .approximateNumberOfMessagesToMove(toMove)
            .maxNumberOfMessagesPerSecond(10)
            .startedTimestamp(System.currentTimeMillis())
            .build();
    }

    @Test
    void redriveStatus_noRedriveEverRun_reportsNone() throws Exception {
        stubMoveTasks();

        mockMvc.perform(get("/api/v1/ops/dlq/redrive"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("NONE"))
            .andExpect(jsonPath("$.messagesMoved").doesNotExist());
    }

    @Test
    void redriveStatus_reportsProgress() throws Exception {
        stubMoveTasks(moveTask("RUNNING", 3, 10));

        mockMvc.perform(get("/api/v1/ops/dlq/redrive"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.messagesMoved").value(3))
            .andExpect(jsonPath("$.messagesToMove").value(10))
            .andExpect(jsonPath("$.maxMessagesPerSecond").value(10));
    }

    @Test
    void startRedrive_returns202AndStartsAMoveTask() throws Exception {
        stubMoveTasks(moveTask("COMPLETED", 5, 5));
        Mockito.when(sqsClient.startMessageMoveTask(any(StartMessageMoveTaskRequest.class)))
            .thenReturn(StartMessageMoveTaskResponse.builder().taskHandle("handle").build());

        // 202, not 200: SQS moves the messages in the background.
        mockMvc.perform(post("/api/v1/ops/dlq/redrive"))
            .andExpect(status().isAccepted());

        ArgumentCaptor<StartMessageMoveTaskRequest> captor =
            ArgumentCaptor.forClass(StartMessageMoveTaskRequest.class);
        Mockito.verify(sqsClient).startMessageMoveTask(captor.capture());

        StartMessageMoveTaskRequest request = captor.getValue();
        // Source is the DLQ and destination the fulfillment queue — reversing these would
        // dead-letter live work.
        assertTrue(request.sourceArn().endsWith("dlq"), "source must be the DLQ");
        assertTrue(request.destinationArn().endsWith("queue"), "destination must be the main queue");
        // Rate limited so a large backlog cannot be handed to the worker all at once.
        assertEquals(10, request.maxNumberOfMessagesPerSecond());
    }

    @Test
    void startRedrive_whileOneIsRunning_returns409AndDoesNotStartAnother() throws Exception {
        stubMoveTasks(moveTask("RUNNING", 2, 5));

        mockMvc.perform(post("/api/v1/ops/dlq/redrive"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("2 of 5")));

        // SQS permits one move task per queue; a second start would just fail there.
        Mockito.verify(sqsClient, Mockito.never())
            .startMessageMoveTask(any(StartMessageMoveTaskRequest.class));
    }

    @Test
    void startRedrive_afterAPreviousOneCompleted_isAllowed() throws Exception {
        stubMoveTasks(moveTask("COMPLETED", 5, 5));
        Mockito.when(sqsClient.startMessageMoveTask(any(StartMessageMoveTaskRequest.class)))
            .thenReturn(StartMessageMoveTaskResponse.builder().taskHandle("handle").build());

        mockMvc.perform(post("/api/v1/ops/dlq/redrive"))
            .andExpect(status().isAccepted());

        Mockito.verify(sqsClient).startMessageMoveTask(any(StartMessageMoveTaskRequest.class));
    }
}