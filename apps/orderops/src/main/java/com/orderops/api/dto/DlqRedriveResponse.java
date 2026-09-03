package com.orderops.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

/**
 * State of the dead-letter redrive, mirroring an SQS message move task.
 *
 * <p>A redrive is asynchronous: starting one returns immediately and SQS moves messages in the
 * background, so this same shape answers both "start a redrive" and "how is it going".
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DlqRedriveResponse {

    /**
     * {@code NONE} when no redrive has ever run, otherwise the SQS task status
     * ({@code RUNNING}, {@code COMPLETED}, {@code CANCELLING}, {@code CANCELLED},
     * {@code FAILED}).
     */
    String status;

    /** How many messages the task expects to move. Null before SQS has estimated it. */
    Long messagesToMove;

    /** How many it has moved so far. */
    Long messagesMoved;

    /** Ceiling SQS was asked to respect, so a redrive cannot outrun the worker. */
    Integer maxMessagesPerSecond;

    String startedAt;

    /** Present only when the task failed. */
    String failureReason;
}
