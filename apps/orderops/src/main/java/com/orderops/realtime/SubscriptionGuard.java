package com.orderops.realtime;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/**
 * Rejects SUBSCRIBE frames naming a destination outside {@link RealtimeTopics}.
 *
 * <p>Without this, the simple broker happily honours any destination a client asks for,
 * including wildcards — so one connection could subscribe to {@code /topic/**} and watch every
 * order in the system. This project has no authentication (out of scope), so it cannot decide
 * *whose* orders a client may watch; it can still refuse to let a client widen its subscription
 * beyond the three shapes the UI actually uses.
 *
 * <p>Honest limitation: this restricts the *shape* of a subscription, not the *identity* behind
 * it. Anyone holding an order ID can watch that order, and the ops topic is open. Real
 * per-customer authorization would need an authenticated principal on the STOMP session.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionGuard implements ChannelInterceptor {

    private final MeterRegistry meterRegistry;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (accessor.getMessageType() != SimpMessageType.SUBSCRIBE) {
            return message;
        }

        String destination = accessor.getDestination();
        if (RealtimeTopics.isSubscribable(destination)) {
            return message;
        }

        meterRegistry.counter("realtime.subscriptions.rejected").increment();
        log.warn("Rejected subscription to disallowed destination: {}", destination);
        // Returning null drops the frame, so the broker never registers the subscription.
        return null;
    }
}
