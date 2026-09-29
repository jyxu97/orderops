package com.orderops.shared.model;

import lombok.Builder;
import lombok.Value;

/**
 * A fulfillment message that has been promised but not yet confirmed sent to SQS.
 *
 * <p>Written inside the same transaction as the order it belongs to, so "the order exists" and
 * "a message is owed for it" become one atomic fact. Deleted once SQS has accepted the message,
 * which makes the table the set of outstanding sends: normally empty, so the publisher's sweep
 * stays cheap and needs no index or status flag.
 */
@Value
@Builder
public class OutboxRecord {
    String orderId;
    /** The exact SQS message body, stored rather than rebuilt so a retry sends the same bytes. */
    String payload;
    String createdAt;
}
