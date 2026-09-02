package com.orderops.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * A page of results plus an opaque cursor for the next page.
 *
 * <p>{@code nextCursor} is omitted when there are no further results, which lets the client
 * treat "field absent" as "end of list" without comparing counts against the page size.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageResponse<T> {
    List<T> items;
    String nextCursor;
}
