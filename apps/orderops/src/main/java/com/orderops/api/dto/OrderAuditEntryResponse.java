package com.orderops.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

/** One recorded state transition, for the order detail page's timeline. */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrderAuditEntryResponse {
    String timestamp;
    String fromStatus;
    String toStatus;
    String reason;
}
