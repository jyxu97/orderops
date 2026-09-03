package com.orderops.api.service;

import com.orderops.api.dto.QueueHealthResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads queue depth straight from SQS for the operations dashboard.
 *
 * <p>These are the same depth signals CloudWatch alarms on in production
 * ({@code ApproximateNumberOfMessages} and DLQ depth), so the dashboard and the alarms tell
 * the same story. Oldest-message age is CloudWatch-only — see {@link QueueHealthResponse}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqsQueueInspector {

    private static final List<QueueAttributeName> ATTRIBUTES = List.of(
        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED
    );

    private final SqsClient sqsClient;

    @Value("${sqs.fulfillment-queue-url}")
    private String queueUrl;

    @Value("${sqs.fulfillment-dlq-url}")
    private String dlqUrl;

    @Value("${ops.queue-health.backlog-threshold:100}")
    private int backlogThreshold;

    public QueueHealthResponse inspect() {
        try {
            QueueHealthResponse.QueueStats queue = readStats(queueUrl);
            QueueHealthResponse.QueueStats dlq   = readStats(dlqUrl);

            List<String> warnings = new ArrayList<>();
            if (queue.getVisibleMessages() > backlogThreshold) {
                warnings.add("Queue backlog is %d message(s), above the threshold of %d"
                    .formatted(queue.getVisibleMessages(), backlogThreshold));
            }
            // Any DLQ message means a fulfillment exhausted its retries, so the threshold is zero.
            if (dlq.getVisibleMessages() > 0) {
                warnings.add("DLQ holds %d message(s)".formatted(dlq.getVisibleMessages()));
            }

            return QueueHealthResponse.builder()
                .available(true)
                .queue(queue)
                .deadLetterQueue(dlq)
                .backlogThreshold(backlogThreshold)
                .healthy(warnings.isEmpty())
                .warnings(warnings)
                .build();

        } catch (RuntimeException e) {
            log.warn("Could not read queue attributes from SQS: {}", e.getMessage());
            return QueueHealthResponse.builder()
                .available(false)
                .unavailableReason(e.getMessage())
                .build();
        }
    }

    private QueueHealthResponse.QueueStats readStats(String url) {
        Map<QueueAttributeName, String> attrs = sqsClient.getQueueAttributes(
                GetQueueAttributesRequest.builder()
                    .queueUrl(url)
                    .attributeNames(ATTRIBUTES)
                    .build())
            .attributes();

        return QueueHealthResponse.QueueStats.builder()
            .queueName(queueNameOf(url))
            .visibleMessages(intAttr(attrs, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
            .inFlightMessages(intAttr(attrs, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
            .delayedMessages(intAttr(attrs, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED))
            .build();
    }

    /** SQS omits attributes it has no value for, so an absent attribute reads as zero. */
    private static int intAttr(Map<QueueAttributeName, String> attrs, QueueAttributeName name) {
        String raw = attrs.get(name);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String queueNameOf(String url) {
        int slash = url.lastIndexOf('/');
        return slash >= 0 ? url.substring(slash + 1) : url;
    }
}
