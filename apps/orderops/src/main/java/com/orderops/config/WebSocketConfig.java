package com.orderops.config;

import com.orderops.realtime.SubscriptionGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over a native WebSocket at {@code /ws}, with the simple in-memory broker.
 *
 * <p>STOMP rather than a raw socket because the product needs per-order and per-customer
 * subscriptions: with a raw socket every client would receive every event and filter locally,
 * which both wastes bandwidth and exposes other customers' order IDs. STOMP gives destination
 * routing without hand-rolling a subscription protocol.
 *
 * <p>The simple broker keeps subscriptions in the instance's memory, which is exactly why
 * events travel over Redis rather than being handed to the broker directly — see
 * {@link com.orderops.realtime.OrderEventPublisher}.
 *
 * <p>No SockJS fallback: the clients are modern browsers and the load-test harness, both of
 * which speak native WebSocket.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final SubscriptionGuard subscriptionGuard;

    @Value("${app.cors.allowed-origins}")
    private String[] allowedOrigins;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // The handshake is a cross-origin request from the React app, so the same explicit
        // origin list the REST API uses applies here.
        registry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        // No application destination prefix is configured: clients only ever subscribe.
        // Nothing in this system accepts a client-sent message, so there is no @MessageMapping
        // surface to reach.
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(subscriptionGuard);
    }
}
