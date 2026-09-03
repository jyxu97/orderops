package com.orderops.api.service;

import com.orderops.api.dto.DlqRedriveResponse;
import com.orderops.api.exception.RedriveConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.ListMessageMoveTasksRequest;
import software.amazon.awssdk.services.sqs.model.ListMessageMoveTasksResultEntry;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.StartMessageMoveTaskRequest;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Moves messages out of the dead-letter queue and back onto the fulfillment queue.
 *
 * <p>Uses the SQS message move task API rather than a hand-rolled receive-send-delete loop.
 * SQS owns the movement, so there is no window in which a message has been sent to the source
 * queue but not yet deleted from the DLQ — which a manual loop cannot avoid and would turn
 * into duplicate deliveries on a crash mid-redrive.
 *
 * <p><b>What a redrive actually recovers.</b> A message reaches the DLQ by exhausting its
 * receive count, which happens when fulfillment kept throwing — a transient fault that outlived
 * the retry budget. Its order is therefore parked in a non-terminal state such as
 * PAYMENT_PROCESSING, and {@code OrderFulfillmentService} resumes from exactly that point when
 * the message comes back. A *permanent* failure never lands here: the worker moves that order
 * to FAILED then NEEDS_MANUAL_REVIEW and deletes the message on the success path. So redrive
 * and cancel are the two distinct operator actions for two distinct situations — redrive
 * retries work that was interrupted, cancel gives up on work that cannot succeed.
 *
 * <p>Redrives are operator-triggered and rate-limited, never automatic. An unbounded automatic
 * redrive would put a permanently failing message into an infinite loop between the two queues.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DlqRedriveService {

    private static final String STATUS_NONE = "NONE";
    private static final String STATUS_RUNNING = "RUNNING";

    private final SqsClient sqsClient;

    @Value("${sqs.fulfillment-queue-url}")
    private String queueUrl;

    @Value("${sqs.fulfillment-dlq-url}")
    private String dlqUrl;

    @Value("${ops.redrive.max-messages-per-second:10}")
    private int maxMessagesPerSecond;

    /** ARNs are immutable for the life of a queue, so they are resolved once and kept. */
    private final AtomicReference<String> cachedQueueArn = new AtomicReference<>();
    private final AtomicReference<String> cachedDlqArn = new AtomicReference<>();

    /**
     * Starts moving every message currently in the DLQ back to the fulfillment queue.
     *
     * @throws RedriveConflictException if a redrive is already running — SQS permits one move
     *                                  task per source queue, and a second request is an
     *                                  operator double-click rather than a new intent
     */
    public DlqRedriveResponse startRedrive() {
        latestTask()
            .filter(task -> STATUS_RUNNING.equalsIgnoreCase(task.status()))
            .ifPresent(task -> {
                throw new RedriveConflictException(
                    task.approximateNumberOfMessagesMoved(), task.approximateNumberOfMessagesToMove());
            });

        try {
            sqsClient.startMessageMoveTask(StartMessageMoveTaskRequest.builder()
                .sourceArn(arnOf(dlqUrl, cachedDlqArn))
                .destinationArn(arnOf(queueUrl, cachedQueueArn))
                // Without a ceiling SQS drains the DLQ as fast as it can, which would hand the
                // worker a burst the size of the whole backlog.
                .maxNumberOfMessagesPerSecond(maxMessagesPerSecond)
                .build());
        } catch (AwsServiceException e) {
            // SQS reports an existing task as a plain validation error, so a race between two
            // operators lands here rather than in the pre-check above.
            if (isAlreadyRunning(e)) {
                throw new RedriveConflictException(null, null);
            }
            throw e;
        }

        log.info("Started DLQ redrive at up to {} message(s)/second", maxMessagesPerSecond);
        return status();
    }

    /** The most recent redrive, or a {@code NONE} status if none has ever run. */
    public DlqRedriveResponse status() {
        return latestTask()
            .map(task -> DlqRedriveResponse.builder()
                .status(task.status())
                .messagesToMove(task.approximateNumberOfMessagesToMove())
                .messagesMoved(task.approximateNumberOfMessagesMoved())
                .maxMessagesPerSecond(task.maxNumberOfMessagesPerSecond())
                .startedAt(task.startedTimestamp() != null
                    ? Instant.ofEpochMilli(task.startedTimestamp()).toString()
                    : null)
                .failureReason(task.failureReason())
                .build())
            .orElseGet(() -> DlqRedriveResponse.builder().status(STATUS_NONE).build());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * SQS returns move tasks newest first, so the first entry is the current or last redrive.
     *
     * <p>Returns empty rather than throwing when the queue has no task history, which is also
     * what a freshly created queue looks like.
     */
    private Optional<ListMessageMoveTasksResultEntry> latestTask() {
        var response = sqsClient.listMessageMoveTasks(ListMessageMoveTasksRequest.builder()
            .sourceArn(arnOf(dlqUrl, cachedDlqArn))
            .maxResults(1)
            .build());

        return response.results().stream().findFirst();
    }

    private String arnOf(String url, AtomicReference<String> cache) {
        String cached = cache.get();
        if (cached != null) {
            return cached;
        }
        String arn = sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(url)
                .attributeNames(QueueAttributeName.QUEUE_ARN)
                .build())
            .attributes()
            .get(QueueAttributeName.QUEUE_ARN);

        cache.compareAndSet(null, arn);
        return arn;
    }

    private static boolean isAlreadyRunning(AwsServiceException e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains("already");
    }
}
